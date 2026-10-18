package com.kasa.repository;

import com.kasa.model.Customer;
import com.kasa.model.OrderItem;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

public interface OrderRepository {
    long placeOrder(Customer customer, List<OrderItem> items, BigDecimal total) throws SQLException;
}
