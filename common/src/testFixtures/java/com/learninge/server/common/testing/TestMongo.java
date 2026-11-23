package com.learninge.server.common.testing;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

import java.util.UUID;

import org.testcontainers.mongodb.MongoDBContainer;

/**
 * MongoDB thật trong container, dùng chung cho cả lượt test; mỗi test một database riêng.
 */
public final class TestMongo {
    private static final MongoDBContainer CONTAINER = new MongoDBContainer("mongo:8.0.38");
    private static final MongoClient CLIENT;

    static {
        CONTAINER.start();
        CLIENT = MongoClients.create(CONTAINER.getConnectionString());
    }

    private TestMongo() {
    }

    public static MongoDatabase newDatabase() {
        return CLIENT.getDatabase("t" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
    }

    public static MongoDatabase database(String name) {
        return CLIENT.getDatabase(name);
    }

    public static String uri() {
        return CONTAINER.getConnectionString();
    }

    public static String[] properties(MongoDatabase database) {
        return new String[]{
                "--learninge.mongo.uri=" + CONTAINER.getConnectionString(),
                "--learninge.mongo.database=" + database.getName()
        };
    }

    /**
     * Địa chỉ không có Mongo nào nghe, để thử tình huống Mongo ngừng.
     */
    public static String[] unreachable() {
        return new String[]{
                "--learninge.mongo.uri=mongodb://127.0.0.1:1/?connectTimeoutMS=500",
                "--learninge.mongo.database=down"
        };
    }
}
