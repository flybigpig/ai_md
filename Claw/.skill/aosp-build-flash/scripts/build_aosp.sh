#!/usr/bin/env bash
# build_aosp.sh — 标准化 AOSP 编译脚本（Android 14，Linux 编译机）
# 用法: build_aosp.sh <lunch_target> [module|full]
# 示例: build_aosp.sh sdk_phone_x86_64-eng full
#       build_aosp.sh aosp_arm64-eng services
set -euo pipefail

LUNCH_TARGET="${1:-sdk_phone_x86_64-eng}"
BUILD_WHAT="${2:-full}"                 # full | <module名> | <目录mmm>
JOBS="${JOBS:-$(nproc)}"

echo "[*] target=$LUNCH_TARGET what=$BUILD_WHAT jobs=$JOBS"

# 0) 资源兜底：内存不足时启用交换（需 root，32G）
FREE_MB=$(awk '/MemFree|MemAvailable/{s+=$2} END{print int(s/1024)}' /proc/meminfo 2>/dev/null || echo 0)
if [ "$FREE_MB" -lt 8192 ] && [ "$(id -u)" -eq 0 ]; then
  echo "[*] low mem (${FREE_MB}MB free), ensure swap >= 32G"
  if ! swapon --show | grep -q '/swapfile'; then
    fallocate -l 32G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  fi
fi

# 1) 环境
source build/envsetup.sh
lunch "$LUNCH_TARGET"

# 2) 编译
export USE_NINJA=true
export JAVAC_STACK_SIZE=4M
mkdir -p out/logs
if [ "$BUILD_WHAT" = "full" ]; then
  make -j"$JOBS" 2>&1 | tee out/logs/build_$(date +%Y%m%d_%H%M).log
else
  m "$BUILD_WHAT" 2>&1 | tee out/logs/build_$(date +%Y%m%d_%H%M).log
fi

echo "[+] done: $(date)"
