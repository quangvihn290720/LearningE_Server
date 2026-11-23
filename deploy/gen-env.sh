#!/usr/bin/env bash
# Tạo deploy/.env với khóa ngẫu nhiên; không ghi đè file đã có.
set -euo pipefail
cd "$(dirname "$0")"
[ -e .env ] && { echo "deploy/.env đã tồn tại, không ghi đè"; exit 1; }
key() { openssl rand -base64 32; }
password() { openssl rand -hex 24; }
umask 077
while IFS= read -r line; do
  case "$line" in
    LEARNINGE_INTERNAL_TOKEN=|LEARNINGE_SERVER_KEY=|LEARNINGE_PSEUDONYM_KEY=) echo "$line$(key)" ;;
    MONGO_*_PASSWORD=) echo "$line$(password)" ;;
    *) echo "$line" ;;
  esac
done < .env.example > .env
echo "Đã tạo deploy/.env"
