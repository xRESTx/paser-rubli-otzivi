package org.example.jsonmodel;

import com.google.gson.annotations.SerializedName;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.List;

public class Product {
    // id может быть как числом, так и строкой в JSON, поэтому используем Object и конвертируем в строку
    public Object id;  // В JSON это число, но мы обрабатываем как Object для гибкости
    public String name;
    @SerializedName("feedbackPoints")
    public Object feedbackPoints;      // количество отзывов (в JSON это число, но может быть и строкой)
    public String totalQuantity;       // общее количество
    public List<Size> sizes;           // размеры/варианты товара
    public String supplier;            // поставщик (если понадобится)
    
    // Методы для безопасного получения значений
    public String getIdAsString() {
        try {
            if (id == null) return "0";
            
            // Если id - это число, конвертируем его в строку без научной нотации
            if (id instanceof Number) {
                Number num = (Number) id;
                
                // Проверяем разные типы чисел для более точной конвертации
                if (num instanceof Long || num instanceof Integer || num instanceof Short || num instanceof Byte) {
                    // Для целочисленных типов просто конвертируем в строку
                    return String.valueOf(num.longValue());
                } else if (num instanceof Double || num instanceof Float) {
                    // Для чисел с плавающей точкой используем DecimalFormat для избежания научной нотации
                    DecimalFormat df = new DecimalFormat("#");
                    df.setMaximumFractionDigits(0);
                    df.setGroupingUsed(false);
                    // Форматируем число как целое без научной нотации
                    return df.format(num.doubleValue());
                } else {
                    // Для других типов чисел (например, BigDecimal) используем toPlainString()
                    try {
                        BigDecimal bd;
                        if (num instanceof BigDecimal) {
                            bd = (BigDecimal) num;
                        } else {
                            bd = BigDecimal.valueOf(num.doubleValue());
                        }
                        return String.valueOf(bd.longValue());
                    } catch (Exception e) {
                        // Если конвертация не удалась, используем DecimalFormat
                        DecimalFormat df = new DecimalFormat("#");
                        df.setMaximumFractionDigits(0);
                        df.setGroupingUsed(false);
                        return df.format(num.doubleValue());
                    }
                }
            }
            
            // Если id - это строка, возвращаем её как есть
            return id.toString();
        } catch (Exception e) {
            // В случае любой ошибки возвращаем "0"
            return "0";
        }
    }
    
    public String getFeedbackPointsAsString() {
        try {
            if (feedbackPoints == null) return "0";
            
            // Если feedbackPoints - это число, конвертируем его в строку без научной нотации
            if (feedbackPoints instanceof Number) {
                Number num = (Number) feedbackPoints;
                
                // Проверяем разные типы чисел для более точной конвертации
                if (num instanceof Long || num instanceof Integer || num instanceof Short || num instanceof Byte) {
                    // Для целочисленных типов просто конвертируем в строку
                    return String.valueOf(num.longValue());
                } else if (num instanceof Double || num instanceof Float) {
                    // Для чисел с плавающей точкой используем DecimalFormat для избежания научной нотации
                    DecimalFormat df = new DecimalFormat("#");
                    df.setMaximumFractionDigits(0);
                    df.setGroupingUsed(false);
                    // Форматируем число как целое без научной нотации
                    return df.format(num.doubleValue());
                } else {
                    // Для других типов чисел (например, BigDecimal) используем toPlainString()
                    try {
                        BigDecimal bd;
                        if (num instanceof BigDecimal) {
                            bd = (BigDecimal) num;
                        } else {
                            bd = BigDecimal.valueOf(num.doubleValue());
                        }
                        return String.valueOf(bd.longValue());
                    } catch (Exception e) {
                        // Если конвертация не удалась, используем DecimalFormat
                        DecimalFormat df = new DecimalFormat("#");
                        df.setMaximumFractionDigits(0);
                        df.setGroupingUsed(false);
                        return df.format(num.doubleValue());
                    }
                }
            }
            
            // Если feedbackPoints - это строка, возвращаем её как есть
            return feedbackPoints.toString();
        } catch (Exception e) {
            // В случае любой ошибки возвращаем "0"
            return "0";
        }
    }
}
