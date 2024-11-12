package org.example;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) throws InterruptedException, IOException {

        ParseWB parseWB = new ParseWB();
        List<String[]> tasks = Arrays.asList(
                new String[]{"https://www.wildberries.ru/catalog/zhenshchinam", "results/zhenshchinam.txt"},
                new String[]{"https://www.wildberries.ru/catalog/obuv", "results/obuv.txt"},
                new String[]{"https://www.wildberries.ru/catalog/detyam", "results/detyam.txt"},
                new String[]{"https://www.wildberries.ru/catalog/muzhchinam", "results/muzhchinam.txt"},
                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha", "results/dom-i-dacha.txt"},
                new String[]{"https://www.wildberries.ru/catalog/krasota", "results/krasota.txt"},
                new String[]{"https://www.wildberries.ru/catalog/aksessuary", "results/aksessuary.txt"},
                new String[]{"https://www.wildberries.ru/catalog/aksessuary/avtotovary", "results/avtotovary.txt"},
                new String[]{"https://www.wildberries.ru/catalog/elektronika", "results/elektronika.txt"},
                new String[]{"https://www.wildberries.ru/catalog/igrushki", "results/igrushki.txt"},
                new String[]{"https://www.wildberries.ru/catalog/dom/mebel", "results/mebel.txt"},
                new String[]{"https://www.wildberries.ru/catalog/aksessuary/tovary-dlya-vzroslyh", "results/tovary-dlya-vzroslyh.txt"},
                new String[]{"https://www.wildberries.ru/catalog/pitanie", "results/pitanie.txt"},
                new String[]{"https://www.wildberries.ru/catalog/bytovaya-tehnika", "results/bytovaya-tehnika.txt"},
                new String[]{"https://www.wildberries.ru/catalog/tovary-dlya-zhivotnyh", "results/tovary-dlya-zhivotnyh.txt"},
                new String[]{"https://www.wildberries.ru/catalog/sport", "results/sport.txt"},
                new String[]{"https://www.wildberries.ru/catalog/knigi", "results/knigi.txt"},
                new String[]{"https://www.wildberries.ru/catalog/yuvelirnye-ukrasheniya", "results/yuvelirnye-ukrasheniya.txt"},
                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha/instrumenty", "results/instrumenty.txt"},
                new String[]{"https://www.wildberries.ru/catalog/dachniy-sezon", "results/dachniy-sezon.txt"},
                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha/zdorove", "results/zdorove.txt"},
                new String[]{"https://www.wildberries.ru/catalog/knigi-i-diski/kantstovary", "results/kantstovary.txt"}
        );
//        ExecutorService executorService = Executors.newFixedThreadPool(4);
//
//        for (String[] task : tasks) {
//            executorService.submit(() -> {
//                try {
//                    parseWB.Parse(task[0], task[1]);
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            });
//        }
//        executorService.shutdown();
//        while (!executorService.isTerminated()) {
//        }
//        parseWB.Parse("https://www.wildberries.ru/catalog/elektronika", "results/elektronika.txt");
//        Thread.sleep(5000);
//        TxtReader txtReader = new TxtReader();
//        txtReader.readTxtFile();
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
}