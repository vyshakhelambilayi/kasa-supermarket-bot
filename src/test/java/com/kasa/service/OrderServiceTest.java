package com.kasa.service;

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
