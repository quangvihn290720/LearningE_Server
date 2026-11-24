package com.learninge.server.insight.service;

import com.learninge.server.common.ops.OpsCommand;
import com.learninge.server.common.time.VnTime;
import com.learninge.server.insight.store.AnalyticsStore;
import com.learninge.server.insight.store.AuditStore;

import java.io.PrintStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.bson.Document;
import org.springframework.boot.ApplicationArguments;

/**
 * {@code --ops=report [--days=30]}: in một trang HTML tự đứng (không script, không tải gì ngoài) về số
 * liệu ngày và từng RPC. Chỉ có số tổng hợp, không có dữ liệu của người dùng nào.
 */
public final class ReportCommand implements OpsCommand {
    private static final int MAX_DAYS = 92;
    private static final int LOG_DAYS = 7;

    private static final String HEAD = """
            <!doctype html>
            <html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>LearningE Server</title><style>
            :root { color-scheme: light dark }
            body { font: 15px/1.5 system-ui, sans-serif; margin: 2rem auto; max-width: 68rem; padding: 0 1rem }
            .scroll { overflow-x: auto }
            table { border-collapse: collapse; width: 100%; font-variant-numeric: tabular-nums }
            th, td { padding: .35rem .7rem; text-align: right; border-bottom: 1px solid #8884; white-space: nowrap }
            th:first-child, td:first-child { text-align: left }
            </style></head><body>
            """;

    private final AnalyticsStore analytics;
    private final AuditStore audit;
    private final Clock clock;
    private final PrintStream out;

    public ReportCommand(AnalyticsStore analytics, AuditStore audit, Clock clock, PrintStream out) {
        this.analytics = analytics;
        this.audit = audit;
        this.clock = clock;
        this.out = out;
    }

    @Override
    public String name() {
        return "report";
    }

    @Override
    public void run(ApplicationArguments arguments) {
        List<String> days = arguments.getOptionValues("days");
        int count = days == null || days.isEmpty() ? 30 : Integer.parseInt(days.getFirst());
        out.print(render(Math.clamp(count, 1, MAX_DAYS)));
        out.flush();
    }

    private String render(int days) {
        LocalDate today = VnTime.date(clock.millis());
        StringBuilder html = new StringBuilder(HEAD);
        html.append("<h1>LearningE Server</h1><p>Lập lúc ")
                .append(clock.instant().atZone(VnTime.ZONE).toLocalDateTime().withNano(0))
                .append(" (giờ Việt Nam)</p>");
        html.append("<h2>").append(days).append(" ngày gần nhất</h2>");
        table(
                html,
                List.of(
                        "Ngày", "Người học", "Đăng ký", "Thẻ", "XP", "Quiz", "Đề thi", "Lượt đẩy", "Bản ghi",
                        "Sai mật khẩu", "Bị khóa"),
                analytics.between(today.minusDays(days - 1L).toString(), today.toString()).reversed().stream()
                        .map(
                                d ->
                                        List.of(
                                                d.getString("_id"),
                                                number(d, "dau"),
                                                number(d, "new_users"),
                                                number(d, "cards_reviewed"),
                                                number(d, "xp_total"),
                                                number(d, "quiz_count"),
                                                number(d, "exam_count"),
                                                number(d, "push_count"),
                                                number(d, "push_records"),
                                                number(d, "login_failed"),
                                                number(d, "locks")))
                        .toList());
        html.append("<h2>Lời gọi từ app, ").append(LOG_DAYS).append(" ngày gần nhất</h2>");
        table(
                html,
                List.of("RPC", "Lượt", "Lỗi máy chủ", "Lỗi khác", "p50 (ms)", "p95 (ms)", "Lâu nhất (ms)"),
                audit.rpcStats().stream()
                        .map(
                                d -> {
                                    List<Number> times = d.getList("times", Number.class);
                                    return List.of(
                                            d.getString("_id"),
                                            number(d, "calls"),
                                            number(d, "server_errors"),
                                            number(d, "other_errors"),
                                            Math.round(times.get(0).doubleValue()) + "",
                                            Math.round(times.get(1).doubleValue()) + "",
                                            number(d, "slowest"));
                                })
                        .toList());
        return html.append("</body></html>\n").toString();
    }

    private static void table(StringBuilder html, List<String> headers, List<List<String>> rows) {
        if (rows.isEmpty()) {
            html.append("<p>Chưa có số liệu.</p>");
            return;
        }
        html.append("<div class=\"scroll\"><table><thead><tr>");
        headers.forEach(h -> html.append("<th>").append(h).append("</th>"));
        html.append("</tr></thead><tbody>");
        for (List<String> row : rows) {
            html.append("<tr>");
            row.forEach(cell -> html.append("<td>").append(escape(cell)).append("</td>"));
            html.append("</tr>");
        }
        html.append("</tbody></table></div>");
    }

    private static String number(Document d, String field) {
        return Long.toString(((Number) d.getOrDefault(field, 0)).longValue());
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
