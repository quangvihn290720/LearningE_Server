package com.learninge.server.common.mongo;

import com.mongodb.client.model.IndexModel;
import java.util.List;

/**
 * Một collection: validator đọc từ {@code mongo/<service>/<name>.schema.json} trên classpath và các
 * index.
 */
public record CollectionSpec(String name, List<IndexModel> indexes) {
  public static CollectionSpec of(String name, IndexModel... indexes) {
    return new CollectionSpec(name, List.of(indexes));
  }
}
