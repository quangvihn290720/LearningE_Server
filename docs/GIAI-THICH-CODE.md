# Giải thích code — để đọc hiểu và vấn đáp

Đọc theo thứ tự: mục 1 (một lời gọi đi qua đâu) → dịch vụ mình phụ trách → mục 8 (câu hỏi hay gặp).

## 1. Một lời gọi đi qua đâu (ví dụ `SyncService/Push`)

1. App gọi `edge:50051` với `authorization: Bearer <token>` và `x-client-version`.
2. Chuỗi interceptor của edge, thứ tự ở [InterceptorOrder](../common/src/main/java/com/learninge/server/common/grpc/InterceptorOrder.java):
   xóa metadata giả → gắn `x-request-id` và MDC → kiểm phiên bản app → cắt deadline → giới hạn theo IP →
   **xác thực token** → giới hạn theo token → trần đồng thời → đổi ngoại lệ thành lỗi gRPC.
3. [SyncForwarder](../edge/src/main/java/com/learninge/server/edge/forward/SyncForwarder.java) gọi dịch vụ `sync`, kèm
   `x-internal-auth` (khóa nội bộ) và `x-user-id` (lấy từ token). Deadline = phần còn lại − 100 ms, tối đa 18 s.
4. `sync` kiểm khóa nội bộ **trước tiên**
   ([InternalAuthServerInterceptor](../common/src/main/java/com/learninge/server/common/grpc/InternalAuthServerInterceptor.java)),
   đúng mới đọc `x-user-id`.
5. [PushService](../sync/src/main/java/com/learninge/server/sync/service/PushService.java) mở **một giao dịch**: ghi bản ghi,
   cập nhật thống kê, ghi sự kiện outbox → commit → đánh thức relay.
6. Relay gửi sự kiện sang `gamification` và `insight` ở luồng nền. App không phải chờ bước này.

## 2. `common` — hạ tầng dùng chung
| Gói | Việc | Lớp nên đọc |
|---|---|---|
| `error` | Mã lỗi nghiệp vụ | `ErrorCode`, `AppException` |
| `grpc` | Interceptor, kênh nội bộ | `ErrorMappingInterceptor`, `RequestIdServerInterceptor` |
| `sqlite` | 1 kết nối ghi + 4 đọc, Flyway, sao lưu | `SqliteDatabase` |
| `outbox` | Ghi và phát sự kiện | `OutboxWriter`, `OutboxDrainer`, `EventPolicy` |
| `mongo` | Tạo collection/validator/index, readiness | `MongoSchema`, `MongoReadiness` |
| `security`, `id`, `time` | HMAC, bút danh, UUIDv5/v7, ngày giờ VN | `UserPseudonym`, `UuidV5`, `VnTime` |

**Outbox giải bài toán gì?** Nếu ghi SQLite rồi gọi sang dịch vụ khác, sập ở giữa thì hai bên lệch nhau. Outbox ghi sự kiện
vào **cùng giao dịch** với dữ liệu: hoặc cả hai cùng có, hoặc cùng không. Relay gửi sau, gửi lại cho tới khi được nhận
("ít nhất một lần"); bên nhận khử trùng theo `event_id`. Mỗi đích một dòng và một luồng riêng nên đích ngừng không chặn đích
khác. Lùi 2, 4, 8, 15 s; đích sống lại thì xả ngay. Sự kiện bị từ chối vĩnh viễn 5 lần thì `dead_at`, không chặn hàng sau.

## 3. `auth` — tài khoản
- **Mật khẩu**: PBKDF2 120.000 vòng, salt riêng (`PasswordHasher`), tối đa 4 phép băm cùng lúc để không bị làm cạn CPU.
  Riêng `auth` bật C2 (`JDK_JAVA_OPTIONS` trong compose): băm khoảng 30 ms thay vì 270 ms.
- **Token**: 32 byte ngẫu nhiên; DB chỉ giữ SHA-256. Mất DB cũng không có token dùng được.
- **Khóa tăng dần** (`LockState`, hàm thuần): sai 5 lần khóa 60 s, gấp đôi tới 1 giờ.
- **Chống dò email** (`CredentialGuard`, `EmailKeys`): email không tồn tại vẫn băm, vẫn đếm, vẫn khóa, vẫn có câu hỏi bảo mật
  giả ổn định (HMAC với khóa chỉ server có).
- **Vì sao đọc lại trạng thái trong giao dịch?** Băm mất ~100 ms ở ngoài giao dịch; trong lúc đó yêu cầu khác có thể đã khóa
  tài khoản hoặc đổi mật khẩu. Giao dịch cuối kiểm lại rồi mới ghi.

## 4. `sync` — đồng bộ offline-first
- **Push** nhận ≤ 500 bản ghi. Mỗi bản ghi có kết quả riêng: `APPLIED`, `STALE`, `DUPLICATE`, `REJECTED`. Một bản sai không
  làm hỏng cả lô; lỗi hệ thống thì rollback cả lô.
- **Xung đột** (`RecordWriter`): bộ từ, từ, cài đặt, yêu thích → bản đến sau thắng. Riêng `card_progress` → bản có
  `updated_at` lớn hơn thắng, vì đó là lần học thật mới nhất.
- **`server_seq`** là số thứ tự tăng dần toàn máy chủ; Pull hỏi "có gì sau số này". Vì chỉ có một kết nối ghi nên số cấp trước
  luôn commit trước, client không bao giờ bỏ sót.
