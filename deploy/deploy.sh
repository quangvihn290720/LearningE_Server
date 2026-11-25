#!/usr/bin/env bash
# Đưa một commit của main lên VPS (chế độ gộp, deploy/.env tạo bằng "gen-env.sh prod"):
#   deploy/deploy.sh            commit mới nhất của main
#   deploy/deploy.sh <commit>   commit cụ thể, kể cả để quay lại bản cũ
# Thứ tự: kéo image → sao lưu → đổi mã nguồn → chạy → nạp nội dung → gọi thử qua TLS. Image chưa có thì dừng trước
# khi đụng tới bản đang chạy.
set -euo pipefail

OPS_JVM="-Xmx128m -Dorg.sqlite.tmpdir=/app/native -Dio.netty.native.workdir=/app/native"

fail() {
  echo "LỖI: $*" >&2
  exit 1
}

env_value() {
  sed -n "s/^$1=//p" .env | tail -1
}

ops() {
  docker compose exec -T -e JAVA_TOOL_OPTIONS="$OPS_JVM" edge \
    java org.springframework.boot.loader.launch.JarLauncher "$@"
}

# 415 qua HTTP/2 là trả lời của gRPC khi gọi bằng HTTP thường: TLS, Caddy và edge đều thông.
answers() {
  local extra=() got
  [ -z "${DEPLOY_CURL_OPTS:-}" ] || read -ra extra <<< "$DEPLOY_CURL_OPTS"
  got=$(curl -s -m 10 --http2 "${extra[@]}" -o /dev/null -w '%{http_code} %{http_version}' "https://$1/" || true)
  [ "$got" = "415 2" ]
}

# Cả script nằm trong một hàm: git đổi chính tệp này giữa chừng cũng không sao.
main() {
  cd "$(dirname "$0")"
  [ -f .env ] || fail "chưa có deploy/.env, chạy: bash deploy/gen-env.sh prod <tên miền>"
  local images domain previous commit i
  images=$(env_value LEARNINGE_IMAGES)
  domain=$(env_value EDGE_DOMAIN)
  previous=$(env_value LEARNINGE_TAG)
  [ -n "$domain" ] && [ -n "$(env_value COMPOSE_FILE)" ] || fail "deploy/.env không phải bản prod"
  [ -z "$(git status --porcelain)" ] || fail "mã nguồn trên máy có sửa đổi, xem: git status"
  git fetch -q origin main
  commit=$(git rev-parse --verify -q "${1:-origin/main}^{commit}") || fail "không có commit ${1:-}"
  git merge-base --is-ancestor "$commit" origin/main || fail "$commit không nằm trên main"

  echo "== Kéo image $commit"
  docker pull -q "$images/all-in-one:$commit" \
    || fail "chưa có image: CI trên main chưa xanh, hoặc package trên GHCR còn để riêng tư"

  # Bản đang chạy hỏng thì bỏ qua bước này, nếu không chính nó chặn đường quay lại.
  if [ "$(docker compose ps --format '{{.Health}}' edge)" = healthy ]; then
    echo "== Sao lưu trước khi đổi"
    ops --only=auth --ops=backup --out=/backup
    ops --only=sync --ops=backup --out=/backup
  else
    echo "== Bỏ qua sao lưu: edge chưa chạy hoặc đang hỏng"
  fi

  echo "== Chạy"
  git checkout -q --detach "$commit"
  sed -i "s/^LEARNINGE_TAG=.*/LEARNINGE_TAG=$commit/" .env
  if ! docker compose up -d --no-build --remove-orphans --wait --wait-timeout 300; then
    docker compose ps -a
    docker compose logs --tail 60 edge
    fail "bản mới không lên. Quay lại: deploy/deploy.sh $previous. Nếu bản mới đã đổi lược đồ SQLite: docs/VAN-HANH.md mục 2"
  fi
  ops --only=content --ops=import --dir=/content-data

  echo "== Gọi thử https://$domain"
  for i in $(seq 1 20); do
    answers "$domain" && break
    [ "$i" -lt 20 ] || fail "không gọi được https://$domain: kiểm DNS, cổng 80/443, và docker compose logs caddy"
    sleep 3
  done
  docker image prune -af --filter "until=720h" >/dev/null
  echo "Xong: $commit (trước đó: $previous)"
}

main "$@"
exit
