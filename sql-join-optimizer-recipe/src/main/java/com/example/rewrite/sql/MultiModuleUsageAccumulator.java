package com.example.rewrite.sql;

import com.example.rewrite.sql.model.QueryMetadata;

import java.util.*;

public class MultiModuleUsageAccumulator {

    /**
     * Map entre le nom pleinement qualifié du type (DTO, projection, record)
     * et l'ensemble des méthodes (getters, accesseurs) effectivement appelées dans le code.
     * Exemple : "com.example.UserDto" -> {"getId", "getName", "id", "name"}
     */
    public final Map<String, Set<String>> invokedMethodsByType = new HashMap<>();

    /**
     * Types directement retournés par des contrôleurs Web (@RestController ou @Controller).
     * Les attributs de ces types peuvent être consommés implicitement par Jackson lors de la sérialisation JSON.
     */
    public final Set<String> typesExposedInWebControllers = new HashSet<>();

    /**
     * Registre des requêtes détectées dans les interfaces ou classes de Repository.
     * Clé : FQN_Classe#nomMethode (ex: "com.example.UserRepository#findUserSummaries")
     */
    public final Map<String, QueryMetadata> registeredQueries = new HashMap<>();

    /**
     * Registre des NamedQueries par leur nom de requête (ex: "User.findSummaries")
     */
    public final Map<String, QueryMetadata> registeredNamedQueries = new HashMap<>();

    /**
     * Modules appelant chaque requête.
     * Clé : FQN_Classe#nomMethode -> Ensemble de noms de modules ("batch-billing", "web-api", etc.)
     */
    public final Map<String, Set<String>> queryCallersByModule = new HashMap<>();

    /**
     * Modules appelant chaque NamedQuery par son nom.
     * Clé : nom de la requête -> Ensemble de noms de modules ("batch-inscription", etc.)
     */
    public final Map<String, Set<String>> namedQueryCallersByModule = new HashMap<>();

    /**
     * Attributs / propriétés déclarés par chaque entité JPA ou DTO.
     * Clé : FQN de la classe entité (ex: "fr.entrepabs.appli.entity.User") -> Ensemble de noms de propriétés {"id", "nom", "prenom", ...}
     */
    public final Map<String, Set<String>> knownEntityProperties = new HashMap<>();

    public int scannedFileCount = 0;
    public final Set<String> scannedModules = new TreeSet<>();

    public void registerEntityProperty(String entityFqn, String propertyName) {
        if (entityFqn == null || propertyName == null || propertyName.isBlank()) return;
        knownEntityProperties.computeIfAbsent(entityFqn, k -> new TreeSet<>()).add(propertyName);
    }

    public void registerInvocation(String typeFqn, String methodName) {
        if (typeFqn == null || methodName == null) return;
        invokedMethodsByType.computeIfAbsent(typeFqn, k -> new HashSet<>()).add(methodName);
    }

    public void registerControllerExposedType(String typeFqn) {
        if (typeFqn != null) {
            typesExposedInWebControllers.add(typeFqn);
        }
    }

    public void registerQuery(QueryMetadata metadata) {
        registeredQueries.put(metadata.getFullQueryKey(), metadata);
        if (metadata.getMethodName() != null) {
            registeredNamedQueries.put(metadata.getMethodName(), metadata);
        }
    }

    public void registerQueryCall(String queryKey, String callingModule) {
        queryCallersByModule.computeIfAbsent(queryKey, k -> new HashSet<>()).add(callingModule);
    }

    public void registerNamedQueryCall(String namedQueryName, String callingModule) {
        namedQueryCallersByModule.computeIfAbsent(namedQueryName, k -> new HashSet<>()).add(callingModule);
    }

    /**
     * Vérifie si une propriété (ex: "city") est invoquée via getCity(), isCity() ou city()
     */
    public boolean isPropertyInvoked(String typeFqn, String propertyName) {
        if (typeFqn == null || propertyName == null || propertyName.isBlank()) {
            return false;
        }
        Set<String> invoked = invokedMethodsByType.get(typeFqn);
        if (invoked == null || invoked.isEmpty()) {
            return false;
        }

        String capitalized = propertyName.substring(0, 1).toUpperCase() + (propertyName.length() > 1 ? propertyName.substring(1) : "");
        String getter1 = "get" + capitalized;
        String getter2 = "is" + capitalized;
        String recordAccessor = propertyName;

        return invoked.contains(getter1) || invoked.contains(getter2) || invoked.contains(recordAccessor);
    }
}
