package com.kasa;

import com.kasa.controller.ChatController;
import com.kasa.service.ai.AiOrderTakingService;
import com.kasa.service.ai.OrderExtractionService;
import com.kasa.service.ai.PromptService;
import com.kasa.service.CustomerService;
import com.kasa.repository.PostgresCustomerRepository;
import com.kasa.repository.PostgresInventoryRepository;
import com.kasa.repository.PostgresOrderRepository;
import com.kasa.repository.PostgresDraftOrderRepository;
import com.kasa.service.OrderService;
import com.kasa.service.ProductService;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;

/** Telegram API adapter. Update handling lives in {@link ChatController}. */
public class KaSaBot extends TelegramLongPollingBot {

    private final String botToken = requireEnvironmentVariable("TELEGRAM_BOT_TOKEN");
    private final String botUsername = System.getenv().getOrDefault("TELEGRAM_BOT_USERNAME", "BOT_USERNAME");
    private final ChatController chatController = new ChatController(
            new CustomerService(new PostgresCustomerRepository()),
            new OrderService(
                    new AiOrderTakingService(new OrderExtractionService(
                            new com.google.genai.Client(), new PromptService())),
                    new ProductService(new PostgresInventoryRepository()),
                    new PostgresOrderRepository()),
            new PostgresDraftOrderRepository());

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        chatController.handle(update, this);
    }

    private static String requireEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is not set: " + name);
        }
        return value;
    }
}
