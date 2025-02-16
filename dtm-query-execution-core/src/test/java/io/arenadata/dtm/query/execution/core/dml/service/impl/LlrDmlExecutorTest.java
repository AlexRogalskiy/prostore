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
package io.arenadata.dtm.query.execution.core.dml.service.impl;

import io.arenadata.dtm.cache.service.CacheService;
import io.arenadata.dtm.common.cache.PreparedQueryKey;
import io.arenadata.dtm.common.cache.PreparedQueryValue;
import io.arenadata.dtm.common.cache.QueryTemplateKey;
import io.arenadata.dtm.common.cache.SourceQueryTemplateValue;
import io.arenadata.dtm.common.dml.ShardingCategory;
import io.arenadata.dtm.common.metrics.RequestMetrics;
import io.arenadata.dtm.common.reader.*;
import io.arenadata.dtm.query.calcite.core.dto.delta.DeltaQueryPreprocessorResponse;
import io.arenadata.dtm.query.calcite.core.service.QueryTemplateExtractor;
import io.arenadata.dtm.query.execution.core.base.service.delta.DeltaQueryPreprocessor;
import io.arenadata.dtm.query.execution.core.dml.dto.DmlRequest;
import io.arenadata.dtm.query.execution.core.dml.dto.DmlRequestContext;
import io.arenadata.dtm.query.execution.core.dml.dto.LlrRequestContext;
import io.arenadata.dtm.query.execution.core.dml.factory.LlrRequestContextFactory;
import io.arenadata.dtm.query.execution.core.dml.service.*;
import io.arenadata.dtm.query.execution.core.dml.service.view.ViewReplacerService;
import io.arenadata.dtm.query.execution.core.metrics.service.MetricsService;
import io.arenadata.dtm.query.execution.core.plugin.service.DataSourcePluginService;
import io.arenadata.dtm.query.execution.core.utils.TestUtils;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.EnumSet;
import java.util.UUID;

import static io.arenadata.dtm.query.execution.core.utils.TestUtils.SQL_DIALECT;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.*;


@ExtendWith({MockitoExtension.class, VertxExtension.class})
class LlrDmlExecutorTest {

    @Mock
    private DataSourcePluginService dataSourcePluginService;
    @Mock
    private AcceptableSourceTypesDefinitionService acceptableSourceTypesService;
    @Mock
    private DeltaQueryPreprocessor deltaQueryPreprocessor;
    @Mock
    private ViewReplacerService viewReplacerService;
    @Mock
    private InformationSchemaExecutor infoSchemaExecutor;
    @Mock
    private InformationSchemaDefinitionService infoSchemaDefService;
    @Mock
    private MetricsService<RequestMetrics> metricsService;
    @Mock
    private QueryTemplateExtractor templateExtractor;
    @Mock
    private CacheService<QueryTemplateKey, SourceQueryTemplateValue> queryCacheService;
    @Mock
    private CacheService<PreparedQueryKey, PreparedQueryValue> preparedQueryCacheService;
    @Mock
    private LlrRequestContextFactory llrRequestContextFactory;
    @Mock
    private SelectCategoryQualifier selectCategoryQualifier;
    @Mock
    private ShardingCategoryQualifier shardingCategoryQualifier;
    @Mock
    private SuitablePluginSelector suitablePluginSelector;
    @Mock
    private SqlParametersTypeExtractor parametersTypeExtractor;
    @Mock
    private RelRoot relNode;

    private LlrDmlExecutor dmlExecutor;


    @BeforeEach
    void setUp() {
        dmlExecutor = new LlrDmlExecutor(dataSourcePluginService, acceptableSourceTypesService, deltaQueryPreprocessor, viewReplacerService,
                infoSchemaExecutor, infoSchemaDefService, metricsService, templateExtractor, queryCacheService, preparedQueryCacheService,
                llrRequestContextFactory, selectCategoryQualifier, shardingCategoryQualifier, suitablePluginSelector, SQL_DIALECT, parametersTypeExtractor);

        lenient().when(viewReplacerService.replace(any(), any())).thenAnswer(invocation -> Future.succeededFuture(invocation.getArgument(0)));
        lenient().when(deltaQueryPreprocessor.process(any())).thenAnswer(invocation -> Future.succeededFuture(new DeltaQueryPreprocessorResponse(Collections.emptyList(), invocation.getArgument(0))));
        lenient().when(templateExtractor.extract(any(SqlNode.class))).thenAnswer(invocation -> {
            SqlNode sqlNode = invocation.getArgument(0);
            return new QueryTemplateResult(sqlNode.toSqlString(SQL_DIALECT).getSql(), sqlNode, Collections.emptyList());
        });
        lenient().when(llrRequestContextFactory.create(any(DeltaQueryPreprocessorResponse.class), any(DmlRequestContext.class))).thenAnswer(invocation -> {
            DeltaQueryPreprocessorResponse preprocessorResponse = invocation.getArgument(0);
            DmlRequestContext requestContext = invocation.getArgument(1);
            SqlNode sqlNode = requestContext.getSqlNode();

            LlrRequestContext llrRequestContextFuture = LlrRequestContext.builder()
                    .deltaInformations(preprocessorResponse.getDeltaInformations())
                    .originalQuery(sqlNode)
                    .dmlRequestContext(requestContext)
                    .sourceRequest(QuerySourceRequest.builder()
                            .query(sqlNode)
                            .logicalSchema(Collections.emptyList())
                            .metadata(Collections.emptyList())
                            .queryRequest(QueryRequest.builder().build())
                            .queryTemplate(new QueryTemplateResult(sqlNode.toSqlString(SQL_DIALECT).getSql(), sqlNode, Collections.emptyList()))
                            .build())
                    .build();
            llrRequestContextFuture.setRelNode(relNode);

            return Future.succeededFuture(llrRequestContextFuture);
        });
        lenient().when(parametersTypeExtractor.extract(any())).thenReturn(Collections.emptyList());
        lenient().when(acceptableSourceTypesService.define(any())).thenAnswer(invocation -> Future.succeededFuture(EnumSet.of(SourceType.ADB)));
        lenient().when(queryCacheService.put(any(), any())).thenAnswer(invocation -> Future.succeededFuture(invocation.getArgument(1)));
    }

