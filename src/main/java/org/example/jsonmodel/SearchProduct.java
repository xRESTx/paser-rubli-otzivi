package org.example.jsonmodel;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Модель товара для нового формата JSON из endpoint /__internal/u-search/exactmatch
 */
public class SearchProduct {
    public long id;  // В новом формате id - это число
    public String name;
    @SerializedName("feedbackPoints")
    public int feedbackPoints;  // В новом формате это число
    @SerializedName("totalQuantity")
    public int totalQuantity;   // В новом формате это число
    public List<Size> sizes;
    public String supplier;
    
    // Дополнительные поля из нового формата (не используются в парсинге, но могут быть полезны)
    public String brand;
    public long brandId;
    public int subjectId;
    public int subjectParentId;
    public String entity;
    public long matchId;
    public long supplierId;
    public double supplierRating;
    public int pics;
    public double rating;
    public int feedbacks;
}

