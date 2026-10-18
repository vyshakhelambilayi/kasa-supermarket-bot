package com.kasa.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kasa.model.Customer;
import com.kasa.model.OrderItem;
import com.kasa.service.OrderService.PendingOrder;
import com.kasa.service.OrderService.UnavailableItem;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

/** Persists the customer's editable order draft so it survives bot restarts. */
public class PostgresDraftOrderRepository implements DraftOrderRepository {
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/kasadb";
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostgresDraftOrderRepository() {
        this(System.getenv().getOrDefault("KASA_DB_URL", DEFAULT_JDBC_URL),
                System.getenv().getOrDefault("KASA_DB_USER", "kasabot"), requirePassword());
    }

    public PostgresDraftOrderRepository(String username, String password) {
        this(DEFAULT_JDBC_URL, username, password);
    }

    public PostgresDraftOrderRepository(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public Optional<PendingOrder> findByTelegramChatId(long chatId) throws SQLException {
        String sql = "SELECT customer_id, customer_name, customer_phone, items::text, unavailable_items::text, "
                + "total_amount, ready_to_confirm "
                + "FROM public.order_drafts WHERE telegram_chat_id = ?";
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, chatId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                List<DraftItem> draftItems = objectMapper.readValue(result.getString("items"), new TypeReference<>() { });
                List<UnavailableItem> unavailableItems = objectMapper.readValue(result.getString("unavailable_items"),
                        new TypeReference<>() { });
                List<OrderItem> items = draftItems.stream().map(item -> new OrderItem(item.itemNumber(),
                        item.itemName(), item.quantity(), item.unitPrice())).toList();
                Customer customer = new Customer(result.getString("customer_id"),
                        result.getString("customer_name"), result.getString("customer_phone"));
                return Optional.of(new PendingOrder(customer, items, result.getBigDecimal("total_amount"),
                        result.getBoolean("ready_to_confirm"), unavailableItems));
            } catch (java.io.IOException e) {
                throw new SQLException("Could not read the saved order draft.", e);
            }
        }
    }

    @Override
    public void save(long chatId, PendingOrder pendingOrder) throws SQLException {
        String sql = "INSERT INTO public.order_drafts "
                + "(telegram_chat_id, customer_id, customer_name, customer_phone, items, unavailable_items, total_amount, ready_to_confirm, updated_at) "
                + "VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, CURRENT_TIMESTAMP) "
                + "ON CONFLICT (telegram_chat_id) DO UPDATE SET customer_id = EXCLUDED.customer_id, "
                + "customer_name = EXCLUDED.customer_name, customer_phone = EXCLUDED.customer_phone, "
                + "items = EXCLUDED.items, unavailable_items = EXCLUDED.unavailable_items, total_amount = EXCLUDED.total_amount, "
                + "ready_to_confirm = EXCLUDED.ready_to_confirm, updated_at = CURRENT_TIMESTAMP";
        List<DraftItem> items = pendingOrder.items().stream().map(item -> new DraftItem(item.getItemNumber(),
                item.getItemName(), item.getQuantity(), item.getUnitPrice())).toList();
        final String json;
        final String unavailableJson;
        try {
            json = objectMapper.writeValueAsString(items);
            unavailableJson = objectMapper.writeValueAsString(pendingOrder.unavailableItems());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException("Could not serialize the order draft.", e);
        }
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, chatId);
            statement.setString(2, pendingOrder.customer().getCustomerId());
            statement.setString(3, pendingOrder.customer().getName());
            statement.setString(4, pendingOrder.customer().getPhoneNumber());
            statement.setObject(5, json, Types.OTHER);
            statement.setObject(6, unavailableJson, Types.OTHER);
            statement.setBigDecimal(7, pendingOrder.total());
            statement.setBoolean(8, pendingOrder.readyToConfirm());
            statement.executeUpdate();
        }
    }

    @Override
    public void delete(long chatId) throws SQLException {
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = connection.prepareStatement("DELETE FROM public.order_drafts WHERE telegram_chat_id = ?")) {
            statement.setLong(1, chatId);
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

    private record DraftItem(String itemNumber, String itemName, int quantity,
                             java.math.BigDecimal unitPrice) { }
}
