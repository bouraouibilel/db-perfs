package com.example.rewrite.sql.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class JoinAnalysisResult {
    private final String mainTable;
    private final String mainAlias;
    private final List<JoinedTableInfo> joins;
    private final List<ProjectedColumn> projectedColumns;
    private final Set<String> tablesUsedInFiltersOrSorting;

    public JoinAnalysisResult(String mainTable, String mainAlias, List<JoinedTableInfo> joins,
                              List<ProjectedColumn> projectedColumns, Set<String> tablesUsedInFiltersOrSorting) {
        this.mainTable = mainTable;
        this.mainAlias = mainAlias;
        this.joins = joins;
        this.projectedColumns = projectedColumns;
        this.tablesUsedInFiltersOrSorting = tablesUsedInFiltersOrSorting;
    }

    public String mainTable() { return mainTable; }
    public String mainAlias() { return mainAlias; }
    public List<JoinedTableInfo> joins() { return joins; }
    public List<ProjectedColumn> projectedColumns() { return projectedColumns; }
    public Set<String> tablesUsedInFiltersOrSorting() { return tablesUsedInFiltersOrSorting; }

    public static class JoinedTableInfo {
        private final String tableName;
        private final String alias;
        private final String joinType;
        private final boolean isLeftJoin;
        private final boolean isInnerJoin;
        private final Set<String> tablesMentionedInOnCondition;

        public JoinedTableInfo(String tableName, String alias, String joinType, boolean isLeftJoin,
                               boolean isInnerJoin, Set<String> tablesMentionedInOnCondition) {
            this.tableName = tableName;
            this.alias = alias;
            this.joinType = joinType;
            this.isLeftJoin = isLeftJoin;
            this.isInnerJoin = isInnerJoin;
            this.tablesMentionedInOnCondition = tablesMentionedInOnCondition;
        }

        public String getEffectiveIdentifier() {
            return (alias != null && !alias.isBlank()) ? alias.toLowerCase() : tableName.toLowerCase();
        }

        public String tableName() { return tableName; }
        public String alias() { return alias; }
        public String joinType() { return joinType; }
        public boolean isLeftJoin() { return isLeftJoin; }
        public boolean isInnerJoin() { return isInnerJoin; }
        public Set<String> tablesMentionedInOnCondition() { return tablesMentionedInOnCondition; }
    }

    public static class ProjectedColumn {
        private final String tableOrAlias;
        private final String columnName;
        private final String targetPropertyName;

        public ProjectedColumn(String tableOrAlias, String columnName, String targetPropertyName) {
            this.tableOrAlias = tableOrAlias;
            this.columnName = columnName;
            this.targetPropertyName = targetPropertyName;
        }

        public String tableOrAlias() { return tableOrAlias; }
        public String columnName() { return columnName; }
        public String targetPropertyName() { return targetPropertyName; }
    }
}
