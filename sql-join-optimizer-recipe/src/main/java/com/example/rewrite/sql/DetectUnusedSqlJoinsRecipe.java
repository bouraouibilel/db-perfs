package com.example.rewrite.sql;

import com.example.rewrite.sql.model.JoinAnalysisResult;
import com.example.rewrite.sql.model.JoinAnalysisResult.JoinedTableInfo;
import com.example.rewrite.sql.model.JoinAnalysisResult.ProjectedColumn;
import com.example.rewrite.sql.model.QueryMetadata;
import com.example.rewrite.sql.parser.SqlSelectAnalyzer;
import com.example.rewrite.sql.report.SqlJoinReport;
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.SearchResult;

import java.nio.file.Path;
import java.util.*;

public class DetectUnusedSqlJoinsRecipe extends ScanningRecipe<MultiModuleUsageAccumulator> {

    private final transient SqlJoinReport report = new SqlJoinReport(this);

    public SqlJoinReport getReport() {
        return report;
    }

    @Override
    public String getDisplayName() {
        return "Détecter les jointures SQL et colonnes non consommées dans le code Java";
    }

    @Override
    public String getDescription() {
        return "Analyse les requêtes SQL et leur consommation dans les modules Java pour identifier les jointures et projections superflues.";
    }

    @Override
    public MultiModuleUsageAccumulator getInitialValue(ExecutionContext ctx) {
        return new MultiModuleUsageAccumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(MultiModuleUsageAccumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {

            private String resolveModuleName() {
                SourceFile sourceFile = getCursor().firstEnclosing(SourceFile.class);
                if (sourceFile != null) {
                    Path path = sourceFile.getSourcePath();
                    if (path != null && path.getNameCount() > 1) {
                        return path.getName(0).toString();
                    }
                }
                return "root";
            }

            // 1. Détection des contrôleurs REST / Web
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                boolean isWebController = cd.getLeadingAnnotations().stream().anyMatch(a -> {
                    String name = a.getSimpleName();
                    return "RestController".equals(name) || "Controller".equals(name);
                });

                if (isWebController && cd.getBody() != null) {
                    for (Statement stmt : cd.getBody().getStatements()) {
                        if (stmt instanceof J.MethodDeclaration md && md.getMethodType() != null) {
                            JavaType returnType = md.getMethodType().getReturnType();
                            String fqn = unwrapGenericType(returnType);
                            if (fqn != null) {
                                acc.registerControllerExposedType(fqn);
                            }
                        }
                    }
                }
                return cd;
            }

            // 2. Détection des méthodes de repository avec @Query
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
                for (J.Annotation annotation : md.getLeadingAnnotations()) {
                    if ("Query".equals(annotation.getSimpleName()) && annotation.getArguments() != null) {
                        String sql = extractQueryString(annotation);
                        JavaType.Method methodType = md.getMethodType();
                        if (sql != null && methodType != null && methodType.getDeclaringType() != null) {
                            String returnTypeFqn = unwrapGenericType(methodType.getReturnType());
                            String declaringClassFqn = methodType.getDeclaringType().getFullyQualifiedName();
                            String methodName = md.getSimpleName();
                            String moduleName = resolveModuleName();
                            SourceFile sf = getCursor().firstEnclosing(SourceFile.class);
                            String sourcePath = sf != null ? sf.getSourcePath().toString() : "";

                            QueryMetadata queryMetadata = new QueryMetadata(
                                    sql,
                                    returnTypeFqn != null ? returnTypeFqn : "void",
                                    declaringClassFqn,
                                    methodName,
                                    moduleName,
                                    sourcePath,
                                    0
                            );
                            acc.registerQuery(queryMetadata);
                        }
                    }
                }
                return md;
            }

            // 3. Détection de tous les appels de méthodes (getters + invocations de requêtes)
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (m.getMethodType() != null && m.getMethodType().getDeclaringType() != null) {
                    String declaringTypeFqn = m.getMethodType().getDeclaringType().getFullyQualifiedName();
                    String methodName = m.getSimpleName();

                    acc.registerInvocation(declaringTypeFqn, methodName);

                    String queryKey = declaringTypeFqn + "#" + methodName;
                    acc.registerQueryCall(queryKey, resolveModuleName());
                }
                return m;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(MultiModuleUsageAccumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
                if (md.getMethodType() == null || md.getMethodType().getDeclaringType() == null) {
                    return md;
                }

                String queryKey = md.getMethodType().getDeclaringType().getFullyQualifiedName() + "#" + md.getSimpleName();
                QueryMetadata queryMetadata = acc.registeredQueries.get(queryKey);
                if (queryMetadata == null) {
                    return md;
                }

                Optional<JoinAnalysisResult> analysisOpt = SqlSelectAnalyzer.analyze(queryMetadata.rawQuery());
                if (analysisOpt.isEmpty()) {
                    return md;
                }

                JoinAnalysisResult analysis = analysisOpt.get();
                if (analysis.joins().isEmpty()) {
                    return md;
                }

                String returnTypeFqn = queryMetadata.returnTypeFqn();
                boolean isExposedInWeb = acc.typesExposedInWebControllers.contains(returnTypeFqn);
                Set<String> callingModules = acc.queryCallersByModule.getOrDefault(queryKey, Collections.emptySet());
                boolean hasBatchCaller = callingModules.stream().anyMatch(m -> m.toLowerCase().contains("batch"));
                boolean hasWebCaller = callingModules.stream().anyMatch(m -> m.toLowerCase().contains("web")) || isExposedInWeb;

                SourceFile sf = getCursor().firstEnclosing(SourceFile.class);
                String sourceFile = sf != null ? sf.getSourcePath().toString() : queryMetadata.sourcePath();

                for (JoinedTableInfo join : analysis.joins()) {
                    String tableAliasOrName = join.getEffectiveIdentifier();

                    // La table est-elle utilisée dans WHERE, GROUP BY, ORDER BY, HAVING ?
                    boolean isUsedInFilters = analysis.tablesUsedInFiltersOrSorting().contains(tableAliasOrName);

                    // Est-elle requise par la condition ON d'une AUTRE jointure ?
                    boolean isUsedInOtherJoinOn = analysis.joins().stream()
                            .filter(other -> other != join)
                            .anyMatch(other -> other.tablesMentionedInOnCondition().contains(tableAliasOrName));

                    if (isUsedInFilters || isUsedInOtherJoinOn) {
                        // La jointure participe au filtrage ou est requise par une autre table
                        continue;
                    }

                    // Récupérer les colonnes projetées de cette table
                    List<ProjectedColumn> tableColumns = analysis.projectedColumns().stream()
                            .filter(c -> c.tableOrAlias().equalsIgnoreCase(tableAliasOrName))
                            .toList();

                    // Vérifier si au moins une colonne de cette table est lue dans le code Java
                    List<String> unusedColNames = new ArrayList<>();
                    boolean anyColumnRead = false;

                    for (ProjectedColumn col : tableColumns) {
                        boolean isRead = acc.isPropertyInvoked(returnTypeFqn, col.targetPropertyName());
                        if (isRead) {
                            anyColumnRead = true;
                        } else {
                            unusedColNames.add(col.targetPropertyName());
                        }
                    }

                    // Si aucune colonne de la table n'est lue (ou si la table n'avait aucune colonne projetée)
                    if (!anyColumnRead) {
                        String status;
                        String message;

                        if (hasBatchCaller && !hasWebCaller) {
                            status = "CANDIDAT_SUR_BATCH";
                            message = String.format("Jointure inutile '%s' (%s) : aucune colonne lue dans le code des modules batchs appelants %s.",
                                    join.tableName(), join.joinType(), callingModules);
                        } else if (isExposedInWeb) {
                            status = "ATTENTION_WEB";
                            message = String.format("Jointure '%s' (%s) dont les colonnes %s ne sont pas lues en Java, mais le type %s est exposé sur une API Web (sérialisation JSON potentielle).",
                                    join.tableName(), join.joinType(), unusedColNames, returnTypeFqn);
                        } else if (hasBatchCaller && hasWebCaller) {
                            status = "SUGGESTION_SPLIT";
                            message = String.format("Requête partagée entre Web et Batch : la jointure '%s' (%s) n'est pas utile au Batch. Découpez en une requête dédiée pour le batch.",
                                    join.tableName(), join.joinType());
                        } else {
                            status = "CANDIDAT_ELIMINATION";
                            message = String.format("Jointure '%s' (%s) potentiellement inutile : aucune colonne référencée dans le code Java.",
                                    join.tableName(), join.joinType());
                        }

                        SqlJoinReport.Row row = new SqlJoinReport.Row(
                                sourceFile,
                                0,
                                queryMetadata.methodName(),
                                join.tableName(),
                                join.joinType(),
                                status,
                                String.join(", ", unusedColNames),
                                message,
                                queryMetadata.rawQuery()
                        );
                        report.insertRow(ctx, row);
                        exportReportToConsoleAndFile(row);

                        md = SearchResult.found(md, "[" + status + "] " + message);
                    }
                }

                return md;
            }
        };
    }

    private static synchronized void exportReportToConsoleAndFile(SqlJoinReport.Row row) {
        // 1. Affichage console immédiat
        System.out.println(String.format(
            "\n[SQL-JOIN-OPTIMIZER] ----------------------------------------------------" +
            "\n  Statut   : [%s]" +
            "\n  Methode  : %s" +
            "\n  Table    : %s (%s)" +
            "\n  Colonnes : %s" +
            "\n  Conseil  : %s" +
            "\n------------------------------------------------------------------------",
            row.status(), row.queryMethod(), row.joinTable(), row.joinType(),
            row.unusedColumns().isBlank() ? "(aucune)" : row.unusedColumns(),
            row.message()
        ));

        // 2. Export automatique dans target/sql-optimization-report.md et .csv
        try {
            java.nio.file.Path targetDir = java.nio.file.Path.of("target");
            java.nio.file.Files.createDirectories(targetDir);

            java.nio.file.Path mdPath = targetDir.resolve("sql-optimization-report.md");
            boolean mdExists = java.nio.file.Files.exists(mdPath);
            StringBuilder mdContent = new StringBuilder();
            if (!mdExists) {
                mdContent.append("# Rapport d'optimisation des requêtes SQL et Jointures\n\n");
                mdContent.append("| Méthode | Table jointe | Type | Colonnes orphelines | Statut | Recommandation |\n");
                mdContent.append("| :--- | :--- | :--- | :--- | :--- | :--- |\n");
            }
            mdContent.append(String.format("| `%s` | `%s` | %s | `%s` | **%s** | %s |\n",
                    row.queryMethod(), row.joinTable(), row.joinType(),
                    row.unusedColumns().isBlank() ? "-" : row.unusedColumns(),
                    row.status(), row.message()));
            java.nio.file.Files.writeString(mdPath, mdContent.toString(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);

            java.nio.file.Path csvPath = targetDir.resolve("sql-optimization-report.csv");
            boolean csvExists = java.nio.file.Files.exists(csvPath);
            StringBuilder csvContent = new StringBuilder();
            if (!csvExists) {
                csvContent.append("Fichier,Methode,TableJointe,TypeJointe,ColonnesNonLues,Statut,Message\n");
            }
            csvContent.append(String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
                    row.sourceFile(), row.queryMethod(), row.joinTable(), row.joinType(),
                    row.unusedColumns(), row.status(), row.message().replace("\"", "'")));
            java.nio.file.Files.writeString(csvPath, csvContent.toString(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);

        } catch (Exception ignored) {
        }
    }

    @Nullable
    private static String extractQueryString(J.Annotation annotation) {
        if (annotation.getArguments() == null) return null;
        for (Expression arg : annotation.getArguments()) {
            if (arg instanceof J.Literal literal && literal.getValue() instanceof String s) {
                return s;
            }
            if (arg instanceof J.Assignment assign) {
                if (assign.getVariable() instanceof J.Identifier id && ("value".equals(id.getSimpleName()) || "nativeQuery".equals(id.getSimpleName()))) {
                    String val = extractLiteralString(assign.getAssignment());
                    if (val != null) return val;
                }
            }
        }
        return null;
    }

    @Nullable
    private static String extractLiteralString(Expression expr) {
        if (expr instanceof J.Literal literal && literal.getValue() instanceof String s) {
            return s;
        }
        if (expr instanceof J.Binary binary && binary.getOperator() == J.Binary.Type.Addition) {
            String left = extractLiteralString(binary.getLeft());
            String right = extractLiteralString(binary.getRight());
            if (left != null && right != null) {
                return left + right;
            }
        }
        return null;
    }

    @Nullable
    private static String unwrapGenericType(@Nullable JavaType type) {
        if (type == null) return null;
        if (type instanceof JavaType.Parameterized parameterized) {
            if (!parameterized.getTypeParameters().isEmpty()) {
                // Déballe List<T>, Optional<T>, ResponseEntity<T>, Page<T>
                return unwrapGenericType(parameterized.getTypeParameters().get(0));
            }
        } else if (type instanceof JavaType.Class clazz) {
            return clazz.getFullyQualifiedName();
        }
        return null;
    }
}
