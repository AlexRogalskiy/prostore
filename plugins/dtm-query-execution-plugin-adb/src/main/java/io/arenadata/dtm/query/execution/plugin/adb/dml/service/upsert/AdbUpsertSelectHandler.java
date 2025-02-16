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

import io.arenadata.dtm.common.dto.QueryParserRequest;
import io.arenadata.dtm.common.dto.QueryParserResponse;
import io.arenadata.dtm.common.model.ddl.EntityFieldUtils;
import io.arenadata.dtm.common.reader.SourceType;
import io.arenadata.dtm.query.calcite.core.service.QueryParserService;
import io.arenadata.dtm.query.calcite.core.service.QueryTemplateExtractor;
import io.arenadata.dtm.query.calcite.core.util.SqlNodeUtil;
import io.arenadata.dtm.query.execution.plugin.adb.mppw.kafka.dto.TransferDataRequest;
import io.arenadata.dtm.query.execution.plugin.adb.mppw.kafka.service.executor.AdbMppwDataTransferService;
import io.arenadata.dtm.query.execution.plugin.adb.query.service.DatabaseExecutor;
import io.arenadata.dtm.query.execution.plugin.api.dml.LlwUtils;
import io.arenadata.dtm.query.execution.plugin.api.request.UpsertSelectRequest;
import io.arenadata.dtm.query.execution.plugin.api.service.enrichment.dto.EnrichQueryRequest;
import io.arenadata.dtm.query.execution.plugin.api.service.enrichment.service.QueryEnrichmentService;
import io.vertx.core.Future;
import lombok.val;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.Util;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.arenadata.dtm.query.calcite.core.util.SqlNodeTemplates.*;

@Component
public class AdbUpsertSelectHandler implements DestinationUpsertSelectHandler {

    private static final SqlLiteral NULL_LITERAL = SqlLiteral.createNull(SqlParserPos.ZERO);
    private static final String SYS_OP = "sys_op";
    private static final SqlIdentifier SYS_OP_IDENTIFIER = identifier(SYS_OP);
    private static final List<SqlNode> TARGET_COLUMN_TO_ADD = Collections.singletonList(SYS_OP_IDENTIFIER);
    private static final SqlBasicCall ZERO_AS_SYS_OP = as(longLiteral(0), SYS_OP);
    private static final List<SqlNode> SELECT_COLUMN_TO_ADD = Collections.singletonList(ZERO_AS_SYS_OP);
    private static final List<SqlNode> SELECT_COLUMNS_TO_ADD = Arrays.asList(as(NULL_LITERAL, "sys_from"), as(NULL_LITERAL, "sys_to"), ZERO_AS_SYS_OP);
    private static final String STAGING_SUFFIX = "_staging";

    private final DatabaseExecutor queryExecutor;
    private final QueryParserService parserService;
    private final QueryEnrichmentService enrichmentService;
    private final QueryTemplateExtractor templateExtractor;
    private final SqlDialect sqlDialect;
    private final AdbMppwDataTransferService dataTransferService;

    public AdbUpsertSelectHandler(DatabaseExecutor queryExecutor,
                                  @Qualifier("adbCalciteDMLQueryParserService") QueryParserService parserService,
                                  @Qualifier("adbQueryEnrichmentService") QueryEnrichmentService enrichmentService,
                                  @Qualifier("adbQueryTemplateExtractor") QueryTemplateExtractor templateExtractor,
                                  @Qualifier("adbSqlDialect") SqlDialect sqlDialect,
                                  AdbMppwDataTransferService dataTransferService) {
        this.parserService = parserService;
        this.enrichmentService = enrichmentService;
        this.templateExtractor = templateExtractor;
        this.sqlDialect = sqlDialect;
        this.queryExecutor = queryExecutor;
        this.dataTransferService = dataTransferService;
    }

    @Override
    public Future<Void> handle(UpsertSelectRequest request) {
        return Future.future(promise -> {
            val insertSql = request.getQuery();
            val sourceSql = SqlNodeUtil.copy(request.getSourceQuery());
            val targetColumns = LlwUtils.extendTargetColumns(insertSql, TARGET_COLUMN_TO_ADD);
            val extendedSelect = extendSelectList(sourceSql, targetColumns);

            enrichSelect(extendedSelect, request)
                    .compose(enrichedSelect -> executeInsert(request.getEntity().getNameWithSchema(), enrichedSelect, targetColumns, request))
                    .compose(ignore -> executeTransfer(request))
                    .onComplete(promise);
        });
    }

    private Future<SqlNode> enrichSelect(SqlNode sourceSql, UpsertSelectRequest request) {
        val schema = request.getDatamarts();
        return parserService.parse(new QueryParserRequest(sourceSql, schema))
                .compose(queryParserResponse -> enrichQuery(request, queryParserResponse));
    }

    private Future<SqlNode> enrichQuery(UpsertSelectRequest request, QueryParserResponse queryParserResponse) {
        val schema = request.getDatamarts();
        val enrichRequest = EnrichQueryRequest.builder()
                .query(queryParserResponse.getSqlNode())
                .schema(schema)
                .envName(request.getEnvName())
                .deltaInformations(request.getDeltaInformations())
                .build();
        return enrichmentService.getEnrichedSqlNode(enrichRequest, queryParserResponse)
                .map(enrichedQuery -> templateExtractor.enrichTemplate(enrichedQuery, request.getExtractedParams()));
    }

    private SqlNode extendSelectList(SqlNode sqlSelect, SqlNodeList targetColumns) {
        if (targetColumns == null) {
            return LlwUtils.extendQuerySelectColumns(sqlSelect, SELECT_COLUMNS_TO_ADD);
        }

        return LlwUtils.extendQuerySelectColumns(sqlSelect, SELECT_COLUMN_TO_ADD);
    }

    private Future<Void> executeInsert(String extTableName, SqlNode enrichedSelect, SqlNodeList targetColumns, UpsertSelectRequest request) {
        val sqlInsert = new SqlInsert(SqlParserPos.ZERO, SqlNodeList.EMPTY, identifier(extTableName + STAGING_SUFFIX), enrichedSelect, targetColumns);
        val insertQuery = sqlNodeToString(sqlInsert);
        return queryExecutor.executeWithParams(insertQuery, request.getParameters(), Collections.emptyList())
                .mapEmpty();
    }

    private String sqlNodeToString(SqlNode sqlNode) {
        return Util.toLinux(sqlNode.toSqlString(sqlDialect).getSql()).replaceAll("\r\n|\r|\n", " ");
    }

    private Future<Void> executeTransfer(UpsertSelectRequest request) {
        val transferDataRequest = TransferDataRequest.builder()
                .datamart(request.getDatamartMnemonic())
                .hotDelta(request.getSysCn())
                .tableName(request.getEntity().getName())
                .columnList(EntityFieldUtils.getFieldNames(request.getEntity()))
                .keyColumnList(EntityFieldUtils.getPkFieldNames(request.getEntity()))
                .build();
        return dataTransferService.execute(transferDataRequest);
    }

    @Override
    public SourceType getDestination() {
        return SourceType.ADB;
    }
}
