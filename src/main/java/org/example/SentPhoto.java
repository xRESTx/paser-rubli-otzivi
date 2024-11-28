package org.example;

import org.jsoup.Connection;
import org.jsoup.Jsoup;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;

public class SentPhoto {

    static public ByteArrayInputStream photo(String article) {
        String imageUrl = findValidImageUrl(article);
        try (ByteArrayInputStream originalStream = downloadImage(imageUrl)) {
            ByteArrayInputStream resizedStream = resizeImage(originalStream, 300, 300);
            if (resizedStream == null) {
                return null;
            }
            return resizedStream;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    // Скачивание изображения по URL
    private static ByteArrayInputStream downloadImage(String url) throws IOException {
        Connection.Response response = Jsoup.connect(url).ignoreContentType(true).execute();
        return new ByteArrayInputStream(response.bodyAsBytes());
    }
    private static String findValidImageUrl(String baseId) {
        for (int i = 10; i <= 20; i++) {
            String url = generateImageUrl(baseId, i);
            if (isImageAvailable(url)) {
                return url;
            }
        }
        return null;
    }
    private static String generateImageUrl(String baseId, int number) {
        String vol = baseId.substring(0, 4);
        String part = baseId.substring(0, 6);
        return "https://basket-" + number + ".wbbasket.ru/vol" + vol + "/part" + part + "/" + baseId + "/images/big/1.webp";
    }

    private static boolean isImageAvailable(String url) {
        try {
            Connection.Response response = Jsoup.connect(url).ignoreContentType(true).execute();
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }
    private static ByteArrayInputStream resizeImage(ByteArrayInputStream inputStream, int maxWidth, int maxHeight) {
        try {
            BufferedImage originalImage = ImageIO.read(inputStream);
            if (originalImage == null) {
                return null;
            }

            double aspectRatio = (double) originalImage.getWidth() / originalImage.getHeight();

            int newWidth = maxWidth;
            int newHeight = (int) (newWidth / aspectRatio);

            if (newHeight > maxHeight) {
                newHeight = maxHeight;
                newWidth = (int) (newHeight * aspectRatio);
            }

            Image scaledImage = originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
            BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);

            Graphics2D g2d = resizedImage.createGraphics();
            g2d.drawImage(scaledImage, 0, 0, null);
            g2d.dispose();

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(resizedImage, "png", outputStream);
            return new ByteArrayInputStream(outputStream.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
