package com.kasa.model;

import java.math.BigDecimal;

public class InventoryItem {
    private final String itemNumber;
    private final String itemName;
    private final BigDecimal price;
    private final int quantityAvailable;

    public InventoryItem(String itemNumber, String itemName, BigDecimal price, int quantityAvailable) {
        this.itemNumber = itemNumber;
        this.itemName = itemName;
        this.price = price;
        this.quantityAvailable = quantityAvailable;
    }

    public String getItemNumber() { return itemNumber; }
    public String getItemName() { return itemName; }
    public BigDecimal getPrice() { return price; }
    public int getQuantityAvailable() { return quantityAvailable; }
}
