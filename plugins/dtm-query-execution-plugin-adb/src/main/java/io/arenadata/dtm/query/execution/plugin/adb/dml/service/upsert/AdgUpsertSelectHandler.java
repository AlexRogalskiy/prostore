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
import io.arenadata.dtm.common.model.ddl.Entity;
import io.arenadata.dtm.common.reader.SourceType;
import io.arenadata.dtm.query.calcite.core.service.QueryParserService;
import io.arenadata.dtm.query.calcite.core.service.QueryTemplateExtractor;
import io.arenadata.dtm.query.calcite.core.util.SqlNodeUtil;
import io.arenadata.dtm.query.execution.plugin.adb.base.factory.adg.AdgConnectorSqlFactory;
import io.arenadata.dtm.query.execution.plugin.adb.base.service.castservice.ColumnsCastService;
import io.arenadata.dtm.query.execution.plugin.adb.query.service.DatabaseExecutor;
import io.arenadata.dtm.query.execution.plugin.api.dml.LlwUtils;
import io.arenadata.dtm.query.execution.plugin.api.request.UpsertSelectRequest;
import io.arenadata.dtm.query.execution.plugin.api.service.enrichment.dto.EnrichQueryRequest;
import io.arenadata.dtm.query.execution.plugin.api.service.enrichment.service.QueryEnrichmentService;
import io.arenadata.dtm.query.execution.plugin.api.service.shared.adg.AdgSharedService;
import io.arenadata.dtm.query.execution.plugin.api.shared.adg.AdgSharedPrepareStagingRequest;
import io.arenadata.dtm.query.execution.plugin.api.shared.adg.AdgSharedTransferDataRequest;
import io.vertx.core.Future;
import lombok.val;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.Util;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.arenadata.dtm.query.calcite.core.util.SqlNodeTemplates.*;

@Service
public class AdgUpsertSelectHandler implements DestinationUpsertSelectHandler {

    private static final String SYS_OP = "sys_op";
    private static final SqlIdentifier SYS_OP_IDENTIFIER = identifier(SYS_OP);
    private static final SqlBasicCall ZERO_AS_SYS_OP = as(longLiteral(0), SYS_OP);
    private static final String BUCKET_ID = "bucket_id";
    private static final SqlIdentifier BUCKET_ID_IDENTIFIER = identifier(BUCKET_ID);
    private static final List<SqlNode> TARGET_COLUMNS_TO_ADD = Arrays.asList(SYS_OP_IDENTIFIER, BUCKET_ID_IDENTIFIER);
    private static final SqlBasicCall NULL_AS_BUCKET_ID = as(SqlLiteral.createNull(SqlParserPos.ZERO), BUCKET_ID);
    private static final List<SqlNode> SELECT_COLUMNS_TO_ADD = Arrays.asList(ZERO_AS_SYS_OP, NULL_AS_BUCKET_ID);

    private final AdgConnectorSqlFactory connectorSqlFactory;
    private final DatabaseExecutor queryExecutor;
    private final AdgSharedService adgSharedService;
    private final QueryParserService parserService;
    private final ColumnsCastService columnsCastService;
    private final QueryEnrichmentService enrichmentService;
    private final QueryTemplateExtractor templateExtractor;
    private final SqlDialect sqlDialect;

    public AdgUpsertSelectHandler(AdgConnectorSqlFactory connectorSqlFactory,
                                  DatabaseExecutor queryExecutor,
                                  AdgSharedService adgSharedService,
                                  @Qualifier("adbCalciteDMLQueryParserService") QueryParserService parserService,
                                  @Qualifier("adgColumnsCastService") ColumnsCastService columnsCastService,
                                  @Qualifier("adbQueryEnrichmentService") QueryEnrichmentService enrichmentService,
                                  @Qualifier("adbQueryTemplateExtractor") QueryTemplateExtractor templateExtractor,
                                  @Qualifier("adbSqlDialect") SqlDialect sqlDialect) {
        this.parserService = parserService;
        this.columnsCastService = columnsCastService;
        this.enrichmentService = enrichmentService;
        this.templateExtractor = templateExtractor;
        this.sqlDialect = sqlDialect;
        this.queryExecutor = queryExecutor;
        this.adgSharedService = adgSharedService;
        this.connectorSqlFactory = connectorSqlFactory;
    }

    @Override
    public Future<Void> handle(UpsertSelectRequest request) {
        return Future.future(promise -> {
            val sourceSql = SqlNodeUtil.copy(request.getSourceQuery());
            val targetColumns = LlwUtils.extendTargetColumns(request.getQuery(), TARGET_COLUMNS_TO_ADD);

            createWritebleExtTable(request.getEntity(), request.getEnvName())
                    .compose(extTableName -> prepareAdgStaging(request)
                            .compose(ignore -> enrichSelect(sourceSql, request))
                            .compose(enrichedSelect -> executeInsert(extTableName, enrichedSelect, targetColumns, request))
                            .compose(ignore -> queryExecutor.executeUpdate(connectorSqlFactory.dropExternalTable(extTableName))))
                    .compose(ignore -> transferData(request))
                    .onComplete(promise);
        });
    }

    private Future<String> createWritebleExtTable(Entity destination, String env) {
        val extTableName = connectorSqlFactory.extTableName(destination);
        val dropExtTable = connectorSqlFactory.dropExternalTable(extTableName);
        val createExtTable = connectorSqlFactory.createExternalTable(env, destination.getSchema(), destination);
        return queryExecutor.executeUpdate(dropExtTable)
                .compose(ignore -> queryExecutor.executeUpdate(createExtTable))
                .map(ignore -> extTableName);
    }

    private Future<Void> prepareAdgStaging(UpsertSelectRequest request) {
        val prepareStagingRequest = new AdgSharedPrepareStagingRequest(request.getEnvName(), request.getDatamartMnemonic(), request.getEntity());
        return adgSharedService.prepareStaging(prepareStagingRequest);
    }

    private Future<SqlNode> enrichSelect(SqlNode sourceSql, UpsertSelectRequest request) {
        val schema = request.getDatamarts();
        val extendedSelect = LlwUtils.extendQuerySelectColumns(sourceSql, SELECT_COLUMNS_TO_ADD);
        return parserService.parse(new QueryParserRequest(extendedSelect, schema))
                .compose(queryParserResponse -> enrichQuery(request, queryParserResponse)
                        .compose(enrichedSqlNode -> columnsCastService.apply(enrichedSqlNode, queryParserResponse.getRelNode().rel)));
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

    private Future<Void> executeInsert(String extTableName, SqlNode enrichedSelect, SqlNodeList targetColumns, UpsertSelectRequest request) {
        val sqlInsert = new SqlInsert(SqlParserPos.ZERO, SqlNodeList.EMPTY, identifier(extTableName), enrichedSelect, targetColumns);
        val insertQuery = sqlNodeToString(sqlInsert);
        return queryExecutor.executeWithParams(insertQuery, request.getParameters(), Collections.emptyList())
                .mapEmpty();
    }

    private String sqlNodeToString(SqlNode sqlNode) {
        return Util.toLinux(sqlNode.toSqlString(sqlDialect).getSql())
                .replaceAll("\r\n|\r|\n", " ");
    }

    private Future<Void> transferData(UpsertSelectRequest request) {
        val adgSharedTransferDataRequest = new AdgSharedTransferDataRequest(request.getEnvName(), request.getDatamartMnemonic(), request.getEntity(), request.getSysCn());
        return adgSharedService.transferData(adgSharedTransferDataRequest);
    }

    @Override
    public SourceType getDestination() {
        return SourceType.ADG;
    }
}
