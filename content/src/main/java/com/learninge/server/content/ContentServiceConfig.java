package com.learninge.server.content;

import com.learninge.server.common.config.InternalServerConfiguration;
import com.learninge.server.common.mongo.CollectionSpec;
import com.learninge.server.common.mongo.MongoConfiguration;
import com.learninge.server.common.mongo.MongoSchema;
import com.learninge.server.content.grpc.ContentBackendService;
import com.learninge.server.content.service.ContentImporter;
import com.learninge.server.content.service.ImportCommand;
import com.learninge.server.content.store.ContentStore;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Dây nối của dịch vụ content. */
@Configuration(proxyBeanMethods = false)
@Import({InternalServerConfiguration.class, MongoConfiguration.class})
public class ContentServiceConfig {

  @Bean
  MongoSchema mongoSchema() {
    return new MongoSchema(
        List.of(
            CollectionSpec.of("manifest"),
            CollectionSpec.of(
                "decks",
                new IndexModel(
                    Indexes.ascending("sort_order"), new IndexOptions().name("ix_decks_sort"))),
            CollectionSpec.of(
                "words",
                new IndexModel(
                    Indexes.ascending("deck_code", "sort_order"),
                    new IndexOptions().name("ix_words_deck"))),
            CollectionSpec.of(
                "exams",
                new IndexModel(
                    Indexes.ascending("sort_order"), new IndexOptions().name("ix_exams_sort")))));
  }

  @Bean
  ContentStore contentStore(MongoDatabase database) {
    return new ContentStore(database);
  }

  @Bean
  ContentImporter contentImporter(
      ContentStore store, MongoSchema schema, MongoDatabase database, Clock clock) {
    return new ContentImporter(store, schema, database, clock);
  }

  @Bean
  ImportCommand importCommand(ContentImporter importer) {
    return new ImportCommand(importer);
  }

  @Bean
  ContentBackendService contentBackendService(ContentStore store) {
    return new ContentBackendService(store);
  }
}
