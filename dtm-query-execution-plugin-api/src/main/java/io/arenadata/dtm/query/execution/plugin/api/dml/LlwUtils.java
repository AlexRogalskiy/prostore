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
package io.arenadata.dtm.query.execution.plugin.api.dml;

import io.arenadata.dtm.common.exception.DtmException;
import io.arenadata.dtm.common.model.ddl.Entity;
import io.arenadata.dtm.common.model.ddl.EntityField;
import io.arenadata.dtm.common.model.ddl.EntityFieldUtils;
import io.arenadata.dtm.query.calcite.core.extension.dml.SqlDynamicLiteral;
import io.arenadata.dtm.query.calcite.core.node.SqlPredicatePart;
import io.arenadata.dtm.query.calcite.core.node.SqlPredicates;
import io.arenadata.dtm.query.calcite.core.node.SqlSelectTree;
import io.arenadata.dtm.query.calcite.core.node.SqlTreeNode;
import io.arenadata.dtm.query.calcite.core.util.CalciteUtil;
import io.arenadata.dtm.query.calcite.core.util.SqlNodeUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.val;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlRowOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.arenadata.dtm.query.calcite.core.util.SqlNodeTemplates.basicCall;
import static io.arenadata.dtm.query.calcite.core.util.SqlNodeTemplates.identifier;

public final class LlwUtils {
    private static final SqlPredicates DYNAMIC_PARAM_PREDICATE = SqlPredicates.builder()
            .anyOf(SqlPredicatePart.eq(SqlKind.DYNAMIC_PARAM))
            .build();

    private static final SqlPredicates COLUMN_PREDICATE = SqlPredicates.builder()
            .anyOf(SqlPredicatePart.eq(SqlKind.SELECT))
            .anyOf(SqlPredicatePart.eq(SqlKind.OTHER))
            .build();

    private LlwUtils() {
    }

    public static SqlNodeList extendTargetColumns(SqlInsert sqlInsert, List<SqlNode> nodeToAdd) {
        val targetColumns = sqlInsert.getTargetColumnList();
        if (targetColumns == null) {
            return targetColumns;
        }

        val copyTargetColumns = (SqlNodeList) SqlNodeUtil.copy(targetColumns);
        nodeToAdd.forEach(copyTargetColumns::add);
        return copyTargetColumns;
    }

    public static SqlNode replaceDynamicParams(SqlNode sqlNode) {
        val selectTree = new SqlSelectTree(SqlNodeUtil.copy(sqlNode));
        val dynamicNodes = selectTree.findNodes(DYNAMIC_PARAM_PREDICATE, true);

        int paramNum = 1;
        for (SqlTreeNode dynamicNode : dynamicNodes) {
            SqlNode param = new SqlDynamicLiteral(paramNum, SqlTypeName.ANY, dynamicNode.getNode().getParserPosition());
            paramNum++;
            dynamicNode.getSqlNodeSetter().accept(param);
        }
        return selectTree.getRoot().getNode();
    }

    public static SqlBasicCall getExtendRowsOfValues(SqlCall valuesNode, List<EntityField> fields, List<SqlLiteral> extendWith, Function<TransformEntry, SqlNode> itemsTransform) {
        val newOperands = new ArrayList<SqlNode>(valuesNode.getOperandList().size());

        for (SqlNode sqlNode : valuesNode.getOperandList()) {
            val sqlCall = (SqlCall) sqlNode;
            val operandList = sqlCall.getOperandList();
            val rowNodes = new ArrayList<SqlNode>(operandList.size() + extendWith.size());

            if (operandList.size() != fields.size()) {
                throw new DtmException(String.format("Values size: [%s] not equal to columns size: [%s]", operandList.size(), fields.size()));
            }

            for (int i = 0; i < operandList.size(); i++) {
                val toTransform = operandList.get(i);
                val field = fields.get(i);
                rowNodes.add(itemsTransform.apply(new TransformEntry(toTransform, CalciteUtil.valueOf(field.getType()))));
            }

            rowNodes.addAll(extendWith);
            newOperands.add(basicCall(new SqlRowOperator(" "), rowNodes));
        }

        return basicCall(valuesNode.getOperator(), newOperands);
    }

