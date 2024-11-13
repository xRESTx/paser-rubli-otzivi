package org.example;

import com.pengrad.telegrambot.TelegramBot;
import org.openqa.selenium.TimeoutException;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {
    private static final String FILE_PATH = "error/sent_articl.txt";
    private List<String> sentArticles = new ArrayList<>();
    public static void main(String[] args) throws InterruptedException, IOException {
        Main main = new Main();
        TgBot tgBot = new TgBot();
        ParseWB parseWB = new ParseWB();

        main.sentArticles = readSentArticles();

        tgBot.bot = new TelegramBot(System.getenv("botToken"));

        List<String[]> tasks = Arrays.asList(
                new String[]{"https://www.wildberries.ru/catalog/zhenshchinam", "results/zhenshchinam.txt"},
                new String[]{"https://www.wildberries.ru/catalog/obuv"},
                new String[]{"https://www.wildberries.ru/catalog/detyam"},
                new String[]{"https://www.wildberries.ru/catalog/muzhchinam"},
                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha"},
                new String[]{"https://www.wildberries.ru/catalog/krasota"},
                new String[]{"https://www.wildberries.ru/catalog/aksessuary"},
                new String[]{"https://www.wildberries.ru/catalog/aksessuary/avtotovary"},
                new String[]{"https://www.wildberries.ru/catalog/elektronika"},
                new String[]{"https://www.wildberries.ru/catalog/igrushki"},
                new String[]{"https://www.wildberries.ru/catalog/dom/mebel"},
                new String[]{"https://www.wildberries.ru/catalog/aksessuary/tovary-dlya-vzroslyh"},
                new String[]{"https://www.wildberries.ru/catalog/pitanie"},
                new String[]{"https://www.wildberries.ru/catalog/bytovaya-tehnika"},
                new String[]{"https://www.wildberries.ru/catalog/tovary-dlya-zhivotnyh"},
                new String[]{"https://www.wildberries.ru/catalog/sport"},
                new String[]{"https://www.wildberries.ru/catalog/knigi"},
                new String[]{"https://www.wildberries.ru/catalog/yuvelirnye-ukrasheniya"},
                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha/instrumenty"},
                new String[]{"https://www.wildberries.ru/catalog/dachniy-sezon"},
                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha/zdorove"},
                new String[]{"https://www.wildberries.ru/catalog/knigi-i-diski/kantstovary"}
        );
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH,true))){
            for (String[] task : tasks) {
                executorService.submit(() -> {
                    try {
                        parseWB.Parse(task[0],writer, main.sentArticles, tgBot);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
            executorService.shutdown();
            while (!executorService.isTerminated()) {
            }
        }catch (IOException e) {
            throw new RuntimeException(e);
        }


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
    private static List<String> readSentArticles(){
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