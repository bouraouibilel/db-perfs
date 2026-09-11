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
import java.util.stream.Collectors;

public class DetectUnusedSqlJoinsRecipe extends ScanningRecipe<MultiModuleUsageAccumulator> {

    private final transient SqlJoinReport report = new SqlJoinReport(this);

    public DetectUnusedSqlJoinsRecipe() {
    }

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
    public Collection<? extends SourceFile> generate(MultiModuleUsageAccumulator acc, ExecutionContext ctx) {
        System.out.println("\n[SQL-JOIN-OPTIMIZER] ========================================================");
        System.out.println("[SQL-JOIN-OPTIMIZER]                BILAN DU SCAN MULTI-MODULES             ");
        System.out.println("[SQL-JOIN-OPTIMIZER] ========================================================");
        System.out.println("  Modules scannés    : " + (acc.scannedModules.isEmpty() ? "aucun" : acc.scannedModules));
        System.out.println("  Fichiers analysés  : " + acc.scannedFileCount);
        System.out.println("  Requêtes détectées : " + acc.registeredQueries.size());
        if (acc.registeredQueries.isEmpty()) {
            System.out.println("  (!) ATTENTION : Aucune requête (@Query ou @NamedQuery) trouvée dans les fichiers scannés.");
            System.out.println("      Vérifiez que le profil Maven contenant vos entités/repositories/DAO");
            System.out.println("      (ex: 'commun', 'services-metier', 'entities') est bien inclus dans votre commande avec -P !");
        } else {
            for (QueryMetadata q : acc.registeredQueries.values()) {
                System.out.println("   * " + q.getFullQueryKey() + " [module: " + q.sourceModule() + "]");
            }
        }
        System.out.println("[SQL-JOIN-OPTIMIZER] ========================================================\n");
        return Collections.emptyList();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(MultiModuleUsageAccumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {

            private String resolveModuleName() {
                SourceFile sourceFile = getCursor().firstEnclosing(SourceFile.class);
                if (sourceFile != null) {
                    Path path = sourceFile.getSourcePath();
                    if (path != null && path.getNameCount() > 0) {
                        String firstPart = path.getName(0).toString();
                        if (!"src".equalsIgnoreCase(firstPart)) {
                            return firstPart;
                        }
                        String pathStr = path.toString().replace('\\', '/').toLowerCase();
                        if (pathStr.contains("batch")) return "batch";
                        if (pathStr.contains("web")) return "web";
                        if (pathStr.contains("core") || pathStr.contains("common")) return "common";
                        return firstPart;
                    }
                }
                return "root";
            }

            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                acc.scannedFileCount++;
                acc.scannedModules.add(resolveModuleName());
                return super.visitCompilationUnit(cu, ctx);
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
                        if (stmt instanceof J.MethodDeclaration) {
                            J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                            if (md.getMethodType() != null) {
                                JavaType returnType = md.getMethodType().getReturnType();
                                String fqn = unwrapGenericType(returnType);
                                if (fqn != null) {
                                    acc.registerControllerExposedType(fqn);
                                }
                            }
                        }
                    }
                }

                // Détection des @NamedQuery / @NamedNativeQuery sur les classes/interfaces
                for (J.Annotation annotation : cd.getLeadingAnnotations()) {
                    scanForNamedQueries(annotation, cd);
                }

                return cd;
            }

            private void scanForNamedQueries(J.Annotation annotation, J.ClassDeclaration cd) {
                String simpleName = annotation.getSimpleName();
                if ("NamedQuery".equals(simpleName) || "NamedNativeQuery".equals(simpleName)) {
                    registerNamedQueryAnnotation(annotation, cd);
                } else if ("NamedQueries".equals(simpleName) || "NamedNativeQueries".equals(simpleName)) {
                    if (annotation.getArguments() != null) {
                        for (Expression arg : annotation.getArguments()) {
                            if (arg instanceof J.NewArray) {
                                J.NewArray array = (J.NewArray) arg;
                                if (array.getInitializer() != null) {
                                    for (Expression elem : array.getInitializer()) {
                                        if (elem instanceof J.Annotation) {
                                            scanForNamedQueries((J.Annotation) elem, cd);
                                        }
                                    }
                                }
                            } else if (arg instanceof J.Annotation) {
                                scanForNamedQueries((J.Annotation) arg, cd);
                            }
                        }
                    }
                }
            }

