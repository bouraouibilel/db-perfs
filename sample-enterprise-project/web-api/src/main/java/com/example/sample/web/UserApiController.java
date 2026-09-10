package com.example.sample.web;

import com.example.sample.dto.UserDirectoryDto;
import com.example.sample.repository.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class UserApiController {

    private final UserRepository userRepository;

    public UserApiController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/api/users")
    public List<UserDirectoryDto> getAllUsers() {
        // Le contrôleur retourne directement la liste au framework Spring MVC
        // Jackson sérialise automatiquement tous les getters en JSON :
        // Aucun appel explicite à user.getThemePreference() n'existe dans le code Java !
        return userRepository.findUsersForApiDirectory();
    }
}
