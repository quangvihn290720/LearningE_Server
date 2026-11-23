# LearningE Server (Java) — thiết kế

**Trạng thái:** đã duyệt 23/11/2026 (Quang Vinh). Thiết kế chi tiết nằm ở `LearningE_app/docs`
(GRPC-API, DATABASE-DESIGN, SYSTEM-BLUEPRINT §Backend, SECURITY-THREAT-MODEL, Business-Rules). Tài liệu này
ghi lựa chọn, phạm vi và **những chỗ tài liệu gốc mâu thuẫn hoặc để ngỏ mà bản hiện thực phải chốt**.

## Mục đích
Dựng lại backend LearningE bằng Java vì Rust khó và không quen với nhóm. Đây là đồ án có vấn đáp: code phải dễ
đọc, dễ giải thích, có test chứng minh từng quy tắc nghiệp vụ.

- **Mục tiêu:** 16 RPC công khai (EP-01..EP-16) + RPC nội bộ; offline-first sync; bảng xếp hạng; nội dung; chạy
  được bằng `docker compose up` và bằng một JVM (`all-in-one`).
- **Không làm:** REST, streaming, JWT, email xác minh, đẩy thời gian thực, chọn nơi triển khai (DEC-023 còn mở).
- **Thất bại nếu:** nhóm không giải thích được code; Mongo tắt làm hỏng đăng nhập/đồng bộ; xóa tài khoản để sót dữ liệu.

## Phương án đã cân nhắc
| Phương án | Đánh đổi | Kết quả |
|---|---|---|
| A. 6 dịch vụ gRPC, Java 21 + Spring Boot 4.1.2 (DEC-024) | Khớp tài liệu, yêu cầu "Java + SQLite"; nhiều code, nặng (bù bằng `all-in-one`) | **Chọn** |
| B. Chuyển API Rust REST + PostgreSQL sang Spring | Nhỏ, dễ; bỏ đồng bộ offline, bảng xếp hạng; phải viết lại tài liệu | Loại |

## Cấu trúc
`proto` (hợp đồng) → `common` (hạ tầng) → 6 dịch vụ `edge auth sync content gamification insight` → `all-in-one`.
Mỗi dịch vụ: `grpc` (chuyển đổi message) → `domain` (quy tắc thuần, nhận `Clock`) → `store` (SQL/Mongo viết tay).
Dịch vụ không phụ thuộc nhau, chỉ gọi qua stub. Không Lombok, JPA, Spring Data repository, AOP tự viết.

## Quyết định của bản hiện thực
| # | Vấn đề trong tài liệu | Chốt |
|---|---|---|
| I-01 | `Pull.limit = 0`: GRPC-API ghi "0 = 500", TC-SRV-26 ghi lỗi | Theo hợp đồng: `0` → 500; `< 0` hoặc `> 500` → `VALIDATION_ERROR` |
| I-02 | XP có `total ≠ correct + wrong`: BR-068 "lưu, 0 XP", DDL lại `CHECK (total = correct + wrong)` | Lưu `total = correct + wrong`, `xp_awarded = 0` |
| I-03 | `card_progress` không có cột `deleted` (lược đồ ghi "không xóa") nhưng BR-045/BR-063 nói tombstone tiến độ | Theo lược đồ: `deleted = true` cho `card_progress` → `INVALID_RECORD`; máy suy ra từ tombstone của từ |
| I-04 | `is_default` do client gửi | Server tự suy: đúng khi `uuid` = UUIDv5 "notebook:" + user; xóa bộ này → `INVALID_RECORD` (BR-045) |
| I-05 | `UNKNOWN_TEST`: `sync` không biết danh sách đề | `test_code` sai mẫu `^[A-Z][0-9]{2}$` → `UNKNOWN_TEST` |
| I-06 | `study_date` ở tương lai xa | Lưu, nhưng ngày > hôm nay (VN) + 1 không tính vào chuỗi |
| I-07 | Đồng hồ giả/virtual thread/`@Scheduled` cho relay | Một luồng nền cho mỗi đích (`SmartLifecycle`), chờ tối đa 1 s hoặc được đánh thức |
| I-08 | Actuator ở cổng riêng 8081 + phải tắt cổng HTTP chính | Một cổng HTTP duy nhất `127.0.0.1:8081`, chỉ phục vụ `health` và `metrics` |
| I-09 | `common` kéo JDBC vào dịch vụ Mongo | `common` để JDBC/SQLite là `compileOnly`; chỉ `auth`, `sync` khai báo lúc chạy; tắt auto-config DataSource/Flyway |
| I-10 | `progress.changed.version = updated_at` có thể trùng trong cùng mili giây | `max(now, bản trước + 1)` — luôn tăng |
| I-11 | `word_uid` dạng UUID của từ người khác | `INVALID_RECORD` nếu từ đã tồn tại và thuộc người khác |
| I-12 | SRS ghi `term ≤ 50`, hợp đồng trần 1 000 | Theo SRS: term 50, reading 60, meaning 200, ví dụ 300, tên bộ 50, mô tả 200 |
| I-13 | Hòa XP: BR-069 ghi "lúc server ghi nhận", DATABASE-DESIGN dùng `occurred_at` (app gửi, lùi được) | Theo BR-069: `received_at` |
| I-14 | Spring Data MongoDB | Chỉ dùng driver chính thức (`spring-boot-starter-mongodb`): không có `_class`, ít phép thuật hơn |
| I-15 | Interceptor lỗi: `GrpcExceptionHandler` của Spring | `ErrorMappingInterceptor` tự viết ở trong cùng, thứ tự chắc chắn |
| I-16 | Sự kiện đến muộn dựng lại dữ liệu ở insight sau khi xóa tài khoản | Thêm collection `deleted_users` (bút danh, TTL 120 ngày) |
| I-17 | Validator Mongo từ chối sự kiện → relay thử lại mãi | Lỗi 121 → `INVALID_ARGUMENT` → "dead" sau 5 lần, không kẹt hàng |
| I-18 | Khóa nội bộ sai trả `UNAUTHENTICATED` làm app đăng xuất oan | Không kèm `error-code`; edge đổi thành `SERVICE_UNAVAILABLE`/`INTERNAL` |
| I-19 | `LISTEN_ADDR` dạng `host:port` | Tách `LISTEN_HOST`, `LISTEN_PORT` (Spring gRPC nhận riêng) |
| I-20 | `request_id` trong payload sự kiện (TC-SVC-09) chưa có trường | Thêm `Event.request_id = 7` (gói nội bộ, chỉ cộng thêm) |
| I-21 | Google Java Style (chờ duyệt KG-8) | Theo yêu cầu: định dạng mặc định IntelliJ (Ctrl+Alt+L) |
| I-22 | Schema Mongo trùng tên giữa dịch vụ ở chế độ gộp | Thư mục riêng `mongo/<dịch vụ>/` (lỗi do test đầu-cuối phát hiện) |
| I-23 | `mongo:8.0.34` không chạy trên kernel ≥ 6.19 (SERVER-121912) | Ghim 8.0.38 theo digest, ngoại lệ quy tắc ≥ 2 tuần |

**Chưa làm:** seed tài khoản demo (`LEARNINGE_DEMO_SEED`), `grammar.json` (tính năng Won't), chốt nơi triển khai (DEC-023).

## Để lại cho người xây
Tên lớp nội bộ, cách chia test, thông điệp log, bố cục README.
