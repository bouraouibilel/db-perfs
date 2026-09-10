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
     * Modules appelant chaque requête.
     * Clé : FQN_Classe#nomMethode -> Ensemble de noms de modules ("batch-billing", "web-api", etc.)
     */
    public final Map<String, Set<String>> queryCallersByModule = new HashMap<>();

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
    }

    public void registerQueryCall(String queryKey, String callingModule) {
        queryCallersByModule.computeIfAbsent(queryKey, k -> new HashSet<>()).add(callingModule);
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
