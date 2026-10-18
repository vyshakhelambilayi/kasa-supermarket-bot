package com.kasa.model;

import java.math.BigDecimal;

public class OrderItem {
    private final String itemNumber;
    private final String itemName;
    private final int quantity;
    private final BigDecimal unitPrice;

    public OrderItem(String itemNumber, String itemName, int quantity, BigDecimal unitPrice) {
        this.itemNumber = itemNumber;
        this.itemName = itemName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public String getItemNumber() { return itemNumber; }
    public String getItemName() { return itemName; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getLineTotal() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
}
