package org.example.parser;

import com.google.gson.Gson;
import org.example.jsonmodel.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProductParserTest {

    private final ProductParser parser = new ProductParser(new Gson());

    @Test
    void parsesWrappedDataObject() {
        String json = """
                {
                  "data": {
                    "products": [
                      {
                        "id": "11",
                        "name": "Item",
                        "feedbackPoints": "500",
                        "sizes": [
                          { "price": { "product": 100000 } }
                        ]
                      }
                    ],
                    "total": 150
                  }
                }
                """;

        ProductParser.CatalogPage page = parser.parseCatalog(json);
        assertEquals(150, page.getTotalProducts());
        List<Product> products = page.getProducts();
        assertEquals(1, products.size());
        assertEquals("11", products.get(0).id);
    }

    @Test
    void parsesFlatProductsArray() {
        String json = """
                {
                  "products": [
                    {
                      "id": "42",
                      "name": "Flat",
                      "feedbackPoints": "1000",
                      "sizes": [
                        { "price": { "product": 50000 } }
                      ]
                    }
                  ]
                }
                """;

        ProductParser.CatalogPage page = parser.parseCatalog(json);
        assertEquals(1, page.getProducts().size());
        assertFalse(page.getProducts().isEmpty());
        assertEquals(1, page.getTotalProducts());
    }
}


