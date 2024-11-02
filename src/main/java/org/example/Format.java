package org.example;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;

public class Format {
    void FormatToTXT(String itemName, String itemCost, String itemfFeedBackCost, String article, BufferedWriter writer) throws IOException {
        String[] parts = itemCost.split(" ");
        itemCost = parts[0];
        String[] part = itemfFeedBackCost.split(" ");
        itemfFeedBackCost = part[0];
        Double cost = Double.parseDouble(itemCost);
        Double feedbackCost = Double.parseDouble(itemfFeedBackCost);
        System.out.println("Number: " + article);
        Double percent = cost/feedbackCost;
        writer.write(String.format("%s\t%s\t%s\t%s\t%s\n", itemName, cost, feedbackCost, percent, article));
    }
}
