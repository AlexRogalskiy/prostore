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
package io.arenadata.dtm.query.execution.plugin.adb.dml.service.upsert;

import io.arenadata.dtm.common.delta.DeltaInformation;
import io.arenadata.dtm.common.delta.DeltaType;
import io.arenadata.dtm.common.model.ddl.ColumnType;
import io.arenadata.dtm.common.model.ddl.Entity;
import io.arenadata.dtm.common.model.ddl.EntityField;
import io.arenadata.dtm.common.reader.QueryParameters;
import io.arenadata.dtm.query.calcite.core.rel2sql.DtmRelToSqlConverter;
import io.arenadata.dtm.query.calcite.core.util.SqlNodeTemplates;
import io.arenadata.dtm.query.execution.model.metadata.Datamart;
import io.arenadata.dtm.query.execution.plugin.adb.base.factory.adqm.AdqmConnectorSqlFactory;
import io.arenadata.dtm.query.execution.plugin.adb.base.service.castservice.AdqmColumnsCastService;
import io.arenadata.dtm.query.execution.plugin.adb.calcite.configuration.CalciteConfiguration;
import io.arenadata.dtm.query.execution.plugin.adb.calcite.factory.AdbCalciteSchemaFactory;
import io.arenadata.dtm.query.execution.plugin.adb.calcite.factory.AdbSchemaFactory;
import io.arenadata.dtm.query.execution.plugin.adb.calcite.service.AdbCalciteContextProvider;
import io.arenadata.dtm.query.execution.plugin.adb.calcite.service.AdbCalciteDMLQueryParserService;
import io.arenadata.dtm.query.execution.plugin.adb.enrichment.service.AdbDmlQueryExtendWithoutHistoryService;
import io.arenadata.dtm.query.execution.plugin.adb.enrichment.service.AdbQueryEnrichmentService;
import io.arenadata.dtm.query.execution.plugin.adb.enrichment.service.AdbQueryGenerator;
import io.arenadata.dtm.query.execution.plugin.adb.enrichment.service.AdbSchemaExtender;
import io.arenadata.dtm.query.execution.plugin.adb.query.service.DatabaseExecutor;
import io.arenadata.dtm.query.execution.plugin.adb.query.service.impl.AdbQueryTemplateExtractor;
import io.arenadata.dtm.query.execution.plugin.api.request.UpsertSelectRequest;
import io.arenadata.dtm.query.execution.plugin.api.service.shared.adqm.AdqmSharedService;
import io.arenadata.dtm.query.execution.plugin.api.shared.adqm.AdqmSharedProperties;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import lombok.val;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static io.arenadata.dtm.query.execution.plugin.adb.utils.TestUtils.DEFINITION_SERVICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith({VertxExtension.class, MockitoExtension.class})
class AdqmUpsertSelectHandlerTest {

    private static final String CLICKHOUSE_SERVER = "clickhouse_server";
    private static final String ENV = "env";
    private static final String DATAMART = "datamart";
    private static final String ENTITY_NAME = "entity_name";
    private static final String USER = "user";
    private static final String PASSWORD = "password";
    private static final int CONNECT_TIMEOUT = 1234;
    private static final long READ_TIMEOUT = 2345L;
    private static final int REQUEST_TIMEOUT = 3456;
    private static final String QUERY = "query";

    @Mock
    private DatabaseExecutor databaseExecutor;

    @Mock
    private AdqmSharedService adqmSharedService;

    private AdqmUpsertSelectHandler adqmUpsertSelectHandler;

    @Captor
    private ArgumentCaptor<String> sqlCaptor;

