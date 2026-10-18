package com.kasa;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/** Application entry point for the KaSa Telegram bot. */
public final class KasaApplication {

    private KasaApplication() {
    }

    public static void main(String[] args) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new KaSaBot());
            System.out.println("KaSa bot is running.");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start KaSa bot", e);
        }
    }
}