    public static SqlBasicCall getExtendRowsOfValues(SqlCall valuesNode, List<EntityField> fields, List<SqlLiteral> extendWith) {
        return getExtendRowsOfValues(valuesNode, fields, extendWith, TransformEntry::getSqlNode);
    }

    public static List<EntityField> getFilteredLogicalFields(Entity entity, SqlNodeList pickedColumns) {
        if (pickedColumns == null || pickedColumns.size() == 0) {
            return entity.getFields().stream()
                    .sorted(Comparator.comparingInt(EntityField::getOrdinalPosition))
                    .collect(Collectors.toList());
        }

        val fieldsMap = EntityFieldUtils.getFieldsMap(entity);
        return pickedColumns.getList().stream()
                .peek(sqlNode -> {
                    if (sqlNode.getClass() != SqlIdentifier.class) {
                        throw new DtmException(String.format("Column name [%s] must be identifier", sqlNode));
                    }
                })
                .map(sqlNode -> (SqlIdentifier) sqlNode)
                .map(sqlIdentifier -> {
                    String fieldName = sqlIdentifier.getSimple();
                    if (!fieldsMap.containsKey(fieldName)) {
                        throw new DtmException(String.format("Column [%s] not exists", fieldName));
                    }
                    return fieldsMap.get(fieldName);
                })
                .collect(Collectors.toList());
    }

    public static SqlNodeList getExtendedColumns(List<EntityField> entityFields, List<SqlIdentifier> additionalColumns) {
        SqlNodeList columns = new SqlNodeList(SqlParserPos.ZERO);
        entityFields.forEach(entityField -> columns.add(identifier(entityField.getName())));
        additionalColumns.forEach(columns::add);
        return columns;
    }

    public static SqlNodeList getExtendedSelectList(List<EntityField> entityFields, List<SqlLiteral> additionalLiterals) {
        SqlNodeList columns = new SqlNodeList(SqlParserPos.ZERO);
        entityFields.forEach(entityField -> columns.add(identifier(entityField.getName())));
        additionalLiterals.forEach(columns::add);
        return columns;
    }

    public static SqlNode getTableIdentifier(String datamart, String entityName, SqlIdentifier alias) {
        SqlNode tableIdentifier = new SqlIdentifier(Arrays.asList(datamart, entityName), SqlParserPos.ZERO);
        if (alias != null) {
            tableIdentifier = new SqlBasicCall(SqlStdOperatorTable.AS, new SqlNode[]{tableIdentifier, alias}, SqlParserPos.ZERO);
        }
        return tableIdentifier;
    }

    public static boolean isValuesSqlNode(SqlNode node) {
        return node instanceof SqlCall && ((SqlCall) node).getOperator().getKind() == SqlKind.VALUES;
    }

    public static boolean isSelectSqlNode(SqlNode node) {
        return node instanceof SqlSelect || node instanceof SqlOrderBy;
    }

    public static SqlNode extendQuerySelectColumns(SqlNode query, List<? extends SqlNode> columnsToAdd) {
        val result = SqlNodeUtil.copy(query);
        val sourceQueryColumnNodes = new SqlSelectTree(result)
                .findNodes(COLUMN_PREDICATE, true);
        val columnNodeList = (SqlNodeList) sourceQueryColumnNodes.stream()
                .filter(sqlTreeNode -> sqlTreeNode.getNode() instanceof SqlNodeList)
                .min(Comparator.comparingInt(SqlTreeNode::getId))
                .map(SqlTreeNode::getNode)
                .orElseThrow(() -> new DtmException("Could not find column node in source query"));
        for (SqlNode sqlNode : columnsToAdd) {
            columnNodeList.add(sqlNode);
        }
        return result;
    }

    @Getter
    @AllArgsConstructor
    public static class TransformEntry {
        private final SqlNode sqlNode;
        private final SqlTypeName sqlTypeName;
    }
}
