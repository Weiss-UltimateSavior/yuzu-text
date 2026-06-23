package com.yuzugame.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JPA AttributeConverter 集合 —— 处理 JSON 列与 Java 对象之间的转换。
 *
 * <p>设计说明：
 * <ul>
 *   <li>反序列化失败时记录 WARN 日志并返回空的可变集合，避免静默丢失数据</li>
 *   <li>返回可变集合（HashMap/HashSet/ArrayList），避免调用方修改时抛出 UnsupportedOperationException</li>
 *   <li>序列化失败时记录 ERROR 日志并返回空 JSON，保证写入不阻塞</li>
 * </ul></p>
 */
public class JsonConverters {

    private static final Logger log = LoggerFactory.getLogger(JsonConverters.class);
    private static final ObjectMapper OM = new ObjectMapper();

    public static class SetConverter implements AttributeConverter<Set<String>, String> {
        @Override
        public String convertToDatabaseColumn(Set<String> attribute) {
            try { return attribute == null ? "[]" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) {
                log.error("Failed to serialize Set<String>: {}", e.getMessage());
                return "[]";
            }
        }
        @Override
        public Set<String> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? new HashSet<>() : OM.readValue(dbData, new TypeReference<HashSet<String>>() {}); }
            catch (JsonProcessingException e) {
                log.warn("Failed to deserialize Set<String>, returning empty set: {}", e.getMessage());
                return new HashSet<>();
            }
        }
    }

    public static class ListConverter implements AttributeConverter<List<String>, String> {
        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            try { return attribute == null ? "[]" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) {
                log.error("Failed to serialize List<String>: {}", e.getMessage());
                return "[]";
            }
        }
        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? new ArrayList<>() : OM.readValue(dbData, new TypeReference<ArrayList<String>>() {}); }
            catch (JsonProcessingException e) {
                log.warn("Failed to deserialize List<String>, returning empty list: {}", e.getMessage());
                return new ArrayList<>();
            }
        }
    }

    public static class IntMapConverter implements AttributeConverter<Map<String, Integer>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, Integer> attribute) {
            try { return attribute == null ? "{}" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) {
                log.error("Failed to serialize Map<String,Integer>: {}", e.getMessage());
                return "{}";
            }
        }
        @Override
        public Map<String, Integer> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? new HashMap<>() : OM.readValue(dbData, new TypeReference<HashMap<String, Integer>>() {}); }
            catch (JsonProcessingException e) {
                log.warn("Failed to deserialize Map<String,Integer>, returning empty map: {}", e.getMessage());
                return new HashMap<>();
            }
        }
    }

    public static class IntSetConverter implements AttributeConverter<Set<Integer>, String> {
        @Override
        public String convertToDatabaseColumn(Set<Integer> attribute) {
            try { return attribute == null ? "[]" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) {
                log.error("Failed to serialize Set<Integer>: {}", e.getMessage());
                return "[]";
            }
        }
        @Override
        public Set<Integer> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? new HashSet<>() : OM.readValue(dbData, new TypeReference<HashSet<Integer>>() {}); }
            catch (JsonProcessingException e) {
                log.warn("Failed to deserialize Set<Integer>, returning empty set: {}", e.getMessage());
                return new HashSet<>();
            }
        }
    }

    public static class PlayerConverter implements AttributeConverter<com.yuzugame.model.Player, String> {
        @Override
        public String convertToDatabaseColumn(com.yuzugame.model.Player attribute) {
            try { return attribute == null ? "{}" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) {
                log.error("Failed to serialize Player: {}", e.getMessage());
                return "{}";
            }
        }
        @Override
        public com.yuzugame.model.Player convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? new com.yuzugame.model.Player() : OM.readValue(dbData, com.yuzugame.model.Player.class); }
            catch (JsonProcessingException e) {
                log.warn("Failed to deserialize Player, returning new instance: {}", e.getMessage());
                return new com.yuzugame.model.Player();
            }
        }
    }

    public static class ChatHistoryConverter implements AttributeConverter<List<com.yuzugame.model.GameSession.ChatMessage>, String> {
        @Override
        public String convertToDatabaseColumn(List<com.yuzugame.model.GameSession.ChatMessage> attribute) {
            try { return attribute == null ? "[]" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) {
                log.error("Failed to serialize ChatHistory: {}", e.getMessage());
                return "[]";
            }
        }
        @Override
        public List<com.yuzugame.model.GameSession.ChatMessage> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? new ArrayList<>() : OM.readValue(dbData, new TypeReference<ArrayList<com.yuzugame.model.GameSession.ChatMessage>>() {}); }
            catch (JsonProcessingException e) {
                log.warn("Failed to deserialize ChatHistory, returning empty list: {}", e.getMessage());
                return new ArrayList<>();
            }
        }
    }

    public static class StringMapConverter implements AttributeConverter<Map<String, String>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, String> attribute) {
            try { return attribute == null ? "{}" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) {
                log.error("Failed to serialize Map<String,String>: {}", e.getMessage());
                return "{}";
            }
        }
        @Override
        public Map<String, String> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? new LinkedHashMap<>() : OM.readValue(dbData, new TypeReference<LinkedHashMap<String, String>>() {}); }
            catch (JsonProcessingException e) {
                log.warn("Failed to deserialize Map<String,String>, returning empty map: {}", e.getMessage());
                return new LinkedHashMap<>();
            }
        }
    }

    public static class PuzzleMemoryConverter implements AttributeConverter<Map<String, List<com.yuzugame.model.PuzzleMemoryEntry>>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, List<com.yuzugame.model.PuzzleMemoryEntry>> attribute) {
            try { return attribute == null ? "{}" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) {
                log.error("Failed to serialize PuzzleMemory: {}", e.getMessage());
                return "{}";
            }
        }
        @Override
        public Map<String, List<com.yuzugame.model.PuzzleMemoryEntry>> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? new HashMap<>() : OM.readValue(dbData, new TypeReference<HashMap<String, List<com.yuzugame.model.PuzzleMemoryEntry>>>() {}); }
            catch (JsonProcessingException e) {
                log.warn("Failed to deserialize PuzzleMemory, returning empty map: {}", e.getMessage());
                return new HashMap<>();
            }
        }
    }
}