- **XP** (`XpRules`): server tự tính, bỏ qua con số app gửi; trần 500/ngày; ngoài khoảng −7 ngày/+5 phút thì 0 XP.
- **Chuỗi ngày** (`Streak`): tính lại từ toàn bộ `study_log` nên không phụ thuộc máy nào đẩy trước.
- **Chống IDOR**: bản ghi của người khác coi như không hợp lệ, không trả dữ liệu.
- **Xóa tài khoản** (`UserLifecycle`): xóa 10 bảng + ghi `purged_users`; lô Push đến muộn bị `ACCOUNT_DELETED`.

## 5. `gamification`, `content`, `insight`
- **gamification**: lưu bản sao sự kiện XP, **tính lại** XP tuần từ gốc (không cộng dồn) nên nhận trùng không sai. Ghi bằng
  `$max` nên hai lượt tính xen kẽ không thể lùi số. Hòa XP → ai đạt trước đứng trên. `--ops=rebuild` dựng lại từ SQLite.
- **content**: `--ops=import` kiểm hết mới ghi; `manifest` ghi cuối cùng nên app không bao giờ thấy nội dung dở dang.
- **insight**: chỉ có bút danh `user_hmac`. Chèn nhật ký theo `event_id`; chỉ khi chèn mới mới cộng số liệu. Dữ liệu tự hết hạn (TTL).

## 6. `edge` — cổng vào
- **Mặc định cần token** (`AppMethods`): RPC mới không tự thành công khai; test sinh từ proto kiểm điều này.
- **Token sai quá 60 lần/phút/IP** thì edge không hỏi auth nữa; người đã có trong cache không bị ảnh hưởng.
- **Gói quá 1 MiB** (`PayloadLimitInterceptor`): tầng vận chuyển cho tới 2 MiB để edge kịp trả `PAYLOAD_TOO_LARGE`.
- **Cache token 60 s** (`TokenAuthenticator`): khóa là SHA-256. Đăng xuất/đổi mật khẩu/xóa tài khoản xóa cache ngay. Bộ đếm
  `revocations` chặn tình huống: hỏi auth xong, trước khi cất vào cache thì token bị thu hồi.
- **`Downstream`**: lỗi nghiệp vụ chuyển nguyên; mất kết nối → `SERVICE_UNAVAILABLE`. Sai khóa nội bộ **không** được thành
  `UNAUTHENTICATED`, nếu không app sẽ đăng xuất người dùng oan.
- **`GetMe`** hỏi 3 dịch vụ song song; phần phụ quá 300 ms thì bỏ, báo `stats_partial`.

## 7. `all-in-one` và triển khai
- Cùng mã, 6 context Spring trong một JVM; dịch vụ nội bộ chỉ nghe `127.0.0.1`.
- Compose: mạng `backend` là `internal`, chỉ edge publish `127.0.0.1:50051`; container không root, hệ tệp chỉ đọc, bỏ mọi
  capability; mỗi dịch vụ Mongo một user riêng.

## 8. Câu hỏi hay gặp
- **Vì sao không JWT?** JWT không thu hồi ngay được; token opaque xóa một dòng là hết hiệu lực.
- **Vì sao SQLite mà vẫn chịu được đồng thời?** WAL cho đọc song song; ghi xếp hàng qua một kết nối, giao dịch ngắn.
- **Vì sao bên nhận sự kiện ack một phần?** Lô có hạn 3 giây. Nếu không xong mà bỏ cả lô thì lần sau lại gửi đúng lô đó và
  đứng mãi. `EventSinkBase` dừng trước hạn 1 giây, ack phần đã làm; `OutboxDrainer` gửi tiếp phần còn lại ngay.
- **Vì sao `gamification` xử lý theo cụm 50?** Tính lại XP tuần một lần cho mỗi người thay vì mỗi sự kiện, và để MongoDB
  cộng sẵn bằng index chứa đủ trường. Đo được: 10.000 sự kiện tồn đọng xả trong khoảng 10 giây (trước đó 88 giây).
- **Vì sao khôi phục `sync.db` phải nhảy số thứ tự?** Máy người dùng nhớ mốc đã kéo, ví dụ 1.000. Bản sao lưu chỉ tới 900
  thì thay đổi mới nhận số 901 và máy đó không bao giờ thấy. `SyncTables.startNewEpoch` nhảy qua mọi mốc đã cấp.
- **Vì sao phiên đăng nhập tự gia hạn?** Người học đều mỗi ngày không nên bị đăng xuất giữa chừng. `SessionService` gia hạn
  khi đã qua nửa hạn, tối đa 180 ngày; bỏ không 30 ngày vẫn hết hạn. Ghi nhiều nhất một lần mỗi ngày cho mỗi phiên.
- **Vì sao `synchronous = FULL`?** App đánh dấu "đã đồng bộ" khi nhận trả lời, nên commit xong không được mất khi mất điện.
  Đo trên máy phát triển: không chậm đi đáng kể.
- **Mongo tắt thì sao?** Đăng nhập và đồng bộ vẫn chạy, sự kiện nằm chờ trong outbox, Mongo lên lại thì tự xả.
- **Gửi lại cùng một lô Push thì sao?** Không nhân đôi: bản ghi chỉ-thêm thành `DUPLICATE`, XP không cộng lại.
- **Vì sao không dùng `@Transactional`?** Giao dịch mở tường minh bằng `inWriteTx` nên nhìn là thấy ranh giới.
- **Vì sao không Lombok/JPA?** SQL nhìn thấy được, không có mã ẩn phải giải thích.
