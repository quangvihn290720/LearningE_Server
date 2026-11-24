package com.learninge.server.common.mongo;

import com.mongodb.client.model.IndexModel;

import java.util.List;

/**
 * Một collection: validator đọc từ {@code mongo/<service>/<name>.schema.json} trên classpath và các
 * index.
 *
 * @param obsoleteIndexes tên index của bản trước, có thì xóa
 */
public record CollectionSpec(String name, List<IndexModel> indexes, List<String> obsoleteIndexes) {
    public static CollectionSpec of(String name, IndexModel... indexes) {
        return new CollectionSpec(name, List.of(indexes), List.of());
    }

    public CollectionSpec without(String... indexNames) {
        return new CollectionSpec(name, indexes, List.of(indexNames));
    }
}
