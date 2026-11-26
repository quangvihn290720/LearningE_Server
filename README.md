# LearningE Server

Máy chủ của ứng dụng học tiếng Anh LearningE, viết 100% bằng **Java 21 + Spring Boot 4**, giao tiếp gRPC.
Sáu dịch vụ nhỏ, hai loại kho dữ liệu: **SQLite** là nguồn sự thật (tài khoản, tiến độ học), **MongoDB** là kho phụ
dựng lại được (nội dung, bảng xếp hạng, số liệu).

```text
            app Android (gRPC)
                   │  :50051 — cổng DUY NHẤT mở ra ngoài
             ┌─────▼─────┐
             │   edge    │  xác thực token, giới hạn tần suất, chuyển tiếp
             └─┬─┬─┬─┬─┬─┘
      ┌────────┘ │ │ │ └──────────┐
  ┌───▼──┐ ┌─────▼┐│┌▼───────┐ ┌──▼─────┐
  │ auth │ │ sync │││content │ │insight │
  └───┬──┘ └──┬───┘│└───┬────┘ └───▲────┘
   auth.db  sync.db│  MongoDB      │
      │       │  ┌─▼───────────┐   │
      │       │  │gamification │   │
      │       │  └──────▲──────┘   │
      └───────┴─ outbox ┴──────────┘   sự kiện: giao ít nhất một lần, bên nhận khử trùng
```

Muốn hiểu code: đọc [docs/GIAI-THICH-CODE.md](docs/GIAI-THICH-CODE.md). Triển khai, sao lưu, theo dõi:
[docs/VAN-HANH.md](docs/VAN-HANH.md). Quy ước làm việc: [CONTRIBUTING.md](CONTRIBUTING.md).

## Chạy nhanh

**Cách 1 — Docker Compose (giống lúc demo).** Cần Docker Desktop.

```powershell
powershell -ExecutionPolicy Bypass -File deploy/gen-env.ps1        # một lần: sinh khóa vào deploy/.env
docker compose -f deploy/docker-compose.yml up --build -d --wait
docker compose -f deploy/docker-compose.yml run --rm content --ops=import --dir=/content-data
```

Emulator Android gọi `10.0.2.2:50051` (h2c, chỉ bản debug). Dừng: `docker compose -f deploy/docker-compose.yml down`
(thêm `-v` để xóa dữ liệu).

**Cách 2 — một JVM, để phát triển.** Cần JDK 17 trở lên để chạy Gradle (Gradle tự tải JDK 21) và một MongoDB.

```powershell
docker run -d --name learninge-mongo -p 127.0.0.1:27017:27017 mongo:8.0.38
./gradlew :all-in-one:bootRun --args=--all
```

Không có Mongo thì đăng ký, đăng nhập, đồng bộ vẫn chạy; bảng xếp hạng và nội dung trả `UNAVAILABLE`.

**Thử bằng Bruno ≥ 4.3:** Open Collection → thư mục [bruno/](bruno), chọn môi trường `Local` hoặc `VPS`, điền biến rồi
chạy `Login`. Đổi proto thì chép lại `proto/src/main/proto/learninge/app` vào `bruno/protos/learninge/app`.

**Thử bằng grpcurl** (bật `LEARNINGE_GRPC_REFLECTION=true` trong `deploy/.env` khi dev, hoặc dùng `-import-path`):

```bash
grpcurl -plaintext 127.0.0.1:50051 grpc.health.v1.Health/Check
grpcurl -plaintext -H 'x-client-version: 1' -d '{"email":"a@b.co","password":"abc12345","nickname":"anh01",
  "display_name":"Anh","security_question_id":1,"security_answer":"Hà Nội"}' \
  127.0.0.1:50051 learninge.app.v1.AuthService/Register
grpcurl -plaintext -H 'x-client-version: 1' -H 'authorization: Bearer <token>' \
  127.0.0.1:50051 learninge.app.v1.AuthService/GetMe
```

## Kiểm thử

```powershell
./gradlew build                    # biên dịch, định dạng, toàn bộ test (cần Docker cho MongoDB)
./gradlew :sync:test               # một dịch vụ
./gradlew spotlessApply            # bỏ import thừa, khoảng trắng cuối dòng
./gradlew jacocoTestReport         # độ phủ: */build/reports/jacoco (build chặn khi gói domain < 80% nhánh)
./gradlew dependencyCheckAggregate # quét lỗ hổng, cần biến NVD_API_KEY
```

Test chạy **dịch vụ thật**: Spring + Netty thật trên cổng ngẫu nhiên, SQLite thật (file tạm), MongoDB thật
(Testcontainers), đồng hồ giả. `all-in-one` có test đầu-cuối đi hết một vòng như app.

Thêm hoặc nâng thư viện thì phải cập nhật mã băm, nếu không build dừng với "Dependency verification failed":

