#!/usr/bin/env bash
# Tạo deploy/.env với khóa ngẫu nhiên; không ghi đè file đã có.
#   bash deploy/gen-env.sh                          máy phát triển
#   bash deploy/gen-env.sh prod api.example.com     VPS: chế độ gộp sau Caddy, image do CI dựng
set -euo pipefail
cd "$(dirname "$0")"
[ -e .env ] && { echo "deploy/.env đã tồn tại, không ghi đè"; exit 1; }
mode="${1:-dev}"
domain="${2:-}"
case "$mode" in
  dev) ;;
  prod) [[ "$domain" =~ ^([a-z0-9-]+\.)+[a-z]{2,}$ ]] || { echo "Cách dùng: gen-env.sh prod api.example.com"; exit 1; } ;;
  *) echo "Cách dùng: gen-env.sh [prod <tên miền>]"; exit 1 ;;
esac
key() { openssl rand -base64 32; }
password() { openssl rand -hex 24; }
for_prod() {
  case "$1" in
    APP_ENV=*) echo "APP_ENV=prod" ;;
    EDGE_DOMAIN=*) echo "EDGE_DOMAIN=$domain" ;;
    LEARNINGE_IMAGES=*) echo "LEARNINGE_IMAGES=${LEARNINGE_IMAGES:-ghcr.io/quangvihn290720/learninge}" ;;
    *) echo "$1" ;;
  esac
}
umask 077
{
  while IFS= read -r line; do
    case "$line" in
      LEARNINGE_INTERNAL_TOKEN=|LEARNINGE_SERVER_KEY=|LEARNINGE_PSEUDONYM_KEY=) echo "$line$(key)" ;;
      MONGO_*_PASSWORD=) echo "$line$(password)" ;;
      *) if [ "$mode" = prod ]; then for_prod "$line"; else echo "$line"; fi ;;
    esac
  done < .env.example
  # Trong thư mục deploy/, mọi lệnh "docker compose …" dùng hai tệp này, không cần -f.
  [ "$mode" = dev ] || echo "COMPOSE_FILE=docker-compose.small.yml:docker-compose.tls.yml"
} > .env
echo "Đã tạo deploy/.env"
