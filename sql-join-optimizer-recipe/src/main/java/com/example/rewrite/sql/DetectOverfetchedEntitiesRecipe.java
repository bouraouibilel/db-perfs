package com.example.rewrite.sql;

import com.example.rewrite.sql.model.QueryMetadata;
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

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
                for (J.Annotation annotation : md.getLeadingAnnotations()) {
                    if ("Query".equals(annotation.getSimpleName()) && annotation.getArguments() != null) {
                        String sql = extractLiteral(annotation.getArguments().get(0));
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

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (m.getMethodType() != null && m.getMethodType().getDeclaringType() != null) {
                    String declaringTypeFqn = m.getMethodType().getDeclaringType().getFullyQualifiedName();
                    String methodName = m.getSimpleName();
                    acc.registerInvocation(declaringTypeFqn, methodName);
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
                if (queryMetadata == null) {
                    return md;
                }

                String returnTypeFqn = queryMetadata.returnTypeFqn();
                Set<String> declaredProps = acc.knownEntityProperties.get(returnTypeFqn);
                if (declaredProps == null || declaredProps.size() <= 2) {
                    // Pas assez de propriétés pour qualifier un overfetching significatif
                    return md;
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

                // Si au moins un tiers des propriétés ne sont JAMAIS lues
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
                    String sourcePath = sf != null ? sf.getSourcePath().toString() : "";

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

                    md = SearchResult.found(md, "[" + status + "] " + recommendation);
                }

                return md;
            }
        };
    }

    private static synchronized void exportReport(EntityOverfetchingReport.Row row) {
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
            java.nio.file.Files.createDirectories(targetDir);

            java.nio.file.Path mdPath = targetDir.resolve("entity-overfetching-report.md");
            boolean mdExists = java.nio.file.Files.exists(mdPath);
            StringBuilder md = new StringBuilder();
            if (!mdExists) {
                md.append("# Rapport d'optimisation : Over-fetching d'Entités et Projections DTO\n\n");
                md.append("| Requête | Entité | Consommés | Total | Attributs consommés | Inutilisés | Recommandation |\n");
                md.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n");
            }
            md.append(String.format("| `%s` | `%s` | %d | %d | `%s` | `%s` | %s |\n",
                    row.queryIdentifier(), row.entityClass(),
                    row.usedPropertiesCount(), row.totalPropertiesCount(),
                    row.usedProperties(), row.unusedProperties(), row.recommendation()));
            java.nio.file.Files.writeString(mdPath, md.toString(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);

            java.nio.file.Path csvPath = targetDir.resolve("entity-overfetching-report.csv");
            boolean csvExists = java.nio.file.Files.exists(csvPath);
            StringBuilder csv = new StringBuilder();
            if (!csvExists) {
                csv.append("Fichier,Requete,Entite,NbConsommes,NbTotal,AttributsConsommes,AttributsInutilises,Statut,Recommandation\n");
            }
            csv.append(String.format("\"%s\",\"%s\",\"%s\",%d,%d,\"%s\",\"%s\",\"%s\",\"%s\"\n",
                    row.sourceFile(), row.queryIdentifier(), row.entityClass(),
                    row.usedPropertiesCount(), row.totalPropertiesCount(),
                    row.usedProperties(), row.unusedProperties(),
                    row.status(), row.recommendation().replace("\"", "'")));
            java.nio.file.Files.writeString(csvPath, csv.toString(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    @Nullable
    private static String extractLiteral(Expression expr) {
        if (expr instanceof J.Literal) {
            J.Literal lit = (J.Literal) expr;
            if (lit.getValue() instanceof String) return (String) lit.getValue();
        }
        if (expr instanceof J.Assignment) {
            return extractLiteral(((J.Assignment) expr).getAssignment());
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
