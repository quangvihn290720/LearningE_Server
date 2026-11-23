package com.learninge.server.insight.grpc;

import com.learninge.proto.internal.v1.DailyStats;
import com.learninge.proto.internal.v1.GetDailyStatsRequest;
import com.learninge.proto.internal.v1.GetDailyStatsResponse;
import com.learninge.proto.internal.v1.InsightBackendGrpc;
import com.learninge.proto.internal.v1.LogRequestsRequest;
import com.learninge.proto.internal.v1.LogRequestsResponse;
import com.learninge.proto.internal.v1.RequestLog;
import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.FieldErrors;
import com.learninge.server.common.grpc.Unary;
import com.learninge.server.insight.store.AnalyticsStore;
import com.learninge.server.insight.store.AuditStore;
import io.grpc.stub.StreamObserver;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * Nhật ký yêu cầu từ edge và số liệu ngày cho người vận hành.
 */
public final class InsightBackendService extends InsightBackendGrpc.InsightBackendImplBase {
    private static final int MAX_LOGS = 100;
    private static final int MAX_DAYS = 92;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern HMAC = Pattern.compile("[0-9a-f]{32}");

    private final AuditStore audit;
    private final AnalyticsStore analytics;

    public InsightBackendService(AuditStore audit, AnalyticsStore analytics) {
        this.audit = audit;
        this.analytics = analytics;
    }

    @Override
    public void logRequests(
            LogRequestsRequest request, StreamObserver<LogRequestsResponse> observer) {
        if (request.getLogsCount() > MAX_LOGS) {
            throw AppException.invalid("logs", FieldErrors.OUT_OF_RANGE);
        }
        Set<String> hmacs =
                request.getLogsList().stream()
                        .map(RequestLog::getUserHmac)
                        .filter(h -> !h.isEmpty())
                        .collect(Collectors.toSet());
        Set<String> deleted = audit.deletedAmong(hmacs);
        List<Document> documents =
                request.getLogsList().stream()
                        .filter(InsightBackendService::valid)
                        .map(log -> document(log, deleted))
                        .toList();
        audit.insertRequests(documents);
        Unary.reply(observer, LogRequestsResponse.newBuilder().setAccepted(documents.size()).build());
    }

    @Override
    public void getDailyStats(
            GetDailyStatsRequest request, StreamObserver<GetDailyStatsResponse> observer) {
        LocalDate from = date("from_date", request.getFromDate());
        LocalDate to = date("to_date", request.getToDate());
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days < 1 || days > MAX_DAYS) {
            throw AppException.invalid("to_date", FieldErrors.OUT_OF_RANGE);
        }
        GetDailyStatsResponse.Builder response = GetDailyStatsResponse.newBuilder();
        analytics.between(from.toString(), to.toString()).forEach(d -> response.addDays(stats(d)));
        Unary.reply(observer, response.build());
    }

    private static boolean valid(RequestLog log) {
        return REQUEST_ID.matcher(log.getRequestId()).matches()
                && log.getRpc().length() <= 80
                && log.getCode() >= 0
                && log.getCode() <= 16
                && log.getDurationMs() >= 0
                && (log.getUserHmac().isEmpty() || HMAC.matcher(log.getUserHmac()).matches());
    }

    private static Document document(RequestLog log, Set<String> deleted) {
        Document document =
                new Document("_id", new ObjectId())
                        .append("request_id", log.getRequestId())
                        .append("rpc", log.getRpc())
                        .append("code", log.getCode())
                        .append("duration_ms", log.getDurationMs())
                        .append("client_version", log.getClientVersion())
                        .append("ts", new Date(log.getTsMs()));
        if (!log.getUserHmac().isEmpty() && !deleted.contains(log.getUserHmac())) {
            document.append("user_hmac", log.getUserHmac());
        }
        return document;
    }

    private static LocalDate date(String field, String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw AppException.invalid(field, FieldErrors.INVALID_FORMAT);
        }
    }

    private static DailyStats stats(Document d) {
        Number xp = (Number) d.getOrDefault("xp_total", 0L);
        return DailyStats.newBuilder()
                .setDate(d.getString("_id"))
                .setDau(d.getInteger("dau", 0))
                .setNewUsers(d.getInteger("new_users", 0))
                .setCardsReviewed(d.getInteger("cards_reviewed", 0))
                .setXpTotal(xp.longValue())
                .setQuizCount(d.getInteger("quiz_count", 0))
                .setExamCount(d.getInteger("exam_count", 0))
                .setPushCount(d.getInteger("push_count", 0))
                .setPushRecords(d.getInteger("push_records", 0))
                .setLoginFailed(d.getInteger("login_failed", 0))
                .setLocks(d.getInteger("locks", 0))
                .build();
    }
}
