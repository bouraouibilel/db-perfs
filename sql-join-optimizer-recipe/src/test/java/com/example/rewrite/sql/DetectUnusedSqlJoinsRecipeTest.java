package com.example.rewrite.sql;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class DetectUnusedSqlJoinsRecipeTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new DetectUnusedSqlJoinsRecipe());
    }

    @Test
    void shouldFlagUnusedJoinWhenCallerDoesNotReadColumns() {
        rewriteRun(
            // DTO
            java(
                """
                package com.example.dto;
                public record UserSummary(Long id, String name, String city) {
                    public Long getId() { return id; }
                    public String getName() { return name; }
                    public String getCity() { return city; }
                }
                """
            ),
            // Repository avec Query contenant un LEFT JOIN address a
            java(
                """
                package com.example.repository;
                import com.example.dto.UserSummary;
                import java.util.List;

                public interface UserRepository {
                    @Query("SELECT u.id AS id, u.name AS name, a.city AS city FROM users u LEFT JOIN address a ON u.address_id = a.id")
                    List<UserSummary> findSummaries();
                }

                @interface Query {
                    String value();
                }
                """,
                """
                package com.example.repository;
                import com.example.dto.UserSummary;
                import java.util.List;

                public interface UserRepository {
                    /*~~([CANDIDAT_ELIMINATION] Jointure 'address' (LEFT JOIN) potentiellement inutile : aucune colonne référencée dans le code Java.)~~>*/@Query("SELECT u.id AS id, u.name AS name, a.city AS city FROM users u LEFT JOIN address a ON u.address_id = a.id")
                    List<UserSummary> findSummaries();
                }

                @interface Query {
                    String value();
                }
                """
            ),
            // Consommateur qui n'appelle que getId() et getName() (jamais getCity())
            java(
                """
                package com.example.service;
                import com.example.repository.UserRepository;
                import com.example.dto.UserSummary;

                public class UserService {
                    private final UserRepository repository;

                    public UserService(UserRepository repository) {
                        this.repository = repository;
                    }

                    public void process() {
                        for (UserSummary summary : repository.findSummaries()) {
                            System.out.println(summary.getId() + ": " + summary.getName());
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void shouldNotFlagWhenJoinedColumnIsActuallyRead() {
        rewriteRun(
            // DTO
            java(
                """
                package com.example.dto;
                public record UserSummary(Long id, String name, String city) {
                    public Long getId() { return id; }
                    public String getName() { return name; }
                    public String getCity() { return city; }
                }
                """
            ),
            // Repository
            java(
                """
                package com.example.repository;
                import com.example.dto.UserSummary;
                import java.util.List;

                public interface UserRepository {
                    @Query("SELECT u.id AS id, u.name AS name, a.city AS city FROM users u LEFT JOIN address a ON u.address_id = a.id")
                    List<UserSummary> findSummaries();
                }

                @interface Query {
                    String value();
                }
                """
            ),
            // Consommateur qui appelle AUSSI getCity() -> Aucune modification / aucun warning attendu
            java(
                """
                package com.example.service;
                import com.example.repository.UserRepository;
                import com.example.dto.UserSummary;

                public class UserService {
                    private final UserRepository repository;

                    public UserService(UserRepository repository) {
                        this.repository = repository;
                    }

                    public void process() {
                        for (UserSummary summary : repository.findSummaries()) {
                            System.out.println(summary.getId() + " - " + summary.getCity());
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void shouldFlagWithCandidateSurBatchWhenCalledFromBatchModule() {
        rewriteRun(
            // DTO
            java(
                """
                package com.example.dto;
                public record UserSummary(Long id, String name, String city) {
                    public Long getId() { return id; }
                    public String getName() { return name; }
                    public String getCity() { return city; }
                }
                """,
                spec -> spec.path("core-common/src/main/java/com/example/dto/UserSummary.java")
            ),
            // Repository dans core-common
            java(
                """
                package com.example.repository;
                import com.example.dto.UserSummary;
                import java.util.List;

                public interface UserRepository {
                    @Query("SELECT u.id AS id, u.name AS name, a.city AS city FROM users u LEFT JOIN address a ON u.address_id = a.id")
                    List<UserSummary> findSummaries();
                }

                @interface Query {
                    String value();
                }
                """,
                """
                package com.example.repository;
                import com.example.dto.UserSummary;
                import java.util.List;

                public interface UserRepository {
                    /*~~([CANDIDAT_SUR_BATCH] Jointure inutile 'address' (LEFT JOIN) : aucune colonne lue dans le code des modules batchs appelants [batch-billing].)~~>*/@Query("SELECT u.id AS id, u.name AS name, a.city AS city FROM users u LEFT JOIN address a ON u.address_id = a.id")
                    List<UserSummary> findSummaries();
                }

                @interface Query {
                    String value();
                }
                """,
                spec -> spec.path("core-common/src/main/java/com/example/repository/UserRepository.java")
            ),
            // Consommateur situé dans le module "batch-billing"
            java(
                """
                package com.example.batch;
                import com.example.repository.UserRepository;
                import com.example.dto.UserSummary;

                public class BillingJob {
                    private final UserRepository repository;

                    public BillingJob(UserRepository repository) {
                        this.repository = repository;
                    }

                    public void run() {
                        for (UserSummary summary : repository.findSummaries()) {
                            System.out.println(summary.getId() + " : " + summary.getName());
                        }
                    }
                }
                """,
                spec -> spec.path("batch-billing/src/main/java/com/example/batch/BillingJob.java")
            )
        );
    }

    @Test
    void shouldFlagWithAttentionWebWhenDtoIsExposedInRestController() {
        rewriteRun(
            // DTO
            java(
                """
                package com.example.dto;
                public record UserSummary(Long id, String name, String city) {
                    public Long getId() { return id; }
                    public String getName() { return name; }
                    public String getCity() { return city; }
                }
                """,
                spec -> spec.path("core-common/src/main/java/com/example/dto/UserSummary.java")
            ),
            // Repository
            java(
                """
                package com.example.repository;
                import com.example.dto.UserSummary;
                import java.util.List;

                public interface UserRepository {
                    @Query("SELECT u.id AS id, u.name AS name, a.city AS city FROM users u LEFT JOIN address a ON u.address_id = a.id")
                    List<UserSummary> findSummaries();
                }

                @interface Query {
                    String value();
                }
                """,
                """
                package com.example.repository;
                import com.example.dto.UserSummary;
                import java.util.List;

                public interface UserRepository {
                    /*~~([ATTENTION_WEB] Jointure 'address' (LEFT JOIN) dont les colonnes [city] ne sont pas lues en Java, mais le type com.example.dto.UserSummary est exposé sur une API Web (sérialisation JSON potentielle).)~~>*/@Query("SELECT u.id AS id, u.name AS name, a.city AS city FROM users u LEFT JOIN address a ON u.address_id = a.id")
                    List<UserSummary> findSummaries();
                }

                @interface Query {
                    String value();
                }
                """,
                spec -> spec.path("core-common/src/main/java/com/example/repository/UserRepository.java")
            ),
            // Web Controller exposant UserSummary
            java(
                """
                package com.example.web;
                import com.example.dto.UserSummary;
                import com.example.repository.UserRepository;
                import java.util.List;

                @RestController
                public class UserController {
                    private final UserRepository repository;

                    public UserController(UserRepository repository) {
                        this.repository = repository;
                    }

                    public List<UserSummary> getUsers() {
                        return repository.findSummaries();
                    }
                }

                @interface RestController {}
                """,
                spec -> spec.path("web-api/src/main/java/com/example/web/UserController.java")
            )
        );
    }

    @Test
    void shouldDetectUnusedJoinInNamedQueryOnEntity() {
        rewriteRun(
            // Entity avec NamedQuery
            java(
                """
                package com.example.model;

                @NamedQuery(
                    name = "User.findWithUnusedAddress",
                    query = "SELECT u.id AS id, u.name AS name, a.city AS city FROM users u LEFT JOIN address a ON u.address_id = a.id"
                )
                public class User {
                    private Long id;
                    private String name;
                    private String city;

                    public Long getId() { return id; }
                    public String getName() { return name; }
                    public String getCity() { return city; }
                }

                @interface NamedQuery {
                    String name();
                    String query();
                }
                """,
                """
                package com.example.model;

                /*~~([CANDIDAT_ELIMINATION] Jointure 'address' (LEFT JOIN) potentiellement inutile : aucune colonne référencée dans le code Java.)~~>*/@NamedQuery(
                    name = "User.findWithUnusedAddress",
                    query = "SELECT u.id AS id, u.name AS name, a.city AS city FROM users u LEFT JOIN address a ON u.address_id = a.id"
                )
                public class User {
                    private Long id;
                    private String name;
                    private String city;

                    public Long getId() { return id; }
                    public String getName() { return name; }
                    public String getCity() { return city; }
                }

                @interface NamedQuery {
                    String name();
                    String query();
                }
                """
            ),
            // Consommateur n'appelant que getId et getName
            java(
                """
                package com.example.service;
                import com.example.model.User;

                public class UserService {
                    public void handle(User user) {
                        System.out.println(user.getId() + " " + user.getName());
                    }
                }
                """
            )
        );
    }

    @Test
    void shouldDetectUnusedJoinInSqlConstantClass() {
        rewriteRun(
            // Classe de constantes dans utilities-commun
            java(
                """
                package com.example.util;

                public class QueryConstants {
                    public static final String REQ_FIND_USERS =
                        "SELECT u.id AS id, u.name AS name, a.city AS city " +
                        "FROM users u " +
                        "LEFT JOIN address a ON u.address_id = a.id";
                }
                """,
                """
                package com.example.util;

                public class QueryConstants {
                    /*~~([CANDIDAT_SUR_BATCH] Jointure inutile 'address' (LEFT JOIN) : aucune colonne lue dans le code des modules batchs appelants [batch-billing].)~~>*/public static final String REQ_FIND_USERS =
                        "SELECT u.id AS id, u.name AS name, a.city AS city " +
                        "FROM users u " +
                        "LEFT JOIN address a ON u.address_id = a.id";
                }
                """,
                spec -> spec.path("utilities-commun/src/main/java/com/example/util/QueryConstants.java")
            ),
            // Modèle DTO
            java(
                """
                package com.example.dto;

                public class UserDto {
                    private Long id;
                    private String name;
                    private String city;

                    public Long getId() { return id; }
                    public String getName() { return name; }
                    public String getCity() { return city; }
                }
                """,
                spec -> spec.path("core-common/src/main/java/com/example/dto/UserDto.java")
            ),
            // Consommateur dans un batch qui utilise la constante mais ne lit pas getCity
            java(
                """
                package com.example.batch;
                import com.example.util.QueryConstants;
                import com.example.dto.UserDto;

                public class BillingJob {
                    public void run(UserDto dto) {
                        String sql = QueryConstants.REQ_FIND_USERS;
                        System.out.println(dto.getId() + " " + dto.getName());
                    }
                }
                """,
                spec -> spec.path("batch-billing/src/main/java/com/example/batch/BillingJob.java")
            )
        );
    }
}

