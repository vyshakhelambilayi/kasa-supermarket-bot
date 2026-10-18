package com.kasa.controller;

import com.kasa.model.Customer;
import com.kasa.service.CustomerService;
import com.kasa.service.OrderService;
import com.kasa.service.OrderService.PendingOrder;
import com.kasa.repository.DraftOrderRepository;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Voice;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Handles incoming Telegram messages and coordinates order processing. */
public class ChatController {

    private static final Logger LOGGER = Logger.getLogger(ChatController.class.getName());
    private final CustomerService customerService;
    private final OrderService orderService;
    private final DraftOrderRepository draftOrderRepository;
    private final ConcurrentMap<Long, Customer> customersByChatId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, PendingOrder> pendingOrdersByChatId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> registrationPhonesByChatId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Boolean> phoneUpdateRequests = new ConcurrentHashMap<>();

    public ChatController(CustomerService customerService,
                          OrderService orderService,
                          DraftOrderRepository draftOrderRepository) {
        this.customerService = customerService;
        this.orderService = orderService;
        this.draftOrderRepository = draftOrderRepository;
    }

    public void handle(Update update, TelegramLongPollingBot bot) {
        if (update == null || !update.hasMessage()) {
            return;
        }

        var message = update.getMessage();
        long chatId = message.getChatId();

        if (message.hasText() && "/confirm".equalsIgnoreCase(message.getText().trim())) {
            confirmOrder(chatId, bot);
            return;
        }
        if (message.hasText() && "/cancel".equalsIgnoreCase(message.getText().trim())) {
            boolean wasRegistering = registrationPhonesByChatId.remove(chatId) != null;
            phoneUpdateRequests.remove(chatId);
            pendingOrdersByChatId.remove(chatId);
            deleteDraft(chatId);
            sendReply(chatId, wasRegistering
                    ? "Registration cancelled. Send /start when you'd like to try again."
                    : "The pending order was cancelled.", bot);
            return;
        }

        if (message.hasText() && "/start".equals(message.getText())) {
            sendReply(chatId, "Hi! You can check product availability without sharing your phone number. "
                    + "A phone number is required to place an order. Send /phone whenever you want to provide or update it.", bot);
            return;
        }

        if (message.hasText() && ("/phone".equalsIgnoreCase(message.getText().trim())
                || "/update_phone".equalsIgnoreCase(message.getText().trim()))) {
            if (customersByChatId.containsKey(chatId)) phoneUpdateRequests.put(chatId, true);
            askForPhoneNumber(chatId, bot, customersByChatId.containsKey(chatId)
                    ? "Share the new phone number you want linked to your customer account."
                    : "Share your phone number. You can check availability without it, but it is required to place an order.");
            return;
        }

        if (message.hasContact()) {
            var contact = message.getContact();
            if (contact.getUserId() != null
                    && (message.getFrom() == null || !contact.getUserId().equals(message.getFrom().getId()))) {
                sendReply(chatId, "Please share your own phone number using the button below.", bot);
                askForPhoneNumber(chatId, bot);
                return;
            }

            try {
                Customer currentCustomer = customersByChatId.get(chatId);
                if (currentCustomer != null && phoneUpdateRequests.containsKey(chatId)) {
                    Customer updatedCustomer = customerService.updatePhoneNumber(currentCustomer, contact.getPhoneNumber());
                    customersByChatId.put(chatId, updatedCustomer);
                    phoneUpdateRequests.remove(chatId);
                    sendReply(chatId, "Your phone number has been updated.", bot);
                    return;
                }

                var customer = customerService.findByPhoneNumber(contact.getPhoneNumber());
                if (customer.isEmpty()) {
                    customersByChatId.remove(chatId);
                    registrationPhonesByChatId.put(chatId, contact.getPhoneNumber());
                    sendReply(chatId, "I couldn't find an account for that phone number. Please type your full name to create your customer account. Send /cancel to stop.", bot);
                    return;
                }

                Customer matchedCustomer = customer.get();
                registrationPhonesByChatId.remove(chatId);
                customersByChatId.put(chatId, matchedCustomer);
                attachCustomerToDraft(chatId, matchedCustomer);
                PendingOrder draft = loadDraft(chatId);
                String greeting = "Hi " + matchedCustomer.getName()
                        + "! I found your customer account. You can check availability or send an order as text, voice, or photo.";
                sendReply(chatId, draft == null ? greeting
                        : greeting + "\n\nI also restored your saved order draft. You can request changes or send /confirm.", bot);
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Customer lookup failed for chat " + chatId, e);
                sendReply(chatId, e instanceof IllegalArgumentException ? e.getMessage()
                        : "Sorry, I couldn't verify your phone number right now. Please try again later.", bot);
            }
            return;
        }

        String registrationPhone = registrationPhonesByChatId.get(chatId);
        if (registrationPhone != null) {
            if (!message.hasText() || message.getText().isBlank() || message.getText().trim().startsWith("/")) {
                sendReply(chatId, "Please type your full name to finish registration, or send /cancel to stop.", bot);
                return;
            }
            try {
                Customer registeredCustomer = customerService.register(message.getText(), registrationPhone);
                registrationPhonesByChatId.remove(chatId, registrationPhone);
                customersByChatId.put(chatId, registeredCustomer);
                attachCustomerToDraft(chatId, registeredCustomer);
                sendReply(chatId, "Thanks, " + registeredCustomer.getName()
                        + "! Your customer account is ready. You can now confirm or continue your order.", bot);
            } catch (IllegalArgumentException e) {
                sendReply(chatId, e.getMessage() + " Please try again, or send /cancel to stop.", bot);
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Customer registration failed for chat " + chatId, e);
                sendReply(chatId, "Sorry, I couldn't save your customer details right now. Please try again later.", bot);
            }
            return;
        }

        Customer registeredCustomer = customersByChatId.get(chatId);
        PendingOrder pendingOrder = registeredCustomer == null
                ? pendingOrdersByChatId.get(chatId) : loadDraft(chatId);
        if (pendingOrder != null && (registeredCustomer != null || pendingOrder.customer() == null)
                && (message.hasText() || message.hasVoice() || message.hasPhoto())) {
            sendReply(chatId, "Checking your message…", bot);
            CompletableFuture.runAsync(() -> applyCorrection(chatId, pendingOrder, update, bot));
            return;
        }

        if (!message.hasText() && !message.hasVoice() && !message.hasPhoto()) {
            sendReply(chatId, "Please send your order as text, a voice message, or a photo.", bot);
            return;
        }

        sendReply(chatId, "Checking your request...", bot);
        Customer customer = customersByChatId.get(chatId);
        CompletableFuture.runAsync(() -> processOrder(update, chatId, customer, bot));
    }

