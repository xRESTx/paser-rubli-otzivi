package org.example.parser;

import com.google.gson.Gson;
import org.example.jsonmodel.Product;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProductParserIntegrationTest {

    @Test
    void parsesRealCatalogPayload() throws IOException {
        String json = readResource("/wb_catalog_example.json");
        ProductParser parser = new ProductParser(new Gson());

        ProductParser.CatalogPage page = parser.parseCatalog(json);
        assertEquals(2, page.getTotalProducts(), "Должны получить total из payload");
        List<Product> products = page.getProducts();
        assertEquals(2, products.size(), "Должны распарсить оба товара");

        Product first = products.get(0);
        assertEquals("10000001", first.id);
        assertEquals("Тестовый товар 1", first.name);
        assertEquals("1500", first.feedbackPoints);
        assertFalse(first.sizes.isEmpty());
        assertEquals(99900, first.sizes.get(0).price.product);

        Product second = products.get(1);
        assertEquals("10000002", second.id);
        assertEquals("Тестовый товар 2", second.name);
        assertEquals(55900, second.sizes.get(0).price.product);
        assertEquals("800", second.feedbackPoints);
    }

    private String readResource(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, "Не найден тестовый файл " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}


