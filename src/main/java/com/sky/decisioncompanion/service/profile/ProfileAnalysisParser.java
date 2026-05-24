package com.sky.decisioncompanion.service.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class ProfileAnalysisParser {

    private final ObjectMapper objectMapper;

    public ProfileAnalysisParser() {
        this(new ObjectMapper());
    }

    ProfileAnalysisParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Analysis parse(String rawOutput) {
        String json = extractJsonObject(rawOutput);
        if (json.isBlank()) {
            return Analysis.empty(false);
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                return Analysis.empty(false);
            }
            return new Analysis(
                    array(root, "values"),
                    array(root, "emotions"),
                    array(root, "decisions"),
                    array(root, "relationships"),
                    array(root, "fears"),
                    true);
        } catch (Exception e) {
            return Analysis.empty(false);
        }
    }

    private String extractJsonObject(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "";
        }

        int start = rawOutput.indexOf('{');
        if (start < 0) {
            return "";
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < rawOutput.length(); i++) {
            char current = rawOutput.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return rawOutput.substring(start, i + 1);
                }
            }
        }

        return "";
    }

    private List<JsonNode> array(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<JsonNode> items = new ArrayList<>();
        node.forEach(items::add);
        return items;
    }

    public record Analysis(
            List<JsonNode> values,
            List<JsonNode> emotions,
            List<JsonNode> decisions,
            List<JsonNode> relationships,
            List<JsonNode> fears,
            boolean parsed) {

        static Analysis empty(boolean parsed) {
            return new Analysis(List.of(), List.of(), List.of(), List.of(), List.of(), parsed);
        }

        public int itemCount() {
            return values.size() + emotions.size() + decisions.size() + relationships.size() + fears.size();
        }
    }
}
