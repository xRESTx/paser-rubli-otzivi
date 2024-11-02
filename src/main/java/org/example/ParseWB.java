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
        try (BufferedWriter writer = new BufferedWriter(new FileWriter("items.txt",true))) {
            Format format = new Format();
            webDriver.get(url);
            Thread.sleep(2000);
            List<WebElement> GoToInMenus = webDriver.findElements(By.cssSelector(".menu-category__link"));
            for(WebElement Menu : GoToInMenus){
                try {
                    //######################################################UNDERCATEGORIES###################################################
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
                    //######################################################UNDERCATEGORIES###################################################



                    //######################################################FILTERS###################################################
                    WebElement filterButton = webDriver.findElement(By.cssSelector(".dropdown-filter__btn.dropdown-filter__btn--all"));
                    filterButton.click();
                    Thread.sleep(500);
                    WebElement filterContainer = webDriver.findElement(By.cssSelector(".filters-desktop__switch.j-filter-container.filters-desktop__switch--ffeedbackpoints.show"));
                    WebElement filterRubliButton = filterContainer.findElement(By.tagName("button")); // Предполагается, что кнопка — это элемент <button>
                    filterRubliButton.click();
                    Thread.sleep(500);
                    WebElement filterSubmitButton = webDriver.findElement(By.cssSelector(".filters-desktop__btn-main.btn-main"));
                    filterSubmitButton.click();
                    Thread.sleep(500);
                    //######################################################FILTERS###################################################



                    //######################################################CHANGE-PAGES###################################################
                    List<WebElement> nextPage;
                    do{
                        List<WebElement> Elemts = webDriver.findElements(By.cssSelector(".product-card.j-card-item"));
                        ((JavascriptExecutor) webDriver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
                        Thread.sleep(500);
                        ((JavascriptExecutor) webDriver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
                        Thread.sleep(500);
                        String itemName;
                        String itemCost;
                        String feedbackCost;
                        String itemArticle;

                        //######################################################DOWNLOAD-INFO###################################################
                        List<WebElement> items = webDriver.findElements(By.cssSelector(".product-card.j-card-item"));
                        for(WebElement item : items){
                            itemArticle = item.getAttribute("data-nm-id");
                            WebElement name = item.findElement(By.cssSelector(".product-card__name"));
                            itemName = name.getText();
                            WebElement price = item.findElement(By.cssSelector(".price__lower-price"));
                            itemCost = price.getText();
                            WebElement feedbackPrice = item.findElement(By.cssSelector(".feedbacks-points-sum"));
                            feedbackCost = feedbackPrice.getText();
                            format.FormatToTXT(itemName, itemCost, feedbackCost, itemArticle, writer);
                        }
                        //######################################################DOWNLOAD-INFO###################################################





                        nextPage = webDriver.findElements(By.cssSelector(".pagination-next.pagination__next.j-next-page"));
                        if(nextPage.isEmpty()){
                            break;
                        }
                        String nextHref = nextPage.get(0).getAttribute("href");
                        webDriver.navigate().to(nextHref);
                        Thread.sleep(2000);
                    }while (!nextPage.isEmpty());
                    //######################################################CHANGE-PAGES###################################################



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