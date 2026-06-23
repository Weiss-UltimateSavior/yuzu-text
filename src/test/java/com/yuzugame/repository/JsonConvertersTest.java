package com.yuzugame.repository;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonConverters 单元测试 —— 验证 JSON 列与 Java 对象之间的转换。
 */
class JsonConvertersTest {

    @Test
    void setConverter_roundTrip() {
        JsonConverters.SetConverter converter = new JsonConverters.SetConverter();
        Set<String> original = new LinkedHashSet<>(Arrays.asList("a", "b", "c"));
        String json = converter.convertToDatabaseColumn(original);
        Set<String> restored = converter.convertToEntityAttribute(json);
        assertEquals(original, restored);
    }

    @Test
    void setConverter_nullInput_returnsEmptyArray() {
        JsonConverters.SetConverter converter = new JsonConverters.SetConverter();
        assertEquals("[]", converter.convertToDatabaseColumn(null));
    }

    @Test
    void setConverter_emptyDbData_returnsEmptySet() {
        JsonConverters.SetConverter converter = new JsonConverters.SetConverter();
        Set<String> result = converter.convertToEntityAttribute("");
        assertTrue(result.isEmpty());
    }

    @Test
    void setConverter_invalidJson_returnsEmptySet() {
        JsonConverters.SetConverter converter = new JsonConverters.SetConverter();
        Set<String> result = converter.convertToEntityAttribute("not valid json");
        assertTrue(result.isEmpty());
    }

    @Test
    void setConverter_returnsMutableSet() {
        JsonConverters.SetConverter converter = new JsonConverters.SetConverter();
        Set<String> result = converter.convertToEntityAttribute("[\"a\"]");
        assertDoesNotThrow(() -> result.add("b"));
    }

    @Test
    void listConverter_roundTrip() {
        JsonConverters.ListConverter converter = new JsonConverters.ListConverter();
        List<String> original = Arrays.asList("x", "y", "z");
        String json = converter.convertToDatabaseColumn(original);
        List<String> restored = converter.convertToEntityAttribute(json);
        assertEquals(original, restored);
    }

    @Test
    void listConverter_returnsMutableList() {
        JsonConverters.ListConverter converter = new JsonConverters.ListConverter();
        List<String> result = converter.convertToEntityAttribute("[\"a\"]");
        assertDoesNotThrow(() -> result.add("b"));
    }

    @Test
    void intMapConverter_roundTrip() {
        JsonConverters.IntMapConverter converter = new JsonConverters.IntMapConverter();
        Map<String, Integer> original = new LinkedHashMap<>();
        original.put("a", 1);
        original.put("b", 2);
        String json = converter.convertToDatabaseColumn(original);
        Map<String, Integer> restored = converter.convertToEntityAttribute(json);
        assertEquals(original, restored);
    }

    @Test
    void intMapConverter_returnsMutableMap() {
        JsonConverters.IntMapConverter converter = new JsonConverters.IntMapConverter();
        Map<String, Integer> result = converter.convertToEntityAttribute("{\"a\":1}");
        assertDoesNotThrow(() -> result.put("b", 2));
    }

    @Test
    void intSetConverter_roundTrip() {
        JsonConverters.IntSetConverter converter = new JsonConverters.IntSetConverter();
        Set<Integer> original = new LinkedHashSet<>(Arrays.asList(1, 2, 3));
        String json = converter.convertToDatabaseColumn(original);
        Set<Integer> restored = converter.convertToEntityAttribute(json);
        assertEquals(original, restored);
    }

    @Test
    void playerConverter_roundTrip() {
        JsonConverters.PlayerConverter converter = new JsonConverters.PlayerConverter();
        com.yuzugame.model.Player original = new com.yuzugame.model.Player();
        original.setSanity(50);
        original.setRevelation(10);
        original.setAffection(5);
        String json = converter.convertToDatabaseColumn(original);
        com.yuzugame.model.Player restored = converter.convertToEntityAttribute(json);
        assertEquals(original.getSanity(), restored.getSanity());
        assertEquals(original.getRevelation(), restored.getRevelation());
        assertEquals(original.getAffection(), restored.getAffection());
    }

    @Test
    void playerConverter_nullInput_returnsNewInstance() {
        JsonConverters.PlayerConverter converter = new JsonConverters.PlayerConverter();
        assertEquals("{}", converter.convertToDatabaseColumn(null));
        com.yuzugame.model.Player result = converter.convertToEntityAttribute(null);
        assertNotNull(result);
    }

    @Test
    void stringMapConverter_roundTrip() {
        JsonConverters.StringMapConverter converter = new JsonConverters.StringMapConverter();
        Map<String, String> original = new LinkedHashMap<>();
        original.put("key1", "value1");
        original.put("key2", "value2");
        String json = converter.convertToDatabaseColumn(original);
        Map<String, String> restored = converter.convertToEntityAttribute(json);
        assertEquals(original, restored);
    }

    @Test
    void stringMapConverter_returnsMutableMap() {
        JsonConverters.StringMapConverter converter = new JsonConverters.StringMapConverter();
        Map<String, String> result = converter.convertToEntityAttribute("{\"a\":\"b\"}");
        assertDoesNotThrow(() -> result.put("c", "d"));
    }
}
