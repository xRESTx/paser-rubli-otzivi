package org.example;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.firefox.FirefoxDriver;

import java.util.List;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        ParseWB parseWB = new ParseWB();
//        parseWB.Parse("https://www.wildberries.ru/catalog/knigi");
        parseWB.Parse("https://www.wildberries.ru/catalog/knigi-i-diski/kantstovary");
//        WebDriver webDriver = new FirefoxDriver();
//        webDriver.get("https://www.wildberries.ru/catalog/knigi-i-kantstovary/kantstovary/bumazhnaya-produktsiya");
//        Thread.sleep(3000);
//        List<WebElement> asd = webDriver.findElements(By.cssSelector("li.menu-category__subcategory-item"));
//        System.out.println(asd.size());
    }
}