#!/usr/bin/env bash
# Chuẩn bị máy Ubuntu 24.04 mới: Docker, swap 2 GB, tự cài bản vá bảo mật, mã nguồn ở /opt/LearningE_Server.
# Chạy lại được nhiều lần. Đăng nhập bằng người dùng thường có sudo:
#   bash setup-vm.sh
# Tường lửa đặt ở nhà cung cấp (Azure NSG): chỉ mở 80, 443, và 22 cho IP của bạn. UFW không chặn được cổng Docker mở.
set -euo pipefail

main() {
  [ "$(id -u)" -ne 0 ] || { echo "Chạy bằng người dùng thường, script tự gọi sudo"; exit 1; }
  # shellcheck disable=SC1091
  . /etc/os-release
  [ "$ID" = ubuntu ] || { echo "Chỉ hỗ trợ Ubuntu"; exit 1; }
  local repo="${LEARNINGE_REPO:-https://github.com/quangvihn290720/LearningE_Server.git}"
  local apt=(sudo DEBIAN_FRONTEND=noninteractive apt-get)

  "${apt[@]}" update -q
  "${apt[@]}" install -y -q ca-certificates curl git openssl unattended-upgrades
  printf 'APT::Periodic::Update-Package-Lists "1";\nAPT::Periodic::Unattended-Upgrade "1";\n' \
    | sudo tee /etc/apt/apt.conf.d/20auto-upgrades >/dev/null

  if ! docker compose version >/dev/null 2>&1; then
    sudo install -m 0755 -d /etc/apt/keyrings
    sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    sudo chmod a+r /etc/apt/keyrings/docker.asc
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc]" \
      "https://download.docker.com/linux/ubuntu $VERSION_CODENAME stable" \
      | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
    "${apt[@]}" update -q
    "${apt[@]}" install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  fi
  sudo systemctl enable --now docker
  sudo usermod -aG docker "$(id -un)"

  if [ -z "$(swapon --show=NAME --noheadings)" ]; then
    sudo fallocate -l 2G /swapfile
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile >/dev/null
    sudo swapon /swapfile
    grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
  fi
  # Chỉ dùng swap khi thật thiếu RAM.
  echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/60-learninge.conf >/dev/null
  sudo sysctl -q -p /etc/sysctl.d/60-learninge.conf

  if [ ! -d /opt/LearningE_Server/.git ]; then
    sudo install -d -o "$(id -un)" -g "$(id -gn)" /opt/LearningE_Server
    git clone -q "$repo" /opt/LearningE_Server
  fi
  echo
  echo "Xong. Đăng xuất rồi đăng nhập lại (để dùng docker không cần sudo), sau đó:"
  echo "  cd /opt/LearningE_Server"
  echo "  bash deploy/gen-env.sh prod <tên miền>"
  echo "  deploy/deploy.sh"
}

main "$@"
