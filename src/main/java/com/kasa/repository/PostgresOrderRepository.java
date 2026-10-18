package com.kasa.repository;

import com.kasa.model.Customer;
import com.kasa.model.OrderItem;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/** Persists a confirmed order and decrements inventory atomically. */
public class PostgresOrderRepository implements OrderRepository {

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/kasadb";
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PostgresOrderRepository() {
        this(System.getenv().getOrDefault("KASA_DB_URL", DEFAULT_JDBC_URL),
                System.getenv().getOrDefault("KASA_DB_USER", "kasabot"), requirePassword());
    }

    public PostgresOrderRepository(String username, String password) {
        this(DEFAULT_JDBC_URL, username, password);
    }

    public PostgresOrderRepository(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public long placeOrder(Customer customer, List<OrderItem> items, BigDecimal total) throws SQLException {
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            connection.setAutoCommit(false);
            try {
                long orderId = insertOrder(connection, customer, total);
                for (OrderItem item : items) {
                    decrementStock(connection, item);
                    insertOrderItem(connection, orderId, item);
                }
                connection.commit();
                return orderId;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private long insertOrder(java.sql.Connection connection, Customer customer, BigDecimal total) throws SQLException {
        String sql = "INSERT INTO public.orders (customer_id, status, total_amount) "
                + "VALUES (?, 'CONFIRMED', ?) RETURNING order_id";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, customer.getCustomerId());
            statement.setBigDecimal(2, total);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("PostgreSQL did not return the new order ID.");
                }
                return result.getLong("order_id");
            }
        }
    }

    private void decrementStock(java.sql.Connection connection, OrderItem item) throws SQLException {
        String sql = "UPDATE public.inventory SET quantity_available = quantity_available - ? "
                + "WHERE item_number = ? AND quantity_available >= ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, item.getQuantity());
            statement.setString(2, item.getItemNumber());
            statement.setInt(3, item.getQuantity());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Insufficient stock for " + item.getItemName() + ". Please check availability again.");
            }
        }
    }

    private void insertOrderItem(java.sql.Connection connection, long orderId, OrderItem item) throws SQLException {
        String sql = "INSERT INTO public.order_items "
                + "(order_id, item_number, item_name, quantity, unit_price, line_total) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, orderId);
            statement.setString(2, item.getItemNumber());
            statement.setString(3, item.getItemName());
            statement.setInt(4, item.getQuantity());
            statement.setBigDecimal(5, item.getUnitPrice());
            statement.setBigDecimal(6, item.getLineTotal());
            statement.executeUpdate();
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
