package com.example.rewrite.sql.model;

import java.util.List;
import java.util.Set;

public record JoinAnalysisResult(
        String mainTable,
        String mainAlias,
        List<JoinedTableInfo> joins,
        List<ProjectedColumn> projectedColumns,
        Set<String> tablesUsedInFiltersOrSorting
) {
    public record JoinedTableInfo(
            String tableName,
            String alias,
            String joinType,
            boolean isLeftJoin,
            boolean isInnerJoin,
            Set<String> tablesMentionedInOnCondition
    ) {
        public String getEffectiveIdentifier() {
            return (alias != null && !alias.isBlank()) ? alias.toLowerCase() : tableName.toLowerCase();
        }
    }

    public record ProjectedColumn(
            String tableOrAlias,
            String columnName,
            String targetPropertyName
    ) {}
}
