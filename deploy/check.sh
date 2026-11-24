#!/usr/bin/env bash
# Kiểm máy chủ từ bên ngoài container; chạy bằng cron mỗi 10 phút:
#   */10 * * * * /opt/LearningE_Server/deploy/check.sh
# In ra vấn đề và thoát 1. Có ALERT_WEBHOOK_URL (Discord, Slack…) thì gửi khi tình trạng ĐỔI, không gửi lặp.
set -uo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1
PROJECT="${COMPOSE_PROJECT_NAME:-learninge}"
SINCE="${CHECK_SINCE:-10m}"
STATE="${CHECK_STATE_FILE:-${TMPDIR:-/tmp}/learninge-check.state}"
problems=()

mapfile -t containers < <(docker ps -a --filter "label=com.docker.compose.project=$PROJECT" \
  --filter "label=com.docker.compose.oneoff=False" --format '{{.Names}}')
[ "${#containers[@]}" -gt 0 ] || problems+=("không có container nào của $PROJECT")
for c in "${containers[@]}"; do
  state=$(docker inspect "$c" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}')
  case "$state" in
    running/healthy|running/none) ;;
    *) problems+=("$c: $state"); continue ;;
  esac
  log=$(docker logs --since "$SINCE" "$c" 2>&1 | grep -E '"level":"ERROR"|outbox_pending|outbox_retry' || true)
  [ -z "$log" ] || problems+=("$c: $(wc -l <<< "$log" | tr -d ' ') dòng ERROR/outbox trong $SINCE")
done

# Bản sao lưu mới nhất của auth và sync không được quá 26 giờ.
container_of() {
  docker ps -q --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.service=$1" | head -1
}
for service in auth sync; do
  holder=$(container_of "$service")
  [ -n "$holder" ] || holder=$(container_of edge)          # chế độ gộp
  [ -n "$holder" ] || continue
  fresh=$(docker exec "$holder" find /backup -name "${service}-*.db" -mmin -1560 2>/dev/null | head -1)
  [ -n "$fresh" ] || problems+=("$service: không có bản sao lưu trong 26 giờ")
done

used=$(df -P . | awk 'NR==2 {gsub("%","",$5); print $5}')
[ "${used:-0}" -lt "${CHECK_DISK_PERCENT:-85}" ] || problems+=("đĩa đã dùng ${used}%")

if [ "${#problems[@]}" -eq 0 ]; then message="OK"; else message=$(printf '%s\n' "${problems[@]}"); fi
echo "$message"
# So sánh không kể số đếm, để "3 dòng" thành "5 dòng" không bị coi là đổi tình trạng.
summary=$(sed -E 's/[0-9]+ dòng/dòng/; s/[0-9]+%/%/' <<< "$message")
previous=$(cat "$STATE" 2>/dev/null || echo OK)
if [ "$summary" != "$previous" ]; then
  printf '%s' "$summary" > "$STATE"
  if [ -n "${ALERT_WEBHOOK_URL:-}" ]; then
    text=$(printf 'LearningE %s: %s' "$(hostname)" "$message" | sed 's/\\/\\\\/g; s/"/\\"/g' | awk '{printf "%s\\n", $0}')
    # Qua stdin: đối số dòng lệnh bị đổi bảng mã trên Windows.
    printf '{"text":"%s","content":"%s"}' "$text" "$text" \
      | curl -fsS -m 10 -H 'content-type: application/json' --data-binary @- "$ALERT_WEBHOOK_URL" >/dev/null \
      || echo "không gửi được cảnh báo"
  fi
fi
[ "$message" = "OK" ]
