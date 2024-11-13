package org.example;

import com.pengrad.telegrambot.TelegramBot;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class TxtReader {
    private static final String FILE_PATH = "error/sent_articles.txt";
    public void readTxtFile() throws IOException {
        try (BufferedWriter write = new BufferedWriter(new FileWriter(FILE_PATH, true))){


            List<String> sentArticles = readSentArticles();

            File directory = new File("results/");
            if (!directory.exists()) {
                System.out.println("Указанная директория не существует: ");
                return;
            }
            List<String> fileName = new ArrayList<>();
            if (directory.isDirectory()) {
                File[] files = directory.listFiles();
                if (files != null && files.length > 0) {
                    System.out.println("Список файлов в директории: ");
                    for (File file : files) {
                        if (file.isFile()) {
                            fileName.add(file.getName());
                        } else if (file.isDirectory()) {
                            System.out.println("Папка: " + file.getName());
                        }
                    }
                } else {
                    System.out.println("Директория пуста: ");
                }
            } else {
                System.out.println("Это не директория: ");
            }
            Properties props = new Properties();
            String chatId = null;
            String botToken = null;
            try (InputStream input = new FileInputStream("application.properties")) {
                props.load(input);

                chatId = props.getProperty("chat-id");
                System.out.println("Chat ID from properties file: " + chatId);
                botToken = props.getProperty("bot-token");
                System.out.println("Bot Token from properties file: " + botToken);
            } catch (IOException e) {
                e.printStackTrace();
            }

            TgBot tgBot = new TgBot();
            tgBot.bot = new TelegramBot(botToken);

            for (String file : fileName) {
                try (BufferedReader br = new BufferedReader(new FileReader("results/" + file))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] values = line.split("\t");
                        if (values.length == 6) {
                            String name = values[0];
                            String price = values[1];
                            String feedback = values[2];
                            String percent = values[3].replace(',', '.');
                            String article = values[4];
                            String href = values[5];
                            if (!sentArticles.contains(article)) {
                                String messege = name + "\nPrice " + price + "\nCashback " + feedback + "\n" + href;
                                if (Double.parseDouble(percent) > 0.49 && Integer.parseInt(feedback) >= 1000 && Integer.parseInt(feedback) < 2500) {
                                    tgBot.sendMessage(chatId, 13, messege);
                                    Thread.sleep(500);
                                    write.write(article + "\n");
                                    write.flush();
                                    sentArticles.add(article);
                                }
                                if (Double.parseDouble(percent) > 0.59 && Integer.parseInt(feedback) >= 699 && Integer.parseInt(feedback) < 1000 && Double.parseDouble(percent) < 0.9) {
                                    tgBot.sendMessage(chatId, 13, messege);
                                    Thread.sleep(500);
                                    write.write(article + "\n");
                                    write.flush();
                                    sentArticles.add(article);
                                }
                                if (Double.parseDouble(percent) >= 0.4 && Integer.parseInt(feedback) >= 2500) {
                                    tgBot.sendMessage(chatId, 13, messege);
                                    Thread.sleep(500);
                                    write.write(article + "\n");
                                    write.flush();
                                    sentArticles.add(article);
                                }
                                if (Double.parseDouble(percent) >= 1) {
                                    tgBot.sendMessage(chatId, 2, messege);
                                    Thread.sleep(500);
                                    write.write(article + "\n");
                                    write.flush();
                                    sentArticles.add(article);
                                }
                                if (Double.parseDouble(percent) >= 0.9 && Double.parseDouble(percent) < 1) {
                                    tgBot.sendMessage(chatId, 4, messege);
                                    Thread.sleep(500);
                                    write.write(article + "\n");
                                    write.flush();
                                    sentArticles.add(article);
                                }
                                if (Double.parseDouble(percent) >= 0.8 && Double.parseDouble(percent) < 0.9) {
                                    tgBot.sendMessage(chatId, 6, messege);
                                    Thread.sleep(500);
                                    write.write(article + "\n");
                                    write.flush();
                                    sentArticles.add(article);
                                }

                            } else {
                                System.out.println("Этот товар уже был отправлен ранее");
                            }
                        } else {
                            System.out.println("Неверное количество параметров в строке: " + line);
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (RuntimeException e) {
            throw new RuntimeException(e);
        }
    }
    private List<String> readSentArticles(){
        List<String> sentArticles = new ArrayList<>();
        try(BufferedReader reader = new BufferedReader(new FileReader(FILE_PATH))) {
            String line;
            while ((line = reader.readLine())!=null){
                sentArticles.add(line);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return sentArticles;
    }
}