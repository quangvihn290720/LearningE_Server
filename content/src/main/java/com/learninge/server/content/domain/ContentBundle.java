package com.learninge.server.content.domain;

import java.util.List;

/**
 * Nội dung đọc từ {@code content-data/} (định dạng DEC-002, cùng định dạng seed của app).
 */
public record ContentBundle(List<Language> languages, List<DeckFile> decks, List<Exam> exams) {

    public record Language(
            String code, String nameVi, String ttsLocale, String readingLabel, int sortOrder) {
    }

    public record DeckFile(Deck deck, String source, List<Word> words) {
    }

    public record Deck(
            String code,
            int version,
            String name,
            String nameEn,
            String description,
            String language,
            String icon,
            String level,
            String examTag,
            int sortOrder) {
    }

    public record Word(
            String uid,
            String term,
            String reading,
            String pos,
            String meaning,
            String example,
            String exampleTranslation) {
    }

    public record ExamFile(List<Exam> tests) {
    }

    public record Exam(
            String code,
            int version,
            String examTag,
            String title,
            String partLabel,
            int durationSec,
            String description,
            int sortOrder,
            List<Question> questions) {
    }

    /**
     * {@code deckCode} + {@code term} gắn câu hỏi với một từ trong bộ luyện thi.
     */
    public record Question(
            int number,
            String passage,
            String question,
            List<String> options,
            int correctIndex,
            String explanation,
            String deckCode,
            String term) {
    }
}
