package org.example;

import java.util.*;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.By;
import org.openqa.selenium.firefox.FirefoxDriver;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.IOException;

public class ParseWB {
    void Parse(String url){

        WebDriver webDriver = new FirefoxDriver();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter("cruises.txt",true))) {
            webDriver.get(url);
            Thread.sleep(2000);
            List<WebElement> GoToInMenus = webDriver.findElements(By.cssSelector(".menu-category__link"));
            for(WebElement Menu : GoToInMenus){
                try {
                    String hrefMenu = Menu.getAttribute("href");
                    System.out.println(hrefMenu);
                    ((JavascriptExecutor) webDriver).executeScript("window.open('" + hrefMenu + "', '_blank');");
                    String originalTab = webDriver.getWindowHandle();
                    Set<String> allTabs = webDriver.getWindowHandles();
                    for (String tab : allTabs) {
                        if (!tab.equals(originalTab)) {
                            webDriver.switchTo().window(tab);
                            break;
                        }
                    }
                    Thread.sleep(2000);
                    WebElement nextPage;
                    do{
                        List<WebElement> Elemts = webDriver.findElements(By.cssSelector(".product-card.j-card-item"));
                        ((JavascriptExecutor) webDriver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
                        Thread.sleep(500);
                        ((JavascriptExecutor) webDriver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
                        Thread.sleep(500);
                        nextPage = webDriver.findElement(By.cssSelector(".pagination-next.pagination__next.j-next-page"));
                        String nextHref = nextPage.getAttribute("href");
                        webDriver.navigate().to(nextHref);
                        Thread.sleep(2000);
                    }while (nextPage != null);



                    webDriver.close();
                    webDriver.switchTo().window(originalTab);
                } catch (NoSuchElementException e) {
                System.err.println("Пропускаем...");
            }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            webDriver.quit();
        }
    }
}