    @Test
    void shouldCallLlr(VertxTestContext testContext) {
        // arrange
        String sql = "select * from users";
        SqlNode sqlNode = TestUtils.DEFINITION_SERVICE.processingQuery(sql);
        QueryRequest queryRequest = QueryRequest.builder()
                .requestId(UUID.randomUUID())
                .datamartMnemonic("datamart")
                .isPrepare(false)
                .sql(sql)
                .build();
        DmlRequestContext dmlRequest = DmlRequestContext.builder()
                .envName("dev")
                .request(new DmlRequest(queryRequest))
                .sourceType(SourceType.ADB)
                .sqlNode(sqlNode)
                .build();

        when(dataSourcePluginService.llr(any(), any(), any())).thenReturn(Future.succeededFuture(QueryResult.emptyResult()));

        // act
        Future<QueryResult> result = dmlExecutor.execute(dmlRequest);

        // assert
        result.onComplete(ar -> testContext.verify(() -> {
            if (ar.failed()) {
                fail(ar.cause());
            }

            verify(dataSourcePluginService).llr(any(), any(), any());
        }).completeNow());
    }

    @Test
    void shouldCallLlrOnLimitGroupBy(VertxTestContext testContext) {
        // arrange
        String sql = "select * from users ESTIMATE_ONLY order by id limit 1";
        SqlNode sqlNode = TestUtils.DEFINITION_SERVICE.processingQuery(sql);
        QueryRequest queryRequest = QueryRequest.builder()
                .requestId(UUID.randomUUID())
                .datamartMnemonic("datamart")
                .isPrepare(false)
                .sql(sql)
                .build();
        DmlRequestContext dmlRequest = DmlRequestContext.builder()
                .envName("dev")
                .request(new DmlRequest(queryRequest))
                .sourceType(SourceType.ADB)
                .sqlNode(sqlNode)
                .build();

        when(dataSourcePluginService.llr(any(), any(), any())).thenReturn(Future.succeededFuture(QueryResult.emptyResult()));

        // act
        Future<QueryResult> result = dmlExecutor.execute(dmlRequest);

        // assert
        result.onComplete(ar -> testContext.verify(() -> {
            if (ar.failed()) {
                fail(ar.cause());
            }

            verify(dataSourcePluginService).llr(any(), any(), any());
        }).completeNow());
    }

    @Test
    void shouldCallLlrEstimate(VertxTestContext testContext) {
        // arrange
        String sql = "select * from users ESTIMATE_ONLY";
        SqlNode sqlNode = TestUtils.DEFINITION_SERVICE.processingQuery(sql);
        QueryRequest queryRequest = QueryRequest.builder()
                .requestId(UUID.randomUUID())
                .datamartMnemonic("datamart")
                .isPrepare(false)
                .sql(sql)
                .build();
        DmlRequestContext dmlRequest = DmlRequestContext.builder()
                .envName("dev")
                .request(new DmlRequest(queryRequest))
                .sourceType(SourceType.ADB)
                .sqlNode(sqlNode)
                .build();

        when(dataSourcePluginService.llrEstimate(any(), any(), any())).thenReturn(Future.succeededFuture(QueryResult.emptyResult()));

        // act
        Future<QueryResult> result = dmlExecutor.execute(dmlRequest);

        // assert
        result.onComplete(ar -> testContext.verify(() -> {
            if (ar.failed()) {
                fail(ar.cause());
            }

            verify(dataSourcePluginService).llrEstimate(any(), any(), any());
        }).completeNow());
    }

    @Test
    void shouldCallLlrEstimateOnLimitGroupBy(VertxTestContext testContext) {
        // arrange
        String sql = "select * from users ESTIMATE_ONLY order by id limit 1 ESTIMATE_ONLY";
        SqlNode sqlNode = TestUtils.DEFINITION_SERVICE.processingQuery(sql);
        QueryRequest queryRequest = QueryRequest.builder()
                .requestId(UUID.randomUUID())
                .datamartMnemonic("datamart")
                .isPrepare(false)
                .sql(sql)
                .build();
        DmlRequestContext dmlRequest = DmlRequestContext.builder()
                .envName("dev")
                .request(new DmlRequest(queryRequest))
                .sourceType(SourceType.ADB)
                .sqlNode(sqlNode)
                .build();

        when(dataSourcePluginService.llrEstimate(any(), any(), any())).thenReturn(Future.succeededFuture(QueryResult.emptyResult()));

        // act
        Future<QueryResult> result = dmlExecutor.execute(dmlRequest);

        // assert
        result.onComplete(ar -> testContext.verify(() -> {
            if (ar.failed()) {
                fail(ar.cause());
            }

            verify(dataSourcePluginService).llrEstimate(any(), any(), any());
        }).completeNow());
    }

}