package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.openqa.selenium.Cookie;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

public class TestMessege {

    public static List<String[]> getURL(Set<Cookie> seleniumCookies) throws IOException {
        Connection connection = Jsoup.connect("https://static-basket-01.wbbasket.ru/vol0/data/main-menu-ru-ru-v3.json")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                .method(Connection.Method.GET)
                .ignoreContentType(true);

        for (Cookie cookie : seleniumCookies) {
            connection.cookie(cookie.getName(), cookie.getValue());
        }
        Connection.Response response = connection.execute();

        String json = response.body();
        JsonReader jsonReader = new JsonReader(new StringReader(json));
        jsonReader.setLenient(true);

        JsonElement rootElement = JsonParser.parseReader(jsonReader);
        JsonArray objectArray = rootElement.getAsJsonArray();
        List<String[]> urls = new ArrayList<>();
        for (JsonElement object : objectArray) {
            JsonObject productObject = object.getAsJsonObject();
            urls = processChildElements(productObject, urls);
        }
        return urls;
    }

    public static List<String[]> processChildElements(JsonObject jsonObject, List<String[]> urls) {
        if (jsonObject.has("childs")) {
            JsonArray childsArray = jsonObject.getAsJsonArray("childs");
            for (JsonElement childElement : childsArray) {
                JsonObject childObject = childElement.getAsJsonObject();
                processChildElements(childObject, urls);
            }
        } else {
            String obj = jsonObject.has("url") ? jsonObject.get("url").getAsString() : " ";
            if (!obj.isEmpty() && !obj.startsWith("https://vmeste.wildberries.ru") && !obj.startsWith("https://travel.wildberries.ru") && !obj.startsWith("https://digital.wildberries.ru")) {
                String shard = jsonObject.has("shard") && !jsonObject.get("shard").isJsonNull() ? jsonObject.get("shard").getAsString() : "";
                if (Objects.equals(shard, "")) {
                    return urls;
                }
                String query = jsonObject.has("query") && !jsonObject.get("query").isJsonNull() ? jsonObject.get("query").getAsString() : "";

                obj += "?sort=popular&page=1&ffeedbackpoints=1";
                obj = ensureUrlStartsWithPrefix(obj);
                urls.add(new String[]{obj, shard, query});
            }
        }
        return urls;
    }

    public static String ensureUrlStartsWithPrefix(String url) {
        String prefixDigital = "https://www.wildberries.ru";

        if (url.startsWith(prefixDigital)) {
            return url;
        }
        return prefixDigital + url + "";
    }

    public static void test2(Set<Cookie> seleniumCookies, String UrlPage, List<String> sentArticles, List<String> sentArticlesCommunity, MyDualBot tgBot, BufferedWriter writerArticle) throws InterruptedException, IOException {


        SentOneMessege sentOneMessege = new SentOneMessege();
        Connection connection = Jsoup.connect(UrlPage)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
                .method(Connection.Method.GET)
                .ignoreContentType(true);
        for (Cookie cookie : seleniumCookies) {
            connection.cookie(cookie.getName(), cookie.getValue());
        }
        Connection.Response response = connection.execute();

        String json = response.body();
        JsonReader jsonReader = new JsonReader(new StringReader(json));
        jsonReader.setLenient(true);

        JsonElement rootElement = JsonParser.parseReader(jsonReader);
        JsonObject rootObject = rootElement.getAsJsonObject();

        JsonArray productsArray = rootObject.getAsJsonObject("data").getAsJsonArray("products");
        List<String> newInem = new ArrayList<>();
        for (JsonElement productElement : productsArray) {
            JsonObject productObject = productElement.getAsJsonObject();

            String itemName = productObject.has("name") ? productObject.get("name").getAsString() : " ";
            String feedBackSum = productObject.has("feedbackPoints") ? productObject.get("feedbackPoints").getAsString() : "0";
            String articule = productObject.has("id") ? productObject.get("id").getAsString() : "0";
            JsonArray sizesArray = productObject.getAsJsonArray("sizes");
            for (JsonElement sizeElement : sizesArray) {
                if (!newInem.contains(articule)) {
                    newInem.add(articule);
                    JsonObject sizeObject = sizeElement.getAsJsonObject();
                    int total = sizeObject.getAsJsonObject("price").has("total") ? sizeObject.getAsJsonObject("price").get("total").getAsInt() : 0;
                    total = total/100;
                    sentOneMessege.readTxtFile(sentArticles, tgBot, itemName, String.valueOf(total), feedBackSum, articule,writerArticle, sentArticlesCommunity, seleniumCookies);
                }
            }
        }
        Thread.sleep(1000);
    }
}