    @BeforeEach
    void setUp(Vertx vertx) {
        val calciteConfiguration = new CalciteConfiguration();
        val sqlDialect = calciteConfiguration.adbSqlDialect();
        val templateExtractor = new AdbQueryTemplateExtractor(DEFINITION_SERVICE, sqlDialect);
        val factory = calciteConfiguration.ddlParserImplFactory();
        val configParser = calciteConfiguration.configDdlParser(factory);
        val schemaFactory = new AdbSchemaFactory();
        val calciteSchemaFactory = new AdbCalciteSchemaFactory(schemaFactory);
        val contextProvider = new AdbCalciteContextProvider(configParser, calciteSchemaFactory);
        val parserService = new AdbCalciteDMLQueryParserService(contextProvider, vertx);
        val columnsCastService = new AdqmColumnsCastService(sqlDialect);
        val schemaExtender = new AdbSchemaExtender();
        val queryExtendService = new AdbDmlQueryExtendWithoutHistoryService();
        val relToSqlConverter = new DtmRelToSqlConverter(sqlDialect);
        val queryGenerator = new AdbQueryGenerator(queryExtendService, sqlDialect, relToSqlConverter);
        val enrichmentService = new AdbQueryEnrichmentService(queryGenerator, contextProvider, schemaExtender);

        lenient().when(adqmSharedService.getSharedProperties()).thenReturn(new AdqmSharedProperties(CLICKHOUSE_SERVER, USER, PASSWORD, CONNECT_TIMEOUT, REQUEST_TIMEOUT));
        val connectorSqlFactory = new AdqmConnectorSqlFactory(adqmSharedService);
        adqmUpsertSelectHandler = new AdqmUpsertSelectHandler(connectorSqlFactory, databaseExecutor, adqmSharedService, parserService, columnsCastService, enrichmentService, templateExtractor, sqlDialect);

        when(databaseExecutor.executeUpdate(anyString())).thenReturn(Future.succeededFuture());
        when(databaseExecutor.executeWithParams(anyString(), any(), anyList())).thenReturn(Future.succeededFuture());
        lenient().when(adqmSharedService.flushActualTable(anyString(), anyString(), any(Entity.class))).thenReturn(Future.succeededFuture());
        lenient().when(adqmSharedService.closeVersionSqlByTableActual(anyString(), anyString(), any(Entity.class), anyLong())).thenReturn(Future.succeededFuture());
    }

    @Test
    void shouldSuccessWhenWithoutCondition(VertxTestContext testContext) {
        val request = getUpsertRequest("UPSERT INTO datamart.abc (id, col1, col2, col3, col4) SELECT id, col1, col2, col3, col4 FROM datamart.src");

        adqmUpsertSelectHandler.handle(request)
                .onComplete(ar -> testContext.verify(() -> {
                    // assert
                    if (!ar.succeeded()) {
                        fail(ar.cause());
                    }

                    verify(databaseExecutor, times(3)).executeUpdate(sqlCaptor.capture());
                    verify(databaseExecutor).executeWithParams(sqlCaptor.capture(), any(), any());

                    List<String> allValues = sqlCaptor.getAllValues();
                    assertEquals("DROP EXTERNAL TABLE IF EXISTS datamart.CLICKHOUSE_EXT_abc", allValues.get(0));
                    assertEquals("CREATE WRITABLE EXTERNAL TABLE datamart.CLICKHOUSE_EXT_abc\n" +
                            "(id int8,col1 int8,col2 int8,col3 int8,col4 int4,sys_from int8,sys_to int8,sys_op int4,sys_close_date int8,sign int4) LOCATION ('pxf://dev__datamart.abc_actual?PROFILE=clickhouse-insert&CLICKHOUSE_SERVERS=clickhouse_server&USER=user&PASSWORD=password&TIMEOUT_CONNECT=1234&TIMEOUT_REQUEST=3456')\n" +
                            "FORMAT 'CUSTOM' (FORMATTER = 'pxfwritable_export')", allValues.get(1));
                    assertEquals("DROP EXTERNAL TABLE IF EXISTS datamart.CLICKHOUSE_EXT_abc", allValues.get(2));
                    assertEquals("INSERT INTO datamart.CLICKHOUSE_EXT_abc (id, col1, col2, col3, col4, sys_from, sys_to, sys_op, sys_close_date, sign) (SELECT id, CAST(EXTRACT(EPOCH FROM col1) / 86400 AS BIGINT), CAST(EXTRACT(EPOCH FROM col2) * 1000000 AS BIGINT), CAST(EXTRACT(EPOCH FROM col3) * 1000000 AS BIGINT), CAST(col4 AS INTEGER), 1 AS sys_from, 9223372036854775807 AS sys_to, 0 AS sys_op, 9223372036854775807 AS sys_close_date, 1 AS sign FROM datamart.src_actual WHERE sys_from <= 0 AND COALESCE(sys_to, 9223372036854775807) >= 0)", allValues.get(3));
                }).completeNow());
    }

