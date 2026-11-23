package com.learninge.server.common.mongo;

import com.learninge.server.common.grpc.ErrorMappingInterceptor;
import com.learninge.server.common.ops.BackgroundTasks;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Kết nối MongoDB cho content, gamification, insight; dịch vụ khai báo bean {@link MongoSchema}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MongoProperties.class)
public class MongoConfiguration {

  /** Timeout ngắn để Mongo ngừng thì RPC thất bại nhanh (≤ 3 giây). */
  @Bean
  MongoClient mongoClient(MongoProperties properties) {
    return MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(properties.uri().reveal()))
            .applyToClusterSettings(c -> c.serverSelectionTimeout(2, TimeUnit.SECONDS))
            .applyToSocketSettings(
                s -> s.connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS))
            .build());
  }

  @Bean
  MongoDatabase mongoDatabase(MongoClient client, MongoProperties properties) {
    return client.getDatabase(properties.database());
  }

  @Bean
  MongoReadiness mongoReadiness(
      MongoDatabase database, MongoSchema schema, BackgroundTasks tasks) {
    MongoReadiness readiness = new MongoReadiness(database, schema);
    tasks.every("mongo-readiness", MongoReadiness.PERIOD, readiness::refresh);
    return readiness;
  }

  @Bean
  ErrorMappingInterceptor.Translator mongoErrorTranslator() {
    return MongoErrors::translate;
  }
}
