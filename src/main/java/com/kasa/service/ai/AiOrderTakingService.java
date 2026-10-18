package com.kasa.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kasa.dto.response.AiOrderSuggestion;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Application service for AI-assisted order extraction. */
public class AiOrderTakingService {

    private final OrderExtractionService orderExtractionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiOrderTakingService(OrderExtractionService orderExtractionService) {
        this.orderExtractionService = orderExtractionService;
    }

    public AiOrderSuggestion understand(File mediaFile, String textInput) throws Exception {
        return understand(mediaFile, textInput, null);
    }

    public AiOrderSuggestion understand(File mediaFile, String textInput, String pendingOrderContext) throws Exception {
        JsonNode root = objectMapper.readTree(orderExtractionService.extract(mediaFile, textInput, pendingOrderContext));
        String intent = normalizeIntent(root.path("intent").asText("NEEDS_CLARIFICATION"));
        String botReply = root.path("botReply").asText("").trim();
        JsonNode labelNode = root.path("labels");
        AiOrderSuggestion.Labels labels = AiOrderSuggestion.Labels.from(new AiOrderSuggestion.Labels(
                labelNode.path("itemName").asText(""), labelNode.path("productName").asText(""),
                labelNode.path("price").asText(""), labelNode.path("quantityRequested").asText(""),
                labelNode.path("availableStock").asText(""), labelNode.path("total").asText("")));
        JsonNode messageNode = root.path("messages");
        AiOrderSuggestion.Messages messages = AiOrderSuggestion.Messages.from(new AiOrderSuggestion.Messages(
                messageNode.path("greeting").asText(""), messageNode.path("availabilityTitle").asText(""),
                messageNode.path("orderTitle").asText(""), messageNode.path("confirmationPrompt").asText(""),
                messageNode.path("notRequest").asText(""), messageNode.path("unclear").asText(""),
                messageNode.path("noProducts").asText(""), messageNode.path("stockIssue").asText(""),
                messageNode.path("clarify").asText(""), messageNode.path("inStock").asText(""),
                messageNode.path("onlyAvailable").asText(""), messageNode.path("unavailable").asText(""),
                messageNode.path("quantityClarification").asText(""), messageNode.path("correctionPrompt").asText("")));
        List<AiOrderSuggestion.Item> items = new ArrayList<>();
        JsonNode itemArray = root.path("items");
        if (itemArray.isArray()) {
            for (JsonNode item : itemArray) {
                String action = item.path("action").asText("ADD").trim().toUpperCase(java.util.Locale.ROOT);
                String quantity = item.path("quantity").asText("").trim();
                if (quantity.isEmpty() && "ADD".equals(action)) quantity = "1";
                if (quantity.isEmpty() && "REMOVE".equals(action)) quantity = "0";
                items.add(new AiOrderSuggestion.Item(
                        item.path("itemName").asText(""),
                        item.path("productName").asText(""),
                        quantity,
                        item.path("notes").asText(""),
                        action));
            }
        }
        return new AiOrderSuggestion(intent, botReply, labels, messages, items);
    }

    private String normalizeIntent(String intent) {
        return switch (intent.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "CHECK_AVAILABILITY", "NEW_ORDER", "MODIFY_ORDER", "CONFIRM_ORDER", "IRRELEVANT", "NEEDS_CLARIFICATION" ->
                    intent.trim().toUpperCase(java.util.Locale.ROOT);
            case "PLACE_ORDER" -> "NEW_ORDER";
            case "NOT_A_REQUEST" -> "IRRELEVANT";
            default -> "NEEDS_CLARIFICATION";
        };
    }
}
