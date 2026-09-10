package com.example.rewrite.sql.report;

import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class SqlJoinReport extends DataTable<SqlJoinReport.Row> {

    public SqlJoinReport(Recipe recipe) {
        super(recipe,
                "SQL Join Optimization Report",
                "Inventaire des requêtes SQL/JPQL et des jointures ou colonnes candidates à la simplification.");
    }

    public record Row(
            String sourceFile,
            int lineNumber,
            String queryMethod,
            String joinTable,
            String joinType,
            String status,
            String unusedColumns,
            String message,
            String rawSql
    ) {}
}