    @Test
    void shouldSuccessAndNotCopyOffsetLimitParams(VertxTestContext testContext) {
        // arrange
        val extractedParams = Arrays.asList((SqlNode) SqlNodeTemplates.longLiteral(123L), SqlLiteral.createBoolean(true, SqlParserPos.ZERO), SqlNodeTemplates.longLiteral(1L), SqlNodeTemplates.longLiteral(2L));
        val extractedParamsTypes = Arrays.asList(SqlTypeName.BIGINT, SqlTypeName.BOOLEAN, SqlTypeName.BIGINT, SqlTypeName.BIGINT);
        val request = getUpsertRequest("UPSERT INTO datamart.abc (id) SELECT id FROM datamart.src WHERE id>? and col4=? ORDER BY id LIMIT ? OFFSET ?",
                null, extractedParams, extractedParamsTypes);

        // act
        adqmUpsertSelectHandler.handle(request)
                .onComplete(ar -> testContext.verify(() -> {
                    // assert
                    if (!ar.succeeded()) {
                        fail(ar.cause());
                    }

                    verify(databaseExecutor, times(3)).executeUpdate(sqlCaptor.capture());
                    verify(databaseExecutor).executeWithParams(sqlCaptor.capture(), any(), any());

                    List<String> allValues = sqlCaptor.getAllValues();
                    assertEquals("DROP EXTERNAL TABLE IF EXISTS datamart.CLICKHOUSE_EXT_abc", allValues.get(0));
                    assertEquals("CREATE WRITABLE EXTERNAL TABLE datamart.CLICKHOUSE_EXT_abc\n" +
                            "(id int8,col1 int8,col2 int8,col3 int8,col4 int4,sys_from int8,sys_to int8,sys_op int4,sys_close_date int8,sign int4) LOCATION ('pxf://dev__datamart.abc_actual?PROFILE=clickhouse-insert&CLICKHOUSE_SERVERS=clickhouse_server&USER=user&PASSWORD=password&TIMEOUT_CONNECT=1234&TIMEOUT_REQUEST=3456')\n" +
                            "FORMAT 'CUSTOM' (FORMATTER = 'pxfwritable_export')", allValues.get(1));
                    assertEquals("DROP EXTERNAL TABLE IF EXISTS datamart.CLICKHOUSE_EXT_abc", allValues.get(2));
                    assertEquals("INSERT INTO datamart.CLICKHOUSE_EXT_abc (id, sys_from, sys_to, sys_op, sys_close_date, sign) (SELECT id, 1 AS sys_from, 9223372036854775807 AS sys_to, 0 AS sys_op, 9223372036854775807 AS sys_close_date, 1 AS sign FROM (SELECT id, col1, col2, col3, col4 FROM datamart.src_actual WHERE sys_from <= 0 AND COALESCE(sys_to, 9223372036854775807) >= 0) AS t0 WHERE id > 123 AND col4 = TRUE ORDER BY id LIMIT 1 OFFSET 2)", allValues.get(3));
                }).completeNow());
    }

    @Test
    void shouldSuccessWhenOnlyPkColumn(VertxTestContext testContext) {
        // arrange
        val request = getUpsertRequest("UPSERT INTO datamart.abc (id) SELECT id FROM datamart.src");

        // act
        adqmUpsertSelectHandler.handle(request)
                .onComplete(ar -> testContext.verify(() -> {
                    // assert
                    if (!ar.succeeded()) {
                        fail(ar.cause());
                    }

                    verify(databaseExecutor).executeWithParams(sqlCaptor.capture(), any(), any());
                    assertEquals("INSERT INTO datamart.CLICKHOUSE_EXT_abc (id, sys_from, sys_to, sys_op, sys_close_date, sign) (SELECT id, 1 AS sys_from, 9223372036854775807 AS sys_to, 0 AS sys_op, 9223372036854775807 AS sys_close_date, 1 AS sign FROM datamart.src_actual WHERE sys_from <= 0 AND COALESCE(sys_to, 9223372036854775807) >= 0)", sqlCaptor.getValue());
                    verifyNoMoreInteractions(databaseExecutor);
                }).completeNow());
    }

    @Test
    void shouldSuccessWhenWithoutColumns(VertxTestContext testContext) {
        // arrange
        val request = getUpsertRequest("UPSERT INTO datamart.abc SELECT id, col1, col2, col3, col4 FROM datamart.src");

        // act
        adqmUpsertSelectHandler.handle(request)
                .onComplete(ar -> testContext.verify(() -> {
                    // assert
                    if (!ar.succeeded()) {
                        fail(ar.cause());
                    }

                    verify(databaseExecutor).executeWithParams(sqlCaptor.capture(), any(), any());
                    assertEquals("INSERT INTO datamart.CLICKHOUSE_EXT_abc (SELECT id, CAST(EXTRACT(EPOCH FROM col1) / 86400 AS BIGINT), CAST(EXTRACT(EPOCH FROM col2) * 1000000 AS BIGINT), CAST(EXTRACT(EPOCH FROM col3) * 1000000 AS BIGINT), CAST(col4 AS INTEGER), 1 AS sys_from, 9223372036854775807 AS sys_to, 0 AS sys_op, 9223372036854775807 AS sys_close_date, 1 AS sign FROM datamart.src_actual WHERE sys_from <= 0 AND COALESCE(sys_to, 9223372036854775807) >= 0)", sqlCaptor.getValue());
                    verifyNoMoreInteractions(databaseExecutor);
                }).completeNow());
    }

