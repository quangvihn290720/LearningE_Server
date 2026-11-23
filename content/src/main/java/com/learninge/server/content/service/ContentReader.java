package com.learninge.server.content.service;

import com.learninge.server.content.domain.ContentBundle;
import com.learninge.server.content.domain.ContentBundle.DeckFile;
import com.learninge.server.content.domain.ContentBundle.ExamFile;
import com.learninge.server.content.domain.ContentBundle.Language;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Đọc {@code languages.json}, {@code exams.json}, {@code decks/*.json}; trường lạ là lỗi. */
final class ContentReader {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private ContentReader() {}

  static ContentBundle read(Path directory) {
    List<Language> languages =
        JSON.readValue(text(directory.resolve("languages.json")), new TypeReference<>() {});
    ExamFile exams = JSON.readValue(text(directory.resolve("exams.json")), ExamFile.class);
    List<DeckFile> decks = new ArrayList<>();
    for (Path file : deckFiles(directory.resolve("decks"))) {
      decks.add(JSON.readValue(text(file), DeckFile.class));
    }
    return new ContentBundle(languages, decks, exams.tests());
  }

  private static List<Path> deckFiles(Path directory) {
    try (Stream<Path> files = Files.list(directory)) {
      return files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String text(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
