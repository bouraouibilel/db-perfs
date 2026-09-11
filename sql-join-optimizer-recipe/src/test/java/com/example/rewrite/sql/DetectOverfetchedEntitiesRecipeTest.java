package com.example.rewrite.sql;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class DetectOverfetchedEntitiesRecipeTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new DetectOverfetchedEntitiesRecipe());
    }

    @Test
    void shouldFlagEntityWhenOnlyFewPropertiesAreRead() {
        rewriteRun(
            // Entité avec 5 attributs
            java(
                """
                package com.example.entity;

                public class BigUserEntity {
                    private Long id;
                    private String username;
                    private String email;
                    private String address;
                    private String phoneNumber;

                    public Long getId() { return id; }
                    public String getUsername() { return username; }
                    public String getEmail() { return email; }
                    public String getAddress() { return address; }
                    public String getPhoneNumber() { return phoneNumber; }
                }
                """
            ),
            // Repository chargeant toute l'entité
            java(
                """
                package com.example.repository;
                import com.example.entity.BigUserEntity;
                import java.util.List;

                public interface UserRepository {
                    @Query("SELECT u FROM BigUserEntity u WHERE u.id > 100")
                    List<BigUserEntity> findActiveUsers();
                }

                @interface Query {
                    String value();
                }
                """,
                """
                package com.example.repository;
                import com.example.entity.BigUserEntity;
                import java.util.List;

                public interface UserRepository {
                    /*~~([CANDIDAT_PROJECTION] Entité 'BigUserEntity' over-fetchée (2/5 attributs consommés : [id, username]). Recommandation : remplacer par une projection ou constructeur JPQL 'new BigUserEntityLightDto(id, username)'.)~~>*/@Query("SELECT u FROM BigUserEntity u WHERE u.id > 100")
                    List<BigUserEntity> findActiveUsers();
                }

                @interface Query {
                    String value();
                }
                """
            ),
            // Consommateur n'appelant que getId() et getUsername()
            java(
                """
                package com.example.batch;
                import com.example.repository.UserRepository;
                import com.example.entity.BigUserEntity;

                public class UserBatchProcessor {
                    private final UserRepository repository;

                    public UserBatchProcessor(UserRepository repository) {
                        this.repository = repository;
                    }

                    public void process() {
                        for (BigUserEntity u : repository.findActiveUsers()) {
                            System.out.println(u.getId() + " - " + u.getUsername());
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void shouldFlagEntityOverfetchingWhenQueryIsConstantInUtilities() {
        rewriteRun(
            // Classe de constantes dans utilities-commun
            java(
                """
                package com.example.util;

                public class QueryConstants {
                    public static final String REQ_BIG_USERS = "SELECT u FROM BigUserEntity u WHERE u.id > 0";
                }
                """,
                """
                package com.example.util;

                public class QueryConstants {
                    /*~~([CANDIDAT_PROJECTION] Entité 'BigUserEntity' over-fetchée (2/4 attributs consommés : [email, id]). Recommandation : remplacer par une projection ou constructeur JPQL 'new BigUserEntityLightDto(email, id)'.)~~>*/public static final String REQ_BIG_USERS = "SELECT u FROM BigUserEntity u WHERE u.id > 0";
                }
                """
            ),
            // Entité
            java(
                """
                package com.example.entity;

                public class BigUserEntity {
                    private Long id;
                    private String email;
                    private String extraData1;
                    private String extraData2;

                    public Long getId() { return id; }
                    public String getEmail() { return email; }
                    public String getExtraData1() { return extraData1; }
                    public String getExtraData2() { return extraData2; }
                }
                """
            ),
            // Consommateur n'appelant que getId et getEmail
            java(
                """
                package com.example.service;
                import com.example.entity.BigUserEntity;

                public class UserConsumer {
                    public void handle(BigUserEntity entity) {
                        System.out.println(entity.getId() + " " + entity.getEmail());
                    }
                }
                """
            )
        );
    }
}

