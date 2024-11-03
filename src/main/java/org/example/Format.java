package org.example;

import java.io.BufferedWriter;
import java.io.IOException;

public class Format {
    void FormatToTXT(String itemName, String itemCost, String itemfFeedBackCost, String article, BufferedWriter writer) throws IOException {
        itemCost = itemCost.replaceAll("[^0-9]", "");  // Удаляем все символы, кроме цифр
        itemfFeedBackCost = itemfFeedBackCost.replaceAll("[^0-9]", "");  // Удаляем все символы, кроме цифр

        int cost = 0;
        int feedbackCost = 0;

        if (itemCost == null || itemCost.isEmpty()) {
            System.out.println("Строка пуста или не инициализирована");
            return;
        } else {
            try {
                cost = Integer.parseInt(itemCost);
            } catch (NumberFormatException e) {
                System.out.println("Ошибка: некорректный формат строки");
                return;
            }
        }

        if (itemfFeedBackCost == null || itemfFeedBackCost.isEmpty()) {
            System.out.println("Строка пуста или не инициализирована");
            return;
        } else {
            try {
                feedbackCost = Integer.parseInt(itemfFeedBackCost);
            } catch (NumberFormatException e) {
                System.out.println("Ошибка: некорректный формат строки");
                return;
            }
        }

        double percent = (double) feedbackCost / cost;
//        if (percent < 0.6) return;

        String purchaseLink = "https://www.wildberries.ru/catalog/" + article + "/detail.aspx";
        writer.write(String.format("%s\t%d\t%d\t%.2f\t%s\t%s\n", itemName, cost, feedbackCost, percent, article, purchaseLink));
    }
}
