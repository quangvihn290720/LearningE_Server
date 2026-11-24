# Quản lý nội dung học

Nguồn **duy nhất** là `content-data/` ở repo này. App chỉ giữ bản chép (seed) để dùng khi chưa có mạng, lấy bằng
`tools/sync-content.ps1` ở repo app; không sửa tay bản chép.

| Tệp | Nội dung |
|---|---|
| `decks/T05.json` | một bộ từ; mã `T..` là chủ đề, `X..` luyện thi, `J..` tiếng Nhật |
| `exams.json` | các đề thi |
| `languages.json` | ngôn ngữ |
| `content.lock` | tự sinh: version, mã băm, mọi uid **từng** dùng |

## Ba luật phải nhớ
1. **uid không bao giờ đổi và không dùng lại.** Tiến độ của người học gắn với uid.
2. **Sửa gì cũng tăng `version`** của bộ/đề đó. Không tăng thì máy đã cài sẽ không nhận bản sửa.
3. **Không xóa file bộ hay đề.** Muốn bỏ thì đặt `"retired": true`.

Quên luật nào thì lệnh `lock`, CI hoặc lệnh nạp sẽ báo lỗi và không ghi gì.

## Làm việc hằng ngày

Cần JDK 17 trở lên để chạy Gradle; không cần Docker hay MongoDB.

### Thêm, sửa, xóa từ bằng Excel/Google Sheets (nên dùng)
```powershell
tools/content.ps1 export T05 T05.csv     # 1. xuất
# 2. sửa T05.csv bằng Excel/Sheets
tools/content.ps1 import T05 T05.csv     # 3. nhập lại
```
- **Thêm:** thêm dòng, để trống cột `uid`.
- **Sửa:** sửa ô, giữ nguyên `uid`.
- **Xóa:** xóa cả dòng.
- **Thay bằng từ khác:** xóa dòng cũ, thêm dòng mới với `uid` trống.
- Excel: lưu kiểu **CSV UTF-8**.

Lệnh nhập in `+` thêm, `~` sửa, `-` xóa, tự cấp uid, tăng version và ghi `content.lock`.

### Sửa thẳng JSON
Sửa tệp, tăng `version`, rồi chạy `tools/content.ps1 lock`.

### Thêm bộ mới
```powershell
tools/content.ps1 new T16 T16.csv
```
Mở `decks/T16.json` điền `name`, `name_en`, `description`, `level`, `source`, rồi chạy `tools/content.ps1 lock`.
`icon` là tên trong `TopicArt.java` của app; để `null` thì app dùng hình quyển sách.

### Ẩn hoặc hiện lại bộ, đề
Thêm `"retired": true` vào `deck` (hoặc vào đề), tăng `version`, chạy `lock`. App ẩn bộ cùng mọi từ của nó nhưng giữ
tiến độ; với đề thì giữ lịch sử làm bài. Bỏ dòng đó và tăng `version` là hiện lại như cũ.

### Sửa đề thi
- Sửa lỗi chính tả, đáp án sai: sửa tại chỗ, tăng `version`.
- Đổi hẳn câu hỏi: tạo đề mới (`E02`) và cho đề cũ `retired`. Sửa tại chỗ thì người đã thi xem lại bài sẽ thấy câu mới,
  và bớt câu thì câu trả lời cũ của các câu cuối bị xóa.
- `number` phải liên tục từ 1.

## Đưa lên
1. PR với `content-data/` (gồm `content.lock`); CI kiểm lại toàn bộ.
2. Merge, rồi trên máy chủ:
   ```bash
   docker compose -f deploy/docker-compose.yml run --rm content --ops=import --dir=/content-data
   ```
3. App tự tải khi được mở lại (nhiều nhất 5 phút một lần), **không cần phát hành APK**.
4. Trước khi phát hành APK: ở repo app chạy `tools/sync-content.ps1 -Server <đường dẫn repo này>` để bản cài mới có sẵn
   nội dung.

## Hoàn tác
Không hạ `version`. Lấy lại nội dung cũ từ Git, đặt `version` **lớn hơn** bản đang chạy, chạy `lock`, nạp lại.

## Dữ liệu mẫu
Bộ có `source` bắt đầu bằng "Dữ liệu mẫu" (hiện là T01, X01, J01 và đề E01) chờ bản chính thức. Thay bằng cách nhập đè;
version phải từ **2**, vì máy đã cài bản mẫu đang ở version 1. Khi thay nhớ đổi `source`.

## Giới hạn để app nhận được
Các giới hạn nằm ở `ContentValidator`. Đáng chú ý: `term` 50 ký tự, `reading` 60, `pos` 20, `meaning` 200; trong một bộ
không có hai từ giống nhau khi bỏ dấu và hoa thường (`resume` và `résumé`); `exam_tag` chỉ TOEIC, IELTS, APTIS vì app
chỉ có màn cho ba kỳ này.
