package com.kasa.repository;

import com.kasa.model.InventoryItem;
import com.kasa.model.InventoryMatch;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Searches inventory names with PostgreSQL's pg_trgm extension. */
public class PostgresInventoryRepository implements InventoryRepository {

    private static final Logger LOGGER = Logger.getLogger(PostgresInventoryRepository.class.getName());
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/kasadb";
    private static final String SQL = """
            SELECT item_number,
                   item_name,
                   price,
                   quantity_available,
                   GREATEST(
                       similarity(lower(item_name), lower(?)),
                       word_similarity(lower(?), lower(item_name))
                   ) AS match_score
            FROM public.inventory
            WHERE lower(item_name) % lower(?)
               OR lower(?) <% lower(item_name)
            ORDER BY match_score DESC, item_name
            LIMIT 5
            """;

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PostgresInventoryRepository() {
        this(System.getenv().getOrDefault("KASA_DB_URL", DEFAULT_JDBC_URL),
                System.getenv().getOrDefault("KASA_DB_USER", "kasabot"), requirePassword());
    }

    public PostgresInventoryRepository(String username, String password) {
        this(DEFAULT_JDBC_URL, username, password);
    }

    public PostgresInventoryRepository(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public List<InventoryMatch> findSimilar(String productName) {
        if (productName == null || productName.isBlank()) return List.of();

        try (var connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            connection.setAutoCommit(false);
            try (var settings = connection.createStatement()) {
                settings.execute("SET LOCAL pg_trgm.similarity_threshold = 0.15");
                settings.execute("SET LOCAL pg_trgm.word_similarity_threshold = 0.30");
            }

            try (var statement = connection.prepareStatement(SQL)) {
                String query = productName.trim();
                statement.setString(1, query);
                statement.setString(2, query);
                statement.setString(3, query);
                statement.setString(4, query);
                try (var resultSet = statement.executeQuery()) {
                    List<InventoryMatch> matches = new ArrayList<>();
                    while (resultSet.next()) {
                        InventoryItem item = new InventoryItem(
                                resultSet.getString("item_number"),
                                resultSet.getString("item_name"),
                                resultSet.getBigDecimal("price"),
                                resultSet.getInt("quantity_available"));
                        matches.add(new InventoryMatch(item, resultSet.getDouble("match_score")));
                    }
                    connection.commit();
                    return matches;
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to search inventory with pg_trgm", e);
            throw new IllegalStateException("Could not search inventory in PostgreSQL.", e);
        }
    }

    private static String requirePassword() {
        String password = System.getenv("KASA_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Required environment variable is not set: KASA_DB_PASSWORD");
        }
        return password;
    }
}
