package com.example.sample.batch;

import com.example.sample.dto.UserSummary;
import com.example.sample.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BillingProcessor {

    private final UserRepository userRepository;

    public BillingProcessor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public void processMonthlyBilling() {
        List<UserSummary> users = userRepository.findUsersForMonthlyBilling();
        for (UserSummary user : users) {
            // Le batch n'utilise QUE l'id et le nom pour générer les factures
            // getCity() et getPhoneNumber() ne sont JAMAIS appelés !
            System.out.println("Processing billing for user #" + user.getId() + " - " + user.getName());
        }
    }
}
