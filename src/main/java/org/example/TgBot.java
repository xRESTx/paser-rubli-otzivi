package org.example;

import com.pengrad.telegrambot.TelegramBot;

import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;
import org.telegram.telegrambots.meta.api.objects.Update;

public class TgBot {
    public TelegramBot bot;

    public void sendMessage(String chatId, Integer messageThreadId, String messageText) {
            boolean sent = false;
            while (!sent) {
                SendMessage request = new SendMessage(chatId, messageText);
                if (messageThreadId != 0) {
                    request = request.replyToMessageId(messageThreadId);
                }
                SendResponse response = bot.execute(request);

                if (response.isOk()) {
                    System.out.println("Message sent successfully");
                    sent = true;
                } else {
                    System.out.println("Failed to send message: " + response.errorCode() + " - " + response.description());
                    int retryAfter = getRetryAfter(response);
                    if (retryAfter > 0) {
                        System.out.println("Too Many Requests: retry after " + retryAfter + " seconds");
                        try {
                            Thread.sleep(retryAfter * 1000L);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            break;
                        }
                    } else {
                        break;
                    }
                }
            }
        }
    private int getRetryAfter(SendResponse response) {
        String description = response.description();
        if (description != null && description.contains("retry after")) {
            String[] parts = description.split(" ");
            try {
                return Integer.parseInt(parts[parts.length - 1]);
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }
}