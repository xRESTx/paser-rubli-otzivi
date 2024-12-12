//package org.example;
//
//import java.io.BufferedWriter;
//import java.io.FileWriter;
//import java.io.IOException;
//
//public class Format {
//    void FormatToTXT(String itemName, String itemCost, String itemfFeedBackCost, String article, BufferedWriter writer) throws IOException {
//        int cost = 0;
//        int feedbackCost = 0;
//
//        if (itemCost == null || itemCost.isEmpty()) {
//            System.out.println("Str is empty, My Lord");
//            BufferedWriter errorFile = new BufferedWriter(new FileWriter("results/error.txt",true));
//            errorFile.write(String.format("%s\n", article));
//            return;
//        } else {
//            try {
//                cost = Integer.parseInt(itemCost);
//            } catch (NumberFormatException e) {
//                System.out.println("Mistake: Uncorrect format str, My Lord");
//                return;
//            }
//        }
//
//        if (itemfFeedBackCost.isEmpty()) {
//            System.out.println("Str is empty, My Lord");
//            BufferedWriter errorFile = new BufferedWriter(new FileWriter("results/error.txt",true));
//            errorFile.write(String.format("%s\n", article));
//            return;
//        } else {
//            try {
//                feedbackCost = Integer.parseInt(itemfFeedBackCost);
//            } catch (NumberFormatException e) {
//                System.out.println("Mistake: Uncorrect format str, My Lord");
//                return;
//            }
//        }
//
//        double percent = (double) feedbackCost / cost;
//
//        String purchaseLink = "https://www.wildberries.ru/catalog/" + article + "/detail.aspx";
//        writer.write(String.format("%s\t%d\t%d\t%.2f\t%s\t%s\n", itemName, cost, feedbackCost, percent, article, purchaseLink));
//    }
//}
