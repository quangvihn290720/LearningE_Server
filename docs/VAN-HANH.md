# Vận hành LearningE Server

Mọi lệnh chạy ở thư mục gốc của repo. Viết gọn:

```bash
C="docker compose -f deploy/docker-compose.yml"
```

Git Bash trên Windows: thêm `export MSYS_NO_PATHCONV=1` trước, nếu không `/backup/…` bị đổi thành đường dẫn Windows.

## 1. Chọn cách chạy

| Cách | Tệp compose | RAM đo được lúc rảnh | Khi nào |
|---|---|---|---|
| Tách 6 dịch vụ | `docker-compose.yml` | khoảng 1,3 GB | máy phát triển, demo, VPS 4 GB |
| Gộp một JVM | `docker-compose.small.yml` | khoảng 0,6 GB | VPS 2 GB |
| Thêm TLS | thêm `-f deploy/docker-compose.tls.yml` sau một trong hai tệp trên | thêm 16 MB | triển khai thật |

Hai cách đầu dùng chung volume dữ liệu. Đổi qua lại: `down` bản cũ (không `-v`) rồi `up` bản mới.

## 2. Triển khai lên VPS

1. VPS Ubuntu, cài Docker Engine và Compose ≥ 2.24. Tường lửa chỉ mở 22, 80, 443.
2. Trỏ bản ghi DNS `A` của tên miền về IP máy. Tên phải có dấu chấm (Java/Android không gửi SNI cho tên như `localhost`).
3. Lấy mã nguồn, chạy `bash deploy/gen-env.sh`, rồi sửa `deploy/.env`:
   - `APP_ENV=prod`
   - `EDGE_DOMAIN=api.example.com`
