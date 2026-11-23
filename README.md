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

Muốn hiểu code: đọc [docs/GIAI-THICH-CODE.md](docs/GIAI-THICH-CODE.md). Quy ước làm việc: [CONTRIBUTING.md](CONTRIBUTING.md).

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
./gradlew jacocoTestReport         # độ phủ: */build/reports/jacoco
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
| `EDGE_TRUST_PROXY`, `EDGE_TRUSTED_PROXIES` | edge | chỉ bật khi đứng sau proxy/tunnel |
| `APP_ENV` | cả 6 | `dev` hoặc `prod` |

## Vận hành

Chạy bằng chính image của dịch vụ, không mở cổng:

```bash
C="docker compose -f deploy/docker-compose.yml"
$C run --rm content --ops=import --dir=/content-data        # nạp nội dung
$C run --rm gamification --ops=rebuild                      # dựng lại bảng xếp hạng từ SQLite
$C run --rm auth --ops=backup --out=/data/backup            # sao lưu auth.db (tương tự sync)
$C run --rm auth --ops=outbox-requeue --event-id=<id>       # cho sự kiện "chết" chạy lại
$C logs -f edge                                             # log JSON, lọc theo request_id
```

Sửa nội dung trong `content-data/` phải **tăng `version`** của bộ/đề, nếu không lệnh import báo lỗi và không ghi gì.

Trước mỗi lần đổi lược đồ SQLite, dịch vụ tự tạo `<file>.bak-<phiên bản>`. Hoàn tác = dừng dịch vụ, chép file đó lại.

## Giới hạn đã biết
- MongoDB ghim 8.0.38 (20/11/2026), ngoại lệ của quy tắc phát hành ≥ 2 tuần: bản 8.0.34 trong tài liệu không khởi
  động trên Linux kernel ≥ 6.19 (SERVER-121912), kernel của máy CI GitHub hiện nay.
- Một bản edge duy nhất: cache token và bộ đếm tần suất nằm trong bộ nhớ.
- Nơi triển khai (DEC-023) chưa chốt; TLS kết thúc ở lớp phía trước edge.
- `content-data/` là dữ liệu mẫu nhỏ, chờ bộ chính thức.
- Chưa làm seed tài khoản demo (`LEARNINGE_DEMO_SEED`).
