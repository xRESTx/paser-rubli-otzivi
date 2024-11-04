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
                new String[]{"https://www.wildberries.ru/catalog/knigi-i-diski/kantstovary", "Kantstovary.txt"},
                new String[]{"https://www.wildberries.ru/catalog/obuv", "obuv.txt"},
                new String[]{"https://www.wildberries.ru/catalog/detyam", "detyam.txt"},
                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha", "dom-i-dacha.txt"},
                new String[]{"https://www.wildberries.ru/catalog/krasota", "krasota.txt"},
                new String[]{"https://www.wildberries.ru/catalog/aksessuary", "aksessuary.txt"},
                new String[]{"https://www.wildberries.ru/catalog/elektronika", "elektronika.txt"},
                new String[]{"https://www.wildberries.ru/catalog/igrushki", "igrushki.txt"},
                new String[]{"https://www.wildberries.ru/catalog/dom/mebel", "mebel.txt"},
                new String[]{"https://www.wildberries.ru/catalog/pitanie", "pitanie.txt"},
                new String[]{"https://www.wildberries.ru/catalog/bytovaya-tehnika", "bytovaya-tehnika.txt"},
                new String[]{"https://www.wildberries.ru/catalog/sport", "sport.txt"},
                new String[]{"https://www.wildberries.ru/catalog/yuvelirnye-ukrasheniya", "yuvelirnye-ukrasheniya.txt"},
                new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha/instrumenty", "instrumenty.txt"},
                new String[]{"https://www.wildberries.ru/catalog/dachniy-sezon", "dachniy-sezon.txt"},
                new String[]{"https://www.wildberries.ru/catalog/kulturnyy-kod", "kulturnyy-kod.txt"},
                new String[]{"https://www.wildberries.ru/catalog/knigi", "knigi.txt"},
                new String[]{"https://www.wildberries.ru/catalog/aksessuary/avtotovary", "avtotovary.txt"}
        );
        // Создаем пул из 3 потоков
        ExecutorService executorService = Executors.newFixedThreadPool(3);

        // Добавляем задачи в очередь
        for (String[] task : tasks) {
            executorService.submit(() -> {
                try {
                    parseWB.Parse(task[0], task[1]);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        // Завершаем выполнение пула потоков после завершения всех задач
        executorService.shutdown();
        while (!executorService.isTerminated()) {
            // Ждем завершения всех задач
        }
        System.out.println("All tasks completed, My Lord");

//        WebDriver webDriver = new FirefoxDriver();
//        BufferedWriter writer = new BufferedWriter(new FileWriter("items.txt",true));
//        parseWB.Bypass(webDriver,  writer, "https://www.wildberries.ru/catalog/yuvelirnye-ukrasheniya/sergi",false);

//        parseWB.Parse("https://www.wildberries.ru/catalog/knigi");
//        WebDriver webDriver = new FirefoxDriver();
//        webDriver.get("https://www.wildberries.ru/catalog/knigi-i-kantstovary/kantstovary/bumazhnaya-produktsiya");
//        Thread.sleep(3000);
//        List<WebElement> asd = webDriver.findElements(By.cssSelector("li.menu-category__subcategory-item"));
//        System.out.println(asd.size());
    }
}

