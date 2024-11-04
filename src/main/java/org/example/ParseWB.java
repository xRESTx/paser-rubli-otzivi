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
    int Bypass(WebDriver webDriver, BufferedWriter writer,String hrefMenu, boolean subbol1, boolean subbol2) throws InterruptedException, IOException {
        webDriver.manage().deleteAllCookies();
        Format format = new Format();
        int count = 0;

        ((JavascriptExecutor) webDriver).executeScript("window.open('" + hrefMenu + "', '_blank');");
        String originalTab = webDriver.getWindowHandle();
        Set<String> allTabs = webDriver.getWindowHandles();
        boolean subsubbol = subbol1;
        int skip = 0;
        for (String tab : allTabs) {
            if(skip==2) {
                subbol1 = false;
                subbol2 = false;
            }
            if(subbol1 && subbol2){
                skip++;
                continue;
            }
            if(subbol1 && !subbol2) {
                subbol1 = false;
                continue;
            }
            if (!tab.equals(originalTab)) {
                webDriver.switchTo().window(tab);
                break;
            }
        }
        Thread.sleep(1500);
        List<WebElement> subcategiry = webDriver.findElements(By.cssSelector("a.menu-category__subcategory-link"));
        if(!subcategiry.isEmpty()){
            for(WebElement subMenu : subcategiry){
                if(subsubbol){
                    String hrefSubMenu = subMenu.getAttribute("href");
                    int ch = Bypass(webDriver,  writer, hrefSubMenu,true,true);
                    if(ch == 404){
                        System.out.println("All huinya, My Lord");
                        continue;
                    }
                }else {
                    String hrefSubMenu = subMenu.getAttribute("href");
                    int ch = Bypass(webDriver,  writer, hrefSubMenu,true,false);
                    if(ch == 404){
                        System.out.println("All huinya, My Lord");
                        continue;
                    }
                }
            }
            webDriver.close();
            webDriver.switchTo().window(originalTab);
            return 404;
        }
        //######################################################UNDERCATEGORIES###################################################

        Thread.sleep(500);
        //######################################################FILTERS###################################################
        List<WebElement> filterButton = webDriver.findElements(By.cssSelector(".dropdown-filter__btn.dropdown-filter__btn--all"));
        while (filterButton.isEmpty()) {
            webDriver.navigate().refresh();
            Thread.sleep(1500);
            System.out.println("Strange huinya, My Lord");
            filterButton = webDriver.findElements(By.cssSelector(".dropdown-filter__btn.dropdown-filter__btn--all"));
        }
        filterButton.get(0).click();
        Thread.sleep(300);
        List<WebElement> filterContainer = webDriver.findElements(By.cssSelector(".filters-desktop__switch.j-filter-container.filters-desktop__switch--ffeedbackpoints.show"));
        if(filterContainer.isEmpty()) {
            webDriver.close();
            webDriver.switchTo().window(originalTab);
            return 404;
        }
        WebElement filterRubliButton = filterContainer.get(0).findElement(By.tagName("button")); // Предполагается, что кнопка — это элемент <button>
        ((JavascriptExecutor) webDriver).executeScript("arguments[0].scrollIntoView({block: 'center', behavior: 'smooth'});", filterRubliButton);
        Thread.sleep(300);
        filterRubliButton.click();
        Thread.sleep(300);
        List<WebElement> filterSubmitButton = webDriver.findElements(By.cssSelector(".filters-desktop__btn-main.btn-main"));
        while(filterSubmitButton.isEmpty()){
             filterContainer = webDriver.findElements(By.cssSelector(".filters-desktop__switch.j-filter-container.filters-desktop__switch--ffeedbackpoints.show"));
            if(filterContainer.isEmpty()) {
                webDriver.close();
                webDriver.switchTo().window(originalTab);
                return 404;
            }
            filterRubliButton = filterContainer.get(0).findElement(By.tagName("button")); // Предполагается, что кнопка — это элемент <button>
            ((JavascriptExecutor) webDriver).executeScript("arguments[0].scrollIntoView({block: 'center', behavior: 'smooth'});", filterRubliButton);
            Thread.sleep(300);
            filterRubliButton.click();
            Thread.sleep(300);
        }
        filterSubmitButton.get(0).click();
        Thread.sleep(200);
        List<WebElement> feedback = webDriver.findElements(By.className("feedbacks-points-sum"));
        while (feedback.isEmpty()) {
            webDriver.navigate().refresh();
            Thread.sleep(1500);
            System.out.println("Strange huinya, My Lord");
            feedback = webDriver.findElements(By.cssSelector(".feedbacks-points-sum"));
        }
        //######################################################FILTERS###################################################



        //######################################################CHANGE-PAGES###################################################
        List<WebElement> nextPage;
        do{
            for(int i=0;i<5;i++){
                List<WebElement> Elemts = webDriver.findElements(By.cssSelector(".product-card.j-card-item"));
                int schetchik = 0;
                while (Elemts.isEmpty()){
                    if(schetchik>10) {
                        System.out.println("We are zaebalis' obnovlyat'sya, My Lord");
                        webDriver.close();
                        webDriver.switchTo().window(originalTab);
                        return 0;
                    }
                    schetchik++;
                    System.out.println("zaebalo obnovlyat'sya, My Lord");
                    webDriver.navigate().refresh();
                    Thread.sleep(1500);
                    Elemts = webDriver.findElements(By.cssSelector(".product-card.j-card-item"));
                }
                WebElement lastElement = Elemts.get(Elemts.size() - 1);  // Получаем последний элемент из списка
                // Скроллим до последнего элемента
                ((JavascriptExecutor) webDriver).executeScript("arguments[0].scrollIntoView({block: 'center', behavior: 'smooth'});", lastElement);
                Thread.sleep(300);
            }

            String itemName;
            String itemCost;
            String feedbackCost;
            String itemArticle;

            //######################################################DOWNLOAD-INFO###################################################
            List<WebElement> items = webDriver.findElements(By.cssSelector(".product-card.j-card-item"));
            for(WebElement item : items){
                List<WebElement> feedbackPrice = item.findElements(By.className("feedbacks-points-sum"));
                if(feedbackPrice.isEmpty()) continue;
                feedbackCost = feedbackPrice.get(0).getText();
                itemArticle = item.getAttribute("data-nm-id");
                WebElement name = item.findElement(By.cssSelector(".product-card__name"));
                itemName = name.getText();
                WebElement price = item.findElement(By.cssSelector(".price__lower-price"));
                itemCost = price.getText();
//              System.out.println(String.format("%s\t%s\t%s\t%s\n", itemName, itemCost, feedbackCost, itemArticle));
                format.FormatToTXT(itemName, itemCost, feedbackCost, itemArticle, writer);
                count++;
            }
            //######################################################DOWNLOAD-INFO###################################################

            nextPage = webDriver.findElements(By.cssSelector(".pagination-next.pagination__next.j-next-page"));
            if(nextPage.isEmpty()){
                break;
            }
            String nextHref = nextPage.get(0).getAttribute("href");
            webDriver.navigate().to(nextHref);
            Thread.sleep(1500);
        }while (!nextPage.isEmpty());
        //######################################################CHANGE-PAGES###################################################
        System.out.println(count);
        webDriver.close();
        webDriver.switchTo().window(originalTab);
        return 0;
    }
    void Parse(String url,String fileName){
        WebDriver webDriver = new FirefoxDriver();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName,true))) {

            webDriver.get(url);
            Thread.sleep(1500);
            List<WebElement> GoToInMenus = webDriver.findElements(By.cssSelector(".menu-category__link"));
            for(WebElement Menu : GoToInMenus){

                try {
                    //######################################################UNDERCATEGORIES###################################################
                    String hrefMenu;
                    hrefMenu = Menu.getAttribute("href");
                    int ch = Bypass(webDriver,  writer, hrefMenu,false,false);
                    if(ch == 404){
                        continue;
                    }
                } catch (NoSuchElementException e) {
                System.err.println("Skip, My Lord");
            }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            System.out.println("All completed, My Lord");
            webDriver.quit();
        }
    }
}
