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
    int TimeOut = 1500;
    int Bypass(WebDriver webDriver, BufferedWriter writer,String hrefMenu, List<Boolean> subBool) throws InterruptedException, IOException {
        webDriver.manage().deleteAllCookies();
        Format format = new Format();
        int count = 0;

        ((JavascriptExecutor) webDriver).executeScript("window.open('" + hrefMenu + "', '_blank');");
        String originalTab = webDriver.getWindowHandle();
        Set<String> allTabs = webDriver.getWindowHandles();
        int skip = 0;
        System.out.println("Я ломаюсь тут 1 ");
        for (String tab : allTabs) {
            if(skip==subBool.size()) {
                System.out.println("Я ломаюсь тут 5 ");
                subBool.replaceAll(ignored -> false);
            }
            if(skip<subBool.size()){
                System.out.println("Я ломаюсь тут 6 ");
                skip++;
                continue;
            }
            if (!tab.equals(originalTab)) {
                System.out.println("Я ломаюсь тут 4");
                webDriver.switchTo().window(tab);
                break;
            }
            System.out.println("Я ломаюсь тут 3 ");
        }
        System.out.println("Я ломаюсь тут 2 ");
        Thread.sleep(TimeOut);
        List<WebElement> subcategiry = webDriver.findElements(By.cssSelector("a.menu-category__subcategory-link"));
        if(!subcategiry.isEmpty()){
            subBool.add(true);
            for(WebElement subMenu : subcategiry){
                String hrefSubMenu = subMenu.getAttribute("href");
                int ch = Bypass(webDriver,  writer, hrefSubMenu,subBool);
                if(ch == 404){
                    System.out.println("All huinya, My Lord");
                    continue;
                }
            }
            webDriver.close();
            webDriver.switchTo().window(originalTab);
            return 404;
        }

        //######################################################UNDERCATEGORIES###################################################

        Thread.sleep(3500);
        //######################################################FILTERS###################################################
        List<WebElement> filterButton = webDriver.findElements(By.cssSelector(".dropdown-filter__btn.dropdown-filter__btn--all"));
        while (filterButton.isEmpty()) {
            webDriver.navigate().refresh();
            Thread.sleep(TimeOut);
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
        List<WebElement> filterRubliButton = filterContainer.get(0).findElements(By.tagName("button"));
        while (filterRubliButton.isEmpty()) {
            webDriver.navigate().refresh();
            Thread.sleep(TimeOut);
            filterButton = webDriver.findElements(By.cssSelector(".dropdown-filter__btn.dropdown-filter__btn--all"));
            while (filterButton.isEmpty()) {
                webDriver.navigate().refresh();
                Thread.sleep(TimeOut);
                System.out.println("Strange huinya, My Lord");
                filterButton = webDriver.findElements(By.cssSelector(".dropdown-filter__btn.dropdown-filter__btn--all"));
            }
            filterButton.get(0).click();
            Thread.sleep(300);
            filterContainer = webDriver.findElements(By.cssSelector(".filters-desktop__switch.j-filter-container.filters-desktop__switch--ffeedbackpoints.show"));
            filterRubliButton = filterContainer.get(0).findElements(By.tagName("button"));
        }
        ((JavascriptExecutor) webDriver).executeScript("arguments[0].scrollIntoView({block: 'center', behavior: 'smooth'});", filterRubliButton.get(0));
        Thread.sleep(300);
        filterRubliButton.get(0).click();
        Thread.sleep(300);
        List<WebElement> filterSubmitButton = webDriver.findElements(By.cssSelector(".filters-desktop__btn-main.btn-main"));
        while(filterSubmitButton.isEmpty()) {
            filterContainer = webDriver.findElements(By.cssSelector(".filters-desktop__switch.j-filter-container.filters-desktop__switch--ffeedbackpoints.show"));
            filterRubliButton = filterContainer.get(0).findElements(By.tagName("button"));
            while (filterRubliButton.isEmpty()) {
                webDriver.navigate().refresh();
                Thread.sleep(TimeOut);
                filterButton = webDriver.findElements(By.cssSelector(".dropdown-filter__btn.dropdown-filter__btn--all"));
                while (filterButton.isEmpty()) {
                    webDriver.navigate().refresh();
                    Thread.sleep(TimeOut);
                    System.out.println("Strange huinya, My Lord");
                    filterButton = webDriver.findElements(By.cssSelector(".dropdown-filter__btn.dropdown-filter__btn--all"));
                }
                filterButton.get(0).click();
                Thread.sleep(300);
                filterContainer = webDriver.findElements(By.cssSelector(".filters-desktop__switch.j-filter-container.filters-desktop__switch--ffeedbackpoints.show"));
                filterRubliButton = filterContainer.get(0).findElements(By.tagName("button"));
            }
            ((JavascriptExecutor) webDriver).executeScript("arguments[0].scrollIntoView({block: 'center', behavior: 'smooth'});", filterRubliButton.get(0));
            Thread.sleep(300);
            filterRubliButton.get(0).click();
            Thread.sleep(300);
            filterSubmitButton = webDriver.findElements(By.cssSelector(".filters-desktop__btn-main.btn-main"));
        }
        filterSubmitButton.get(0).click();
        Thread.sleep(200);
        List<WebElement> feedback = webDriver.findElements(By.className("feedbacks-points-sum"));
        while (feedback.isEmpty()) {
            webDriver.navigate().refresh();
            Thread.sleep(TimeOut);
            System.out.println("Strange huinya, My Lord");
            feedback = webDriver.findElements(By.cssSelector(".feedbacks-points-sum"));
        }
        //######################################################FILTERS###################################################



        //######################################################CHANGE-PAGES###################################################
        List<WebElement> nextPage;
        do{
            for(int i=0;i<5;i++){
                int schetchik = 0;
                List<WebElement> Elemts = webDriver.findElements(By.cssSelector(".product-card.j-card-item"));
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
                    Thread.sleep(TimeOut);
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
            Thread.sleep(TimeOut);
        }while (!nextPage.isEmpty());
        //######################################################CHANGE-PAGES###################################################
        System.out.println(count);
        webDriver.close();
        webDriver.switchTo().window(originalTab);
        return 0;
    }
    void Parse(String url,String fileName){
        WebDriver webDriver = new FirefoxDriver();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {

            webDriver.get(url);
            Thread.sleep(TimeOut);
            List<WebElement> GoToInMenus = webDriver.findElements(By.cssSelector(".menu-category__link"));
            for(WebElement Menu : GoToInMenus){
                try {
                    //######################################################UNDERCATEGORIES###################################################
                    String hrefMenu;
                    hrefMenu = Menu.getAttribute("href");
                    List<Boolean> subBool = new ArrayList<>();
                    int ch = Bypass(webDriver,  writer, hrefMenu,subBool);
                    if(ch == 404){
                        continue;
                    }
                } catch (NoSuchElementException e) {
                System.err.println("Skip, My Lord");
            }
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            System.out.println("All completed, My Lord");
            webDriver.quit();
        }
    }
}
