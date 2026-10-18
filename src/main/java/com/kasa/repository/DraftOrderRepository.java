package com.kasa.repository;

import com.kasa.service.OrderService.PendingOrder;

import java.sql.SQLException;
import java.util.Optional;

public interface DraftOrderRepository {
    Optional<PendingOrder> findByTelegramChatId(long chatId) throws SQLException;
    void save(long chatId, PendingOrder pendingOrder) throws SQLException;
    void delete(long chatId) throws SQLException;
}
