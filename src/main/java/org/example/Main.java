package org.example;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class Main {
    public static void main(String[] args) throws InterruptedException, IOException {
        ParseWB parseWB = new ParseWB();
                List<String[]> tasks = Arrays.asList(
                        new String[]{"https://www.wildberries.ru/catalog/knigi-i-diski/kantstovary", "Potok2.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/obuv", "Potok2.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/detyam", "Potok2.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha", "Potok2.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/krasota", "Potok2.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/aksessuary", "Potok2.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/elektronika", "Potok1.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/igrushki", "Potok1.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/dom/mebel", "Potok1.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/pitanie", "Potok1.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/bytovaya-tehnika", "Potok1.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/sport", "Potok1.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/yuvelirnye-ukrasheniya", "Potok3.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/dom-i-dacha/instrumenty", "Potok3.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/dachniy-sezon", "Potok3.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/kulturnyy-kod", "Potok3.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/knigi", "Potok3.txt"},
                        new String[]{"https://www.wildberries.ru/catalog/aksessuary/avtotovary", "Potok3.txt"}
                );

                // Запускаем потоки
                for (int i = 0; i < tasks.size(); i += 3) {
                    int index1 = i;
                    int index2 = i + 1;
                    int index3 = i + 2;

                    Thread thread1 = new Thread(() -> {
                        try {
                            if (index1 < tasks.size()) {
                                parseWB.Parse(tasks.get(index1)[0], tasks.get(index1)[1]);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });

                    Thread thread2 = new Thread(() -> {
                        try {
                            if (index2 < tasks.size()) {
                                parseWB.Parse(tasks.get(index2)[0], tasks.get(index2)[1]);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });

                    Thread thread3 = new Thread(() -> {
                        try {
                            if (index3 < tasks.size()) {
                                parseWB.Parse(tasks.get(index3)[0], tasks.get(index3)[1]);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });

                    thread1.start();
                    thread2.start();
                    thread3.start();
                    try {
                        thread1.join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    try {
                        thread2.join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    try {
                        thread3.join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
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

