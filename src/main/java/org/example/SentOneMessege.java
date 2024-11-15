package org.example;

import com.pengrad.telegrambot.TelegramBot;

import java.io.*;
import java.util.*;

public class SentOneMessege {
    public void readTxtFile(List<String> sentArticles, TgBot tgBot, String itemName, String itemCost, String itemfFeedBackCost, String article, BufferedWriter writer, List<String> sentArticlesCommunity, int[] salfetka6) throws IOException, InterruptedException {
        if (!sentArticles.contains(article)) {
            String chatId = System.getenv("chat-id");
            String messege;
            double percent = Double.parseDouble(itemfFeedBackCost)/Integer.parseInt(itemCost);
            if (percent > 0.49 && Integer.parseInt(itemfFeedBackCost) >= 1000 && Integer.parseInt(itemfFeedBackCost) < 2500) {
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                tgBot.sendMessage(chatId, 13, messege);
                writer.write(article + "\n");
                writer.flush();
                Thread.sleep(500);
            }else if (percent > 0.59 && Integer.parseInt(itemfFeedBackCost) >= 699 && Integer.parseInt(itemfFeedBackCost) < 1000 && percent < 0.9) {
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                tgBot.sendMessage(chatId, 13, messege);
                writer.write(article + "\n");
                writer.flush();
                Thread.sleep(500);
            }else if (percent >= 0.4 && Integer.parseInt(itemfFeedBackCost) >= 2500) {
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                tgBot.sendMessage(chatId, 13, messege);
                writer.write(article + "\n");
                writer.flush();
                Thread.sleep(500);
            }
            if (percent >= 1) {
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                tgBot.sendMessage(chatId, 2, messege);
                writer.write(article + "\n");
                writer.flush();
                Thread.sleep(500);
            }else if (percent >= 0.9 && percent < 1) {
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                tgBot.sendMessage(chatId, 4, messege);
                writer.write(article + "\n");
                writer.flush();
                Thread.sleep(500);
            }else if (percent >= 0.8 && percent < 0.9) {
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                tgBot.sendMessage(chatId, 6, messege);
                writer.write(article + "\n");
                writer.flush();
                Thread.sleep(500);
            }
        }
        if(sentArticlesCommunity.contains(article)){
            String chatCommunity = System.getenv("chat-id2");
            String messege;
            double percent = (double) Integer.parseInt(itemfFeedBackCost) /Integer.parseInt(itemCost);
            if (percent>1 && percent<1.70 && salfetka6[0]<1) {
                salfetka6[0]++;
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                sendDelayedMessage(messege,tgBot,chatCommunity);
                Thread.sleep(500);
            }else if(percent>0.9 && percent<1 && salfetka6[1]<3&& Integer.parseInt(itemfFeedBackCost)>300){
                salfetka6[1]++;
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                sendDelayedMessage(messege,tgBot,chatCommunity);
                Thread.sleep(500);
            }
            else if(percent>0.8 && percent<0.9 && salfetka6[2]<5 && Integer.parseInt(itemfFeedBackCost)>400){
                salfetka6[2]++;
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                sendDelayedMessage(messege,tgBot,chatCommunity);
                Thread.sleep(500);
            }else if(percent>0.7 && percent<0.8 && salfetka6[3]<10 && Integer.parseInt(itemfFeedBackCost)>300){
                salfetka6[3]++;
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                sendDelayedMessage(messege,tgBot,chatCommunity);
                Thread.sleep(500);
            }else if(percent>0.6 && percent<0.7 && salfetka6[4]<30 && Integer.parseInt(itemfFeedBackCost)>100){
                salfetka6[4]++;
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                sendDelayedMessage(messege,tgBot,chatCommunity);
                Thread.sleep(500);
            }else if(percent>0.5 && percent<0.6 && salfetka6[5]<10 && Integer.parseInt(itemfFeedBackCost)>600){
                salfetka6[5]++;
                messege = createMessege( itemName,  itemCost,  itemfFeedBackCost,  article);
                sendDelayedMessage(messege,tgBot,chatCommunity);
                Thread.sleep(500);
            }
        }
        return;
    }
    String createMessege(String itemName, String itemCost, String itemfFeedBackCost, String article){
        String href = "https://www.wildberries.ru/catalog/" + article + "/detail.aspx";
        return itemName + "\nPrice " + itemCost + "\nCashback " + itemfFeedBackCost + "\n" + href;
    }
    public static void sendDelayedMessage(String message, TgBot tgBot,String chat_id) {
        Timer timer = new Timer();
        long delay = 10*60*1000;
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                tgBot.sendMessage(chat_id,0 , message);
                System.out.println("Отложенное сообщение отправлено: " + message);
            }
        }, delay);
    }
}



