package com.kasa;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Application entry point for the KaSa Telegram bot. */
public final class KasaApplication {

    private KasaApplication() {
    }

    public static void main(String[] args) {
        HttpServer healthServer = null;
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            healthServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            healthServer.createContext("/", exchange -> {
                byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, response.length);
                try (var responseBody = exchange.getResponseBody()) {
                    responseBody.write(response);
                }
            });
            healthServer.start();
            System.out.println("HTTP health server is listening on port " + port + ".");

            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new KaSaBot());
            System.out.println("KaSa bot is running.");
        } catch (Exception e) {
            if (healthServer != null) {
                healthServer.stop(0);
            }
            throw new IllegalStateException("Failed to start KaSa bot", e);
        }
    }
}
