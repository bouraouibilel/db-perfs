package com.example.sample.dto;

public record UserDirectoryDto(
        Long id,
        String fullName,
        String themePreference
) {
    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getThemePreference() {
        return themePreference;
    }
}
