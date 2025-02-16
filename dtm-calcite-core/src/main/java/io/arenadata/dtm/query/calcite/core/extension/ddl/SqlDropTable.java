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
package io.arenadata.dtm.query.calcite.core.extension.ddl;

import com.google.common.collect.ImmutableList;
import io.arenadata.dtm.common.reader.SourceType;
import io.arenadata.dtm.query.calcite.core.util.SqlNodeUtil;
import lombok.Getter;
import org.apache.calcite.sql.SqlDrop;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.List;

@Getter
public class SqlDropTable extends SqlDrop implements SqlLogicalCall {

    private static final SqlOperator OPERATOR = new SqlSpecialOperator("DROP TABLE", SqlKind.DROP_TABLE);
    private final SourceType destination;
    private final SqlIdentifier name;
    private final boolean isLogicalOnly;

    public SqlDropTable(SqlParserPos pos,
                        boolean ifExists,
                        SqlIdentifier name,
                        SqlNode destination,
                        boolean isLogicalOnly) {
        super(OPERATOR, pos, ifExists);
        this.name = name;
        this.destination = SqlNodeUtil.extractSourceType(destination);
        this.isLogicalOnly = isLogicalOnly;
    }

    @Override
    public List<SqlNode> getOperandList() {
        return ImmutableList.of(name);
    }

    @Override
    public void unparse(SqlWriter writer,
                        int leftPrec,
                        int rightPrec) {
        writer.keyword(this.getOperator().getName());
        if (this.ifExists) {
            writer.keyword("IF EXISTS");
        }

        this.name.unparse(writer, leftPrec, rightPrec);
    }
}
