package org.example.messaging;

import java.text.DecimalFormat;

/**
 * Responsible for rendering Telegram-ready text messages.
 */
public final class MessageFormatter {

    private static final ThreadLocal<DecimalFormat> PERCENT_FORMAT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#.##"));

    public String format(String article,
                         String name,
                         double price,
                         double cashback,
                         double percent,
                         String totalQuantity) {
        String sanitizedName = name == null ? "" : name.replace(":", " ");
        String percentString = PERCENT_FORMAT.get().format(percent * 100);
        String href = "https://www.wildberries.ru/catalog/" + article + "/detail.aspx";
        return new StringBuilder()
                .append(sanitizedName).append("\n")
                .append("\uD83D\uDCB8Стоимость ").append((int) price).append("\u20BD\n")
                .append("\uD83C\uDFB0Кешбэк ").append((int) cashback).append("\u20BD\n")
                .append(" \uD83D\uDCAFПроцент выгоды ").append(percentString).append("%\n")
                .append("\uD83C\uDFB2Количество ").append(totalQuantity).append("\n")
                .append(href)
                .toString();
    }
}


