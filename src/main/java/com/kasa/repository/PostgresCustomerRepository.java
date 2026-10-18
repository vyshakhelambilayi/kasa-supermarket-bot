package com.kasa.repository;

import com.kasa.model.Customer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** PostgreSQL-backed customer lookup for the existing public.customers table. */
public class PostgresCustomerRepository implements CustomerRepository {

    private static final Logger LOGGER = Logger.getLogger(PostgresCustomerRepository.class.getName());
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/kasadb";
    private static final String SQL = """
            SELECT customer_id, name, phone_number
            FROM public.customers
            WHERE regexp_replace(phone_number, '[^0-9]', '', 'g') = ?
            LIMIT 1
            """;

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PostgresCustomerRepository() {
        this(
                envOrDefault("KASA_DB_URL", DEFAULT_JDBC_URL),
                envOrDefault("KASA_DB_USER", "kasabot"),
                requireEnvironmentVariable("KASA_DB_PASSWORD"));
    }

    public PostgresCustomerRepository(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public Optional<Customer> findByPhoneNumber(String phoneNumber) {
        String normalizedNumber = phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
        if (normalizedNumber.isBlank()) {
            return Optional.empty();
        }

        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = connection.prepareStatement(SQL)) {
            statement.setString(1, normalizedNumber);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Customer(
                        resultSet.getString("customer_id"),
                        resultSet.getString("name"),
                        resultSet.getString("phone_number")));
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to look up a customer by phone number", e);
            throw new IllegalStateException("Could not look up the customer in PostgreSQL.", e);
        }
    }

    @Override
    public Customer create(String name, String phoneNumber) {
        String customerId = "CUS" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        String sql = "INSERT INTO public.customers (customer_id, name, phone_number) VALUES (?, ?, ?)";
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, customerId);
            statement.setString(2, name);
            statement.setString(3, phoneNumber);
            statement.executeUpdate();
            return new Customer(customerId, name, phoneNumber);
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                Optional<Customer> existing = findByPhoneNumber(phoneNumber);
                if (existing.isPresent()) return existing.get();
            }
            LOGGER.log(Level.SEVERE, "Failed to register a customer", e);
            throw new IllegalStateException("Could not register the customer in PostgreSQL.", e);
        }
    }

    @Override
    public void updatePhoneNumber(String customerId, String phoneNumber) {
        String sql = "UPDATE public.customers SET phone_number = ? WHERE customer_id = ?";
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, phoneNumber);
            statement.setString(2, customerId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("The customer account could not be found.");
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to update a customer's phone number", e);
            throw new IllegalStateException("Could not update the customer's phone number in PostgreSQL.", e);
        }
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String requireEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is not set: " + name);
        }
        return value;
    }
}
