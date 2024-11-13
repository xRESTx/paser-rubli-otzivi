package org.example;

import java.io.*;
import java.util.List;

public class SentOneMessege {
    public void readTxtFile(List<String> sentArticles, TgBot tgBot, String itemName, String itemCost, String itemfFeedBackCost, String article) throws IOException, InterruptedException {
        if (!sentArticles.contains(article)) {
            String chatId = System.getenv("chat-id");
            String messege;
            double percent = Double.parseDouble(itemfFeedBackCost)/Integer.parseInt(itemCost);
            if (percent > 0.49 && Integer.parseInt(itemfFeedBackCost) >= 1000 && Integer.parseInt(itemfFeedBackCost) < 2500) {
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                tgBot.sendMessage(chatId, 13, messege);
                Thread.sleep(500);
            }else if (percent > 0.59 && Integer.parseInt(itemfFeedBackCost) >= 699 && Integer.parseInt(itemfFeedBackCost) < 1000 && percent < 0.9) {
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                tgBot.sendMessage(chatId, 13, messege);
                Thread.sleep(500);
            }else if (percent >= 0.4 && Integer.parseInt(itemfFeedBackCost) >= 2500) {
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                tgBot.sendMessage(chatId, 13, messege);
                Thread.sleep(500);
            }
            if (percent >= 1) {
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                tgBot.sendMessage(chatId, 2, messege);
                Thread.sleep(500);
            }else if (percent >= 0.9 && percent < 1) {
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                tgBot.sendMessage(chatId, 4, messege);
                Thread.sleep(500);
            }else if (percent >= 0.8 && percent < 0.9) {
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                tgBot.sendMessage(chatId, 6, messege);
                Thread.sleep(500);
            }
        } else {
            System.out.println("Этот товар уже был отправлен ранее");
        }
        return;
    }
    String createMessege(String itemName, String itemCost, String itemfFeedBackCost, String article){
        String href = "https://www.wildberries.ru/catalog/" + article + "/detail.aspx";
        String messege = itemName + "\nPrice " + itemCost + "\nCashback " + itemfFeedBackCost + "\n" + href;
        return messege;
    }
}