package com.kasa.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kasa.dto.response.AiOrderSuggestion;
import com.kasa.model.Customer;
import com.kasa.model.InventoryItem;
import com.kasa.model.InventoryMatch;
import com.kasa.model.OrderItem;
import com.kasa.repository.InventoryRepository;
import com.kasa.repository.OrderRepository;
import com.kasa.service.OrderService.PendingOrder;
import com.kasa.service.ai.AiOrderTakingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderServiceTest {

    private static final Customer CUSTOMER = new Customer("C-1", "Asha", "9999999999");
    private static final InventoryItem MILK = new InventoryItem("P-1", "Nandini Toned Milk 1L",
            new BigDecimal("32.50"), 10);

    private StubAiService aiService;
    private StubInventoryRepository inventoryRepository;
    private RecordingOrderRepository orderRepository;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        aiService = new StubAiService();
        inventoryRepository = new StubInventoryRepository();
        orderRepository = new RecordingOrderRepository();
        orderService = new OrderService(aiService, new ProductService(inventoryRepository), orderRepository);
    }

    @Test
    void newOrderUsesDefaultQuantityAndCalculatesTotal() throws Exception {
        aiService.suggestion = suggestion("NEW_ORDER", item("milk", "Nandini Toned Milk 1L", "1"));

        OrderService.Result result = orderService.process(CUSTOMER, null, "one milk");

        assertNotNull(result.pendingOrder());
        assertTrue(result.pendingOrder().readyToConfirm());
        assertEquals(1, result.pendingOrder().items().get(0).getQuantity());
        assertEquals(new BigDecimal("32.50"), result.pendingOrder().total());
        assertTrue(result.message().contains("₹32.50"));
        assertTrue(result.message().contains("Asha"));
    }

    @Test
    void availabilityCheckDoesNotCreateOrderDraft() throws Exception {
        aiService.suggestion = suggestion("CHECK_AVAILABILITY", item("milk", "Nandini Toned Milk 1L", "2"));

        OrderService.Result result = orderService.process(CUSTOMER, null, "is milk available?");

        assertNull(result.pendingOrder());
        assertTrue(result.message().contains("Availability and prices"));
        assertTrue(result.message().contains("₹32.50"));
        assertTrue(result.message().contains("×  2"));
    }

    @Test
    void unavailableProductIsReportedWithoutShowingPriceAndDraftCannotBeConfirmed() throws Exception {
        aiService.suggestion = suggestion("NEW_ORDER", item("rare item", "Not in inventory", "2"));
        inventoryRepository.matches = List.of();

        OrderService.Result result = orderService.process(CUSTOMER, null, "two rare items");

        assertNotNull(result.pendingOrder());
        assertFalse(result.pendingOrder().readyToConfirm());
        assertFalse(result.message().contains("₹"));
        assertTrue(result.message().contains("Currently unavailable"));
        assertThrows(IllegalStateException.class, () -> orderService.confirm(result.pendingOrder()));
        assertFalse(orderRepository.called);
    }

    @Test
    void insufficientStockReportsAvailableQuantityAndPreventsConfirmation() throws Exception {
        inventoryRepository.matches = List.of(new InventoryMatch(MILK, 0.95));
        aiService.suggestion = suggestion("NEW_ORDER", item("milk", "Nandini Toned Milk 1L", "12"));

        OrderService.Result result = orderService.process(CUSTOMER, null, "12 milk");

        assertNotNull(result.pendingOrder());
        assertFalse(result.pendingOrder().readyToConfirm());
        assertTrue(result.message().contains("Only 10 available"));
        assertThrows(IllegalStateException.class, () -> orderService.confirm(result.pendingOrder()));
    }

    @Test
    void ambiguousOrderAsksCustomerToChooseAndKeepsCandidatesInPendingDraft() throws Exception {
        InventoryItem goodlifeMilk = new InventoryItem("P-2", "Nandini Goodlife Toned Milk 1L",
                new BigDecimal("34.00"), 8);
        inventoryRepository.matches = List.of(
                new InventoryMatch(MILK, 0.95),
                new InventoryMatch(goodlifeMilk, 0.90));
        aiService.suggestion = suggestion("NEW_ORDER", item("milk", "milk", "2"));

        OrderService.Result result = orderService.process(CUSTOMER, null, "2 milk");

        assertNotNull(result.pendingOrder());
        assertFalse(result.pendingOrder().readyToConfirm());
        assertTrue(result.pendingOrder().items().isEmpty());
        assertEquals(2, result.pendingOrder().unavailableItems().get(0).alternatives().size());
        assertTrue(result.message().contains("Which milk did you mean?"));
        assertTrue(result.message().contains("1) Nandini Toned Milk 1L"));
        assertTrue(result.message().contains("2) Nandini Goodlife Toned Milk 1L"));
        assertTrue(result.message().contains("₹34.00"));
        assertThrows(IllegalStateException.class, () -> orderService.confirm(result.pendingOrder()));
    }

    @Test
    void customerChoiceAddsSelectedInventoryItemAndEnablesConfirmation() throws Exception {
        InventoryItem goodlifeMilk = new InventoryItem("P-2", "Nandini Goodlife Toned Milk 1L",
                new BigDecimal("34.00"), 8);
        inventoryRepository.matches = List.of(
                new InventoryMatch(MILK, 0.95),
                new InventoryMatch(goodlifeMilk, 0.90));
        aiService.suggestion = suggestion("NEW_ORDER", item("milk", "milk", "2"));
        PendingOrder ambiguousDraft = orderService.process(CUSTOMER, null, "2 milk").pendingOrder();
        ObjectMapper objectMapper = new ObjectMapper();
        List<OrderService.UnavailableItem> restoredChoices = objectMapper.readValue(
                objectMapper.writeValueAsString(ambiguousDraft.unavailableItems()),
                new TypeReference<>() { });
        ambiguousDraft = new PendingOrder(CUSTOMER, ambiguousDraft.items(), ambiguousDraft.total(),
                ambiguousDraft.readyToConfirm(), restoredChoices);

        OrderService.Result result = orderService.handlePendingMessage(ambiguousDraft, "2");

        assertTrue(result.pendingOrder().readyToConfirm());
        assertTrue(result.pendingOrder().unavailableItems().isEmpty());
        assertEquals(1, result.pendingOrder().items().size());
        assertEquals("P-2", result.pendingOrder().items().get(0).getItemNumber());
        assertEquals("Nandini Goodlife Toned Milk 1L", result.pendingOrder().items().get(0).getItemName());
        assertEquals(2, result.pendingOrder().items().get(0).getQuantity());
        assertEquals(new BigDecimal("68.00"), result.pendingOrder().total());
        assertTrue(result.message().contains("Reply /confirm"));

        assertEquals(42L, orderService.confirm(result.pendingOrder()));
        assertEquals("P-2", orderRepository.items.get(0).getItemNumber());
    }

    @Test
    void invalidAmbiguousChoiceKeepsDraftWaitingAndRepeatsOptions() throws Exception {
        InventoryItem goodlifeMilk = new InventoryItem("P-2", "Nandini Goodlife Toned Milk 1L",
                new BigDecimal("34.00"), 8);
        inventoryRepository.matches = List.of(
                new InventoryMatch(MILK, 0.95),
                new InventoryMatch(goodlifeMilk, 0.90));
        aiService.suggestion = suggestion("NEW_ORDER", item("milk", "milk", "1"));
        PendingOrder ambiguousDraft = orderService.process(CUSTOMER, null, "milk").pendingOrder();

        OrderService.Result result = orderService.handlePendingMessage(ambiguousDraft, "3");

        assertSame(ambiguousDraft, result.pendingOrder());
        assertFalse(result.pendingOrder().readyToConfirm());
        assertTrue(result.message().contains("1) Nandini Toned Milk 1L"));
        assertTrue(result.message().contains("2) Nandini Goodlife Toned Milk 1L"));
    }

    @Test
    void confirmPersistsCustomerItemsAndCalculatedTotal() throws Exception {
        aiService.suggestion = suggestion("NEW_ORDER", item("milk", "Nandini Toned Milk 1L", "3"));
        PendingOrder pending = orderService.process(CUSTOMER, null, "3 milk").pendingOrder();

        long orderId = orderService.confirm(pending);

        assertEquals(42L, orderId);
        assertTrue(orderRepository.called);
        assertSame(CUSTOMER, orderRepository.customer);
        assertEquals(3, orderRepository.items.get(0).getQuantity());
        assertEquals(new BigDecimal("97.50"), orderRepository.total);
    }

    @Test
    void irrelevantMessageDoesNotCreatePendingOrder() throws Exception {
        aiService.suggestion = new AiOrderSuggestion("IRRELEVANT", "How can I help?", null,
                AiOrderSuggestion.Messages.english(), List.of());

        OrderService.Result result = orderService.process(CUSTOMER, null, "hello");

        assertNull(result.pendingOrder());
        assertTrue(result.message().contains("How can I help?"));
    }

    private static AiOrderSuggestion suggestion(String intent, AiOrderSuggestion.Item... items) {
        return new AiOrderSuggestion(intent, "", AiOrderSuggestion.Labels.english(),
                AiOrderSuggestion.Messages.english(), List.of(items));
    }

    private static AiOrderSuggestion.Item item(String requestedName, String productName, String quantity) {
        return new AiOrderSuggestion.Item(requestedName, productName, quantity, "", "ADD");
    }

    private static class StubAiService extends AiOrderTakingService {
        private AiOrderSuggestion suggestion;

        private StubAiService() {
            super(null);
        }

        @Override
        public AiOrderSuggestion understand(java.io.File file, String text) {
            return suggestion;
        }

        @Override
        public AiOrderSuggestion understand(java.io.File file, String text, String context) {
            return suggestion;
        }
    }

    private static class StubInventoryRepository implements InventoryRepository {
        private List<InventoryMatch> matches = List.of(new InventoryMatch(MILK, 0.95));

        @Override
        public List<InventoryMatch> findSimilar(String productName) {
            for (InventoryMatch match : matches) {
                if (match.item().getItemName().equalsIgnoreCase(productName)) {
                    return List.of(new InventoryMatch(match.item(), 1.0));
                }
            }
            return matches;
        }
    }

    private static class RecordingOrderRepository implements OrderRepository {
        private boolean called;
        private Customer customer;
        private List<OrderItem> items;
        private BigDecimal total;

        @Override
        public long placeOrder(Customer customer, List<OrderItem> items, BigDecimal total) {
            called = true;
            this.customer = customer;
            this.items = List.copyOf(items);
            this.total = total;
            return 42L;
        }
    }
}