            private void registerNamedQueryAnnotation(J.Annotation annotation, J.ClassDeclaration cd) {
                String queryName = extractAnnotationAttribute(annotation, "name");
                String sql = extractAnnotationAttribute(annotation, "query");
                if (sql == null) {
                    sql = extractAnnotationAttribute(annotation, "value");
                }

                if (sql != null && cd.getType() != null) {
                    String declaringClassFqn = cd.getType().getFullyQualifiedName();
                    String keyName = (queryName != null && !queryName.isBlank()) ? queryName : "NamedQuery_" + annotation.getId();
                    String moduleName = resolveModuleName();
                    SourceFile sf = getCursor().firstEnclosing(SourceFile.class);
                    String sourcePath = sf != null ? sf.getSourcePath().toString() : "";

                    QueryMetadata queryMetadata = new QueryMetadata(
                            sql,
                            declaringClassFqn, // Par défaut la classe entité portant le NamedQuery
                            declaringClassFqn,
                            keyName,
                            moduleName,
                            sourcePath,
                            0
                    );
                    acc.registerQuery(queryMetadata);
                }
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

                    // Support EntityManager.createNamedQuery("nomRequete", ...)
                    if ("createNamedQuery".equals(methodName) && m.getArguments() != null && !m.getArguments().isEmpty()) {
                        Expression firstArg = m.getArguments().get(0);
                        String namedQueryName = extractLiteralString(firstArg);
                        if (namedQueryName != null) {
                            acc.registerNamedQueryCall(namedQueryName, resolveModuleName());
                        }
                    }
                }
                return m;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(MultiModuleUsageAccumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                if (cd.getType() == null) {
                    return cd;
                }

                String declaringClassFqn = cd.getType().getFullyQualifiedName();
                for (J.Annotation annotation : cd.getLeadingAnnotations()) {
                    cd = processNamedQueryAnnotationOnClass(annotation, declaringClassFqn, cd, ctx);
                }
                return cd;
            }

            private J.ClassDeclaration processNamedQueryAnnotationOnClass(J.Annotation annotation, String declaringClassFqn, J.ClassDeclaration cd, ExecutionContext ctx) {
                String simpleName = annotation.getSimpleName();
                if ("NamedQuery".equals(simpleName) || "NamedNativeQuery".equals(simpleName)) {
                    String queryName = extractAnnotationAttribute(annotation, "name");
                    String key = (queryName != null && !queryName.isBlank()) ? queryName : declaringClassFqn;
                    QueryMetadata qm = acc.registeredNamedQueries.get(key);
                    if (qm == null) {
                        qm = acc.registeredQueries.get(declaringClassFqn + "#" + key);
                    }
                    if (qm != null) {
                        cd = analyzeAndMarkQuery(qm, cd, ctx);
                    }
                } else if ("NamedQueries".equals(simpleName) || "NamedNativeQueries".equals(simpleName)) {
                    if (annotation.getArguments() != null) {
                        for (Expression arg : annotation.getArguments()) {
                            if (arg instanceof J.NewArray) {
                                J.NewArray array = (J.NewArray) arg;
                                if (array.getInitializer() != null) {
                                    for (Expression elem : array.getInitializer()) {
                                        if (elem instanceof J.Annotation) {
                                            cd = processNamedQueryAnnotationOnClass((J.Annotation) elem, declaringClassFqn, cd, ctx);
                                        }
                                    }
                                }
                            } else if (arg instanceof J.Annotation) {
                                cd = processNamedQueryAnnotationOnClass((J.Annotation) arg, declaringClassFqn, cd, ctx);
                            }
                        }
                    }
                }
                return cd;
            }