    private void applyCorrection(long chatId, PendingOrder current, Update update, TelegramLongPollingBot bot) {
        File localFile = null;
        try {
            var message = update.getMessage();
            String text = message.hasText() ? message.getText() : null;
            localFile = downloadIncomingMedia(message, bot);
            OrderService.Result result = orderService.handlePendingMessage(current, localFile, text);
            if (result.confirmRequested()) {
                confirmOrder(chatId, bot);
                return;
            }
            updateDraft(chatId, result.pendingOrder());
            if (current.customer() == null && result.pendingOrder() != null) {
                sendReplyWithPhoneKeyboard(chatId, result.message()
                        + "\n\nA phone number is required to place the order. Share it now, or continue editing the draft.", bot);
            } else {
                sendReply(chatId, result.message(), bot);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to apply order correction for chat " + chatId, e);
            sendReply(chatId, "Sorry, I couldn't apply that change. Please try again or send /cancel.", bot);
        } finally {
            deleteTemporaryFile(localFile);
        }
    }

    private void processOrder(Update update, long chatId, Customer customer, TelegramLongPollingBot bot) {
        File localFile = null;
        try {
            var message = update.getMessage();
            String text = message.hasText() ? message.getText() : null;
            localFile = downloadIncomingMedia(message, bot);

            OrderService.Result result = orderService.process(customer, localFile, text);
            updateDraft(chatId, result.pendingOrder());
            if (customer == null && result.pendingOrder() != null) {
                sendReplyWithPhoneKeyboard(chatId, result.message()
                        + "\n\nTo place this order, share your phone number. If it isn't registered yet, I'll ask for your name and create your account.", bot);
            } else if (customer == null) {
                sendReply(chatId, result.message()
                        + "\n\nYou can check availability without a phone number. To place an order, send /phone to provide one.", bot);
            } else {
                sendReply(chatId, result.message(), bot);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to process order for chat " + chatId, e);
            sendReply(chatId, "Sorry, I couldn't process that order. Please try again.", bot);
        } finally {
            deleteTemporaryFile(localFile);
        }
    }

    private void confirmOrder(long chatId, TelegramLongPollingBot bot) {
        if (!customersByChatId.containsKey(chatId)) {
            askForPhoneNumber(chatId, bot,
                    "A phone number is required to place this order. Share it now; if it isn't registered, I'll ask for your name to create your account.");
            return;
        }
        PendingOrder pendingOrder = loadDraft(chatId);
        if (pendingOrder == null) {
            sendReply(chatId, "There is no order waiting for confirmation. Send the items you want to order first.", bot);
            return;
        }
        if (!pendingOrder.readyToConfirm()) {
            sendReply(chatId, "Some requested quantities are not available. Please reduce the quantity or remove an item, then review the order again.", bot);
            return;
        }
        try {
            long orderId = orderService.confirm(pendingOrder);
            pendingOrdersByChatId.remove(chatId, pendingOrder);
            deleteDraft(chatId);
            sendReply(chatId, "Thank you " + pendingOrder.customer().getName()
                    + "! Your order #" + orderId + " is confirmed.", bot);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to save confirmed order for chat " + chatId, e);
            sendReply(chatId, "I couldn't place the order. Stock may have changed; please check availability and try again.", bot);
        }
    }

    private PendingOrder loadDraft(long chatId) {
        PendingOrder cached = pendingOrdersByChatId.get(chatId);
        if (cached != null) return cached;
        try {
            PendingOrder saved = draftOrderRepository.findByTelegramChatId(chatId).orElse(null);
            if (saved != null) pendingOrdersByChatId.put(chatId, saved);
            return saved;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Could not restore the saved order draft for chat " + chatId, e);
            return null;
        }
    }

    private void updateDraft(long chatId, PendingOrder updated) {
        if (updated == null) {
            PendingOrder removed = pendingOrdersByChatId.remove(chatId);
            if (removed != null && removed.customer() != null) deleteDraft(chatId);
            return;
        }
        pendingOrdersByChatId.put(chatId, updated);
        if (updated.customer() == null) return;
        try {
            draftOrderRepository.save(chatId, updated);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Could not save the order draft for chat " + chatId, e);
        }
    }

    private void attachCustomerToDraft(long chatId, Customer customer) {
        PendingOrder pending = pendingOrdersByChatId.get(chatId);
        if (pending == null || pending.customer() != null) return;
        PendingOrder attached = new PendingOrder(customer, pending.items(), pending.total(),
                pending.readyToConfirm(), pending.unavailableItems());
        updateDraft(chatId, attached);
    }

    private void deleteDraft(long chatId) {
        try {
            draftOrderRepository.delete(chatId);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Could not delete the saved order draft for chat " + chatId, e);
        }
    }

    private File downloadIncomingMedia(org.telegram.telegrambots.meta.api.objects.Message message,
                                       TelegramLongPollingBot bot) throws Exception {
        if (message.hasVoice()) {
            return downloadTelegramMedia(message.getVoice().getFileId(), ".ogg", bot);
        }
        if (message.hasPhoto()) {
            List<PhotoSize> photos = message.getPhoto();
            String fileId = photos.stream()
                    .max(Comparator.comparing(PhotoSize::getFileSize, Comparator.nullsFirst(Integer::compareTo)))
                    .orElseThrow(() -> new IllegalArgumentException("The photo message contains no image."))
                    .getFileId();
            return downloadTelegramMedia(fileId, ".jpg", bot);
        }
        return null;
    }

    private void deleteTemporaryFile(File localFile) {
        if (localFile != null && localFile.exists() && !localFile.delete()) {
            LOGGER.warning("Could not delete temporary media file: " + localFile.getAbsolutePath());
        }
    }

    private File downloadTelegramMedia(String fileId, String extension, TelegramLongPollingBot bot) throws Exception {
        GetFile getFile = new GetFile();
        getFile.setFileId(fileId);
        org.telegram.telegrambots.meta.api.objects.File telegramFile = bot.execute(getFile);
        File destination = File.createTempFile("kasa-media-", extension);
        try {
            return bot.downloadFile(telegramFile, destination);
        } catch (Exception e) {
            if (!destination.delete()) {
                LOGGER.warning("Could not delete partial media file: " + destination.getAbsolutePath());
            }
            throw e;
        }
    }

    private void askForPhoneNumber(long chatId, TelegramLongPollingBot bot) {
        askForPhoneNumber(chatId, bot, "Please share your phone number.");
    }

    private void askForPhoneNumber(long chatId, TelegramLongPollingBot bot, String prompt) {
        KeyboardButton contactButton = new KeyboardButton();
        contactButton.setText("Share phone number");
        contactButton.setRequestContact(true);

        KeyboardRow row = new KeyboardRow();
        row.add(contactButton);
        List<KeyboardRow> rows = new ArrayList<>();
        rows.add(row);

        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(rows);
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(true);

        SendMessage message = new SendMessage();
        message.setChatId(Long.toString(chatId));
        message.setText(prompt);
        message.setReplyMarkup(keyboard);
        execute(message, bot);
    }

    private void sendReplyWithPhoneKeyboard(long chatId, String text, TelegramLongPollingBot bot) {
        KeyboardButton contactButton = new KeyboardButton();
        contactButton.setText("Share phone number");
        contactButton.setRequestContact(true);
        KeyboardRow row = new KeyboardRow();
        row.add(contactButton);
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row));
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(true);
        SendMessage message = new SendMessage();
        message.setChatId(Long.toString(chatId));
        message.setText(text);
        message.setReplyMarkup(keyboard);
        execute(message, bot);
    }

    private void sendReply(long chatId, String text, TelegramLongPollingBot bot) {
        SendMessage message = new SendMessage();
        message.setChatId(Long.toString(chatId));
        message.setText(text);
        execute(message, bot);
    }

    private void execute(SendMessage message, TelegramLongPollingBot bot) {
        try {
            bot.execute(message);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to send Telegram message", e);
        }
    }
}
