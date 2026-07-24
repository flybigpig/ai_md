# 高通 8295 车机证书体系（应用层 / 系统层）

车机"证书"不是单一实体，而是以 **OEM 密钥库**为根、向下派生出三类用途：
1. 系统签名证书（让 App 拿到 signature/system 级权限，含 Car API）
2. AVB / vbmeta 证书（secure boot 启动验签）
3. 网络 & 设备身份证书（TLS、证书固定、Keystore Attestation）

---

## 一、系统签名证书（platform / release key）—— 与 Car API 直接相关

### 1.1 密钥形态与位置
- AOSP 默认测试密钥：`build/target/product/security/`，含
  `platform.pk8` / `platform.x509.pem`、`shared.pk8`、`media.pk8`、`releasekey.pk8` 等。
- **量产车机用的是 OEM 自己的密钥**（高通 BSP 树私有 repo 内），绝不会用 AOSP 默认测试密钥。拿不到这把 key，普通三方 App 永远无法获得 `signature` 级权限。

### 1.2 为什么它和 Car API 绑定
前面说过的 `CAR_SPEED`、`CAR_POWERTRAIN`、`CAR_EXTERIOR_ENVIRONMENT` 等车辆信号权限，保护级别是
`signature | system` 或 `system`。只有当 App 用**与 `CarService` 相同的 platform 密钥签名**，或作为
**特权预装应用（priv-app）**时，才能拿到完整车辆信号。

### 1.3 两种落地方式
**方式 A：sharedUserId + platform 签名（系统应用）**
```xml
<!-- AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    android:sharedUserId="android.uid.system">
```
再用 OEM platform 密钥签名。风险：与系统共用 uid，权限极大，OEM 通常只给自家或深度合作 App。

**方式 B：priv-app + privapp-permissions（推荐给车载 App）**
- 把 APK 放到 `/system/priv-app/<YourApp>/`
- 在 `etc/permissions/privapp-permissions-<yourapp>.xml` 中显式声明需要的 signature 权限：
```xml
<permissions>
  <privapp-permissions package="com.example.car8295">
    <permission name="android.car.permission.CAR_SPEED"/>
    <permission name="android.car.permission.CAR_POWERTRAIN"/>
  </privapp-permissions>
</permissions>
```
priv-app 不强制 sharedUserId，权限范围可控，是车载预装的标准做法。

### 1.4 签名命令
```bash
# 用 OEM platform 密钥对齐签名（v1+v2+v3）
apksigner sign \
  --key platform.pk8 \
  --cert platform.x509.pem \
  --out app-signed.apk app-unsigned-aligned.apk

# 校验某 APK 的签名证书（确认是否与系统同源）
apksigner verify --print-certs app-signed.apk

# 对比 CarService 的签名，是否与你的 App 一致
adb shell pm dump com.android.car | grep "primaryCpuAbi"   # 辅助
# 更直接：导出两者证书比对 subject
```

### 1.5 开发期绕过（无需 OEM 密钥）
- 在 **AAOS 模拟器**里，`CarService` 自带 mock，普通 `debug` 签名即可跑通 `CarPropertyManager`。
- 真机若暂时拿不到 platform 密钥：先只申请 `normal`/`dangerous` 级车辆权限做 UI 联调，完整信号等 OEM 签名/预装后再验证。

---

## 二、AVB / vbmeta 证书（verified boot，安全启动）

### 2.1 链路回顾
启动链 `PBL → XBL → ABL → boot` 中，ABL 加载 `vbmeta.img` 和各分区镜像时，会用 **OEM AVB 公钥**校验
hash/签名。这是高通 8295 与车规安全（含 QNX Hypervisor 的 `hyp` 分区）强相关的一环。

### 2.2 密钥生成与镜像签名（avbtool）
```bash
# 生成 4096 位 RSA AVB 密钥
avbtool extract_public_key --key avb.pem --output avb_pkmd.bin

# 对各分区加 hash footer
avbtool add_hash_footer \
  --image system.img \
  --partition_name system \
  --partition_size <size> \
  --key avb.pem --algorithm SHA256_RSA4096

# 生成/签名 vbmeta（汇聚各分区描述符）
avbtool make_vbmeta_image \
  --output vbmeta.img \
  --key avb.pem --algorithm SHA256_RSA4096 \
  --include_descriptors_from_image system.img \
  --include_descriptors_from_image boot.img
```

### 2.3 调试注意
- `fastboot flashing lock` 后，任何未用 OEM AVB 密钥签名的镜像都会**拒绝启动**（进 recovery / 变砖风险）。
- 开发板常提供 `verity` 关闭开关或 `userdebug` 解锁状态；量产 `user` build 一律 locked。
- 与高通私有分区（`tz`、`hyp`、`devcfg`、`xbl`）的验签由高通签名体系保证，OEM 一般只控制 `vbmeta` 及以上。

---

## 三、网络 & 设备身份证书

### 3.1 TLS 与证书固定
车载 App 与车厂后端的通信，建议用 `network_security_config.xml` 集中管理：
```xml
<network-security-config>
  <domain-config>
    <domain includeSubdomains="true">api.car-oem.com</domain>
    <pin-set expiration="2027-01-01">
      <pin digest="SHA-256">base64==</pin>
    </pin-set>
  </domain-config>
</network-security-config>
```
注意：车机时钟若未校准（无 RTC / 未联网），证书有效期校验会失败，需在首次联网时兜底处理。

### 3.2 Keystore Attestation / StrongBox
- 用 `KeyGenParameterSpec.Builder(...).setAttestationChallenge(...)` 生成受硬件保护的密钥。
- 8295 上的 Keymaster/StrongBox 由高通 TEE（trustzone）提供，可证明"密钥确实在芯片内生成"，用于设备身份/防克隆。
- 后端用 OEM 下发的 **attestation root CA** 校验 `Certificate` 链。

### 3.3 典型用途
- 后端双向认证（mTLS）：车机持 OEM 设备证书，后端持服务端证书。
- OTA 包校验、远程诊断鉴权、账号绑定防伪。

---

## 四、实操清单（落地前自查）

1. `adb shell pm list packages | grep car` —— 确认是 AAOS 还是定制 Android（决定能否用 `android.car`）。
2. `apksigner verify --print-certs your.apk` —— 确认签名证书来源。
3. 需要完整车辆信号：`priv-app` + `privapp-permissions` + OEM platform 签名。
4. OTA/烧录：确认 AVB 密钥与车机 locked 状态匹配，避免变砖。
5. 网络：时钟同步就绪后再启用严格证书固定。
