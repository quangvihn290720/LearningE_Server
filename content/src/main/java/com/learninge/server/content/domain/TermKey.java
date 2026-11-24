package com.learninge.server.content.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Khóa so trùng từ, giống hệt {@code TextNormalizer.searchKey} của app: app có index duy nhất trên khóa này.
 */
public final class TermKey {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private TermKey() {
    }

    public static String of(String term, String language) {
        if (term == null) {
            return "";
        }
        String text = SPACES.matcher(term.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
        return switch (language == null ? "" : language) {
            // Dấu là một phần của chữ (BR-053).
            case "ja", "zh", "ko" -> Normalizer.normalize(text, Normalizer.Form.NFKC);
            // NFD không tách được "đ".
            default -> MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD))
                    .replaceAll("")
                    .replace('đ', 'd');
        };
    }
}
