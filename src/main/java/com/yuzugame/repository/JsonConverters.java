package com.yuzugame.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class JsonConverters {

    private static final ObjectMapper OM = new ObjectMapper();

    public static class SetConverter implements AttributeConverter<Set<String>, String> {
        @Override
        public String convertToDatabaseColumn(Set<String> attribute) {
            try { return attribute == null ? "[]" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) { return "[]"; }
        }
        @Override
        public Set<String> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? Set.of() : OM.readValue(dbData, new TypeReference<Set<String>>() {}); }
            catch (JsonProcessingException e) { return Set.of(); }
        }
    }

    public static class ListConverter implements AttributeConverter<List<String>, String> {
        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            try { return attribute == null ? "[]" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) { return "[]"; }
        }
        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? List.of() : OM.readValue(dbData, new TypeReference<List<String>>() {}); }
            catch (JsonProcessingException e) { return List.of(); }
        }
    }

    public static class IntMapConverter implements AttributeConverter<Map<String, Integer>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, Integer> attribute) {
            try { return attribute == null ? "{}" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) { return "{}"; }
        }
        @Override
        public Map<String, Integer> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? Map.of() : OM.readValue(dbData, new TypeReference<Map<String, Integer>>() {}); }
            catch (JsonProcessingException e) { return Map.of(); }
        }
    }

    public static class IntSetConverter implements AttributeConverter<Set<Integer>, String> {
        @Override
        public String convertToDatabaseColumn(Set<Integer> attribute) {
            try { return attribute == null ? "[]" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) { return "[]"; }
        }
        @Override
        public Set<Integer> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? Set.of() : OM.readValue(dbData, new TypeReference<Set<Integer>>() {}); }
            catch (JsonProcessingException e) { return Set.of(); }
        }
    }

    public static class PlayerConverter implements AttributeConverter<com.yuzugame.model.Player, String> {
        @Override
        public String convertToDatabaseColumn(com.yuzugame.model.Player attribute) {
            try { return attribute == null ? "{}" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) { return "{}"; }
        }
        @Override
        public com.yuzugame.model.Player convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? new com.yuzugame.model.Player() : OM.readValue(dbData, com.yuzugame.model.Player.class); }
            catch (JsonProcessingException e) { return new com.yuzugame.model.Player(); }
        }
    }

    public static class ChatHistoryConverter implements AttributeConverter<List<com.yuzugame.model.GameSession.ChatMessage>, String> {
        @Override
        public String convertToDatabaseColumn(List<com.yuzugame.model.GameSession.ChatMessage> attribute) {
            try { return attribute == null ? "[]" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) { return "[]"; }
        }
        @Override
        public List<com.yuzugame.model.GameSession.ChatMessage> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? List.of() : OM.readValue(dbData, new TypeReference<List<com.yuzugame.model.GameSession.ChatMessage>>() {}); }
            catch (JsonProcessingException e) { return List.of(); }
        }
    }

    public static class StringMapConverter implements AttributeConverter<Map<String, String>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, String> attribute) {
            try { return attribute == null ? "{}" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) { return "{}"; }
        }
        @Override
        public Map<String, String> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? Map.of() : OM.readValue(dbData, new TypeReference<Map<String, String>>() {}); }
            catch (JsonProcessingException e) { return Map.of(); }
        }
    }

    public static class PuzzleMemoryConverter implements AttributeConverter<Map<String, List<com.yuzugame.model.PuzzleMemoryEntry>>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, List<com.yuzugame.model.PuzzleMemoryEntry>> attribute) {
            try { return attribute == null ? "{}" : OM.writeValueAsString(attribute); }
            catch (JsonProcessingException e) { return "{}"; }
        }
        @Override
        public Map<String, List<com.yuzugame.model.PuzzleMemoryEntry>> convertToEntityAttribute(String dbData) {
            try { return dbData == null || dbData.isBlank() ? Map.of() : OM.readValue(dbData, new TypeReference<Map<String, List<com.yuzugame.model.PuzzleMemoryEntry>>>() {}); }
            catch (JsonProcessingException e) { return Map.of(); }
        }
    }
}
