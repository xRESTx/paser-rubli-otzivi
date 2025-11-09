package org.example.jsonmodel;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class Product {
    public String id;
    public String name;
    @SerializedName("feedbackPoints")
    public String feedbackPoints;      // количество отзывов
    public String totalQuantity;       // общее количество
    public List<Size> sizes;           // размеры/варианты товара
    public String supplier;            // поставщик (если понадобится)
}