4. Chạy (VPS 2 GB thì thay tệp đầu bằng `docker-compose.small.yml`):

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.tls.yml up --build -d --wait
```

5. Nạp nội dung: xem mục 6.
6. Bản release của app đặt `GRPC_HOST=api.example.com`, `GRPC_PORT=443`, `GRPC_TLS=true`.

Tệp TLS tự bật `EDGE_TRUST_PROXY` và gỡ cổng 50051 khỏi máy chủ. Caddy tự xin, gia hạn chứng chỉ, và ghi đè
`X-Forwarded-For` nên người dùng không giả được IP. Thử trên máy cá nhân: `CADDY_TLS="tls internal"`.

**Cất `deploy/.env` ở nơi an toàn ngoài máy chủ.** Mất `LEARNINGE_PSEUDONYM_KEY` thì số liệu cũ không nối được với
người dùng; mất `LEARNINGE_SERVER_KEY` thì bộ đếm khóa đăng nhập về 0; mất mật khẩu Mongo thì không vào được kho phụ.

Dùng tunnel trên máy cá nhân thay cho VPS: chạy `docker-compose.yml`, đặt `EDGE_TRUST_PROXY=true` (Cloudflare thì thêm
`EDGE_CLIENT_IP_HEADER=cf-connecting-ip`). Quên bước này thì mọi người dùng bị tính chung một IP.

## 3. Sao lưu

`auth` và `sync` tự sao lưu mỗi ngày vào volume riêng, giữ 14 bản (`LEARNINGE_BACKUP_INTERVAL_HOURS`,
`LEARNINGE_BACKUP_KEEP`). Mỗi bản được kiểm trước khi mang tên chính thức. MongoDB không cần sao lưu: nội dung nạp lại
từ `content-data`, bảng xếp hạng dựng lại từ SQLite; chỉ mất nhật ký và số liệu của `insight`.

Các bản này nằm **cùng máy**. Chép ra máy khác mỗi ngày (tệp chứa email và mật khẩu đã băm, giữ như bí mật):

```bash
$C cp auth:/backup ./backup-auth
$C cp sync:/backup ./backup-sync
```

Sao lưu ngay: `$C run --rm auth --ops=backup --out=/backup` (tương tự `sync`).

## 4. Khôi phục

Lệnh từ chối nếu dịch vụ còn chạy hoặc bản sao lưu hỏng. Kho đang dùng được giữ lại với đuôi `.before-restore-…`.

```bash
$C exec sync ls /backup
$C stop sync
$C run --rm --no-deps sync --ops=restore --from=/backup/sync-20261124-111650.db
$C up -d --wait
$C run --rm auth --ops=outbox-replay --destination=sync
$C run --rm gamification --ops=rebuild --clean
```

Khôi phục `auth` làm tương tự, bỏ dòng `outbox-replay`. Điều gì xảy ra:

| Kho | Sau khi khôi phục |
|---|---|
| `sync` | Số thứ tự nhảy tới mốc theo giờ khôi phục để không máy nào bỏ sót thay đổi mới; `data_epoch` đổi nên app tự kéo lại từ đầu và đẩy lại phần server đã mất. Dữ liệu chỉ mất nếu máy người dùng không mở lại app. XP cũ hơn 7 ngày được đẩy lại sẽ tính 0 điểm, nên đừng khôi phục bản cũ hơn một tuần |
| `auth` | Mọi máy phải đăng nhập lại. Tài khoản tạo sau bản sao lưu bị mất; tài khoản đã xóa sau bản sao lưu sống lại (đồng bộ của họ vẫn bị từ chối): phải xóa lại |
| Cả hai | Nên lấy hai bản cùng thời điểm |

Diễn tập ít nhất một lần trước buổi chấm, trên một bản sao: thêm `-p learninge-thu` vào lệnh compose.

## 5. Theo dõi

`deploy/check.sh` kiểm container, lỗi trong log, hàng đợi sự kiện, tuổi bản sao lưu, dung lượng đĩa. Cron mỗi 10 phút:

```bash
*/10 * * * * ALERT_WEBHOOK_URL=https://… /opt/LearningE_Server/deploy/check.sh >/dev/null
```

Có `ALERT_WEBHOOK_URL` (Discord, Slack) thì gửi một tin khi tình trạng đổi. Không gửi dữ liệu người dùng.

| Muốn xem | Lệnh |
|---|---|
| Số liệu ngày, độ trễ và lỗi từng RPC (trang HTML) | `$C run --rm -T insight --ops=report --days=30 > report.html` |
| Hàng đợi sự kiện, sự kiện "chết" | `$C run --rm -T sync --ops=outbox-list` (và `auth`) |
| Cho sự kiện "chết" chạy lại sau khi sửa lỗi | `$C run --rm sync --ops=outbox-requeue --all-dead` hoặc `--event-id=<id>` |
| Một lời gọi cụ thể | `$C logs edge \| grep <request_id>` |

## 6. Nội dung và dữ liệu demo

```bash
$C run --rm content --ops=import --dir=/content-data
$C run --rm -e LEARNINGE_DEMO_SEED=true -e LEARNINGE_DEMO_PASSWORD='<mật khẩu>' edge --ops=demo-seed
```

`demo-seed` tạo `demo1@learninge.test`, `demo2@learninge.test` (cùng mật khẩu trên) và 8 người học giả cho bảng xếp
hạng, đi qua đúng RPC như app. Chạy lại thì bỏ qua người đã có. `APP_ENV=prod` phải thêm `--confirm-prod`.

## 7. Lệnh vận hành ở chế độ gộp

Cùng các lệnh, chạy trong container đang có, thêm `--only=<dịch vụ>`:

```bash
S="docker compose -f deploy/docker-compose.small.yml"
ops() { $S exec -T -e JAVA_TOOL_OPTIONS="-Xmx128m -Dorg.sqlite.tmpdir=/app/native -Dio.netty.native.workdir=/app/native" \
  edge java org.springframework.boot.loader.launch.JarLauncher "$@"; }
ops --only=content --ops=import --dir=/content-data
ops --only=gamification --ops=rebuild
ops --only=auth --ops=outbox-list
```

Riêng khôi phục cần dừng JVM trước:

```bash
$S stop edge
$S run --rm --no-deps edge --only=sync --ops=restore --from=/backup/sync-….db
$S up -d --wait
```
