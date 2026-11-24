package com.learninge.server.content.tool;

import com.learninge.server.content.domain.ContentBundle.Word;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Danh sách từ ⇄ CSV mở được bằng Excel/Google Sheets. Đọc được dấu phẩy, chấm phẩy (Excel tiếng Việt) và tab.
 */
public final class DeckCsv {
    static final List<String> COLUMNS =
            List.of("uid", "term", "reading", "pos", "meaning", "example", "example_translation");
    private static final String BOM = "\uFEFF";
    private static final char[] DELIMITERS = {',', ';', '\t'};

    private DeckCsv() {
    }

    /**
     * Có BOM để Excel nhận ra UTF-8.
     */
    public static String format(List<Word> words) {
        StringBuilder csv = new StringBuilder(BOM).append(String.join(",", COLUMNS)).append("\r\n");
        for (Word w : words) {
            List<String> cells = new ArrayList<>();
            cells.add(w.uid());
            cells.add(w.term());
            cells.add(w.reading());
            cells.add(w.pos());
            cells.add(w.meaning());
            cells.add(w.example());
            cells.add(w.exampleTranslation());
            csv.append(String.join(",", cells.stream().map(DeckCsv::quote).toList())).append("\r\n");
        }
        return csv.toString();
    }

    /**
     * Cột theo tên ở dòng đầu, thứ tự tùy ý; ô trống thành {@code null}; dòng trống bỏ qua.
     */
    public static List<Word> parse(String csv) {
        String text = csv.startsWith(BOM) ? csv.substring(1) : csv;
        List<List<String>> rows = rows(text, delimiter(text.lines().findFirst().orElse("")));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV rỗng");
        }
        Map<String, Integer> columns = columns(rows.getFirst());
        List<Word> words = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            if (row.size() > columns.size()) {
                throw new IllegalArgumentException("Dòng thừa cột, kiểm tra dấu ngăn cách và dấu nháy: " + row);
            }
            words.add(
                    new Word(
                            cell(row, columns, "uid"),
                            cell(row, columns, "term"),
                            cell(row, columns, "reading"),
                            cell(row, columns, "pos"),
                            cell(row, columns, "meaning"),
                            cell(row, columns, "example"),
                            cell(row, columns, "example_translation")));
        }
        return words;
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> columns = new TreeMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).strip().toLowerCase(Locale.ROOT);
            if (!COLUMNS.contains(name) || columns.put(name, i) != null) {
                throw new IllegalArgumentException("Cột lạ hoặc trùng: \"" + name + "\"; các cột: " + COLUMNS);
            }
        }
        Set<String> missing = new HashSet<>(COLUMNS);
        missing.removeAll(columns.keySet());
        if (!missing.isEmpty()) {
            // Thiếu cột mà cứ nhập thì dữ liệu cột đó bị xóa trắng.
            throw new IllegalArgumentException("Thiếu cột " + missing);
        }
        return columns;
    }

    private static String cell(List<String> row, Map<String, Integer> columns, String name) {
        int index = columns.get(name);
        String value = index < row.size() ? row.get(index).strip() : "";
        return value.isEmpty() ? null : value;
    }

    private static char delimiter(String header) {
        char best = DELIMITERS[0];
        for (char candidate : DELIMITERS) {
            if (count(header, candidate) > count(header, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static long count(String text, char wanted) {
        return text.chars().filter(c -> c == wanted).count();
    }

    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        boolean plain = value.chars().noneMatch(c -> c == ',' || c == ';' || c == '\t' || c == '"' || c == '\n');
        return plain ? value : '"' + value.replace("\"", "\"\"") + '"';
    }

    private static List<List<String>> rows(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') {
                    cell.append(c);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append(c);
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == delimiter) {
                row.add(take(cell));
            } else if (c == '\n') {
                row.add(take(cell));
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Thiếu dấu nháy đóng");
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(take(cell));
            rows.add(row);
        }
        return rows;
    }

    private static String take(StringBuilder cell) {
        String value = cell.toString();
        cell.setLength(0);
        return value;
    }
}
