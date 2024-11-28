package org.example;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.*;
import com.pengrad.telegrambot.request.*;
import com.pengrad.telegrambot.response.*;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TgBot {
    public TelegramBot bot;

    public void sendMessage(String chatId, Integer messageThreadId, String messageText, ByteArrayInputStream imageStream) throws IOException {
        boolean sent = false;
        while (!sent) {
            InputStream inputStream = imageStream;
            SendPhoto sendPhotoRequest = new SendPhoto(chatId, inputStream.readAllBytes())
                    .caption(messageText)
                    .messageThreadId(messageThreadId);
            SendResponse response = bot.execute(sendPhotoRequest);

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