            private <T extends J> T analyzeAndMarkQuery(QueryMetadata queryMetadata, T targetAstNode, ExecutionContext ctx) {
                Optional<JoinAnalysisResult> analysisOpt = SqlSelectAnalyzer.analyze(queryMetadata.rawQuery());
                if (analysisOpt.isEmpty()) {
                    return targetAstNode;
                }

                JoinAnalysisResult analysis = analysisOpt.get();
                if (analysis.joins().isEmpty()) {
                    return targetAstNode;
                }

                String returnTypeFqn = queryMetadata.returnTypeFqn();
                boolean isExposedInWeb = acc.typesExposedInWebControllers.contains(returnTypeFqn);

                Set<String> callingModules = new HashSet<>();
                Set<String> byMethod = acc.queryCallersByModule.get(queryMetadata.getFullQueryKey());
                if (byMethod != null) callingModules.addAll(byMethod);
                Set<String> byName = acc.namedQueryCallersByModule.get(queryMetadata.getMethodName());
                if (byName != null) callingModules.addAll(byName);

                boolean hasBatchCaller = callingModules.stream().anyMatch(m -> m.toLowerCase().contains("batch"));
                boolean hasWebCaller = callingModules.stream().anyMatch(m -> m.toLowerCase().contains("web")) || isExposedInWeb;

                SourceFile sf = getCursor().firstEnclosing(SourceFile.class);
                String sourceFile = sf != null ? sf.getSourcePath().toString() : queryMetadata.sourcePath();

                T resultNode = targetAstNode;

                for (JoinedTableInfo join : analysis.joins()) {
                    String tableAliasOrName = join.getEffectiveIdentifier();

                    // La table est-elle utilisée dans WHERE, GROUP BY, ORDER BY, HAVING ?
                    boolean isUsedInFilters = analysis.tablesUsedInFiltersOrSorting().contains(tableAliasOrName);

                    // Est-elle requise par la condition ON d'une AUTRE jointure ?
                    boolean isUsedInOtherJoinOn = analysis.joins().stream()
                            .filter(other -> other != join)
                            .anyMatch(other -> other.tablesMentionedInOnCondition().contains(tableAliasOrName));

                    if (isUsedInFilters || isUsedInOtherJoinOn) {
                        continue;
                    }

                    // Récupérer les colonnes projetées de cette table
                    List<ProjectedColumn> tableColumns = analysis.projectedColumns().stream()
                            .filter(c -> c.tableOrAlias().equalsIgnoreCase(tableAliasOrName))
                            .collect(Collectors.toList());

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

                        resultNode = SearchResult.found(resultNode, "[" + status + "] " + message);
                    }
                }
                return resultNode;
            }

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

                return analyzeAndMarkQuery(queryMetadata, md, ctx);
            }
        };
    }

    private static synchronized void exportReportToConsoleAndFile(SqlJoinReport.Row row) {
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
    private static String extractAnnotationAttribute(J.Annotation annotation, String attributeName) {
        if (annotation.getArguments() == null) return null;
        for (Expression arg : annotation.getArguments()) {
            if (arg instanceof J.Assignment) {
                J.Assignment assign = (J.Assignment) arg;
                if (assign.getVariable() instanceof J.Identifier) {
                    J.Identifier id = (J.Identifier) assign.getVariable();
                    if (attributeName.equals(id.getSimpleName())) {
                        return extractLiteralString(assign.getAssignment());
                    }
                }
            } else if ("value".equals(attributeName) || "query".equals(attributeName)) {
                String val = extractLiteralString(arg);
                if (val != null) return val;
            }
        }
        return null;
    }

    @Nullable
    private static String extractQueryString(J.Annotation annotation) {
        if (annotation.getArguments() == null) return null;
        for (Expression arg : annotation.getArguments()) {
            if (arg instanceof J.Assignment) {
                J.Assignment assign = (J.Assignment) arg;
                if (assign.getVariable() instanceof J.Identifier) {
                    J.Identifier id = (J.Identifier) assign.getVariable();
                    if ("value".equals(id.getSimpleName())) {
                        return extractLiteralString(assign.getAssignment());
                    }
                }
            } else {
                String val = extractLiteralString(arg);
                if (val != null) return val;
            }
        }
        return null;
    }

    @Nullable
    private static String extractLiteralString(Expression expr) {
        if (expr instanceof J.Literal) {
            J.Literal literal = (J.Literal) expr;
            if (literal.getValue() instanceof String) {
                return (String) literal.getValue();
            }
        }
        if (expr instanceof J.Binary) {
            J.Binary binary = (J.Binary) expr;
            if (binary.getOperator() == J.Binary.Type.Addition) {
                String left = extractLiteralString(binary.getLeft());
                String right = extractLiteralString(binary.getRight());
                if (left != null && right != null) {
                    return left + right;
                }
            }
        }
        return null;
    }

    @Nullable
    private static String unwrapGenericType(@Nullable JavaType type) {
        if (type == null) return null;
        if (type instanceof JavaType.Parameterized) {
            JavaType.Parameterized parameterized = (JavaType.Parameterized) type;
            if (!parameterized.getTypeParameters().isEmpty()) {
                return unwrapGenericType(parameterized.getTypeParameters().get(0));
            }
        } else if (type instanceof JavaType.Class) {
            JavaType.Class clazz = (JavaType.Class) type;
            return clazz.getFullyQualifiedName();
        }
        return null;
    }
}
