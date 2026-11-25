#!/usr/bin/env bash
# Chép bản sao lưu mới nhất của auth và sync ra ~/learninge-backup trên VPS để máy khác kéo về (pull-backup.ps1).
# Tệp chứa email và mật khẩu đã băm: chỉ chủ đọc được.
set -euo pipefail
cd "$(dirname "$0")"
umask 077
out="$HOME/learninge-backup"
rm -rf "$out"
mkdir "$out"
services=$(docker compose config --services)
for service in auth sync; do
  holder="$service"
  grep -qx "$service" <<< "$services" || holder=edge          # chế độ gộp
  newest=$(docker compose exec -T "$holder" sh -c "ls -1 /backup/$service-*.db | tail -1")
  docker compose cp "$holder:$newest" "$out/" >/dev/null 2>&1
done
chmod 600 "$out"/*.db
cd "$out"
sha256sum ./*.db > SHA256SUMS
