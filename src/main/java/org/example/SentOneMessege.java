package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.time.Duration;
import java.util.*;
import java.text.DecimalFormat;

public class SentOneMessege {

    public static List<String> pidory = new ArrayList<>(Arrays.asList("elena novvv вечерние и свадебные украшения","FOVERE AROMA","elena novvv колье","Славянский Дворъ"));

    public void readTxtFile(List<String> sentArticles, MyDualBot tgBot, String itemName, String itemCost, String itemfFeedBackCost, String article, BufferedWriter writer, List<String> sentArticlesCommunity, Set<org.openqa.selenium.Cookie> seleniumCookies) throws IOException, InterruptedException {
        if (!sentArticles.contains(article)) {

            String chatIds = "-1002340997107";
            String messege;

            double percent = Double.parseDouble(itemfFeedBackCost) / Integer.parseInt(itemCost);
            if (((percent > 0.49 && Integer.parseInt(itemfFeedBackCost) >= 1000 && Integer.parseInt(itemfFeedBackCost) < 2500)
                    || (percent > 0.59 && Integer.parseInt(itemfFeedBackCost) >= 699 && Integer.parseInt(itemfFeedBackCost) < 1000 && percent < 0.9)
                    || (percent >= 0.4 && Integer.parseInt(itemfFeedBackCost) >= 2500))) {
                boolean bol = hasFeedbackPoints(article,seleniumCookies);
                if (!bol) {
                    return;
                }
                String chatId = "-1002290311759";
//                ByteArrayInputStream bytePhoto = photo(article);
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent);
                tgBot.sendMessage(chatId, 0, messege);
                tgBot.sendMessage(chatIds, 13, messege);
                writer.write(article + "\n");
                writer.flush();
                sentArticles.add(article);
                Thread.sleep(500);
            }
            if (percent >= 1) {
                boolean bol = hasFeedbackPoints(article,seleniumCookies);
                if (!bol) {
                    return;
                }
                String chatId = "-1002402655346";
//                ByteArrayInputStream bytePhoto = photo(article);
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent);
                tgBot.sendMessage(chatId, 0, messege);
                tgBot.sendMessage(chatIds, 2, messege);
                writer.write(article + "\n");
                writer.flush();
                sentArticles.add(article);
                Thread.sleep(500);
            } else if (percent >= 0.9 && percent < 1) {
                boolean bol = hasFeedbackPoints(article,seleniumCookies);
                if (!bol) {
                    return;
                }
                String chatId = "-1002446322077";
//                ByteArrayInputStream bytePhoto = photo(article);
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent);
                tgBot.sendMessage(chatId, 0, messege);

                tgBot.sendMessage(chatIds, 4, messege);
                writer.write(article + "\n");
                writer.flush();
                sentArticles.add(article);
                Thread.sleep(500);
            } else if (percent >= 0.8 && percent < 0.9) {
                boolean bol = hasFeedbackPoints(article,seleniumCookies);
                if (!bol) {
                    return;
                }
                String chatId = "-1002305962649";
//                ByteArrayInputStream bytePhoto = photo(article);
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent);
                tgBot.sendMessage(chatId, 0, messege);
                tgBot.sendMessage(chatIds, 6, messege);
                writer.write(article + "\n");
                writer.flush();
                sentArticles.add(article);
                Thread.sleep(500);
            }
        }
        if (!sentArticlesCommunity.contains(article)) {
            double percent = Double.parseDouble(itemfFeedBackCost) / Integer.parseInt(itemCost);
            String messege;
//            String chatId = System.getenv("chat-id3");
            String chatId = "-1002397733938";
            if (percent >= 1.5 || (Double.parseDouble(itemfFeedBackCost) - Double.parseDouble(itemCost) >= 199 && percent > 1)) {
                boolean bol = hasFeedbackPoints(article, seleniumCookies);
                if (!bol) {
                    return;
                }
//                ByteArrayInputStream bytePhoto = photo(article);
                messege = createMessege(itemName, itemCost, itemfFeedBackCost, article,percent);
                tgBot.sendMessage(chatId, 8, messege);
                writer.write(article + "\n");
                writer.flush();
                sentArticlesCommunity.add(article);
                Thread.sleep(500);
            }
        }
        return;
    }
    public boolean checkFeedbackPoint(String url1) throws InterruptedException {
        boolean hasFeedback = false;
        FirefoxOptions firefoxOptions = new FirefoxOptions();
        firefoxOptions.addArguments("--headless");
        WebDriver webDriver = new FirefoxDriver(firefoxOptions);
        WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofSeconds(10));
        String href = "https://www.wildberries.ru/catalog/"+ url1 + "/detail.aspx";

        try{
            webDriver.get(href);
            for(int i = 0;i<10; i++){
                wait.until(ExpectedConditions.presenceOfElementLocated(By.className("breadcrumbs__list")));
                List<WebElement> checkItem = webDriver.findElements(By.cssSelector(".product-page__badges.product-page__badges--common.hide-mobile.product-page__badges--feedback-for-points"));
                if(!checkItem.isEmpty()){
                    hasFeedback = true;
                    break;
                }
                webDriver.navigate().refresh();
                Thread.sleep(2000);
            }
        }catch (Exception e){
            hasFeedback = false;
        }finally {
            webDriver.quit();
        }
        return hasFeedback;
    }

    public static boolean hasFeedbackPoints(String url1, Set<org.openqa.selenium.Cookie> seleniumCookies) throws IOException, InterruptedException {
        String jsonUrl = "https://card.wb.ru/cards/v2/detail?appType=1&curr=rub&dest=-5923914&spp=30&ab_testing=false&nm="+ url1;
        Connection connection = Jsoup.connect(jsonUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                .method(Connection.Method.GET)
                .ignoreContentType(true);

        for (Cookie cookie : seleniumCookies) {
            connection.cookie(cookie.getName(), cookie.getValue());
        }

        Connection.Response response = connection.execute();

        String json = response.body();
        System.out.println(json);

        JsonReader jsonReader = new JsonReader(new StringReader(json));
        jsonReader.setLenient(true);

        JsonElement rootElement = JsonParser.parseReader(jsonReader);
        JsonObject rootObject = rootElement.getAsJsonObject();

        JsonArray productsArray = rootObject.getAsJsonObject("data").getAsJsonArray("products");
        boolean hasFeedbackPoint = false;
        for (JsonElement productElement : productsArray) {
            JsonObject productObject = productElement.getAsJsonObject();
            if(productObject.has("supplier")){
                String supplier = productObject.get("supplier").getAsString();
                if(pidory.contains(supplier)){
                    return false;
                }
            }
            if (productObject.has("feedbackPoints")) {
                String feedBackSum = productObject.get("feedbackPoints").getAsString();
                if (!feedBackSum.equals("0")) {
                    hasFeedbackPoint = true;
                    break;
                }
            }
        }
        return hasFeedbackPoint;
    }

    String createMessege(String itemName, String itemCost, String itemfFeedBackCost, String article, Double percent){
        String href = "https://www.wildberries.ru/catalog/" + article + "/detail.aspx";
        DecimalFormat df = new DecimalFormat("#.##");
        String formattedString = itemName + "\n\uD83D\uDCB8Price " + itemCost + "\u20BD\n" +
                "\uD83E\uDD11Cashback " + itemfFeedBackCost + "\u20BD\n" +
                "\uD83D\uDCAFPercent " + df.format(percent * 100) + "%\n" + href;
        return formattedString;
    }
}