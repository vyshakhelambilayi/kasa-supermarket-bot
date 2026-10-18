package com.kasa.service.ai;

import com.google.genai.types.Schema;
import com.google.genai.types.Type;

import java.util.Map;

/** Builds the extraction instructions and structured response schema. */
public class PromptService {

    public String orderExtractionPrompt() {
        return "Understand the customer's message, voice recording, or image and return only data matching the JSON schema. "
                + "Detect the language used by the customer. Return responseLanguage with that language's English name. "
                + "Translate every label and message in labels and messages into the customer's language. "
                + "Keep these label/message translations short and natural. "
                + "Choose exactly one intent: NEW_ORDER, CHECK_AVAILABILITY, MODIFY_ORDER, CONFIRM_ORDER, IRRELEVANT, NEEDS_CLARIFICATION. "
                + "NEW_ORDER means the customer intends to buy items. CHECK_AVAILABILITY means asking about stock or prices without placing an order. "
                + "MODIFY_ORDER is only for corrections to the pending order included in the context. "
                + "CONFIRM_ORDER means the customer clearly approves the currently pending order; use it for natural replies like yes, correct, or place it. "
                + "If no pending order context is included, never classify as MODIFY_ORDER. "
                + "Use IRRELEVANT for greetings and unrelated topics. Use NEEDS_CLARIFICATION if a store request is ambiguous or incomplete. "
                + "A product may be a valid supermarket item even when it is absent from inventory. Do not call it IRRELEVANT or omit it for that reason; extract it so the application can reply that it is unavailable. "
                + "Set botReply to a short friendly message in the customer's language for IRRELEVANT or NEEDS_CLARIFICATION; otherwise use an empty string. "
                + "For confirmationPrompt, ask whether the displayed list is correct, invite additions/removals/quantity changes, and mention /confirm and /cancel. "
                + "Extract products only when actual product names are present. Never invent product details. "
                + "For each item, set itemName to the item name exactly as given in the source language, "
                + "and set productName to the corresponding standard product name in English. "
                + "Each item action must be exactly ADD, REMOVE, or SET_QUANTITY. For NEW_ORDER use ADD. "
                + "For MODIFY_ORDER, return only changed items; map remove/delete to REMOVE, quantity replacement to SET_QUANTITY, and adding to ADD. "
                + "For REMOVE, quantity must be 0 and notes must include remove. Do not treat a mention of an existing item as a modification unless a change is requested. "
                + "Do not transliterate productName; translate it when needed. Extract explicit order counts from speech or text as ASCII digits, "
                + "including number words in the customer's language. Do not confuse package size (such as 1L or 5kg) with the count ordered. "
                + "For ADD items, default quantity to 1 when the customer did not state a count. For SET_QUANTITY, use an empty quantity if no new count was stated. "
                + "Use an empty items array for IRRELEVANT. Do not calculate price, stock, or totals.";
    }

    public Schema orderSchema() {
        Schema itemSchema = Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(Map.of(
                        "itemName", stringSchema(),
                        "productName", stringSchema(),
                        "quantity", stringSchema(),
                        "notes", stringSchema(),
                        "action", stringSchema()))
                .build();

        Schema labelsSchema = Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(Map.of(
                        "itemName", stringSchema(),
                        "productName", stringSchema(),
                        "price", stringSchema(),
                        "quantityRequested", stringSchema(),
                        "availableStock", stringSchema(),
                        "total", stringSchema()))
                .build();

        Schema messagesSchema = Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(Map.ofEntries(
                        Map.entry("greeting", stringSchema()),
                        Map.entry("availabilityTitle", stringSchema()),
                        Map.entry("orderTitle", stringSchema()),
                        Map.entry("confirmationPrompt", stringSchema()),
                        Map.entry("notRequest", stringSchema()),
                        Map.entry("unclear", stringSchema()),
                        Map.entry("noProducts", stringSchema()),
                        Map.entry("stockIssue", stringSchema()),
                        Map.entry("clarify", stringSchema()),
                        Map.entry("inStock", stringSchema()),
                        Map.entry("onlyAvailable", stringSchema()),
                        Map.entry("unavailable", stringSchema()),
                        Map.entry("quantityClarification", stringSchema()),
                        Map.entry("correctionPrompt", stringSchema())))
                .build();

        return Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(Map.of(
                        "intent", stringSchema(),
                        "botReply", stringSchema(),
                        "responseLanguage", stringSchema(),
                        "labels", labelsSchema,
                        "messages", messagesSchema,
                        "items", Schema.builder()
                                .type(Type.Known.ARRAY)
                                .items(itemSchema)
                                .build()))
                .build();
    }

    private Schema stringSchema() {
        return Schema.builder().type(Type.Known.STRING).build();
    }
}
