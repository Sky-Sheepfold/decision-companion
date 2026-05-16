package com.sky.decisioncompanion.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chroma")
@Tag(name = "Chroma向量库管理")
public class ChromaController {

    private static final String DEFAULT_TENANT = "default_tenant";
    private static final String DEFAULT_DATABASE = "default_database";

    private final ChromaApi chromaApi;

    public ChromaController(ChromaApi chromaApi) {
        this.chromaApi = chromaApi;
    }

    @GetMapping("/collections")
    @Operation(summary = "获取所有collections")
    public ResponseEntity<Map<String, Object>> listCollections() {
        var collections = chromaApi.listCollections(DEFAULT_TENANT, DEFAULT_DATABASE);
        var items = collections.stream()
                .map(collection -> Map.<String, Object>of(
                        "id", collection.id() != null ? collection.id() : "",
                        "name", collection.name() != null ? collection.name() : ""))
                .toList();

        return ResponseEntity.ok(Map.of(
                "collections", items,
                "count", items.size()));
    }

    @PostMapping("/collections")
    @Operation(summary = "创建collection")
    public ResponseEntity<Map<String, Object>> createCollection(@RequestBody CreateCollectionRequest request) {
        try {
            var createRequest = new ChromaApi.CreateCollectionRequest(request.name(), null);
            var collection = chromaApi.createCollection(DEFAULT_TENANT, DEFAULT_DATABASE, createRequest);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Collection创建成功",
                    "collection", Map.of(
                            "id", collection.id() != null ? collection.id() : "",
                            "name", collection.name() != null ? collection.name() : "")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Collection创建失败: " + e.getMessage()));
        }
    }

    @DeleteMapping("/collections/{name}")
    @Operation(summary = "删除collection")
    public ResponseEntity<Map<String, Object>> deleteCollection(@PathVariable String name) {
        try {
            chromaApi.deleteCollection(DEFAULT_TENANT, DEFAULT_DATABASE, name);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Collection删除成功: " + name));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Collection删除失败: " + e.getMessage()));
        }
    }

    @GetMapping("/collections/{name}")
    @Operation(summary = "获取指定collection信息")
    public ResponseEntity<Map<String, Object>> getCollection(@PathVariable String name) {
        try {
            var collection = chromaApi.getCollection(DEFAULT_TENANT, DEFAULT_DATABASE, name);
            return ResponseEntity.ok(Map.of(
                    "id", collection.id() != null ? collection.id() : "",
                    "name", collection.name() != null ? collection.name() : ""));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    public record CreateCollectionRequest(String name) {
    }
}
