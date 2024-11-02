package org.example;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;

public class Format {
    void FormatToTXT(String itemName, String itemCost, String itemfFeedBackCost, String article, BufferedWriter writer) throws IOException {

        itemCost = itemCost.replaceAll("[^0-9]", "");  // Удаляем все символы, кроме цифр
        itemfFeedBackCost = itemfFeedBackCost.replaceAll("[^0-9]", "");  // Удаляем все символы, кроме цифр
        int cost = Integer.parseInt(itemCost);
        int feedbackCost = Integer.parseInt(itemfFeedBackCost);
        Double percent = (double) feedbackCost/cost;
        writer.write(String.format("%s\t%s\t%s\t%s\t%s\n", itemName, cost, feedbackCost, percent, article));
    }
}
