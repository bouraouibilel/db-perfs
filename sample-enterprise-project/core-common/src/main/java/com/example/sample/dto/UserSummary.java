package com.example.sample.dto;

public record UserSummary(
        Long id,
        String name,
        String city,
        String phoneNumber
) {
    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCity() {
        return city;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }
}
