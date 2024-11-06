package org.example;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) throws InterruptedException, IOException {
        ParseWB parseWB = new ParseWB();
        List<String[]> tasks = Arrays.asList(
//                new String[]{"https://www.wildberries.ru/catalog/zhenshchinam", "TXTFILE/zhenshchinam.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/obuv", "TXTFILE/obuv.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/detyam", "TXTFILE/detyam.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/muzhchinam", "TXTFILE/muzhchinam.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha", "TXTFILE/dom-i-dacha.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/krasota", "TXTFILE/krasota.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/aksessuary", "TXTFILE/aksessuary.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/aksessuary/avtotovary", "TXTFILE/avtotovary.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/elektronika", "TXTFILE/elektronika.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/igrushki", "TXTFILE/igrushki.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/dom/mebel", "TXTFILE/mebel.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/aksessuary/tovary-dlya-vzroslyh", "tovary-dlya-vzroslyh.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/pitanie", "pitanie.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/bytovaya-tehnika", "bytovaya-tehnika.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/tovary-dlya-zhivotnyh", "tovary-dlya-zhivotnyh.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/sport", "sport.txt"}
//                new String[]{"https://www.wildberries.ru/catalog/knigi", "knigi.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/yuvelirnye-ukrasheniya", "yuvelirnye-ukrasheniya.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha/instrumenty", "instrumenty.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/dachniy-sezon", "dachniy-sezon.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha/zdorove", "zdorove.txt"},
//                new String[]{"https://www.wildberries.ru/catalog/knigi-i-diski/kantstovary", "kantstovary.txt"}
        );

        parseWB.Parse("https://www.wildberries.ru/catalog/dom/mebel", "TXTFILE/mebel.txt");
        // Создаем пул из 3 потоков
//        ExecutorService executorService = Executors.newFixedThreadPool(3);
//
//        // Добавляем задачи в очередь
//        for (String[] task : tasks) {
//            executorService.submit(() -> {
//                try {
//                    parseWB.Parse(task[0], task[1]);
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            });
//        }
//
//        // Завершаем выполнение пула потоков после завершения всех задач
//        executorService.shutdown();
//        while (!executorService.isTerminated()) {
//            // Ждем завершения всех задач
//        }
        System.out.println("All tasks completed, My Lord");
    }
}

