#!/usr/bin/env bash
# flash_emulator.sh — 启动 sdk_phone_x86_64 模拟器并验证 KVM
# 前提: 已 lunch sdk_phone_x86_64-eng 且 make 完成(含 emulator)
set -euo pipefail

echo "[*] check /dev/kvm"
if [ -e /dev/kvm ]; then
  echo "[+] KVM available: $(ls -l /dev/kvm)"
  ACCEL="-accel on"
else
  echo "[!] no /dev/kvm, fallback to software (slow)"
  ACCEL="-accel off"
fi

# 可选: 指定内核/ramdisk 用于驱动/DTS 验证
KERNEL=${KERNEL:-}
RAMDISK=${RAMDISK:-}
EXTRA=()
[ -n "$KERNEL" ]  && EXTRA+=( -kernel "$KERNEL" )
[ -n "$RAMDISK" ] && EXTRA+=( -ramdisk "$RAMDISK" )

emulator \
  $ACCEL \
  -gpu swiftshader \
  -show-kernel \
  -no-snapshot \
  "${EXTRA[@]}" \
  "$@"

# 验证: 另开终端
#   adb shell getprop ro.build.version.release
#   adb shell dmesg | grep -i <your_driver>
