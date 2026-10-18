package com.kasa.service.ai;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Calls Gemini to convert an order message and optional media into structured JSON. */
public class OrderExtractionService {

    private static final String MODEL = "gemini-3.8-flash"; //gemini-3.5-flash-lite
    private final Client geminiClient;
    private final PromptService promptService;

    public OrderExtractionService() {
        this(new Client(), new PromptService());
    }

    public OrderExtractionService(PromptService promptService) {
        this(new Client(), promptService);
    }

    public OrderExtractionService(Client geminiClient, PromptService promptService) {
        this.geminiClient = geminiClient;
        this.promptService = promptService;
    }

    public String extract(File mediaFile, String textInput) throws Exception {
        return extract(mediaFile, textInput, null);
    }

    public String extract(File mediaFile, String textInput, String pendingOrderContext) throws Exception {
        if (mediaFile == null && (textInput == null || textInput.isBlank())) {
            throw new IllegalArgumentException("An order message or media file is required.");
        }

        com.google.genai.types.File uploadedFile = null;
        try {
            List<Part> parts = new ArrayList<>();
            String prompt = promptService.orderExtractionPrompt();
            if (pendingOrderContext != null && !pendingOrderContext.isBlank()) {
                prompt += "\nThe customer has a pending order. Treat their message as a correction to that order. "
                        + "Current pending order (English product name: quantity): " + pendingOrderContext;
            }
            parts.add(Part.builder().text(prompt).build());

            if (mediaFile != null) {
                uploadedFile = geminiClient.files.upload(
                        mediaFile,
                        com.google.genai.types.UploadFileConfig.builder().build());
                parts.add(Part.builder()
                        .fileData(com.google.genai.types.FileData.builder()
                                .fileUri(uploadedFile.uri().orElseThrow(
                                        () -> new IllegalStateException("Gemini upload returned no file URI.")))
                                .mimeType(uploadedFile.mimeType().orElseThrow(
                                        () -> new IllegalStateException("Gemini upload returned no MIME type.")))
                                .build())
                        .build());
            }

            if (textInput != null && !textInput.isBlank()) {
                parts.add(Part.builder().text(textInput).build());
            }

            Content content = Content.builder().role("user").parts(parts).build();
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseMimeType("application/json")
                    .responseSchema(promptService.orderSchema())
                    .temperature(0.1f)
                    .build();

            GenerateContentResponse response = geminiClient.models.generateContent(MODEL, content, config);
            String responseText = response.text();
            if (responseText == null || responseText.isBlank()) {
                throw new IllegalStateException("Gemini returned an empty order response.");
            }
            return responseText;
        } finally {
            if (uploadedFile != null) {
                try {
                    uploadedFile.name().ifPresent(name -> geminiClient.files.delete(
                            name, com.google.genai.types.DeleteFileConfig.builder().build()));
                } catch (RuntimeException cleanupError) {
                    System.err.println("Failed to remove uploaded Gemini file: " + cleanupError.getMessage());
                }
            }
        }
    }

}
