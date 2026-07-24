---
name: aosp-can-selinux
description: Linux SocketCAN / 内核驱动 / DTS 设备树 / SELinux 适配技能。当用户适配 CAN 控制器(M_CAN 等)、写 DTS 节点、配置内核 defconfig 宏、排查 SELinux avc denied、定义 te 域与文件/设备上下文、或用 canutils 调测 CAN 报文时触发。即使用户只说"CAN 驱动怎么写"、"DTS 节点怎么配"、"selinux 报 denied 怎么办"、"defconfig 开哪些宏"、"ip link set can0"、"candump 收不到"，也应触发。内核走 GKI android14-6.1，驱动改动落 vendor 分区。
agent_created: true
---

# aosp-can-selinux — SocketCAN / 内核驱动 / DTS / SELinux

适配顺序（改造类一律按此）：**DTS → 内核 defconfig → 驱动 → SELinux → 刷 vendor → 验证**。

## 何时使用

- 车载 CAN / 硬件控制器驱动适配。
- DTS 设备树节点编写与pinmux。
- 内核宏开关、SELinux 域/上下文定义。
- canutils 收发测试、调试验证。

## 一、SocketCAN 协议栈与用户态

```
应用(cansend/candump) ── socket(AF_CAN, SOCK_RAW, CAN_RAW)
   ↓
内核 net/can/ (af_can.c, raw.c, bcm.c)
   ↓
CAN 协议驱动 (drivers/net/can/ m_can/m_can.c, m_can_platform.c)
   ↓
CAN 控制器 (SoC M_CAN IP)
```
用户态工具（外部仓 `canutils` / `iproute2`）：
```bash
ip link set can0 type can bitrate 500000
ip link set can0 up
candump can0                 # 监听
cansend can0 123#DEADBEEF   # 发送
canplayer / cangen           # 回放/压测
```

## 二、DTS 节点模板（见 references/can_dts_template.dts）

- 节点挂到 `&soc`，配 `compatible = "bosch,m_can"`、寄存器、`clocks`、`interrupts`、`bosch,mram-cfg`。
- pinmux 在 `pinctrl` 子节点配 `CAN0_TX`/`CAN0_RX` 管脚。
- `status = "okay"` 启用。

## 三、内核 defconfig 宏（GKI 外挂模块需 `=m`）

```
CONFIG_CAN=y
CONFIG_CAN_RAW=y
CONFIG_CAN_BCM=y
CONFIG_CAN_DEV=y
CONFIG_CAN_M_CAN=y
CONFIG_CAN_M_CAN_PLATFORM=y
CONFIG_CAN_VCAN=y          # 虚拟 CAN 调测
```
GKI 内置符号不可改，厂商驱动作可加载模块 `=m`，经 `modules_load` 进 vendor。

## 四、SELinux（system/sepolicy）

- **设备节点上下文**：`file_contexts` 给 `/dev/can[0-9]*` 标 `can_device` 类型。
- **域定义**：`can_exec` 可执行、`can_domain` 域、`allow can_domain can_device:chr_file { read write ioctl };`。
- **HAL 关联**：HAL 服务域见 `aosp-hal-treble`；驱动模块加载需 `allow kernel self:capability sys_module;` 及模块文件 `vendor_file` 上下文。
- 模板见 `references/can_selinux.te`。
- 排障：`dmesg | grep avc` 或 `logcat | grep avc`；`cat /sys/fs/selinux/deny` 临时放宽（仅调试）。

## 五、调试与验证步骤

```bash
dmesg | grep -i can            # 驱动 probe 是否成功
ip -details link show can0     # 状态/bitrate/error count
candump can0 & cansend can0 123#11223344
ip link set can0 type can restart-ms 100   # 总线关闭自动恢复
# 内核错误: 中断/位填充/ACK 错误 → 查 CAN 收发器供电与终端电阻(120Ω)
```

## 六、踩坑清单

- **DTS 不生效**：`dtb` 未更新 / `make dtbo` 漏编；`ls /proc/device-tree/` 核对节点。
- **CAN 收不到**：终端电阻缺失、bitrate 两端不一致、`ip link` 未 `up`。
- **avc denied**：补 te 后需 `make selinux_policy` 并重刷 `vendor`/`boot`。
- **模块加载失败**：GKI 版本不匹配（模块须与运行内核 `vermagic` 一致），签名缺失。

## 关联

- HAL 服务域 → `aosp-hal-treble`
- 刷 vendor 验证 → `aosp-build-flash`
- 内核 binder 驱动 → `aosp-binder`
- 源码路径坐标 → `aosp-navigator`
