package org.example.jsonmodel;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class DetailProduct {
    public Long id;
    public String name;
    @SerializedName("feedbackPoints")
    public String feedbackPoints;
    public List<DetailSize> sizes;
    
    public static class DetailSize {
        public DetailPrice price;
    }
    
    public static class DetailPrice {
        public Long basic;    // базовая цена
        public Long product;  // цена со скидкой
    }
}

