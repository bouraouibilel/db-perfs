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
    @Query("SELECT u.id AS id, u.name AS name, a.city AS city, p.phoneNumber AS phoneNumber " +
           "FROM users u " +
           "LEFT JOIN address a ON u.address_id = a.id " +
           "LEFT JOIN phone_contact p ON u.phone_id = p.id")
    List<UserSummary> findUsersForMonthlyBilling();

    /**
     * Requête appelée par le contrôleur REST web-api.
     * themePreference n'est pas lu en Java, mais retourné directement en JSON !
     */
    @Query("SELECT u.id AS id, u.name AS fullName, up.theme AS themePreference " +
           "FROM users u " +
           "LEFT JOIN user_preferences up ON u.id = up.user_id")
    List<com.example.sample.dto.UserDirectoryDto> findUsersForApiDirectory();
}
