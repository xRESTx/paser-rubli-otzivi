//package org.example;
//
//import org.jsoup.Connection;
//import org.jsoup.Jsoup;
//
//import java.io.*;
//
//public class SentPhoto {
//
//    static public ByteArrayInputStream photo(String article) {
//        String imageUrl = findValidImageUrl(article);
//        try (ByteArrayInputStream originalStream = downloadImage(imageUrl)) {
//            return originalStream;
//        } catch (IOException e) {
//            e.printStackTrace();
//            return null;
//        }
//    }
//    private static ByteArrayInputStream downloadImage(String url) throws IOException {
//        Connection.Response response = Jsoup.connect(url).ignoreContentType(true).execute();
//        return new ByteArrayInputStream(response.bodyAsBytes());
//    }
//    private static String findValidImageUrl(String baseId) {
//        for (int i = 1; i <= 20; i++) {
//            String url = generateImageUrl(baseId, i);
//            if (isImageAvailable(url)) {
//                return url;
//            }
//        }
//        return null;
//    }
//    private static String generateImageUrl(String baseId, int number) {
//        String vol = baseId.substring(0, baseId.length()-5);
//        String part = baseId.substring(0, baseId.length()-3);
//        if(number<10){
//            return "https://basket-0" + number + ".wbbasket.ru/vol" + vol + "/part" + part + "/" + baseId + "/images/c246x328/1.webp";
//        }
//        return "https://basket-" + number + ".wbbasket.ru/vol" + vol + "/part" + part + "/" + baseId + "/images/c246x328/1.webp";
//    }
//
//    private static boolean isImageAvailable(String url) {
//        try {
//            Connection.Response response = Jsoup.connect(url).ignoreContentType(true).execute();
//            return response.statusCode() == 200;
//        } catch (IOException e) {
//            return false;
//        }
//    }
//}
