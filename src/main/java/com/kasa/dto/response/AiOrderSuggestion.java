package com.kasa.dto.response;

import java.util.List;

public class AiOrderSuggestion {
    private final String intent;
    private final String botReply;
    private final Labels labels;
    private final Messages messages;
    private final List<Item> items;

    public AiOrderSuggestion(String intent, String botReply, Labels labels, Messages messages, List<Item> items) {
        this.intent = intent;
        this.botReply = botReply;
        this.labels = labels;
        this.messages = messages;
        this.items = List.copyOf(items);
    }

    public String getIntent() { return intent; }
    public String getBotReply() { return botReply; }
    public Labels getLabels() { return labels; }
    public Messages getMessages() { return messages; }
    public List<Item> getItems() { return items; }

    public static class Labels {
        private final String itemName;
        private final String productName;
        private final String price;
        private final String quantityRequested;
        private final String availableStock;
        private final String total;

        public Labels(String itemName, String productName, String price,
                      String quantityRequested, String availableStock, String total) {
            this.itemName = itemName;
            this.productName = productName;
            this.price = price;
            this.quantityRequested = quantityRequested;
            this.availableStock = availableStock;
            this.total = total;
        }

        public String getItemName() { return itemName; }
        public String getProductName() { return productName; }
        public String getPrice() { return price; }
        public String getQuantityRequested() { return quantityRequested; }
        public String getAvailableStock() { return availableStock; }
        public String getTotal() { return total; }

        public static Labels english() {
            return new Labels("Item name", "Product name", "Price", "Qty requested", "Available stock", "Total");
        }

        public static Labels from(AiOrderSuggestion.Labels source) {
            if (source == null) return english();
            Labels fallback = english();
            return new Labels(value(source.itemName, fallback.itemName), value(source.productName, fallback.productName),
                    value(source.price, fallback.price), value(source.quantityRequested, fallback.quantityRequested),
                    value(source.availableStock, fallback.availableStock), value(source.total, fallback.total));
        }

        private static String value(String text, String fallback) {
            return text == null || text.isBlank() ? fallback : text.trim();
        }
    }

    public static class Messages {
        private final String greeting;
        private final String availabilityTitle;
        private final String orderTitle;
        private final String confirmationPrompt;
        private final String notRequest;
        private final String unclear;
        private final String noProducts;
        private final String stockIssue;
        private final String clarify;
        private final String inStock;
        private final String onlyAvailable;
        private final String unavailable;
        private final String quantityClarification;
        private final String correctionPrompt;

        public Messages(String greeting, String availabilityTitle, String orderTitle,
                        String confirmationPrompt, String notRequest, String unclear,
                        String noProducts, String stockIssue, String clarify,
                        String inStock, String onlyAvailable, String unavailable, String quantityClarification,
                        String correctionPrompt) {
            this.greeting = greeting;
            this.availabilityTitle = availabilityTitle;
            this.orderTitle = orderTitle;
            this.confirmationPrompt = confirmationPrompt;
            this.notRequest = notRequest;
            this.unclear = unclear;
            this.noProducts = noProducts;
            this.stockIssue = stockIssue;
            this.clarify = clarify;
            this.inStock = inStock;
            this.onlyAvailable = onlyAvailable;
            this.unavailable = unavailable;
            this.quantityClarification = quantityClarification;
            this.correctionPrompt = correctionPrompt;
        }

        public String getGreeting() { return greeting; }
        public String getAvailabilityTitle() { return availabilityTitle; }
        public String getOrderTitle() { return orderTitle; }
        public String getConfirmationPrompt() { return confirmationPrompt; }
        public String getNotRequest() { return notRequest; }
        public String getUnclear() { return unclear; }
        public String getNoProducts() { return noProducts; }
        public String getStockIssue() { return stockIssue; }
        public String getClarify() { return clarify; }
        public String getInStock() { return inStock; }
        public String getOnlyAvailable() { return onlyAvailable; }
        public String getUnavailable() { return unavailable; }
        public String getQuantityClarification() { return quantityClarification; }
        public String getCorrectionPrompt() { return correctionPrompt; }

        public static Messages english() {
            return new Messages("Hi", "Availability and prices", "Please review your order",
                    "Does this look correct? Reply /confirm to place it, /cancel to discard it, or send a change such as 'add 2 milk', 'remove bread', or 'change milk quantity to 3'.",
                    "I can check product availability and prices or prepare an order. Tell me which items you need.",
                    "I didn't understand that as an availability check or an order. Please name the item and say whether you want to check it or order it.",
                    "Please tell me which product you want to check or order.",
                    "I can't prepare this order yet:",
                    "Please clarify which inventory item you mean.",
                    "Available", "Only {quantity} available", "Currently unavailable",
                    "How many {item} would you like?",
                    "Tell me which unavailable item to remove or what replacement you would like.");
        }

        public static Messages from(AiOrderSuggestion.Messages source) {
            if (source == null) return english();
            Messages fallback = english();
            return new Messages(value(source.greeting, fallback.greeting), value(source.availabilityTitle, fallback.availabilityTitle),
                    value(source.orderTitle, fallback.orderTitle), value(source.confirmationPrompt, fallback.confirmationPrompt),
                    value(source.notRequest, fallback.notRequest), value(source.unclear, fallback.unclear),
                    value(source.noProducts, fallback.noProducts), value(source.stockIssue, fallback.stockIssue),
                    value(source.clarify, fallback.clarify), value(source.inStock, fallback.inStock),
                    value(source.onlyAvailable, fallback.onlyAvailable), value(source.unavailable, fallback.unavailable),
                    value(source.quantityClarification, fallback.quantityClarification),
                    value(source.correctionPrompt, fallback.correctionPrompt));
        }

        private static String value(String text, String fallback) {
            return text == null || text.isBlank() ? fallback : text.trim();
        }
    }

    public static class Item {
        private final String itemName;
        private final String productName;
        private final String quantity;
        private final String notes;
        private final String action;

        public Item(String itemName, String productName, String quantity, String notes, String action) {
            this.itemName = itemName;
            this.productName = productName;
            this.quantity = quantity;
            this.notes = notes;
            this.action = action;
        }

        public String getItemName() { return itemName; }
        public String getProductName() { return productName; }
        public String getQuantity() { return quantity; }
        public String getNotes() { return notes; }
        public String getAction() { return action; }
    }
}
