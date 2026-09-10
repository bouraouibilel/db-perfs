# SQL Join & Projection Optimizer (OpenRewrite Recipe)

Analyse statique et optimisation des requêtes SQL/JPQL et de leurs jointures (`JOIN`) dans un projet Java multi-modules (Batchs, Web, Socle Commun).

## Objectifs

1. **Détecter les requêtes SQL/JPQL** dans les repositories Spring Data (`@Query`), les appels JDBC et JPA.
2. **Parser l'arbre syntaxique SQL** via **JSqlParser** pour extraire les tables, types de jointure (`LEFT JOIN`, `INNER JOIN`), conditions `ON`, projections (`SELECT`) et filtres (`WHERE`, `ORDER BY`, `GROUP BY`, `HAVING`).
3. **Cartographier la consommation réelle** des DTOs, records et projections dans le code Java (invocations de getters et accesseurs).
4. **Identifier les jointures superflues** : quand aucune colonne d'une table jointe n'est accédée par le code appelant.
5. **Qualifier le risque selon le contexte multi-modules** :
   - `[CANDIDAT_SUR_BATCH]` : Requête appelée uniquement par des modules batchs, aucun risque de sérialisation JSON masquée (Jackson) -> Élimination immédiate recommandée.
   - `[ATTENTION_WEB]` : Colonnes non lues en Java mais le DTO est renvoyé par un `@RestController` (sérialisation Jackson potentielle).
   - `[SUGGESTION_SPLIT]` : Requête commune partagée entre Web et Batch, où le Batch n'a pas besoin de la jointure -> Scission recommandée.

## Structure du Repository

```text
db-perfs/
├── pom.xml                                    # Aggregator parent
├── sql-join-optimizer-recipe/                # Recette OpenRewrite sur-mesure
│   ├── src/main/java/com/example/rewrite/sql/
│   │   ├── DetectUnusedSqlJoinsRecipe.java   # ScanningRecipe principale
│   │   ├── MultiModuleUsageAccumulator.java   # Accumulateur d'état multi-modules
│   │   ├── parser/SqlSelectAnalyzer.java     # Analyseur SQL (JSqlParser 4.9)
│   │   ├── report/SqlJoinReport.java         # OpenRewrite DataTable (export CSV)
│   │   └── model/                            # Modèles de données
│   └── src/test/java/com/example/rewrite/sql/# Suite de tests unitaires (RewriteTest)
└── sample-enterprise-project/                 # Projet témoin multi-modules
    ├── core-common/                           # DTOs et UserRepository
    ├── batch-billing/                         # Module batch (consommation partielle)
    └── web-api/                               # Contrôleur REST
```

## Prérequis

- **Java 21**
- **Maven 3.9+**

## Compilation et Tests

Exécuter la suite complète de tests unitaires et d'intégration :

```bash
mvn clean test
```

Compiler et installer les modules dans le cache local :

```bash
mvn clean install
```

Exécuter la recette OpenRewrite sur le projet d'entreprise témoin :

```bash
cd sample-enterprise-project
mvn rewrite:run
```
