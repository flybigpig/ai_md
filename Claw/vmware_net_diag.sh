#!/usr/bin/env bash
# VMware 下 AOSP 下载网速诊断（只读，不修改任何配置）
# 用法: bash vmware_net_diag.sh
set -u

echo "===== 1. 网络适配器与驱动 ====="
for dev in /sys/class/net/*; do
  ifc=$(basename "$dev")
  [ "$ifc" = "lo" ] && continue
  drv=$(readlink "$dev/device/driver" 2>/dev/null | sed 's#.*/##')
  spd=$(cat "$dev/speed" 2>/dev/null || echo "?")
  echo "  $ifc : driver=$drv speed=${spd}Mbps"
done
echo "  说明: driver=e1000/e1000e 是模拟网卡(慢)，vmxnet3 才是半虚拟化(快)"

echo "===== 2. VMware Tools 状态 ====="
if command -v vmware-toolbox-cmd >/dev/null 2>&1; then
  vmware-toolbox-cmd -v 2>/dev/null && echo "  open-vm-tools 已安装 (vmxnet3 需要它)"
else
  echo "  !! 未检测到 vmware-toolbox-cmd，可能没装 open-vm-tools"
fi

echo "===== 3. 当前 DNS 配置 ====="
grep -E '^(nameserver|options)' /etc/resolv.conf 2>/dev/null || echo "  (无 resolv.conf 内容)"

echo "===== 4. 主网卡 offload 状态 ====="
main=$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="dev") print $(i+1)}' | head -1)
[ -z "$main" ] && main=eth0
echo "  主网卡: $main"
ethtool -k "$main" 2>/dev/null | grep -E 'tcp-segmentation|generic-receive|large-receive' || echo "  (ethtool 不可用，跳过)"

echo "===== 5. 清华镜像 速度 + 建连耗时(含 DNS) ====="
url="https://mirrors.tuna.tsinghua.edu.cn/AOSP/platform/manifest.git/clone.bundle"
curl -s -o /dev/null -w '  速度=%{speed_download} B/s  建连=%{time_connect}s  DNS解析=%{time_namelookup}s  总耗时=%{time_total}s\n' --max-time 25 "$url" || echo "  测试失败/超时"
echo "  判断: DNS解析>0.2s 说明 DNS 慢; 建连慢说明链路/NAT 慢; 速度低说明镜像或 offload 问题"

echo "===== 完成（本脚本只读，未修改任何配置）====="
