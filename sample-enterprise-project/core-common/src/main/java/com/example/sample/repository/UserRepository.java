package com.example.sample.repository;

import com.example.sample.dto.UserSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRepository {

    /**
     * Requête commune joignant address et phone_contact.
     * Dans le batch, ni address ni phone_contact ne sont utilisés !
     */
    /*~~([CANDIDAT_ELIMINATION] Jointure 'address' (LEFT JOIN) potentiellement inutile : aucune colonne référencée dans le code Java.)~~>*/@Query("SELECT u.id AS id, u.name AS name, a.city AS city, p.phoneNumber AS phoneNumber " +
           "FROM users u " +
           "LEFT JOIN address a ON u.address_id = a.id " +
           "LEFT JOIN phone_contact p ON u.phone_id = p.id")
    List<UserSummary> findUsersForMonthlyBilling();

    /**
     * Requête appelée par le contrôleur REST web-api.
     * themePreference n'est pas lu en Java, mais retourné directement en JSON !
     */
    /*~~([ATTENTION_WEB] Jointure 'user_preferences' (LEFT JOIN) dont les colonnes [themePreference] ne sont pas lues en Java, mais le type com.example.sample.dto.UserDirectoryDto est exposé sur une API Web (sérialisation JSON potentielle).)~~>*/@Query("SELECT u.id AS id, u.name AS fullName, up.theme AS themePreference " +
           "FROM users u " +
           "LEFT JOIN user_preferences up ON u.id = up.user_id")
    List<com.example.sample.dto.UserDirectoryDto> findUsersForApiDirectory();
}
