package com.learninge.server.edge.ops;

import com.learninge.proto.app.v1.ActivityType;
import com.learninge.proto.app.v1.CardProgressData;
import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.PushRequest;
import com.learninge.proto.app.v1.RegisterRequest;
import com.learninge.proto.app.v1.StudyLogData;
import com.learninge.proto.app.v1.XpEventData;
import com.learninge.proto.app.v1.XpSourceType;
import com.learninge.proto.internal.v1.AuthBackendGrpc;
import com.learninge.proto.internal.v1.SyncBackendGrpc;
import com.learninge.server.common.config.AppEnv;
import com.learninge.server.common.grpc.CallerContext;
import com.learninge.server.common.grpc.InternalChannels;
import com.learninge.server.common.ops.OpsCommand;
import com.learninge.server.common.time.VnTime;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

/**
 * {@code --ops=demo-seed}: hai tài khoản demo và tám người học giả cho bảng xếp hạng, tạo qua đúng RPC
 * Register và Push như app (dữ liệu đi qua outbox tới MongoDB). Chạy lại thì bỏ qua người đã có.
 */
public final class DemoSeedCommand implements OpsCommand {
    private static final Logger log = LoggerFactory.getLogger(DemoSeedCommand.class);
    private static final int STUDY_DAYS = 5;
    private static final List<String> FAKE_NICKNAMES =
            List.of(
                    "minh.anh", "thu_ha", "quang.huy", "lan.chi", "duc_tri", "bao.ngoc", "hoang_nam", "my.linh");

    private final InternalChannels channels;
    private final DemoProperties demo;
    private final AppEnv env;
    private final Clock clock;

    public DemoSeedCommand(InternalChannels channels, DemoProperties demo, AppEnv env, Clock clock) {
        this.channels = channels;
        this.demo = demo;
        this.env = env;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "demo-seed";
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!demo.seed() || demo.password().isBlank()) {
            throw new IllegalStateException("Đặt LEARNINGE_DEMO_SEED=true và LEARNINGE_DEMO_PASSWORD trước");
        }
        if (env == AppEnv.PROD && !arguments.containsOption("confirm-prod")) {
            throw new IllegalStateException("APP_ENV=prod: thêm --confirm-prod nếu thật sự cần cho buổi demo");
        }
        int created = 0;
        for (int i = 1; i <= 2; i++) {
            created += seed("demo" + i, "Demo " + i, demo.password().reveal(), 4 * i);
        }
        for (int i = 0; i < FAKE_NICKNAMES.size(); i++) {
            // Không ai đăng nhập tài khoản giả: mật khẩu ngẫu nhiên, không lưu ở đâu.
            created += seed(FAKE_NICKNAMES.get(i), "Người học " + (i + 1), UUID.randomUUID() + "a1", 1 + i);
        }
        log.info("Đã tạo {} tài khoản demo", created);
    }

    private int seed(String nickname, String displayName, String password, int quizzesPerDay) {
        String userId;
        try {
            userId =
                    AuthBackendGrpc.newBlockingStub(channels.to("auth"))
                            .withDeadlineAfter(10, TimeUnit.SECONDS)
                            .register(
                                    RegisterRequest.newBuilder()
                                            .setEmail(nickname + "@learninge.test")
                                            .setPassword(password)
                                            .setNickname(nickname)
                                            .setDisplayName(displayName)
                                            .setSecurityQuestionId(1)
                                            .setSecurityAnswer(UUID.randomUUID().toString())
                                            .build())
                            .getUser()
                            .getId();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                log.info("{} đã có, bỏ qua", nickname);
                return 0;
            }
            throw e;
        }
        PushRequest history =
                PushRequest.newBuilder()
                        .setDeviceId(UUID.randomUUID().toString())
                        .addAllChanges(history(quizzesPerDay))
                        .build();
        Context.current()
                .withValue(CallerContext.USER_ID, userId)
                .run(
                        () ->
                                SyncBackendGrpc.newBlockingStub(channels.to("sync"))
                                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                                        .push(history));
        return 1;
    }

    private List<Change> history(int quizzesPerDay) {
        List<Change> changes = new ArrayList<>();
        for (int day = STUDY_DAYS - 1; day >= 0; day--) {
            long at = clock.millis() - Duration.ofDays(day).toMillis();
            changes.add(
                    change(UUID.randomUUID().toString(), at)
                            .setStudyLog(
                                    StudyLogData.newBuilder()
                                            .setActivityType(ActivityType.ACTIVITY_TYPE_QUIZ)
                                            .setStudyDate(VnTime.date(at).toString())
                                            .setCreatedAtMs(at))
                            .build());
            for (int i = 0; i < quizzesPerDay; i++) {
                changes.add(
                        change(UUID.randomUUID().toString(), at)
                                .setXpEvent(
                                        XpEventData.newBuilder()
                                                .setSourceType(XpSourceType.XP_SOURCE_TYPE_QUIZ)
                                                .setCorrectCount(10 - i % 4)
                                                .setWrongCount(i % 4)
                                                .setTotalCount(10)
                                                .setOccurredAtMs(at))
                                .build());
            }
        }
        for (int i = 1; i <= 2 * quizzesPerDay; i++) {
            long now = clock.millis();
            String today = VnTime.date(now).toString();
            changes.add(
                    change(String.format("T05-%04d", i), now)
                            .setCardProgress(
                                    CardProgressData.newBuilder()
                                            .setBox(1 + i % 5)
                                            .setNextReviewDate(today)
                                            .setFirstLearnedDate(today)
                                            .setLastReviewedAtMs(now)
                                            .setReviewCount(1))
                            .build());
        }
        return changes;
    }

    private static Change.Builder change(String id, long at) {
        return Change.newBuilder().setRecordId(id).setUpdatedAtMs(at);
    }
}
