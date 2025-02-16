/*
 * Copyright © 2021 ProStore
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.arenadata.dtm.query.execution.core.ddl;

import io.arenadata.dtm.cache.service.CacheService;
import io.arenadata.dtm.common.dto.QueryParserRequest;
import io.arenadata.dtm.common.dto.QueryParserResponse;
import io.arenadata.dtm.common.exception.InvalidSourceTypeException;
import io.arenadata.dtm.common.model.ddl.ColumnType;
import io.arenadata.dtm.common.model.ddl.Entity;
import io.arenadata.dtm.common.model.ddl.EntityField;
import io.arenadata.dtm.common.model.ddl.EntityType;
import io.arenadata.dtm.common.reader.QueryRequest;
import io.arenadata.dtm.common.reader.QueryResult;
import io.arenadata.dtm.common.reader.SourceType;
import io.arenadata.dtm.common.request.DatamartRequest;
import io.arenadata.dtm.query.calcite.core.configuration.CalciteCoreConfiguration;
import io.arenadata.dtm.query.calcite.core.dialect.LimitSqlDialect;
import io.arenadata.dtm.query.calcite.core.framework.DtmCalciteFramework;
import io.arenadata.dtm.query.calcite.core.provider.CalciteContextProvider;
import io.arenadata.dtm.query.calcite.core.rel2sql.DtmRelToSqlConverter;
import io.arenadata.dtm.query.calcite.core.service.QueryParserService;
import io.arenadata.dtm.query.execution.core.base.dto.cache.EntityKey;
import io.arenadata.dtm.query.execution.core.base.dto.cache.MaterializedViewCacheValue;
import io.arenadata.dtm.query.execution.core.base.exception.datamart.DatamartNotExistsException;
import io.arenadata.dtm.query.execution.core.base.exception.entity.EntityAlreadyExistsException;
import io.arenadata.dtm.query.execution.core.base.exception.materializedview.MaterializedViewValidationException;
import io.arenadata.dtm.query.execution.core.base.exception.table.ValidationDtmException;
import io.arenadata.dtm.query.execution.core.base.exception.view.ViewDisalowedOrDirectiveException;
import io.arenadata.dtm.query.execution.core.base.repository.ServiceDbFacade;
import io.arenadata.dtm.query.execution.core.base.repository.zookeeper.DatamartDao;
import io.arenadata.dtm.query.execution.core.base.repository.zookeeper.EntityDao;
import io.arenadata.dtm.query.execution.core.base.repository.zookeeper.ServiceDbDao;
import io.arenadata.dtm.query.execution.core.base.service.metadata.LogicalSchemaProvider;
import io.arenadata.dtm.query.execution.core.base.service.metadata.MetadataExecutor;
import io.arenadata.dtm.query.execution.core.base.service.metadata.impl.MetadataCalciteGeneratorImpl;
import io.arenadata.dtm.query.execution.core.calcite.configuration.CalciteConfiguration;
import io.arenadata.dtm.query.execution.core.calcite.factory.CoreCalciteSchemaFactory;
import io.arenadata.dtm.query.execution.core.calcite.factory.CoreSchemaFactory;
import io.arenadata.dtm.query.execution.core.calcite.service.CoreCalciteContextProvider;
import io.arenadata.dtm.query.execution.core.ddl.dto.DdlRequestContext;
import io.arenadata.dtm.query.execution.core.ddl.service.QueryResultDdlExecutor;
import io.arenadata.dtm.query.execution.core.ddl.service.impl.CreateMaterializedViewExecutor;
import io.arenadata.dtm.query.execution.core.dml.service.impl.ColumnMetadataServiceImpl;
import io.arenadata.dtm.query.execution.core.plugin.service.DataSourcePluginService;
import io.arenadata.dtm.query.execution.core.query.utils.DefaultDatamartSetter;
import io.arenadata.dtm.query.execution.model.metadata.Datamart;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import lombok.SneakyThrows;
import lombok.val;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Planner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.stream.Collectors;

import static io.arenadata.dtm.query.execution.core.utils.TestUtils.assertException;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateMaterializedViewExecutorTest {
    private static final LimitSqlDialect SQL_DIALECT = new LimitSqlDialect(CalciteSqlDialect.DEFAULT_CONTEXT);
    private static final String SCHEMA = "matviewdatamart";
    private static final String TBL_ENTITY_NAME = "tbl";
    private static final String MAT_VIEW_ENTITY_NAME = "mat_view";

    private final CalciteConfiguration calciteConfiguration = new CalciteConfiguration();
    private final CalciteCoreConfiguration calciteCoreConfiguration = new CalciteCoreConfiguration();
    private final SqlParser.Config parserConfig = calciteConfiguration.configEddlParser(calciteCoreConfiguration.eddlParserImplFactory());
    private final FrameworkConfig frameworkConfig = DtmCalciteFramework.newConfigBuilder().parserConfig(parserConfig).build();
    private final Planner planner = DtmCalciteFramework.getPlanner(frameworkConfig);
    private final CoreCalciteSchemaFactory coreSchemaFactory = new CoreCalciteSchemaFactory(new CoreSchemaFactory());
    private final CalciteContextProvider contextProvider = new CoreCalciteContextProvider(parserConfig, coreSchemaFactory);
    private final DtmRelToSqlConverter relToSqlConverter = new DtmRelToSqlConverter(SQL_DIALECT);

    @Mock
    private LogicalSchemaProvider logicalSchemaProvider;
    @Mock
    private MetadataExecutor<DdlRequestContext> metadataExecutor;
    @Mock
    private ServiceDbFacade serviceDbFacade;
    @Mock
    private ServiceDbDao serviceDbDao;
    @Mock
    private DatamartDao datamartDao;
    @Mock
    private EntityDao entityDao;
    @Mock
    private DataSourcePluginService dataSourcePluginService;
    @Mock
    private CacheService<EntityKey, Entity> entityCacheService;
    @Mock
    private CacheService<EntityKey, MaterializedViewCacheValue> materializedViewCacheService;
    @Mock
    private QueryParserService parserService;
    @InjectMocks
    private ColumnMetadataServiceImpl columnMetadataService;
    @InjectMocks
    private MetadataCalciteGeneratorImpl metadataCalciteGenerator;
    @InjectMocks
    private DefaultDatamartSetter defaultDatamartSetter;

    @Captor
    private ArgumentCaptor<Entity> entityCaptor;
    @Captor
    private ArgumentCaptor<MaterializedViewCacheValue> cachedViewCaptor;

    private QueryResultDdlExecutor createTableDdlExecutor;

    private Entity tblEntity;

    @BeforeEach
    void setUp() {
        lenient().when(serviceDbFacade.getServiceDbDao()).thenReturn(serviceDbDao);
        lenient().when(serviceDbDao.getEntityDao()).thenReturn(entityDao);
        lenient().when(serviceDbDao.getDatamartDao()).thenReturn(datamartDao);
        Set<SourceType> sourceTypes = new HashSet<>();
        sourceTypes.add(SourceType.ADB);
        sourceTypes.add(SourceType.ADG);
        lenient().when(dataSourcePluginService.getSourceTypes()).thenReturn(sourceTypes);
        lenient().when(dataSourcePluginService.hasSourceType(Mockito.any(SourceType.class))).thenAnswer(invocationOnMock -> sourceTypes.contains(invocationOnMock.getArgument(0, SourceType.class)));
        createTableDdlExecutor = new CreateMaterializedViewExecutor(metadataExecutor, serviceDbFacade, new SqlDialect(SqlDialect.EMPTY_CONTEXT), entityCacheService,
                materializedViewCacheService, logicalSchemaProvider, columnMetadataService, parserService, metadataCalciteGenerator, dataSourcePluginService, relToSqlConverter);

        tblEntity = Entity.builder()
                .name(TBL_ENTITY_NAME)
                .schema(SCHEMA)
                .entityType(EntityType.TABLE)
                .fields(Arrays.asList(
                        EntityField.builder()
                                .ordinalPosition(0)
                                .name("id")
                                .type(ColumnType.INT)
                                .nullable(false)
                                .primaryOrder(1)
                                .shardingOrder(1)
                                .build(),
                        EntityField.builder()
                                .ordinalPosition(1)
                                .name("name")
                                .type(ColumnType.VARCHAR)
                                .nullable(true)
                                .size(100)
                                .build(),
                        EntityField.builder()
                                .ordinalPosition(1)
                                .name("enddate")
                                .type(ColumnType.TIMESTAMP)
                                .nullable(true)
                                .accuracy(5)
                                .build()
                ))
                .build();

        Datamart mainDatamart = new Datamart(SCHEMA, true, Arrays.asList(tblEntity));
        List<Datamart> logicSchema = Arrays.asList(mainDatamart);
        lenient().when(logicalSchemaProvider.getSchemaFromQuery(any(), anyString())).thenReturn(Future.succeededFuture(logicSchema));
        lenient().when(parserService.parse(Mockito.any())).thenAnswer(invocationOnMock -> Future.succeededFuture(parse(invocationOnMock.getArgument(0, QueryParserRequest.class))));
    }

    @Test
    void shouldSuccessWhenStarQuery() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.succeededFuture(true));
        when(entityDao.existsEntity(SCHEMA, MAT_VIEW_ENTITY_NAME))
                .thenReturn(Future.succeededFuture(false));
        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(metadataExecutor.execute(any())).thenReturn(Future.succeededFuture());
        when(entityDao.createEntity(any()))
                .thenReturn(Future.succeededFuture());

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        if (promise.future().cause() != null) {
            fail(promise.future().cause());
        }
        verify(materializedViewCacheService).put(any(EntityKey.class), any(MaterializedViewCacheValue.class));
        assertTrue(promise.future().succeeded());
        assertNotNull(promise.future().result());
        verify(metadataExecutor).execute(context);
    }

    @Test
    void shouldSuccessWhenStarQueryLogicalOnly() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view " +
                "(id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) " +
                "DATASOURCE_TYPE (ADG) " +
                "AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB' " +
                "LOGICAL_ONLY");

        Promise<QueryResult> promise = Promise.promise();

        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.succeededFuture(true));
        when(entityDao.existsEntity(SCHEMA, MAT_VIEW_ENTITY_NAME))
                .thenReturn(Future.succeededFuture(false));
        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(entityDao.createEntity(any()))
                .thenReturn(Future.succeededFuture());

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        if (promise.future().cause() != null) {
            fail(promise.future().cause());
        }
        verify(materializedViewCacheService).put(any(EntityKey.class), any(MaterializedViewCacheValue.class));
        assertTrue(promise.future().succeeded());
        assertNotNull(promise.future().result());
        verify(metadataExecutor, never()).execute(any());
    }

    @Test
    void shouldFailWhenNotDefinedColumns() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        if (promise.future().succeeded()) {
            fail("Unexpected success");
        }
        Assertions.assertEquals("Materialized view matviewdatamart.mat_view columns are not defined", promise.future().cause().getMessage());
        assertTrue(promise.future().failed());
    }

    @Test
    void shouldSuccessWhenIntToBigIntTypes() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id int, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.succeededFuture(true));
        when(entityDao.existsEntity(SCHEMA, MAT_VIEW_ENTITY_NAME))
                .thenReturn(Future.succeededFuture(false));
        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(metadataExecutor.execute(any())).thenReturn(Future.succeededFuture());
        when(entityDao.createEntity(any()))
                .thenReturn(Future.succeededFuture());

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        if (promise.future().cause() != null) {
            fail(promise.future().cause());
        }
        verify(materializedViewCacheService).put(any(EntityKey.class), any(MaterializedViewCacheValue.class));
        assertTrue(promise.future().succeeded());
        assertNotNull(promise.future().result());
    }

    @Test
    void shouldSuccessWhenAnyToDoubleType() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id double, PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT 1.0 * sum(id) FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.succeededFuture(true));
        when(entityDao.existsEntity(SCHEMA, MAT_VIEW_ENTITY_NAME))
                .thenReturn(Future.succeededFuture(false));
        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(metadataExecutor.execute(any())).thenReturn(Future.succeededFuture());
        when(entityDao.createEntity(any()))
                .thenReturn(Future.succeededFuture());

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        if (promise.future().cause() != null) {
            fail(promise.future().cause());
        }
        verify(materializedViewCacheService).put(any(EntityKey.class), any(MaterializedViewCacheValue.class));
        assertTrue(promise.future().succeeded());
        assertNotNull(promise.future().result());
    }

    @Test
    void shouldSuccessWhenStarQueryAndAllTypes() {
        // arrange
        ArrayList<EntityField> fields = new ArrayList<>();
        fields.add(EntityField.builder()
                .ordinalPosition(0)
                .name("id")
                .type(ColumnType.BIGINT)
                .nullable(false)
                .primaryOrder(1)
                .shardingOrder(1)
                .build());

        int pos = 1;
        for (ColumnType columnType : ColumnType.values()) {
            if (columnType == ColumnType.ANY || columnType == ColumnType.BLOB) continue;

            EntityField field = EntityField.builder()
                    .ordinalPosition(pos++)
                    .name("col_" + columnType.name().toLowerCase())
                    .type(columnType)
                    .nullable(true)
                    .build();

            switch (columnType) {
                case TIME:
                case TIMESTAMP:
                    field.setAccuracy(5);
                    break;
                case CHAR:
                case VARCHAR:
                    field.setSize(100);
                    break;
                case UUID:
                    field.setSize(36);
                    break;
            }

            fields.add(field);
        }

        tblEntity = Entity.builder()
                .name(TBL_ENTITY_NAME)
                .schema(SCHEMA)
                .entityType(EntityType.TABLE)
                .fields(fields)
                .build();

        Datamart matviewdatamart = new Datamart(SCHEMA, false, Arrays.asList(tblEntity));
        List<Datamart> logicSchema = Arrays.asList(matviewdatamart);
        lenient().when(logicalSchemaProvider.getSchemaFromQuery(any(), anyString())).thenReturn(Future.succeededFuture(logicSchema));

        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id bigint,\n" +
                "        col_varchar varchar(100),\n" +
                "        col_char char(100),\n" +
                "        col_bigint bigint,\n" +
                "        col_int int,\n" +
                "        col_int32 int32,\n" +
                "        col_double double,\n" +
                "        col_float float,\n" +
                "        col_date date,\n" +
                "        col_time time(5),\n" +
                "        col_timestamp timestamp(5),\n" +
                "        col_boolean boolean,\n" +
                "        col_uuid uuid,\n" +
                "        col_link link,\n" +
                "        PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.succeededFuture(true));
        when(entityDao.existsEntity(SCHEMA, MAT_VIEW_ENTITY_NAME))
                .thenReturn(Future.succeededFuture(false));
        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(metadataExecutor.execute(any())).thenReturn(Future.succeededFuture());
        when(entityDao.createEntity(any()))
                .thenReturn(Future.succeededFuture());

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        if (promise.future().cause() != null) {
            fail(promise.future().cause());
        }
        assertTrue(promise.future().succeeded());
        assertNotNull(promise.future().result());
    }

    @Test
    void shouldSuccessWhenExplicitQuery() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT id, name FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.succeededFuture(true));
        when(entityDao.existsEntity(SCHEMA, MAT_VIEW_ENTITY_NAME))
                .thenReturn(Future.succeededFuture(false));
        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(metadataExecutor.execute(any())).thenReturn(Future.succeededFuture());
        when(entityDao.createEntity(any()))
                .thenReturn(Future.succeededFuture());

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        if (promise.future().cause() != null) {
            fail(promise.future().cause());
        }
        verify(materializedViewCacheService).put(any(EntityKey.class), any(MaterializedViewCacheValue.class));
        assertTrue(promise.future().succeeded());
        assertNotNull(promise.future().result());
    }

    @Test
    void shouldAddAliasesToViewQueryColumns() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (" +
                "id_field bigint, " +
                "name_field varchar(100), " +
                "PRIMARY KEY(id_field))\n" +
                "DISTRIBUTED BY (id_field) " +
                "DATASOURCE_TYPE (ADG) " +
                "AS SELECT id, name FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.succeededFuture(true));
        when(entityDao.existsEntity(SCHEMA, MAT_VIEW_ENTITY_NAME))
                .thenReturn(Future.succeededFuture(false));
        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(metadataExecutor.execute(any())).thenReturn(Future.succeededFuture());
        when(entityDao.createEntity(any()))
                .thenReturn(Future.succeededFuture());

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        if (promise.future().cause() != null) {
            fail(promise.future().cause());
        }
        verify(entityDao).createEntity(entityCaptor.capture());
        Entity entity = entityCaptor.getValue();
        assertThat(entity.getViewQuery(), is("SELECT id AS id_field, name AS name_field FROM matviewdatamart.tbl"));

        verify(materializedViewCacheService).put(any(EntityKey.class), cachedViewCaptor.capture());
        entity = cachedViewCaptor.getValue().getEntity();
        assertThat(entity.getViewQuery(), is("SELECT id AS id_field, name AS name_field FROM matviewdatamart.tbl"));

        assertTrue(promise.future().succeeded());
        assertNotNull(promise.future().result());
    }

    @Test
    void shouldOverrideAliasesToViewQueryColumns() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (" +
                "id_field bigint, " +
                "name_field varchar(100), " +
                "PRIMARY KEY(id_field))\n" +
                "DISTRIBUTED BY (id_field) " +
                "DATASOURCE_TYPE (ADG) " +
                "AS SELECT id as ID_ALIAS_TO_OVERRIDE, name AS NAME_ALIAS_TO_OVERRIDE FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.succeededFuture(true));
        when(entityDao.existsEntity(SCHEMA, MAT_VIEW_ENTITY_NAME))
                .thenReturn(Future.succeededFuture(false));
        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(metadataExecutor.execute(any())).thenReturn(Future.succeededFuture());
        when(entityDao.createEntity(any()))
                .thenReturn(Future.succeededFuture());

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        if (promise.future().cause() != null) {
            fail(promise.future().cause());
        }
        verify(entityDao).createEntity(entityCaptor.capture());
        Entity entity = entityCaptor.getValue();
        assertThat(entity.getViewQuery(), is("SELECT id AS id_field, name AS name_field FROM matviewdatamart.tbl"));

        verify(materializedViewCacheService).put(any(EntityKey.class), cachedViewCaptor.capture());
        entity = cachedViewCaptor.getValue().getEntity();
        assertThat(entity.getViewQuery(), is("SELECT id AS id_field, name AS name_field FROM matviewdatamart.tbl"));

        assertTrue(promise.future().succeeded());
        assertNotNull(promise.future().result());
    }

    @Test
    void shouldAddAliasesToViewQueryColumnsWithLiterals() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (" +
                "id_field bigint, " +
                "name_field varchar(100), " +
                "PRIMARY KEY(id_field))\n" +
                "DISTRIBUTED BY (id_field) " +
                "DATASOURCE_TYPE (ADG) " +
                "AS SELECT 1, name FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.succeededFuture(true));
        when(entityDao.existsEntity(SCHEMA, MAT_VIEW_ENTITY_NAME))
                .thenReturn(Future.succeededFuture(false));
        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(metadataExecutor.execute(any())).thenReturn(Future.succeededFuture());
        when(entityDao.createEntity(any()))
                .thenReturn(Future.succeededFuture());

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        if (promise.future().cause() != null) {
            fail(promise.future().cause());
        }
        verify(entityDao).createEntity(entityCaptor.capture());
        Entity entity = entityCaptor.getValue();
        assertThat(entity.getViewQuery(), is("SELECT 1 AS id_field, name AS name_field FROM matviewdatamart.tbl"));

        verify(materializedViewCacheService).put(any(EntityKey.class), cachedViewCaptor.capture());
        entity = cachedViewCaptor.getValue().getEntity();
        assertThat(entity.getViewQuery(), is("SELECT 1 AS id_field, name AS name_field FROM matviewdatamart.tbl"));

        assertTrue(promise.future().succeeded());
        assertNotNull(promise.future().result());
    }

    @Test
    void shouldAddAliasesToViewQueryColumnsWithCast() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (" +
                "id_field int not null, " +
                "name_field varchar(100), " +
                "PRIMARY KEY(id_field))\n" +
                "DISTRIBUTED BY (id_field) " +
                "DATASOURCE_TYPE (ADG) " +
                "AS SELECT CAST(id as bigint), name FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.succeededFuture(true));
        when(entityDao.existsEntity(SCHEMA, MAT_VIEW_ENTITY_NAME))
                .thenReturn(Future.succeededFuture(false));
        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(metadataExecutor.execute(any())).thenReturn(Future.succeededFuture());
        when(entityDao.createEntity(any()))
                .thenReturn(Future.succeededFuture());

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        if (promise.future().cause() != null) {
            fail(promise.future().cause());
        }
        verify(entityDao).createEntity(entityCaptor.capture());
        Entity entity = entityCaptor.getValue();
        assertThat(entity.getViewQuery(), is("SELECT CAST(id AS BIGINT) AS id_field, name AS name_field FROM matviewdatamart.tbl"));

        verify(materializedViewCacheService).put(any(EntityKey.class), cachedViewCaptor.capture());
        entity = cachedViewCaptor.getValue().getEntity();
        assertThat(entity.getViewQuery(), is("SELECT CAST(id AS BIGINT) AS id_field, name AS name_field FROM matviewdatamart.tbl"));

        assertTrue(promise.future().succeeded());
        assertNotNull(promise.future().result());
    }

    @Test
    void shouldAddAliasesToViewQueryColumnsWithAggregator() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (" +
                "id_field bigint, " +
                "PRIMARY KEY(id_field))\n" +
                "DISTRIBUTED BY (id_field) " +
                "DATASOURCE_TYPE (ADG) " +
                "AS SELECT 1.0 * sum(id) FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.succeededFuture(true));
        when(entityDao.existsEntity(SCHEMA, MAT_VIEW_ENTITY_NAME))
                .thenReturn(Future.succeededFuture(false));
        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(metadataExecutor.execute(any())).thenReturn(Future.succeededFuture());
        when(entityDao.createEntity(any()))
                .thenReturn(Future.succeededFuture());

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        if (promise.future().cause() != null) {
            fail(promise.future().cause());
        }
        verify(entityDao).createEntity(entityCaptor.capture());
        Entity entity = entityCaptor.getValue();
        assertThat(entity.getViewQuery(), is("SELECT 1.0 * SUM(tbl.id) AS id_field FROM matviewdatamart.tbl AS tbl"));

        verify(materializedViewCacheService).put(any(EntityKey.class), cachedViewCaptor.capture());
        entity = cachedViewCaptor.getValue().getEntity();
        assertThat(entity.getViewQuery(), is("SELECT 1.0 * SUM(tbl.id) AS id_field FROM matviewdatamart.tbl AS tbl"));

        assertTrue(promise.future().succeeded());
        assertNotNull(promise.future().result());
    }

    @Test
    void shouldFailWhenNoQuerySourceType() {
        testFailDatasourceType("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                        "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl",
                "DATASOURCE_TYPE not specified or invalid");
    }

    @Test
    void shouldFailWhenInvalidQuerySourceType() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'DB'");

        Promise<QueryResult> promise = Promise.promise();

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        verifyNoInteractions(parserService, datamartDao, entityDao, materializedViewCacheService);
        assertTrue(promise.future().failed());
        assertException(InvalidSourceTypeException.class, "isn't a valid datasource type, please use one of the following:", promise.future().cause());
    }

    @Test
    void shouldFailWhenForbiddenSystemNames() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT id as sys_op FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        verifyNoInteractions(parserService, datamartDao, entityDao, materializedViewCacheService);
        assertTrue(promise.future().failed());
        assertException(ViewDisalowedOrDirectiveException.class, "View query contains forbidden system names: [sys_op]", promise.future().cause());
    }

    @Test
    void shouldFailWhenDisabledQuerySourceType() {
        testFailDatasourceType("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                        "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADQM'",
                "DATASOURCE_TYPE not specified or invalid");
    }

    @Test
    void shouldFailWhenDisabledDestination() {
        testFailDatasourceType("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                        "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADQM) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'",
                "DATASOURCE_TYPE has non exist items:");
    }

    @Test
    void shouldFailWhenForSystemTimePresentInQuery() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl FOR SYSTEM_TIME AS OF 1 DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        verifyNoInteractions(parserService, datamartDao, entityDao, materializedViewCacheService);
        assertTrue(promise.future().failed());
        assertException(ViewDisalowedOrDirectiveException.class, "Disallowed view or directive in a subquery", promise.future().cause());
    }

    @Test
    void shouldFailWhenTblEntityIsNotLogicTable() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Set<EntityType> allowedEntityTypes = EnumSet.of(EntityType.TABLE); // change this if something added

        Set<EntityType> disallowedEntityTypes = Arrays.stream(EntityType.values())
                .filter(entityType -> !allowedEntityTypes.contains(entityType))
                .collect(Collectors.toSet());

        for (EntityType entityType : disallowedEntityTypes) {
            // arrange 2
            reset(entityDao);
            when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                    .thenReturn(Future.succeededFuture(tblEntity));
            tblEntity.setEntityType(entityType);
            Promise<QueryResult> promise = Promise.promise();

            // act
            createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                    .onComplete(promise);

            // assert
            verify(entityDao).getEntity(SCHEMA, TBL_ENTITY_NAME);
            verifyNoMoreInteractions(entityDao);
            verifyNoInteractions(parserService);
            verifyNoInteractions(datamartDao);
            verifyNoInteractions(materializedViewCacheService);
            assertTrue(promise.future().failed());
            assertException(ViewDisalowedOrDirectiveException.class, "Disallowed view or directive in a subquery", promise.future().cause());
        }
    }

    @Test
    void shouldFailWhenTblEntityIsNotLogicTableInInnerJoin() {
        testWrongEntityTypeInJoin("INNER");
    }

    @Test
    void shouldFailWhenTblEntityIsNotLogicTableInFullJoin() {
        testWrongEntityTypeInJoin("FULL");
    }

    @Test
    void shouldFailWhenTblEntityIsNotLogicTableInLeftJoin() {
        testWrongEntityTypeInJoin("LEFT");
    }

    @Test
    void shouldFailWhenTblEntityIsNotLogicTableInRightJoin() {
        testWrongEntityTypeInJoin("RIGHT");
    }

    @Test
    void shouldFailWhenMultipleDatamarts() {
        // arrange
        String secondDatamartName = "tblmart";
        String secondTableName = "tbl2";
        Entity tbl2 = Entity.builder()
                .name(secondTableName)
                .schema(secondDatamartName)
                .entityType(EntityType.TABLE)
                .fields(Arrays.asList(
                        EntityField.builder()
                                .ordinalPosition(0)
                                .name("col_id")
                                .type(ColumnType.BIGINT)
                                .nullable(false)
                                .primaryOrder(1)
                                .shardingOrder(1)
                                .build()
                ))
                .build();


        Datamart mainDatamart = new Datamart(SCHEMA, true, Arrays.asList(tblEntity));
        Datamart secondDatamart = new Datamart(secondDatamartName, false, Arrays.asList(tbl2));
        List<Datamart> logicSchema = Arrays.asList(mainDatamart, secondDatamart);
        lenient().when(logicalSchemaProvider.getSchemaFromQuery(any(), anyString())).thenReturn(Future.succeededFuture(logicSchema));

        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT col_id, name, enddate FROM matviewdatamart.tbl, tblmart.tbl2 DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));

        when(entityDao.getEntity(secondDatamartName, tbl2.getName()))
                .thenReturn(Future.succeededFuture(tbl2));

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        verifyNoInteractions(datamartDao);
        verify(entityDao).getEntity(SCHEMA, tblEntity.getName());
        verify(entityDao).getEntity(secondDatamartName, tbl2.getName());
        verifyNoMoreInteractions(entityDao);
        verifyNoInteractions(metadataExecutor, materializedViewCacheService);

        assertTrue(promise.future().failed());
        assertException(MaterializedViewValidationException.class, "has multiple datamarts", promise.future().cause());
    }

    @Test
    void shouldFailWhenDifferentDatamartInEntityAndQuery() {
        // arrange
        String secondDatamartName = "tblmart";
        String secondTableName = "tbl2";
        Entity tbl2 = Entity.builder()
                .name(secondTableName)
                .schema(secondDatamartName)
                .entityType(EntityType.TABLE)
                .fields(Arrays.asList(
                        EntityField.builder()
                                .ordinalPosition(0)
                                .name("col_id")
                                .type(ColumnType.BIGINT)
                                .nullable(false)
                                .primaryOrder(1)
                                .shardingOrder(1)
                                .build()
                ))
                .build();


        Datamart secondDatamart = new Datamart(secondDatamartName, false, Arrays.asList(tbl2));
        List<Datamart> logicSchema = Arrays.asList(secondDatamart);
        lenient().when(logicalSchemaProvider.getSchemaFromQuery(any(), anyString())).thenReturn(Future.succeededFuture(logicSchema));

        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW matviewdatamart.mat_view (id bigint, PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT col_id FROM tblmart.tbl2 DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(entityDao.getEntity(secondDatamartName, secondTableName))
                .thenReturn(Future.succeededFuture(tbl2));

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        verifyNoInteractions(datamartDao);
        verify(entityDao).getEntity(secondDatamartName, secondTableName);
        verifyNoMoreInteractions(entityDao);
        verifyNoInteractions(metadataExecutor, materializedViewCacheService);

        assertTrue(promise.future().failed());
        assertException(MaterializedViewValidationException.class, "not equal to query", promise.future().cause());
    }

    @Test
    void shouldFailWhenNoPrimaryKey() {
        testFailOnValidation("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5))\n" +
                        "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'",
                "Primary keys and Sharding keys are required",
                ValidationDtmException.class);
    }

    @Test
    void shouldFailWhenNoShardingKey() {
        testFailOnValidation("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                        "DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'",
                "Primary keys and Sharding keys are required",
                ValidationDtmException.class);
    }

    @Test
    void shouldFailWhenCharColumnHasNoSize() {
        testFailOnValidation("CREATE MATERIALIZED VIEW mat_view (id bigint, name char, enddate timestamp(5), PRIMARY KEY(id))\n" +
                        "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'",
                "Specifying the size for columns[name] with types[CHAR] is required",
                ValidationDtmException.class);
    }

    @Test
    void shouldFailWhenQueryColumnsCountDifferWithView() {
        testFailOnValidation("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), num float, PRIMARY KEY(id))\n" +
                        "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'",
                "has conflict with query columns wrong count",
                MaterializedViewValidationException.class);
    }

    @Test
    void shouldFailWhenDuplicationFieldsNames() {
        testFailOnValidation("CREATE MATERIALIZED VIEW mat_view (id bigint, id bigint, PRIMARY KEY(id))\n" +
                        "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT id, id FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'",
                "has duplication fields names",
                ValidationDtmException.class);
    }

    @Test
    void shouldFailWhenDatamartException() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.failedFuture(new DatamartNotExistsException(SCHEMA)));

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        verify(entityDao).getEntity(SCHEMA, tblEntity.getName());
        verifyNoMoreInteractions(entityDao);
        verifyNoInteractions(metadataExecutor, materializedViewCacheService);

        assertTrue(promise.future().failed());
        assertException(DatamartNotExistsException.class, "Database " + SCHEMA + " does not exist", promise.future().cause());
    }

    @Test
    void shouldFailWhenDatamartNotExist() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.succeededFuture(false));

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        verify(entityDao).getEntity(SCHEMA, tblEntity.getName());
        verifyNoMoreInteractions(entityDao);
        verifyNoInteractions(metadataExecutor, materializedViewCacheService);

        assertTrue(promise.future().failed());
        assertException(DatamartNotExistsException.class, "Database " + SCHEMA + " does not exist", promise.future().cause());
    }

    @Test
    void shouldFailWhenEntityAlreadyExist() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));
        when(datamartDao.existsDatamart(SCHEMA))
                .thenReturn(Future.succeededFuture(true));
        when(entityDao.existsEntity(SCHEMA, MAT_VIEW_ENTITY_NAME)).thenReturn(Future.succeededFuture(true));

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        verify(entityDao).getEntity(SCHEMA, tblEntity.getName());
        verifyNoMoreInteractions(entityDao);
        verifyNoInteractions(metadataExecutor, materializedViewCacheService);

        assertTrue(promise.future().failed());
        assertException(EntityAlreadyExistsException.class, "Entity " + MAT_VIEW_ENTITY_NAME + " already exists", promise.future().cause());
    }

    @Test
    void shouldFailWhenInvalidTimestampFormat() {
        // arrange
        DdlRequestContext context = getContext("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) DATASOURCE_TYPE (ADG) AS SELECT * FROM matviewdatamart.tbl WHERE enddate = '123456' DATASOURCE_TYPE = 'ADB'");

        Promise<QueryResult> promise = Promise.promise();

        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        assertTrue(promise.future().failed());
        assertTrue(promise.future().cause() instanceof ValidationDtmException);
    }

    private void testWrongEntityTypeInJoin(String joinType) {
        // arrange
        String secondTableName = "tbl2";
        Entity tbl2 = Entity.builder()
                .name(secondTableName)
                .schema(SCHEMA)
                .fields(Arrays.asList(
                        EntityField.builder()
                                .ordinalPosition(0)
                                .name("col_id")
                                .type(ColumnType.BIGINT)
                                .nullable(false)
                                .primaryOrder(1)
                                .shardingOrder(1)
                                .build()
                ))
                .build();
        DdlRequestContext context = getContext(String.format("CREATE MATERIALIZED VIEW mat_view (id bigint, name varchar(100), enddate timestamp(5), PRIMARY KEY(id))\n" +
                "DISTRIBUTED BY (id) " +
                "DATASOURCE_TYPE (ADG) AS " +
                "SELECT * FROM matviewdatamart.tbl %s JOIN matviewdatamart.tbl2 ON matviewdatamart.id = matviewdatamart.id " +
                "DATASOURCE_TYPE = 'ADB'", joinType));

        Set<EntityType> allowedEntityTypes = EnumSet.of(EntityType.TABLE); // change this if something added

        Set<EntityType> disallowedEntityTypes = Arrays.stream(EntityType.values())
                .filter(entityType -> !allowedEntityTypes.contains(entityType))
                .collect(Collectors.toSet());


        for (EntityType entityType : disallowedEntityTypes) {
            // arrange 2
            reset(entityDao);

            when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                    .thenReturn(Future.succeededFuture(tblEntity));
            when(entityDao.getEntity(SCHEMA, secondTableName))
                    .thenReturn(Future.succeededFuture(tbl2));
            tbl2.setEntityType(entityType);
            Promise<QueryResult> promise = Promise.promise();

            // act
            createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                    .onComplete(promise);

            // assert
            verify(entityDao).getEntity(SCHEMA, TBL_ENTITY_NAME);
            verify(entityDao).getEntity(SCHEMA, secondTableName);
            verifyNoMoreInteractions(entityDao);
            verifyNoInteractions(parserService);
            verifyNoInteractions(datamartDao);
            verifyNoInteractions(materializedViewCacheService);
            assertTrue(promise.future().failed());
            System.out.println(promise.future().cause().getMessage());
            assertException(ViewDisalowedOrDirectiveException.class, "Disallowed view or directive in a subquery", promise.future().cause());
        }
    }

    private void testFailDatasourceType(String sql, String errorMessage) {
        // arrange
        DdlRequestContext context = getContext(sql);

        Promise<QueryResult> promise = Promise.promise();

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        verifyNoInteractions(parserService, datamartDao, entityDao, materializedViewCacheService);
        assertTrue(promise.future().failed());
        assertException(MaterializedViewValidationException.class, errorMessage, promise.future().cause());
    }

    private void testFailOnValidation(String sql, String errorMessage, Class<? extends Exception> exceptionClass) {
        // arrange
        DdlRequestContext context = getContext(sql);

        Promise<QueryResult> promise = Promise.promise();

        when(entityDao.getEntity(SCHEMA, tblEntity.getName()))
                .thenReturn(Future.succeededFuture(tblEntity));

        // act
        createTableDdlExecutor.execute(context, MAT_VIEW_ENTITY_NAME)
                .onComplete(promise);

        // assert
        verifyNoInteractions(datamartDao);
        verify(entityDao).getEntity(SCHEMA, tblEntity.getName());
        verifyNoMoreInteractions(entityDao);
        verifyNoInteractions(metadataExecutor, materializedViewCacheService);

        assertTrue(promise.future().failed());
        assertException(exceptionClass, errorMessage, promise.future().cause());
    }

    @SneakyThrows
    private DdlRequestContext getContext(String sql) {
        QueryRequest queryRequest = new QueryRequest();
        queryRequest.setRequestId(UUID.randomUUID());
        queryRequest.setDatamartMnemonic(SCHEMA);
        queryRequest.setSql(sql);
        SqlNode sqlNode = planner.parse(queryRequest.getSql());
        defaultDatamartSetter.set(sqlNode, SCHEMA);
        DdlRequestContext context = new DdlRequestContext(null, new DatamartRequest(queryRequest), sqlNode, null, null);
        context.setDatamartName(SCHEMA);
        return context;
    }

    @SneakyThrows
    private QueryParserResponse parse(QueryParserRequest request) {
        val context = contextProvider.context(request.getSchema());
        val sql = request.getQuery().toSqlString(SQL_DIALECT).getSql();
        val parse = context.getPlanner().parse(sql);
        val validatedQuery = context.getPlanner().validate(parse);
        val relQuery = context.getPlanner().rel(validatedQuery);
        return new QueryParserResponse(
                context,
                request.getSchema(),
                relQuery,
                validatedQuery);
    }
}