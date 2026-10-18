package com.kasa.repository;

import com.kasa.model.Customer;

import java.util.Optional;

public interface CustomerRepository {
    Optional<Customer> findByPhoneNumber(String phoneNumber);

    Customer create(String name, String phoneNumber);

    void updatePhoneNumber(String customerId, String phoneNumber);
}