    @Test
    void shouldSuccessWhenStarQuery(VertxTestContext testContext) {
        // arrange
        val request = getUpsertRequest("UPSERT INTO datamart.abc SELECT * FROM datamart.src");

        // act
        adqmUpsertSelectHandler.handle(request)
                .onComplete(ar -> testContext.verify(() -> {
                    // assert
                    if (!ar.succeeded()) {
                        fail(ar.cause());
                    }

                    verify(databaseExecutor).executeWithParams(sqlCaptor.capture(), any(), any());
                    assertEquals("INSERT INTO datamart.CLICKHOUSE_EXT_abc (SELECT id, CAST(EXTRACT(EPOCH FROM col1) / 86400 AS BIGINT), CAST(EXTRACT(EPOCH FROM col2) * 1000000 AS BIGINT), CAST(EXTRACT(EPOCH FROM col3) * 1000000 AS BIGINT), CAST(col4 AS INTEGER), 1 AS sys_from, 9223372036854775807 AS sys_to, 0 AS sys_op, 9223372036854775807 AS sys_close_date, 1 AS sign FROM datamart.src_actual WHERE sys_from <= 0 AND COALESCE(sys_to, 9223372036854775807) >= 0)", sqlCaptor.getValue());
                    verifyNoMoreInteractions(databaseExecutor);
                }).completeNow());
    }

    @Test
    void shouldFailWhenExecuteWithParamsFail(VertxTestContext testContext) {
        // arrange
        reset(databaseExecutor);
        when(databaseExecutor.executeUpdate(anyString())).thenReturn(Future.succeededFuture());
        when(databaseExecutor.executeWithParams(any(), any(), any())).thenReturn(Future.failedFuture(new RuntimeException("Exception")));

        val request = getUpsertRequest("UPSERT INTO datamart.abc (id) SELECT id FROM datamart.src");

        // act
        adqmUpsertSelectHandler.handle(request)
                .onComplete(ar -> testContext.verify(() -> {
                    // assert
                    if (ar.succeeded()) {
                        fail("Unexpected success");
                    }

                    assertEquals("Exception", ar.cause().getMessage());
                }).completeNow());
    }

    private UpsertSelectRequest getUpsertRequest(String sql) {
        return getUpsertRequest(sql, null, Collections.emptyList(), Collections.emptyList());
    }

    private UpsertSelectRequest getUpsertRequest(String sql, QueryParameters queryParameters, List<SqlNode> extractedParams, List<SqlTypeName> extractedParamsTypes) {
        SqlInsert sqlNode = (SqlInsert) DEFINITION_SERVICE.processingQuery(sql);
        SqlNode source = sqlNode.getSource();
        Entity entity = prepareEntity("abc");
        Entity entity2 = prepareEntity("src");
        Datamart datamart = Datamart.builder()
                .mnemonic("datamart")
                .isDefault(true)
                .entities(Arrays.asList(entity, entity2))
                .build();
        List<Datamart> datamarts = Arrays.asList(datamart);
        List<DeltaInformation> deltaInformations = Arrays.asList(DeltaInformation.builder()
                .selectOnNum(0L)
                .tableName("src")
                .schemaName("datamart")
                .type(DeltaType.WITHOUT_SNAPSHOT)
                .build());
        return new UpsertSelectRequest(UUID.randomUUID(), "dev", "datamart", 1L, entity, sqlNode, queryParameters, datamarts, deltaInformations, source, null, extractedParams, extractedParamsTypes);
    }

    private Entity prepareEntity(String name) {
        return Entity.builder()
                .schema("datamart")
                .name(name)
                .fields(Arrays.asList(
                        EntityField.builder()
                                .name("id")
                                .primaryOrder(1)
                                .ordinalPosition(0)
                                .type(ColumnType.BIGINT)
                                .nullable(true)
                                .build(),
                        EntityField.builder()
                                .name("col1")
                                .ordinalPosition(1)
                                .type(ColumnType.DATE)
                                .nullable(true)
                                .build(),
                        EntityField.builder()
                                .name("col2")
                                .ordinalPosition(2)
                                .type(ColumnType.TIME)
                                .nullable(true)
                                .build(),
                        EntityField.builder()
                                .name("col3")
                                .ordinalPosition(3)
                                .type(ColumnType.TIMESTAMP)
                                .nullable(true)
                                .build(),
                        EntityField.builder()
                                .name("col4")
                                .ordinalPosition(4)
                                .type(ColumnType.BOOLEAN)
                                .nullable(true)
                                .build()
                ))
                .build();
    }
}
