package com.kasa.service;

import com.kasa.model.Customer;
import com.kasa.repository.CustomerRepository;

import java.util.Optional;

public class CustomerService {
    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public Optional<Customer> findByPhoneNumber(String phoneNumber) {
        return customerRepository.findByPhoneNumber(phoneNumber);
    }

    public Customer register(String name, String phoneNumber) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Customer name is required.");
        }
        if (phoneNumber == null || phoneNumber.replaceAll("\\D", "").isBlank()) {
            throw new IllegalArgumentException("A valid phone number is required.");
        }

        String cleanName = name.trim().replaceAll("\\s+", " ");
        if (cleanName.length() > 100) {
            throw new IllegalArgumentException("Customer name must be 100 characters or fewer.");
        }
        return customerRepository.create(cleanName, phoneNumber.trim());
    }

    public Customer updatePhoneNumber(Customer customer, String phoneNumber) {
        if (customer == null) throw new IllegalArgumentException("Customer account is required.");
        if (phoneNumber == null || phoneNumber.replaceAll("\\D", "").isBlank()) {
            throw new IllegalArgumentException("Please share a valid phone number.");
        }
        String cleanPhone = phoneNumber.trim();
        Optional<Customer> existing = customerRepository.findByPhoneNumber(cleanPhone);
        if (existing.isPresent() && !existing.get().getCustomerId().equals(customer.getCustomerId())) {
            throw new IllegalArgumentException("That phone number is already linked to another customer account.");
        }
        customerRepository.updatePhoneNumber(customer.getCustomerId(), cleanPhone);
        return new Customer(customer.getCustomerId(), customer.getName(), cleanPhone);
    }
}
