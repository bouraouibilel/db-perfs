package com.example.rewrite.sql.report;

import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class SqlJoinReport extends DataTable<SqlJoinReport.Row> {

    public SqlJoinReport(Recipe recipe) {
        super(recipe,
                "SQL Join Optimization Report",
                "Inventaire des requêtes SQL/JPQL et des jointures ou colonnes candidates à la simplification.");
    }

    public static class Row {
        private final String sourceFile;
        private final int lineNumber;
        private final String queryMethod;
        private final String joinTable;
        private final String joinType;
        private final String status;
        private final String unusedColumns;
        private final String message;
        private final String rawSql;

        public Row(String sourceFile, int lineNumber, String queryMethod, String joinTable,
                   String joinType, String status, String unusedColumns, String message, String rawSql) {
            this.sourceFile = sourceFile;
            this.lineNumber = lineNumber;
            this.queryMethod = queryMethod;
            this.joinTable = joinTable;
            this.joinType = joinType;
            this.status = status;
            this.unusedColumns = unusedColumns;
            this.message = message;
            this.rawSql = rawSql;
        }

        public String getSourceFile() { return sourceFile; }
        public int getLineNumber() { return lineNumber; }
        public String getQueryMethod() { return queryMethod; }
        public String getJoinTable() { return joinTable; }
        public String getJoinType() { return joinType; }
        public String getStatus() { return status; }
        public String getUnusedColumns() { return unusedColumns; }
        public String getMessage() { return message; }
        public String getRawSql() { return rawSql; }

        public String sourceFile() { return sourceFile; }
        public int lineNumber() { return lineNumber; }
        public String queryMethod() { return queryMethod; }
        public String joinTable() { return joinTable; }
        public String joinType() { return joinType; }
        public String status() { return status; }
        public String unusedColumns() { return unusedColumns; }
        public String message() { return message; }
        public String rawSql() { return rawSql; }
    }
}
