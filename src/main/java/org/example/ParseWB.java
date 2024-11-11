package org.example;

import java.time.Duration;

import org.openqa.selenium.*;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.NoSuchElementException;

import java.util.List;
import java.util.Set;

public class ParseWB {
    int Bypass(WebDriver webDriver, BufferedWriter writer,String hrefMenu, List<Boolean> subBool) throws InterruptedException, IOException {

        webDriver.manage().deleteAllCookies();
        WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofSeconds(5));
        Format format = new Format();
        int count = 0;
        String hrefFeedPoint = hrefMenu + "?sort=popular&page=1&ffeedbackpoints=1";
        ((JavascriptExecutor) webDriver).executeScript("window.open('" + hrefFeedPoint + "', '_blank');");
        String originalTab = webDriver.getWindowHandle();
        Set<String> allTabs = webDriver.getWindowHandles();
        int skip = 0;
        for (String tab : allTabs) {
            if(skip==subBool.size()) {
                subBool.replaceAll(ignored -> false);
            }
            if(skip<subBool.size()){
                skip++;
                continue;
            }
            if (!tab.equals(originalTab)) {
                webDriver.switchTo().window(tab);
                break;
            }
        }
        try {
            List<WebElement> subcategiry = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(By.cssSelector("a.menu-category__subcategory-link")));
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
        }catch (TimeoutException ignore){
            //######################################################UNDERCATEGORIES###################################################
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".dropdown-filter__btn.dropdown-filter__btn--all")));
            List<WebElement> checkList = webDriver.findElements(By.cssSelector(".product-card__wrapper"));
            if(checkList.isEmpty()){
                webDriver.close();
                webDriver.switchTo().window(originalTab);
                return 404;
            }
            //######################################################FILTERS###################################################
            if (hrefMenu.equals("https://digital.wildberries.ru/catalog/audiobooks?sort=rating")
                    || hrefMenu.equals("https://digital.wildberries.ru/catalog/services")) {
                webDriver.close();
                webDriver.switchTo().window(originalTab);
                return 404;
            }


            List<WebElement> feedback = webDriver.findElements(By.className("feedbacks-points-sum"));
            int schetchik = 0;
            while (feedback.isEmpty()){
                System.out.println("zaebalo obnovlyat'sya, My Lord");
                webDriver.navigate().refresh();
                Thread.sleep(500);
                wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".dropdown-filter__btn.dropdown-filter__btn--all")));
                feedback = webDriver.findElements(By.className("feedbacks-points-sum"));
                schetchik++;
                if(schetchik > 10){
                    subBool.replaceAll(ignored -> true);
                    subBool.add(true);
                    Bypass(webDriver,writer,hrefMenu,subBool);
                    webDriver.close();
                    webDriver.switchTo().window(originalTab);
                    return 0;
                }
            }
            //######################################################FILTERS###################################################



            //######################################################CHANGE-PAGES###################################################
            List<WebElement> nextPage;
            do{
                List<WebElement> items = webDriver.findElements(By.cssSelector(".product-card.j-card-item"));
                schetchik = 0;
                while (items.isEmpty()){
                    if(schetchik>10) {
                        System.out.println("We are zaebalis' obnovlyat'sya, My Lord");
                        webDriver.close();
                        webDriver.switchTo().window(originalTab);
                        return 0;
                    }
                    schetchik++;
                    System.out.println("zaebalo obnovlyat'sya, My Lord");
                    webDriver.navigate().refresh();
                    wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".dropdown-filter__btn.dropdown-filter__btn--all")));
                    items = webDriver.findElements(By.className("feedbacks-points-sum"));
                }

                WebElement lastItem = items.get(items.size() - 1);
                while (true) {
                    ((JavascriptExecutor) webDriver).executeScript("arguments[0].scrollIntoView({block: 'center', behavior: 'smooth'});", lastItem);
                    Thread.sleep(200);
                    List<WebElement> newItems = webDriver.findElements(By.cssSelector(".product-card.j-card-item"));
                    if (items.size() == newItems.size()) {
                        if (lastItem.isDisplayed()) {
                            break;
                        }
                    }
                    else {
                        items = newItems;
                        lastItem = items.get(items.size() - 1);
                    }
                }
                String itemName;
                String itemCost;
                String feedbackCost;
                String itemArticle;

                //######################################################DOWNLOAD-INFO###################################################
                items = webDriver.findElements(By.cssSelector(".product-card.j-card-item"));
                for(WebElement item : items){
                    List<WebElement> feedbackPrice = item.findElements(By.className("feedbacks-points-sum"));
                    itemArticle = item.getAttribute("data-nm-id");
                    if(feedbackPrice.isEmpty()){
                        BufferedWriter errorFile = new BufferedWriter(new FileWriter("error/error.txt",true));
                        errorFile.write(String.format("%s\n", itemArticle));
                        continue;
                    }
                    feedbackCost = feedbackPrice.get(0).getText();
                    WebElement name = item.findElement(By.cssSelector(".product-card__name"));
                    itemName = name.getText();
                    WebElement price = item.findElement(By.cssSelector(".price__lower-price"));
                    itemCost = price.getText();
                    format.FormatToTXT(itemName, itemCost, feedbackCost, itemArticle, writer);
                    count++;
                }
                //######################################################DOWNLOAD-INFO###################################################

                nextPage = webDriver.findElements(By.cssSelector(".pagination-next.pagination__next.j-next-page"));
                if(nextPage.isEmpty()){
                    break;
                }
                String nextHref = nextPage.getFirst().getAttribute("href");
                webDriver.navigate().to(nextHref);
                wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".dropdown-filter__btn.dropdown-filter__btn--all")));
            }while (!nextPage.isEmpty());
            //######################################################CHANGE-PAGES###################################################
            System.out.println(count);
            webDriver.close();
            webDriver.switchTo().window(originalTab);
        }
        return 0;
    }
    void Parse(String url,String fileName){

        WebDriver webDriver = new FirefoxDriver();
        WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofSeconds(10));
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {

            webDriver.get(url);
            List<WebElement> GoToInMenus = wait.until(ExpectedConditions.visibilityOfAllElementsLocatedBy(By.cssSelector(".menu-category__link")));
            for(WebElement Menu : GoToInMenus){
                try {
                    //######################################################UNDERCATEGORIES###################################################
                    String hrefMenu = Menu.getAttribute("href");
                    List<Boolean> subBool = new ArrayList<>();
                    int ch = Bypass(webDriver, writer, hrefMenu, subBool);
                    if (ch == 404) {
                        continue;
                    }
                } catch (NoSuchElementException | InvalidSelectorException e) {
                    System.err.println("Skip, My Lord");
                    webDriver.quit();
                    Parse(url,fileName);
                }
            }
        }  catch (IOException | InterruptedException | TimeoutException e) {
            webDriver.quit();
            Parse(url, fileName);
            throw new RuntimeException(e);
        } finally {
            System.out.println("All completed, My Lord");
            webDriver.quit();
        }
    }
}