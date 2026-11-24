# Quy ước đóng góp

## Nhánh và commit
- `main` luôn chạy được. Nhánh: `feat/…`, `fix/…`, `docs/…`; PR cần 1 người review và CI xanh.
- Commit: `feat(auth): …`, `fix(sync): …`, `test: …`, `docs: …`.
- Người viết phải tự giải thích được luồng code trong mô tả PR.

## Quy tắc code
1. Tầng: `grpc` (đổi message) → `service` (nghiệp vụ, giao dịch) → `domain` (quy tắc thuần, không I/O) + `store` (SQL/Mongo).
2. `user_id` **chỉ** lấy bằng `CallerContext.userId()`. Không bao giờ đọc từ message.
3. SQL luôn dùng tham số `?`. Filter Mongo dựng bằng `Filters.*`, không từ chuỗi.
4. Thay đổi mà dịch vụ khác cần biết → `OutboxWriter.append` **trong cùng giao dịch**. Không gọi dịch vụ khác trong giao dịch.
5. Bên nhận sự kiện phải idempotent: trùng và lệch thứ tự là chuyện bình thường.
6. Lỗi nghiệp vụ ném `AppException` với `ErrorCode`; không tự tạo `Status`.
7. Không log message, token, email, mật khẩu. So sánh bí mật bằng `MessageDigest.isEqual`.
8. Không Lombok, JPA, Spring MVC, quét component. Bean khai báo tường minh trong `<Svc>ServiceConfig`.
9. Thời gian lấy từ `Clock` được tiêm vào, không gọi `System.currentTimeMillis()`.
10. Thư viện có trong BOM Spring Boot thì không ghi phiên bản; thêm thư viện mới phải nêu lý do trong PR.

11. Định dạng: kiểu mặc định của IntelliJ IDEA (Ctrl+Alt+L), chép trong `.editorconfig`. Android Studio dùng cùng bộ.

## Đổi `.proto`
Trong v1 chỉ **thêm**: trường số mới, RPC mới, giá trị enum mới. Không đổi số, kiểu, tên, nghĩa. Xóa thì `reserved`.
Sau khi merge, đồng bộ sang repo app bằng `tools/sync-proto.ps1` ở repo app.

## Đổi nội dung học
Theo [docs/NOI-DUNG-HOC.md](docs/NOI-DUNG-HOC.md); commit `content.lock` cùng nội dung.

## Đổi lược đồ
- SQLite: thêm tệp `V00N__mo_ta.sql`, **không sửa** tệp đã chạy.
- MongoDB: sửa `resources/mongo/<dịch vụ>/<collection>.schema.json`; đổi kiểu dữ liệu cũ thì thêm bước chuyển đổi.

## Trước khi mở PR
`./gradlew spotlessApply build` xanh, có test cho quy tắc mới (kèm mã BR/TC trong tên hoặc chú thích).
