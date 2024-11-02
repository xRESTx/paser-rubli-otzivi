package org.example;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;

public class Format {
    void FormatToTXT(String ItemName, String ItemCost, String ItemfFeedBackCost, String href, BufferedWriter writer) throws IOException {
        Double Cost = Double.parseDouble(ItemCost);
        Double FeedBackCost = Double.parseDouble(ItemfFeedBackCost);
        String[] parts = href.split("/");
        String number = parts[parts.length - 2];
        System.out.println("Number: " + number);
        Double percent = Cost/FeedBackCost;
        writer.write(String.format("%s\t%s\t%s\t%s\t%s\t%s", ItemName, Cost, FeedBackCost, percent, number, href));
    }
}