```powershell
./gradlew --write-verification-metadata sha256 build
```

Lệnh này chỉ ghi tệp nhị phân của hệ điều hành đang chạy; nâng `protoc` thì thêm cả bản `linux-x86_64` cho CI/Docker.

## Cấu hình

Mọi biến nằm trong [deploy/.env.example](deploy/.env.example) và các tệp `<dịch vụ>/src/main/resources/<dịch vụ>.yml`.
Quan trọng nhất:

| Biến | Ai giữ | Ghi chú |
|---|---|---|
| `LEARNINGE_INTERNAL_TOKEN` | cả 6 | ≥ 32 ký tự; thiếu thì không khởi động |
| `LEARNINGE_SERVER_KEY` | chỉ auth | base64 ≥ 32 byte; `APP_ENV=prod` bắt buộc |
| `LEARNINGE_PSEUDONYM_KEY` | auth, sync | base64 ≥ 32 byte, **phải giống nhau** ở hai dịch vụ |
| `LEARNINGE_MONGO_URI`, `LEARNINGE_MONGO_DB` | content, gamification, insight | mỗi dịch vụ chỉ có user của mình |
| `AUTH_DB_PATH`, `SYNC_DB_PATH` | auth, sync | phải nằm trên volume bền vững |
| `LISTEN_HOST`, `LISTEN_PORT` | cả 6 | mặc định loopback |
| `EDGE_TRUST_PROXY`, `EDGE_TRUSTED_PROXIES`, `EDGE_CLIENT_IP_HEADER` | edge | **bắt buộc bật khi đứng sau proxy/tunnel**, nếu không mọi người dùng chung một IP và chặn lẫn nhau; header phải là header proxy đó tự ghi |
| `LEARNINGE_BACKUP_DIR` | auth, sync | nơi sao lưu tự động; rỗng = tắt |
| `TOKEN_TTL_DAYS`, `TOKEN_MAX_AGE_DAYS` | auth | 30 ngày không dùng, tối đa 180 ngày |
| `EDGE_DOMAIN` | Caddy | chỉ với `docker-compose.tls.yml` |
| `LEARNINGE_IMAGES`, `LEARNINGE_TAG` | compose | chỉ trên VPS: kho image và commit đang chạy; `gen-env.sh prod` và `deploy.sh` tự ghi |
| `APP_ENV` | cả 6 | `dev` hoặc `prod` |

## Vận hành

Đầy đủ ở [docs/VAN-HANH.md](docs/VAN-HANH.md). Chạy bằng chính image của dịch vụ, không mở cổng:

```bash
C="docker compose -f deploy/docker-compose.yml"
$C run --rm content --ops=import --dir=/content-data        # nạp nội dung
$C run --rm gamification --ops=rebuild                      # dựng lại bảng xếp hạng từ SQLite
$C run --rm gamification --ops=rebuild --clean              # như trên, xóa kho trước (sau khi khôi phục)
$C run --rm auth --ops=backup --out=/backup                 # sao lưu ngay (mỗi ngày tự sao lưu một lần)
$C run --rm --no-deps sync --ops=restore --from=/backup/…   # khôi phục (dừng dịch vụ trước)
$C run --rm -T insight --ops=report > report.html           # số liệu ngày và từng RPC
$C run --rm -T sync --ops=outbox-list                       # hàng đợi và sự kiện "chết"
$C run --rm auth --ops=outbox-requeue --event-id=<id>       # cho sự kiện "chết" chạy lại
$C logs -f edge                                             # log JSON, lọc theo request_id
```

Thêm, sửa, xóa nội dung học: xem [docs/NOI-DUNG-HOC.md](docs/NOI-DUNG-HOC.md).

Trước mỗi lần đổi lược đồ SQLite, dịch vụ tự tạo `<file>.bak-<phiên bản>`. Hoàn tác = dừng dịch vụ, chép file đó lại.

Hàng đợi sự kiện đứng thì log có `outbox_retry` (mỗi phút một dòng) và `outbox_pending` (chờ quá 5 phút hoặc quá
10.000 dòng). Log mỗi container tối đa 30 MB.

## Giới hạn đã biết
- MongoDB ghim 8.0.38 (20/11/2026), ngoại lệ của quy tắc phát hành ≥ 2 tuần: bản 8.0.34 trong tài liệu không khởi
  động trên Linux kernel ≥ 6.19 (SERVER-121912), kernel của máy CI GitHub hiện nay.
- Một bản edge duy nhất: cache token và bộ đếm tần suất nằm trong bộ nhớ.
- Nơi triển khai (DEC-023): dự kiến Azure for Students, 2 GB; chưa dựng. Quy trình ở docs/VAN-HANH.md mục 2.
- T01, X01, J01, E01 trong `content-data/` còn là dữ liệu mẫu, chờ bộ chính thức.
