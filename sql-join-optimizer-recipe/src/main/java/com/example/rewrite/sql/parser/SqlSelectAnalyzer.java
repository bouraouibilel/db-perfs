package com.example.rewrite.sql.parser;

import com.example.rewrite.sql.model.JoinAnalysisResult;
import com.example.rewrite.sql.model.JoinAnalysisResult.JoinedTableInfo;
import com.example.rewrite.sql.model.JoinAnalysisResult.ProjectedColumn;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;

import java.util.*;

public class SqlSelectAnalyzer {

    public static Optional<JoinAnalysisResult> analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            return Optional.empty();
        }

        try {
            String cleanSql = sql.trim();
            Statement statement = CCJSqlParserUtil.parse(cleanSql);
            if (!(statement instanceof Select)) {
                return Optional.empty();
            }
            Select select = (Select) statement;

            PlainSelect plainSelect;
            if (select.getSelectBody() instanceof PlainSelect) {
                plainSelect = (PlainSelect) select.getSelectBody();
            } else {
                return Optional.empty();
            }

            // 1. Table principale (FROM)
            String mainTableName = "";
            String mainTableAlias = "";
            if (plainSelect.getFromItem() instanceof Table) {
                Table mainTable = (Table) plainSelect.getFromItem();
                mainTableName = mainTable.getName();
                if (mainTable.getAlias() != null) {
                    mainTableAlias = mainTable.getAlias().getName();
                }
            }

            // 2. Tables jointes (JOIN)
            List<JoinedTableInfo> joins = new ArrayList<>();
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    if (join.getRightItem() instanceof Table) {
                        Table joinedTable = (Table) join.getRightItem();
                        String tName = joinedTable.getName();
                        String tAlias = joinedTable.getAlias() != null ? joinedTable.getAlias().getName() : "";
                        String joinType = formatJoinType(join);

                        Set<String> onTables = new HashSet<>();
                        if (join.getOnExpressions() != null) {
                            for (Expression onExpr : join.getOnExpressions()) {
                                collectColumnsFromExpression(onExpr, onTables);
                            }
                        }

                        joins.add(new JoinedTableInfo(
                                tName,
                                tAlias,
                                joinType,
                                join.isLeft(),
                                join.isInner() || (!join.isLeft() && !join.isRight() && !join.isFull() && !join.isCross()),
                                onTables
                        ));
                    }
                }
            }

            // 3. Colonnes projetées (SELECT)
            List<ProjectedColumn> projectedColumns = new ArrayList<>();
            if (plainSelect.getSelectItems() != null) {
                for (SelectItem<?> item : plainSelect.getSelectItems()) {
                    String targetPropName = null;
                    if (item.getAlias() != null && item.getAlias().getName() != null) {
                        targetPropName = item.getAlias().getName();
                    }

                    Expression expr = item.getExpression();
                    if (expr instanceof Column) {
                        Column col = (Column) expr;
                        String colName = col.getColumnName();
                        String tableOrAlias = col.getTable() != null ? col.getTable().getName() : "";
                        if (targetPropName == null) {
                            targetPropName = colName;
                        }
                        projectedColumns.add(new ProjectedColumn(tableOrAlias, colName, targetPropName));
                    }
                }
            }

            // 4. Tables / Alias référencés dans les filtres, tris et regroupements
            Set<String> tablesUsedInFilters = new HashSet<>();
            collectColumnsFromExpression(plainSelect.getWhere(), tablesUsedInFilters);
            collectColumnsFromExpression(plainSelect.getHaving(), tablesUsedInFilters);

            if (plainSelect.getGroupBy() != null && plainSelect.getGroupBy().getGroupByExpressionList() != null) {
                for (Object item : plainSelect.getGroupBy().getGroupByExpressionList()) {
                    if (item instanceof Expression) {
                        collectColumnsFromExpression((Expression) item, tablesUsedInFilters);
                    }
                }
            }

            if (plainSelect.getOrderByElements() != null) {
                for (OrderByElement obe : plainSelect.getOrderByElements()) {
                    collectColumnsFromExpression(obe.getExpression(), tablesUsedInFilters);
                }
            }

            return Optional.of(new JoinAnalysisResult(
                    mainTableName,
                    mainTableAlias,
                    joins,
                    projectedColumns,
                    tablesUsedInFilters
            ));

        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static String formatJoinType(Join join) {
        if (join.isLeft()) return "LEFT JOIN";
        if (join.isRight()) return "RIGHT JOIN";
        if (join.isFull()) return "FULL JOIN";
        if (join.isCross()) return "CROSS JOIN";
        if (join.isInner()) return "INNER JOIN";
        return "JOIN";
    }

    private static void collectColumnsFromExpression(Expression expr, Set<String> targetTables) {
        if (expr == null) return;
        expr.accept(new net.sf.jsqlparser.expression.ExpressionVisitorAdapter() {
            @Override
            public void visit(Column column) {
                if (column.getTable() != null && column.getTable().getName() != null) {
                    targetTables.add(column.getTable().getName().toLowerCase());
                }
            }
        });
    }
}
