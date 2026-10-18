package com.kasa.service;

import com.kasa.dto.response.AiOrderSuggestion;
import com.kasa.model.Customer;
import com.kasa.model.InventoryItem;
import com.kasa.model.OrderItem;
import com.kasa.repository.OrderRepository;
import com.kasa.service.ai.AiOrderTakingService;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class OrderService {
    private final AiOrderTakingService aiOrderTakingService;
    private final ProductService productService;
    private final OrderRepository orderRepository;

    public OrderService(AiOrderTakingService aiOrderTakingService,
                        ProductService productService,
                        OrderRepository orderRepository) {
        this.aiOrderTakingService = aiOrderTakingService;
        this.productService = productService;
        this.orderRepository = orderRepository;
    }

    public Result process(Customer customer, File mediaFile, String textInput) throws Exception {
        AiOrderSuggestion suggestion = aiOrderTakingService.understand(mediaFile, textInput);
        return processSuggestion(customer, suggestion);
    }

    private Result processSuggestion(Customer customer, AiOrderSuggestion suggestion) {
        AiOrderSuggestion.Labels labels = AiOrderSuggestion.Labels.from(suggestion.getLabels());
        AiOrderSuggestion.Messages messages = AiOrderSuggestion.Messages.from(suggestion.getMessages());
        String greeting = customer == null ? "" : messages.getGreeting() + " " + customer.getName() + "!";
        String intent = suggestion.getIntent().toUpperCase(Locale.ROOT);

        if ("IRRELEVANT".equals(intent) || "NEEDS_CLARIFICATION".equals(intent)) {
            String reply = suggestion.getBotReply().isBlank() ? messages.getUnclear() : suggestion.getBotReply();
            return new Result(greeting + " " + reply, null);
        }
        if ("MODIFY_ORDER".equals(intent)) return new Result(greeting + " " + messages.getClarify(), null);
        if (!"CHECK_AVAILABILITY".equals(intent) && !"NEW_ORDER".equals(intent)) {
            return new Result(greeting + " " + messages.getUnclear(), null);
        }
        if (suggestion.getItems().isEmpty()) return new Result(greeting + " " + messages.getNoProducts(), null);

        boolean isOrder = "NEW_ORDER".equals(intent);
        boolean hasUnmatchedItem = false;
        boolean hasMissingQuantity = false;
        List<UnavailableItem> unavailableItems = new ArrayList<>();
        List<DisplayRow> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<String, OrderItem> orderItemsByNumber = new LinkedHashMap<>();
        Map<String, InventoryItem> matchedInventoryByNumber = new LinkedHashMap<>();

        for (AiOrderSuggestion.Item requested : suggestion.getItems()) {
            String productName = requested.getProductName().isBlank()
                    ? requested.getItemName() : requested.getProductName();
            ProductService.MatchResult match = productService.matchInventoryItem(productName);
            if (match.status() != ProductService.MatchResult.Status.MATCHED) {
                rows.add(new DisplayRow(requested.getItemName(), messages.getUnavailable(),
                        null, requested.getQuantity(), messages.getUnavailable()));
                if (isOrder) problems.add(requested.getItemName() + ": " + messages.getUnavailable() + ".");
                if (isOrder) {
                    hasUnmatchedItem = true;
                    int requestedQuantity = parseQuantity(requested.getQuantity()) == null
                            ? 1 : parseQuantity(requested.getQuantity());
                    unavailableItems.add(new UnavailableItem(requested.getItemName(), productName, requestedQuantity));
                }
                continue;
            }

            InventoryItem inventory = match.item();
            matchedInventoryByNumber.put(inventory.getItemNumber(), inventory);
            Integer quantity = parseQuantity(requested.getQuantity());
            String stockStatus = quantity != null && inventory.getQuantityAvailable() < quantity
                    ? availableMessage(messages, inventory.getQuantityAvailable())
                    : inventory.getQuantityAvailable() > 0 ? messages.getInStock() : messages.getUnavailable();
            rows.add(new DisplayRow(requested.getItemName(), inventory.getItemName(), inventory.getPrice(),
                    requested.getQuantity(), stockStatus));

            if (isOrder) {
                if (quantity == null || quantity <= 0) {
                    problems.add(quantityClarification(messages, requested.getItemName()));
                    hasMissingQuantity = true;
                    orderItemsByNumber.putIfAbsent(inventory.getItemNumber(), new OrderItem(
                            inventory.getItemNumber(), inventory.getItemName(), 0, inventory.getPrice()));
                } else {
                    OrderItem newItem = new OrderItem(inventory.getItemNumber(), inventory.getItemName(),
                            quantity, inventory.getPrice());
                    orderItemsByNumber.merge(inventory.getItemNumber(), newItem,
                            (previous, next) -> new OrderItem(previous.getItemNumber(), previous.getItemName(),
                                    previous.getQuantity() + next.getQuantity(), previous.getUnitPrice()));
                }
            }
        }

        String title = isOrder ? messages.getOrderTitle() : messages.getAvailabilityTitle();
        String table = renderSummary(rows, labels, isOrder);
        if (!isOrder) return new Result(greeting + "\n" + title + ":\n\n" + table, null);

        List<OrderItem> orderItems = new ArrayList<>(orderItemsByNumber.values());
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : orderItems) {
            total = total.add(item.getLineTotal());
            InventoryItem stock = matchedInventoryByNumber.get(item.getItemNumber());
            if (stock == null || stock.getQuantityAvailable() < item.getQuantity()) {
                int available = stock == null ? 0 : stock.getQuantityAvailable();
                problems.add(item.getItemName() + ": " + availableMessage(messages, available) + ".");
            }
        }

        String totalLine = labels.getTotal() + ": ₹" + money(total);
        if (!problems.isEmpty()) {
            String suffix = "\n\n" + messages.getStockIssue() + "\n• " + String.join("\n• ", problems);
            if (!hasUnmatchedItem && hasMissingQuantity) {
                return new Result(greeting + "\n" + title + ":\n\n" + table + suffix,
                        new PendingOrder(customer, orderItems, total, false));
            }
            if (!hasUnmatchedItem) {
                return new Result(greeting + "\n" + title + ":\n\n" + table + "\n\n" + totalLine
                        + suffix + "\n\n" + messages.getClarify(),
                        new PendingOrder(customer, orderItems, total, false));
            }
            return new Result(greeting + "\n" + title + ":\n\n" + table + suffix
                    + "\n\n" + messages.getCorrectionPrompt(),
                    new PendingOrder(customer, orderItems, total, false, unavailableItems));
        }

        return new Result(greeting + "\n" + title + ":\n\n" + table + "\n\n" + totalLine
                + "\n" + messages.getConfirmationPrompt(), new PendingOrder(customer, orderItems, total, true));
    }

    /** Classifies a pending-order message before taking any action. */
    public Result handlePendingMessage(PendingOrder pending, String text) throws Exception {
        return handlePendingMessage(pending, null, text);
    }

    public Result handlePendingMessage(PendingOrder pending, File mediaFile, String text) throws Exception {
        String context = pending.items().stream()
                .map(item -> item.getItemName() + ": " + item.getQuantity())
                .collect(java.util.stream.Collectors.joining(", "));
        if (!pending.unavailableItems().isEmpty()) {
            String unavailableContext = pending.unavailableItems().stream()
                    .map(item -> "unavailable " + item.productName() + ": " + item.quantity())
                    .collect(java.util.stream.Collectors.joining(", "));
            context = context.isBlank() ? unavailableContext : context + ", " + unavailableContext;
        }
        AiOrderSuggestion suggestion = aiOrderTakingService.understand(mediaFile, text, context);
        AiOrderSuggestion.Messages messages = AiOrderSuggestion.Messages.from(suggestion.getMessages());
        switch (suggestion.getIntent()) {
            case "MODIFY_ORDER" -> { return applyCorrection(pending, suggestion); }
            case "CONFIRM_ORDER" -> { return new Result("", pending, true); }
            case "IRRELEVANT", "NEEDS_CLARIFICATION" -> {
                String reply = suggestion.getBotReply().isBlank() ? messages.getClarify() : suggestion.getBotReply();
                return new Result(reply + "\n\n" + messages.getConfirmationPrompt(), pending);
            }
            case "CHECK_AVAILABILITY" -> {
                Result availability = processSuggestion(pending.customer(), suggestion);
                return new Result(availability.message() + "\n\nYour order draft is still open.\n"
                        + messages.getConfirmationPrompt(), pending);
            }
            case "NEW_ORDER" -> {
                return new Result(messages.getClarify()
                        + " Please confirm or cancel the current order before starting another one.", pending);
            }
            default -> { return new Result(messages.getClarify(), pending); }
        }
    }

    /** Applies only a Gemini-classified modification to the draft. */
    private Result applyCorrection(PendingOrder pending, AiOrderSuggestion suggestion) {
        AiOrderSuggestion.Messages messages = AiOrderSuggestion.Messages.from(suggestion.getMessages());
        AiOrderSuggestion.Labels labels = AiOrderSuggestion.Labels.from(suggestion.getLabels());
        Map<String, OrderItem> updated = new LinkedHashMap<>();
        Map<String, InventoryItem> inventoryByNumber = new LinkedHashMap<>();
        List<UnavailableItem> unavailableItems = new ArrayList<>(pending.unavailableItems());

        for (OrderItem old : pending.items()) {
            ProductService.MatchResult match = productService.matchInventoryItem(old.getItemName());
            if (match.status() == ProductService.MatchResult.Status.MATCHED) {
                inventoryByNumber.put(old.getItemNumber(), match.item());
                updated.put(old.getItemNumber(), old);
            } else {
                unavailableItems.add(new UnavailableItem(old.getItemName(), old.getItemName(), old.getQuantity()));
            }
        }

        boolean appliedAny = false;
        for (AiOrderSuggestion.Item change : suggestion.getItems()) {
            String name = change.getProductName().isBlank() ? change.getItemName() : change.getProductName();
            ProductService.MatchResult match = productService.matchInventoryItem(name);
            String action = change.getAction() == null ? "ADD" : change.getAction().toUpperCase(Locale.ROOT);
            if ("REMOVE".equals(action)) {
                boolean removed = false;
                if (match.status() == ProductService.MatchResult.Status.MATCHED) {
                    removed = updated.remove(match.item().getItemNumber()) != null;
                }
                int unavailableIndex = findUnavailableIndex(unavailableItems, name);
                if (unavailableIndex >= 0) {
                    unavailableItems.remove(unavailableIndex);
                    removed = true;
                }
                appliedAny |= removed;
                continue;
            }
            Integer quantity = parseQuantity(change.getQuantity());
            if (quantity == null || quantity <= 0) continue;
            if (match.status() != ProductService.MatchResult.Status.MATCHED) {
                int unavailableIndex = findUnavailableIndex(unavailableItems, name);
                if ("SET_QUANTITY".equals(action) && unavailableIndex >= 0) {
                    UnavailableItem old = unavailableItems.get(unavailableIndex);
                    unavailableItems.set(unavailableIndex,
                            new UnavailableItem(old.requestedName(), old.productName(), quantity));
                    appliedAny = true;
                } else if ("ADD".equals(action)) {
                    if (unavailableIndex >= 0) {
                        UnavailableItem old = unavailableItems.get(unavailableIndex);
                        unavailableItems.set(unavailableIndex, new UnavailableItem(old.requestedName(),
                                old.productName(), old.quantity() + quantity));
                    } else {
                        unavailableItems.add(new UnavailableItem(change.getItemName(), name, quantity));
                    }
                    appliedAny = true;
                }
                continue;
            }
            InventoryItem inventory = match.item();
            if ("SET_QUANTITY".equals(action)) {
                updated.put(inventory.getItemNumber(), new OrderItem(inventory.getItemNumber(),
                        inventory.getItemName(), quantity, inventory.getPrice()));
            } else if ("ADD".equals(action)) {
                OrderItem addition = new OrderItem(inventory.getItemNumber(), inventory.getItemName(), quantity,
                        inventory.getPrice());
                updated.merge(inventory.getItemNumber(), addition, (old, add) -> new OrderItem(old.getItemNumber(),
                        old.getItemName(), old.getQuantity() + add.getQuantity(), old.getUnitPrice()));
            } else {
                continue;
            }
            inventoryByNumber.put(inventory.getItemNumber(), inventory);
            appliedAny = true;
        }

        if (!appliedAny) {
            return new Result(messages.getClarify()
                    + " You can say: add 2 milk, remove bread, or change milk quantity to 3.", pending);
        }
        if (updated.isEmpty() && unavailableItems.isEmpty()) {
            return new Result("Your order is now empty. Send more items to add, or /cancel to discard it.", null);
        }

        List<OrderItem> items = new ArrayList<>(updated.values());
        BigDecimal total = BigDecimal.ZERO;
        List<String> issues = new ArrayList<>();
        StringBuilder reply = new StringBuilder(messages.getOrderTitle()).append("\n\n");
        int lineNumber = 1;
        for (OrderItem item : items) {
            InventoryItem inventory = inventoryByNumber.get(item.getItemNumber());
            total = total.add(item.getLineTotal());
            reply.append(lineNumber++).append(") ").append(item.getItemName()).append('\n')
                    .append("   ").append(labels.getPrice()).append(": ₹").append(money(item.getUnitPrice()))
                    .append(" × ").append(item.getQuantity()).append(" = ₹").append(money(item.getLineTotal()));
            if (item.getQuantity() <= 0) {
                reply.append('\n').append("   ").append(quantityClarification(messages, item.getItemName())).append('\n');
                continue;
            }
            if (inventory != null) {
                if (inventory.getQuantityAvailable() < item.getQuantity()) {
                    String status = availableMessage(messages, inventory.getQuantityAvailable());
                    reply.append('\n').append("   ").append(status);
                    issues.add(item.getItemName() + ": " + status + ".");
                } else {
                    reply.append('\n').append("   ").append(messages.getInStock());
                }
            }
            reply.append('\n');
        }
        for (UnavailableItem item : unavailableItems) {
            reply.append(lineNumber++).append(") ").append(item.requestedName()).append('\n')
                    .append("   ").append(messages.getUnavailable()).append(" — ")
                    .append(labels.getQuantityRequested()).append(": ").append(item.quantity()).append("\n\n");
            issues.add(item.requestedName() + ": " + messages.getUnavailable() + ". Remove it or choose another item.");
        }
        boolean hasMissingQuantity = items.stream().anyMatch(item -> item.getQuantity() <= 0);
        boolean canShowTotal = !hasMissingQuantity && unavailableItems.isEmpty();
        if (canShowTotal) reply.append('\n').append(labels.getTotal()).append(": ₹").append(money(total));
        if (!issues.isEmpty()) {
            if (unavailableItems.isEmpty()) {
                reply.append("\n\n").append(messages.getStockIssue()).append("\n• ")
                        .append(String.join("\n• ", issues)).append("\n\n").append(messages.getCorrectionPrompt());
            } else {
                reply.append("\n\n").append(messages.getCorrectionPrompt());
            }
            return new Result(reply.toString(),
                    new PendingOrder(pending.customer(), items, total, false, unavailableItems));
        }
        if (hasMissingQuantity) {
            reply.append("\n\n").append(messages.getQuantityClarification().replace("{item}", "the item"));
            return new Result(reply.toString(), new PendingOrder(pending.customer(), items, total, false, unavailableItems));
        }
        reply.append("\n\n").append(messages.getConfirmationPrompt());
        return new Result(reply.toString(), new PendingOrder(pending.customer(), items, total, true, unavailableItems));
    }

    private int findUnavailableIndex(List<UnavailableItem> items, String requestedName) {
        String query = normalizeName(requestedName);
        for (int i = 0; i < items.size(); i++) {
            String candidate = normalizeName(items.get(i).productName());
            if (query.equals(candidate) || (query.length() >= 5 && candidate.contains(query))
                    || (candidate.length() >= 5 && query.contains(candidate))) return i;
        }
        return -1;
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private String renderSummary(List<DisplayRow> rows, AiOrderSuggestion.Labels labels, boolean isOrder) {
        StringBuilder summary = new StringBuilder();
        int index = 1;
        for (DisplayRow row : rows) {
            if (index > 1) summary.append('\n');
            summary.append(index++).append(") ").append(cell(row.itemName())).append('\n');
            if (row.price() != null) {
                summary.append("   ").append(cell(row.productName())).append('\n')
                        .append("   ").append(labels.getPrice()).append(": ₹").append(money(row.price()));
                if (row.quantity() != null && !row.quantity().isBlank()) {
                    summary.append("  ×  ").append(cell(row.quantity()));
                    Integer quantity = parseQuantity(row.quantity());
                    if (isOrder && quantity != null) {
                        summary.append(" = ₹").append(money(row.price().multiply(BigDecimal.valueOf(quantity))));
                    }
                }
            } else {
                summary.append("   ").append(row.stockStatus());
                if (row.quantity() != null && !row.quantity().isBlank()) {
                    summary.append(" — ").append(labels.getQuantityRequested()).append(": ").append(cell(row.quantity()));
                }
            }
            if (row.price() != null && row.stockStatus() != null && !row.stockStatus().isBlank()) {
                summary.append('\n').append("   ").append(row.stockStatus());
            }
            summary.append('\n');
        }
        return summary.toString().stripTrailing();
    }

    private String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ").trim();
    }

    public long confirm(PendingOrder pendingOrder) throws Exception {
        if (pendingOrder.customer() == null) {
            throw new IllegalStateException("A customer phone number is required before placing the order.");
        }
        if (!pendingOrder.readyToConfirm() || !pendingOrder.unavailableItems().isEmpty()) {
            throw new IllegalStateException("The order has unavailable items and needs customer correction.");
        }
        return orderRepository.placeOrder(pendingOrder.customer(), pendingOrder.items(), pendingOrder.total());
    }

    private Integer parseQuantity(String quantity) {
        if (quantity == null || !quantity.trim().matches("[0-9]+")) return null;
        try { return Integer.parseInt(quantity.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private String availableMessage(AiOrderSuggestion.Messages messages, int available) {
        if (available <= 0) return messages.getUnavailable();
        return messages.getOnlyAvailable().replace("{quantity}", Integer.toString(available));
    }

    private String quantityClarification(AiOrderSuggestion.Messages messages, String itemName) {
        return messages.getQuantityClarification().replace("{item}", itemName);
    }

    private String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private record DisplayRow(String itemName, String productName, BigDecimal price,
                              String quantity, String stockStatus) { }

    public record PendingOrder(Customer customer, List<OrderItem> items, BigDecimal total,
                               boolean readyToConfirm, List<UnavailableItem> unavailableItems) {
        public PendingOrder(Customer customer, List<OrderItem> items, BigDecimal total, boolean readyToConfirm) {
            this(customer, items, total, readyToConfirm, List.of());
        }

        public PendingOrder {
            items = List.copyOf(items);
            unavailableItems = List.copyOf(unavailableItems);
        }
    }

    public record UnavailableItem(String requestedName, String productName, int quantity) { }

    public record Result(String message, PendingOrder pendingOrder, boolean confirmRequested) {
        public Result(String message, PendingOrder pendingOrder) {
            this(message, pendingOrder, false);
        }
    }
}
