package com.example.rewrite.sql.report;

import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class EntityOverfetchingReport extends DataTable<EntityOverfetchingReport.Row> {

    public EntityOverfetchingReport(Recipe recipe) {
        super(recipe,
                "Entity Over-fetching Report",
                "Inventaire des entites chargees avec detection des attributs reellement consommes vs inutilises.");
    }

    public static class Row {
        private final String sourceFile;
        private final String queryIdentifier;
        private final String entityClass;
        private final int totalPropertiesCount;
        private final int usedPropertiesCount;
        private final String usedProperties;
        private final String unusedProperties;
        private final String status;
        private final String recommendation;

        public Row(String sourceFile, String queryIdentifier, String entityClass,
                   int totalPropertiesCount, int usedPropertiesCount,
                   String usedProperties, String unusedProperties,
                   String status, String recommendation) {
            this.sourceFile = sourceFile;
            this.queryIdentifier = queryIdentifier;
            this.entityClass = entityClass;
            this.totalPropertiesCount = totalPropertiesCount;
            this.usedPropertiesCount = usedPropertiesCount;
            this.usedProperties = usedProperties;
            this.unusedProperties = unusedProperties;
            this.status = status;
            this.recommendation = recommendation;
        }

        public String getSourceFile() { return sourceFile; }
        public String getQueryIdentifier() { return queryIdentifier; }
        public String getEntityClass() { return entityClass; }
        public int getTotalPropertiesCount() { return totalPropertiesCount; }
        public int getUsedPropertiesCount() { return usedPropertiesCount; }
        public String getUsedProperties() { return usedProperties; }
        public String getUnusedProperties() { return unusedProperties; }
        public String getStatus() { return status; }
        public String getRecommendation() { return recommendation; }

        public String sourceFile() { return sourceFile; }
        public String queryIdentifier() { return queryIdentifier; }
        public String entityClass() { return entityClass; }
        public int totalPropertiesCount() { return totalPropertiesCount; }
        public int usedPropertiesCount() { return usedPropertiesCount; }
        public String usedProperties() { return usedProperties; }
        public String unusedProperties() { return unusedProperties; }
        public String status() { return status; }
        public String recommendation() { return recommendation; }
    }
}
