package org.example;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MyDualBotTest {

    @Test
    void normalizeExtractsSellerIdFromUrl() throws Exception {
        Method method = MyDualBot.class.getDeclaredMethod("normalizeBlockedSupplierInput", String.class);
        method.setAccessible(true);

        String value = (String) method.invoke(null, "https://www.wildberries.ru/seller/1234567/some");
        assertEquals("1234567", value);
    }

    @Test
    void normalizeReturnsLowercaseName() throws Exception {
        Method method = MyDualBot.class.getDeclaredMethod("normalizeBlockedSupplierInput", String.class);
        method.setAccessible(true);

        String value = (String) method.invoke(null, "BrAnD TEST");
        assertEquals("brand test", value);
    }

    @Test
    void normalizeHandlesInvalidInput() throws Exception {
        Method method = MyDualBot.class.getDeclaredMethod("normalizeBlockedSupplierInput", String.class);
        method.setAccessible(true);

        Object value = method.invoke(null, "   ");
        assertNull(value);
    }
}


