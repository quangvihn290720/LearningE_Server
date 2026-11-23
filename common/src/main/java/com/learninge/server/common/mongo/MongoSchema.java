package com.learninge.server.common.mongo;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;

/**
 * Tạo collection + validator + index, chạy lại bao nhiêu lần cũng được. Mọi định nghĩa nằm một chỗ,
 * không dùng annotation tự tạo index.
 */
public final class MongoSchema {
  private static final int VERSION = 1;

  private final List<CollectionSpec> collections;

  public MongoSchema(List<CollectionSpec> collections) {
    this.collections = List.copyOf(collections);
  }

  public void apply(MongoDatabase database) {
    List<String> existing = database.listCollectionNames().into(new ArrayList<>());
    for (CollectionSpec spec : collections) {
      Document validator = new Document("$jsonSchema", load(spec.name()));
      if (existing.contains(spec.name())) {
        database.runCommand(
            new Document("collMod", spec.name())
                .append("validator", validator)
                .append("validationLevel", "strict")
                .append("validationAction", "error"));
      } else {
        database.createCollection(
            spec.name(),
            new CreateCollectionOptions()
                .validationOptions(
                    new ValidationOptions()
                        .validator(validator)
                        .validationLevel(ValidationLevel.STRICT)
                        .validationAction(ValidationAction.ERROR)));
      }
      if (!spec.indexes().isEmpty()) {
        database.getCollection(spec.name()).createIndexes(spec.indexes());
      }
    }
    Document meta = new Document("_id", "schema").append("version", VERSION);
    database
        .getCollection("meta")
        .replaceOne(new Document("_id", "schema"), meta, new ReplaceOptions().upsert(true));
  }

  private static Document load(String collection) {
    String path = "/mongo/" + collection + ".schema.json";
    try (InputStream in = MongoSchema.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Thiếu " + path);
      }
      return Document.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
