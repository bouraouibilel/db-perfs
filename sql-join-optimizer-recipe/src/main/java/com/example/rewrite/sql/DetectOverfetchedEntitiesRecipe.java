package com.example.rewrite.sql;

import com.example.rewrite.sql.model.QueryMetadata;
import com.example.rewrite.sql.parser.SqlSelectAnalyzer;
import com.example.rewrite.sql.report.EntityOverfetchingReport;
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.SearchResult;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class DetectOverfetchedEntitiesRecipe extends ScanningRecipe<MultiModuleUsageAccumulator> {

    private final transient EntityOverfetchingReport report = new EntityOverfetchingReport(this);

    private static final Set<String> loggedRowKeys = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean reportFilesInitialized = false;

    public DetectOverfetchedEntitiesRecipe() {
    }

    public EntityOverfetchingReport getReport() {
        return report;
    }

    @Override
    public String getDisplayName() {
        return "Détecter l'over-fetching des entités et suggérer des projections DTO";
    }

    @Override
    public String getDescription() {
        return "Identifie les requêtes chargeant des entités complètes dont seule une minorité d'attributs est lue par le code consommateur, et suggère une projection DTO.";
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

            // 1. Enregistrement de tous les attributs déclarés dans chaque classe
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                if (cd.getType() != null && cd.getBody() != null) {
                    String classFqn = cd.getType().getFullyQualifiedName();
                    for (Statement stmt : cd.getBody().getStatements()) {
                        if (stmt instanceof J.VariableDeclarations) {
                            J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                            for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                                acc.registerEntityProperty(classFqn, nv.getSimpleName());
                            }
                        }
                    }
                }
                return cd;
            }

            // 2. Détection des constantes String SQL (ex: public static final String REQ_... = "SELECT ...")
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(multiVariable, ctx);
                J.ClassDeclaration parentClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
                String declaringClassFqn = parentClass != null && parentClass.getType() != null ? parentClass.getType().getFullyQualifiedName() : "UnknownClass";

                for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                    if (var.getInitializer() != null) {
                        String sql = extractLiteralString(var.getInitializer());
                        if (sql != null && isSqlSelectQuery(sql)) {
                            String varName = var.getSimpleName();
                            String moduleName = resolveModuleName();
                            SourceFile sf = getCursor().firstEnclosing(SourceFile.class);
                            String sourcePath = sf != null ? sf.getSourcePath().toString() : "";

                            acc.registerQuery(new QueryMetadata(
                                    sql,
                                    declaringClassFqn,
                                    declaringClassFqn,
                                    varName,
                                    moduleName,
                                    sourcePath,
                                    0
                            ));
                        }
                    }
                }
                return vd;
            }

            // 3. Détection des méthodes de repository avec @Query
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
                for (J.Annotation annotation : md.getLeadingAnnotations()) {
                    if ("Query".equals(annotation.getSimpleName()) && annotation.getArguments() != null) {
                        String sql = extractLiteralString(annotation.getArguments().get(0));
                        JavaType.Method methodType = md.getMethodType();
                        if (sql != null && methodType != null && methodType.getDeclaringType() != null) {
                            String returnTypeFqn = unwrapGenericType(methodType.getReturnType());
                            String declaringClassFqn = methodType.getDeclaringType().getFullyQualifiedName();
                            String methodName = md.getSimpleName();

                            acc.registerQuery(new QueryMetadata(
                                    sql,
                                    returnTypeFqn != null ? returnTypeFqn : "void",
                                    declaringClassFqn,
                                    methodName,
                                    resolveModuleName(),
                                    "",
                                    0
                            ));
                        }
                    }
                }
                return md;
            }

            // 4. Détection des appels de méthodes (getters) et des appels createQuery(sql, MonEntite.class)
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (m.getMethodType() != null && m.getMethodType().getDeclaringType() != null) {
                    String declaringTypeFqn = m.getMethodType().getDeclaringType().getFullyQualifiedName();
                    String methodName = m.getSimpleName();
                    acc.registerInvocation(declaringTypeFqn, methodName);

                    // Si on rencontre entityManager.createQuery(CONSTANTE, MonEntite.class)
                    if (("createQuery".equals(methodName) || "createNamedQuery".equals(methodName))
                            && m.getArguments() != null && m.getArguments().size() >= 2) {
                        Expression queryArg = m.getArguments().get(0);
                        Expression entityClassArg = m.getArguments().get(1);

                        String queryIdentifier = extractIdentifierName(queryArg);
                        String targetEntityFqn = extractClassLiteralFqn(entityClassArg);

                        if (queryIdentifier != null && targetEntityFqn != null) {
                            QueryMetadata qm = acc.registeredNamedQueries.get(queryIdentifier);
                            if (qm != null) {
                                // Mettre à jour avec le vrai type d'entité ciblé par le createQuery
                                acc.registerQuery(new QueryMetadata(
                                        qm.rawQuery(),
                                        targetEntityFqn,
                                        qm.declaringClassFqn(),
                                        qm.methodName(),
                                        qm.sourceModule(),
                                        qm.sourcePath(),
                                        qm.lineNumber()
                                ));
                            }
                        }
                    }
                }
                return m;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(MultiModuleUsageAccumulator acc, ExecutionContext ctx) {
        System.out.println("\n[ENTITY-OVERFETCHING-DETECTOR] ========================================");
        System.out.println("  Analyse de l'over-fetching sur les entités/DTOs...");
        System.out.println("  Classes référencées avec propriétés : " + acc.knownEntityProperties.size());
        System.out.println("  Requêtes répertoriées               : " + acc.registeredQueries.size());
        System.out.println("[ENTITY-OVERFETCHING-DETECTOR] ========================================\n");
        return Collections.emptyList();
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
                if (queryMetadata != null) {
                    md = checkAndReportOverfetching(queryMetadata, md, ctx);
                }
                return md;
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(multiVariable, ctx);
                J.ClassDeclaration parentClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
                String declaringClassFqn = parentClass != null && parentClass.getType() != null ? parentClass.getType().getFullyQualifiedName() : "UnknownClass";

                for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                    String varName = var.getSimpleName();
                    String key = declaringClassFqn + "#" + varName;
                    QueryMetadata qm = acc.registeredQueries.get(key);
                    if (qm == null) {
                        qm = acc.registeredNamedQueries.get(varName);
                    }
                    if (qm != null) {
                        vd = checkAndReportOverfetching(qm, vd, ctx);
                    }
                }
                return vd;
            }

            private <T extends J> T checkAndReportOverfetching(QueryMetadata queryMetadata, T targetAstNode, ExecutionContext ctx) {
                String returnTypeFqn = queryMetadata.returnTypeFqn();

                // Si le type retourné est inconnu ou non résolu, essayer de le déduire du FROM de la requête
                if ("void".equals(returnTypeFqn) || returnTypeFqn.equals(queryMetadata.declaringClassFqn())) {
                    String resolvedFromTable = resolveEntityFromQuery(queryMetadata.rawQuery(), acc.knownEntityProperties.keySet());
                    if (resolvedFromTable != null) {
                        returnTypeFqn = resolvedFromTable;
                    }
                }

                Set<String> declaredProps = acc.knownEntityProperties.get(returnTypeFqn);
                if (declaredProps == null || declaredProps.size() <= 2) {
                    return targetAstNode;
                }

                List<String> usedProps = new ArrayList<>();
                List<String> unusedProps = new ArrayList<>();

                for (String prop : declaredProps) {
                    if (acc.isPropertyInvoked(returnTypeFqn, prop)) {
                        usedProps.add(prop);
                    } else {
                        unusedProps.add(prop);
                    }
                }

                // S'il y a des propriétés lues et au moins 2 propriétés non lues
                if (!usedProps.isEmpty() && unusedProps.size() >= 2 && usedProps.size() < declaredProps.size()) {
                    String shortEntityName = returnTypeFqn.contains(".") ?
                            returnTypeFqn.substring(returnTypeFqn.lastIndexOf('.') + 1) : returnTypeFqn;

                    String status = "CANDIDAT_PROJECTION";
                    String recommendation = String.format(
                            "Entité '%s' over-fetchée (%d/%d attributs consommés : %s). Recommandation : remplacer par une projection ou constructeur JPQL 'new %sLightDto(%s)'.",
                            shortEntityName, usedProps.size(), declaredProps.size(), usedProps,
                            shortEntityName, String.join(", ", usedProps)
                    );

                    SourceFile sf = getCursor().firstEnclosing(SourceFile.class);
                    String sourcePath = sf != null ? sf.getSourcePath().toString() : queryMetadata.sourcePath();

                    EntityOverfetchingReport.Row row = new EntityOverfetchingReport.Row(
                            sourcePath,
                            queryMetadata.getMethodName(),
                            returnTypeFqn,
                            declaredProps.size(),
                            usedProps.size(),
                            String.join(", ", usedProps),
                            String.join(", ", unusedProps),
                            status,
                            recommendation
                    );
                    report.insertRow(ctx, row);
                    exportReport(row);

                    return SearchResult.found(targetAstNode, "[" + status + "] " + recommendation);
                }

                return targetAstNode;
            }
        };
    }

    private static String resolveEntityFromQuery(String sql, Set<String> knownEntities) {
        if (sql == null || knownEntities.isEmpty()) return null;
        try {
            var analysisOpt = SqlSelectAnalyzer.analyze(sql);
            if (analysisOpt.isPresent()) {
                String mainTable = analysisOpt.get().mainTable();
                if (mainTable != null && !mainTable.isBlank()) {
                    for (String entityFqn : knownEntities) {
                        String simpleName = entityFqn.contains(".") ? entityFqn.substring(entityFqn.lastIndexOf('.') + 1) : entityFqn;
                        if (simpleName.equalsIgnoreCase(mainTable)) {
                            return entityFqn;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static synchronized void initReportFilesOnce() {
        if (!reportFilesInitialized) {
            reportFilesInitialized = true;
            try {
                java.nio.file.Path targetDir = java.nio.file.Path.of("target");
                java.nio.file.Files.createDirectories(targetDir);

                java.nio.file.Path mdPath = targetDir.resolve("entity-overfetching-report.md");
                StringBuilder md = new StringBuilder();
                md.append("# Rapport d'optimisation : Over-fetching d'Entités et Projections DTO\n\n");
                md.append("| Requête | Entité | Consommés | Total | Attributs consommés | Inutilisés | Recommandation |\n");
                md.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n");
                java.nio.file.Files.writeString(mdPath, md.toString(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

                java.nio.file.Path csvPath = targetDir.resolve("entity-overfetching-report.csv");
                String csvHeader = "Fichier,Requete,Entite,NbConsommes,NbTotal,AttributsConsommes,AttributsInutilises,Statut,Recommandation\n";
                java.nio.file.Files.writeString(csvPath, csvHeader,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception ignored) {
            }
        }
    }

    private static synchronized void exportReport(EntityOverfetchingReport.Row row) {
        String deduplicationKey = row.queryIdentifier() + "#" + row.entityClass() + "#" + row.usedProperties();
        if (!loggedRowKeys.add(deduplicationKey)) {
            return;
        }

        initReportFilesOnce();

        System.out.println(String.format(
                "\n[OVERFETCHING-DETECTOR] --------------------------------------------------" +
                "\n  Statut         : [%s]" +
                "\n  Requete        : %s" +
                "\n  Entite         : %s" +
                "\n  Attributs lus  : %d / %d (%s)" +
                "\n  Inutilises     : %s" +
                "\n  Conseil        : %s" +
                "\n------------------------------------------------------------------------",
                row.status(), row.queryIdentifier(), row.entityClass(),
                row.usedPropertiesCount(), row.totalPropertiesCount(), row.usedProperties(),
                row.unusedProperties(), row.recommendation()
        ));

        try {
            java.nio.file.Path targetDir = java.nio.file.Path.of("target");
            java.nio.file.Path mdPath = targetDir.resolve("entity-overfetching-report.md");
            String mdLine = String.format("| `%s` | `%s` | %d | %d | `%s` | `%s` | %s |\n",
                    row.queryIdentifier(), row.entityClass(),
                    row.usedPropertiesCount(), row.totalPropertiesCount(),
                    row.usedProperties(), row.unusedProperties(), row.recommendation());
            java.nio.file.Files.writeString(mdPath, mdLine,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);

            java.nio.file.Path csvPath = targetDir.resolve("entity-overfetching-report.csv");
            String csvLine = String.format("\"%s\",\"%s\",\"%s\",%d,%d,\"%s\",\"%s\",\"%s\",\"%s\"\n",
                    row.sourceFile(), row.queryIdentifier(), row.entityClass(),
                    row.usedPropertiesCount(), row.totalPropertiesCount(),
                    row.usedProperties(), row.unusedProperties(),
                    row.status(), row.recommendation().replace("\"", "'"));
            java.nio.file.Files.writeString(csvPath, csvLine,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    @Nullable
    private static String extractIdentifierName(Expression expr) {
        if (expr instanceof J.Identifier) {
            return ((J.Identifier) expr).getSimpleName();
        } else if (expr instanceof J.FieldAccess) {
            return ((J.FieldAccess) expr).getName().getSimpleName();
        } else if (expr instanceof J.Literal) {
            return extractLiteralString(expr);
        }
        return null;
    }

    @Nullable
    private static String extractClassLiteralFqn(Expression expr) {
        if (expr instanceof J.FieldAccess) {
            J.FieldAccess fa = (J.FieldAccess) expr;
            if ("class".equals(fa.getName().getSimpleName())) {
                if (fa.getTarget().getType() instanceof JavaType.Class) {
                    return ((JavaType.Class) fa.getTarget().getType()).getFullyQualifiedName();
                } else if (fa.getTarget() instanceof J.Identifier) {
                    return ((J.Identifier) fa.getTarget()).getSimpleName();
                }
            }
        }
        return null;
    }

    private static boolean isSqlSelectQuery(String str) {
        if (str == null) return false;
        String trimmed = str.trim().toUpperCase();
        return trimmed.startsWith("SELECT") && trimmed.contains("FROM");
    }

    @Nullable
    private static String extractLiteralString(Expression expr) {
        if (expr instanceof J.Literal) {
            J.Literal lit = (J.Literal) expr;
            if (lit.getValue() instanceof String) return (String) lit.getValue();
        }
        if (expr instanceof J.Assignment) {
            return extractLiteralString(((J.Assignment) expr).getAssignment());
        }
        if (expr instanceof J.Binary) {
            J.Binary binary = (J.Binary) expr;
            if (binary.getOperator() == J.Binary.Type.Addition) {
                String left = extractLiteralString(binary.getLeft());
                String right = extractLiteralString(binary.getRight());
                if (left != null && right != null) return left + right;
            }
        }
        return null;
    }

    @Nullable
    private static String unwrapGenericType(@Nullable JavaType type) {
        if (type == null) return null;
        if (type instanceof JavaType.Parameterized) {
            JavaType.Parameterized p = (JavaType.Parameterized) type;
            if (!p.getTypeParameters().isEmpty()) {
                return unwrapGenericType(p.getTypeParameters().get(0));
            }
        } else if (type instanceof JavaType.Class) {
            return ((JavaType.Class) type).getFullyQualifiedName();
        }
        return null;
    }
}
