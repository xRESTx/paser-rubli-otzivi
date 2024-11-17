package org.example;

import com.pengrad.telegrambot.TelegramBot;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    private static final String FILE_PATH = "error/sent_articles.txt";
    private static final String FILE_PATH_COMMUNITY = "error/sent_articles_community.txt";
    private static final String ALL_ITEM = "error/all_items.txt";
    private List<String> sentArticles = new ArrayList<>();
    private List<String> sentArticlesCommunity = new ArrayList<>();
    private final int[] salfetka6 = new int[6];
    public static void main(String[] args) throws InterruptedException, IOException {
        Main main = new Main();
        TgBot tgBot = new TgBot();
        FirefoxOptions options = new FirefoxOptions();
        options.addArguments("--headless");

        WebDriver driver = new FirefoxDriver(options);

        String urlWb = "https://www.wildberries.ru/";

        driver.get(urlWb);
        Thread.sleep(1000);

        Set<Cookie> seleniumCookies = driver.manage().getCookies();
        driver.quit();

        List<String[]> urls = TestMessege.getURL(seleniumCookies);

        main.sentArticles = readSentArticles(FILE_PATH);
        main.sentArticlesCommunity = readSentArticles(FILE_PATH_COMMUNITY);
        Arrays.fill(main.salfetka6,0);
        String botToken  = System.getenv("botToken");
        System.out.println(botToken);
        tgBot.bot = new TelegramBot(botToken);

        ExecutorService executorService = Executors.newFixedThreadPool(1000);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(ALL_ITEM));
             BufferedWriter writerCommunity = new BufferedWriter(new FileWriter(FILE_PATH, true))){
            AtomicInteger size = new AtomicInteger();
            for(String[] url : urls){
                executorService.submit(() -> {

                    String jsonPage = "https://catalog.wb.ru/catalog/" + url[1] + "/v6/filters?ab_testing=false&appType=1&" + url[2] + "&curr=rub&dest=-5551776&ffeedbackpoints=1&spp=30";
                    try {
                        writer.write(url[0]+"\n");
                        int localizes = TestMessege.test2(url[0], seleniumCookies, url[1], url[2], jsonPage,writer,main.sentArticles,main.sentArticlesCommunity,main.salfetka6, tgBot, writerCommunity);
                        size.set(localizes);
                    } catch (InterruptedException | IOException e) {
                        throw new RuntimeException(e);
                    }

                });
            }
            System.out.println(size.get());
            executorService.shutdown();
            while (!executorService.isTerminated()) {
            }
        }catch (IOException e) {
            throw new RuntimeException(e);
        }


        try (BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH_COMMUNITY))) {
            for (String item : main.sentArticlesCommunity) {
                writer.write(item + "\n");
                writer.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }







//        List<String[]> tasks = Arrays.asList(
//                new String[]{"https://www.wildberries.ru/catalog/zhenshchinam", "results/zhenshchinam.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/obuv", "results/obuv.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/detyam", "results/detyam.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/muzhchinam", "results/muzhchinam.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha", "results/dom-i-dacha.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/krasota", "results/krasota.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/aksessuary", "results/aksessuary.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/aksessuary/avtotovary", "results/avtotovary.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/elektronika", "results/elektronika.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/igrushki", "results/igrushki.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/dom/mebel", "results/mebel.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/aksessuary/tovary-dlya-vzroslyh", "results/tovary-dlya-vzroslyh.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/pitanie", "results/pitanie.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/bytovaya-tehnika", "results/bytovaya-tehnika.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/tovary-dlya-zhivotnyh", "results/tovary-dlya-zhivotnyh.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/sport", "results/sport.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/knigi", "results/knigi.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/yuvelirnye-ukrasheniya", "results/yuvelirnye-ukrasheniya.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha/instrumenty", "results/instrumenty.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/dachniy-sezon", "results/dachniy-sezon.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha/zdorove", "results/zdorove.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/knigi-i-diski/kantstovary", "results/kantstovary.txt"}
//        );
//        ExecutorService executorService = Executors.newFixedThreadPool(3);
//        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH,true))){
//            for (String[] task : tasks) {
//                executorService.submit(() -> {
//                    try {
//                        parseWB.Parse(task[0],writer, main.sentArticles,main.sentArticlesCommunity, tgBot,task[1],main.salfetka6);
//                    } catch (Exception e) {
//                        System.out.println(main.sentArticles.size());
//                        e.printStackTrace();
//                    }
//                });
//            }
//            executorService.shutdown();
//            while (!executorService.isTerminated()) {
//            }
//        }catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//
//        try (BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH_COMMUNITY))) {
//            for (String item : main.sentArticlesCommunity) {
//                writer.write(item + "\n");
//                writer.flush();
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        }

//        parseWB.Parse("https://www.wildberries.ru/catalog/elektronika", "results/elektronika.txt");
//        //######################################################ONE-POISK-ZAPUSK###################################################
//        WebDriver webDriver = new FirefoxDriver();
//        BufferedWriter writer = new BufferedWriter(new FileWriter("test.txt",true));
//        String hrefPoisk = "https://www.wildberries.ru/catalog/elektronika/smartfony-i-telefony";
////        ParseWB parseWB = new ParseWB();
//        List<Boolean> list = new ArrayList<>();
//        parseWB.Bypass(webDriver, writer,hrefPoisk, list);
//        //######################################################ONE-POISK-ZAPUSK###################################################

        System.out.println("All tasks completed, My Lord");
    }
    private static List<String> readSentArticles(String FILE_PATH){
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