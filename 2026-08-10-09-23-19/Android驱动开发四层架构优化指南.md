# Android 驱动开发四层架构（经典 C-ABI HAL）优化指南

> **适用：** Android 底层驱动开发，经典四层链路
> **内核：** `android14-6.1` / Linux 6.1
> **说明：** 本文基于经典「内核驱动 → HAL → JNI → Framework 服务」四层模板，修复原示例代码缺陷、**补齐缺失文件（HAL 头文件 / 构建脚本 / SELinux / App 层）**，并在末尾给出 Android 14 现代化升级路线（GKI 2.0 + AIDL HAL）。

---

## 核心结论前置

1. **经典四层模板仍然成立**，但 C-ABI HAL（`hw_module_t` / `hw_device_t`）是 Android 8.0 之前的方式。**Android 14 新开发应优先用 AIDL HAL**（见第十一节）。
2. **内核驱动在 Android 14 必须编成可加载模块（`=m`）**，不能编进 GKI 内核二进制（GKI 2.0 冻结）。
3. 你贴的原示例代码有 **3 处必须修复的缺陷**（见第十节），否则编译/运行直接报错。
4. 原模板**缺了 4 个关键文件**：HAL 头文件 `hello.h`、SystemServer 注册、`Android.mk`、SELinux 策略。本文全部补齐。

---

## 四层架构总览

```
┌─────────────────────────────────────────────────────────┐
│  APP 层                                                  │
│  context.getSystemService("hello") → IHelloService       │
└────────────────────────┬────────────────────────────────┘
                         │ Binder IPC
┌────────────────────────┴────────────────────────────────┐
│  Framework 层 (system_server 进程内)                      │
│  HelloService extends IHelloService.Stub                 │
│  └─ 加载 libhello_jni.so，调用 native 方法               │
└────────────────────────┬────────────────────────────────┘
                         │ JNI (同进程 dlopen)
┌────────────────────────┴────────────────────────────────┐
│  HAL 层 (C-ABI，与 SystemServer 同进程)                   │
│  hw_get_module() → hello_device_open() → open("/dev/hello")│
└────────────────────────┬────────────────────────────────┘
                         │ open / read / write (系统调用)
┌────────────────────────┴────────────────────────────────┐
│  内核层                                                   │
│  /dev/hello 字符设备: hello_open/read/write               │
└─────────────────────────────────────────────────────────┘
```

---

## 第一层：内核驱动程序（优化版）

原示例用 `register_chrdev()` + `class_create()`，逻辑可跑，但缺少 `linux/device.h` 头文件，且无并发保护。下面给一个**修正 + 加并发锁**的版本。

**文件：** `drivers/hello/hello.c`

```c
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/fs.h>
#include <linux/cdev.h>
#include <linux/device.h>      // ← 原代码缺：class_create/device_create 需要
#include <linux/uaccess.h>
#include <linux/mutex.h>        // ← 新增：并发保护

#define DEVICE_NAME "hello"
#define CLASS_NAME  "hello_class"

static int hello_major;
static struct class *hello_class;
static struct cdev  hello_cdev;
static int hello_val;          // ← 去掉 static 初始化冗余，统一在 init 里置 0
static DEFINE_MUTEX(hello_lock); // ← 新增：保护 hello_val 的互斥锁

static int hello_open(struct inode *inode, struct file *file)
{
    return 0;
}

static int hello_release(struct inode *inode, struct file *file)
{
    return 0;
}

static ssize_t hello_read(struct file *file, char __user *buf,
                           size_t count, loff_t *offset)
{
    int val;

    if (*offset >= sizeof(val))
        return 0;  // EOF

    mutex_lock(&hello_lock);
    val = hello_val;
    mutex_unlock(&hello_lock);

    if (copy_to_user(buf, &val, sizeof(val)))
        return -EFAULT;

    *offset += sizeof(val);
    return sizeof(val);
}

static ssize_t hello_write(struct file *file, const char __user *buf,
                            size_t count, loff_t *offset)
{
    int val;

    if (count < sizeof(val))
        return -EINVAL;

    if (copy_from_user(&val, buf, sizeof(val)))
        return -EFAULT;

    mutex_lock(&hello_lock);
    hello_val = val;
    mutex_unlock(&hello_lock);

    return sizeof(val);
}

static struct file_operations hello_fops = {
    .owner   = THIS_MODULE,
    .open    = hello_open,
    .release = hello_release,
    .read    = hello_read,
    .write   = hello_write,
};

static int __init hello_init(void)
{
    dev_t devno;

    // 现代做法：用 alloc_chrdev_region 替代 register_chrdev
    if (alloc_chrdev_region(&devno, 0, 1, DEVICE_NAME) < 0) {
        printk(KERN_ALERT "hello: failed to alloc chrdev region\n");
        return -1;
    }
    hello_major = MAJOR(devno);

    hello_class = class_create(THIS_MODULE, CLASS_NAME);
    if (IS_ERR(hello_class)) {
        unregister_chrdev_region(MKDEV(hello_major, 0), 1);
        return PTR_ERR(hello_class);
    }

    cdev_init(&hello_cdev, &hello_fops);
    if (cdev_add(&hello_cdev, devno, 1) < 0) {
        class_destroy(hello_class);
        unregister_chrdev_region(MKDEV(hello_major, 0), 1);
        return -1;
    }

    device_create(hello_class, NULL, devno, NULL, DEVICE_NAME);
    hello_val = 0;
    printk(KERN_INFO "hello: device registered, major=%d\n", hello_major);
    return 0;
}

static void __exit hello_exit(void)
{
    device_destroy(hello_class, MKDEV(hello_major, 0));
    cdev_del(&hello_cdev);
    class_destroy(hello_class);
    unregister_chrdev_region(MKDEV(hello_major, 0), 1);
    printk(KERN_INFO "hello: device unregistered\n");
}

module_init(hello_init);
module_exit(hello_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Hello hardware driver (optimized)");
```

---

## 第二层：HAL 模块（优化版）

**原代码致命缺陷：** `hello_device_close` / `hello_set_val` / `hello_get_val` 在 `hello_device_open` 中被调用，但**使用前未声明**；且缺少 `<string.h>`（memset）、`<unistd.h>`（read/write/close）。下面修正并给出配套的 **HAL 头文件**（原模板完全缺失）。

### 2.1 HAL 头文件（原模板缺失，必须补）

**文件：** `hardware/libhardware/include/hardware/hello.h`

```c
#ifndef ANDROID_HELLO_INTERFACE_H
#define ANDROID_HELLO_INTERFACE_H

#include <hardware/hardware.h>

#define HELLO_HARDWARE_MODULE_ID "hello"

struct hello_device_t {
    struct hw_device_t common;
    int fd;  // /dev/hello 文件描述符

    int (*set_val)(struct hello_device_t *dev, int val);
    int (*get_val)(struct hello_device_t *dev, int *val);
};

struct hello_module_t {
    struct hw_module_t common;
};

#endif  // ANDROID_HELLO_INTERFACE_H
```

### 2.2 HAL 实现（修正版）

**文件：** `hardware/libhardware/modules/hello/hello.c`

```c
#include <hardware/hardware.h>
#include <hardware/hello.h>     // ← 引入上面的头文件
#include <fcntl.h>
#include <errno.h>
#include <string.h>             // ← 新增：memset 需要
#include <unistd.h>             // ← 新增：read/write/close 需要
#include <cutils/log.h>

#define DEVICE_NAME "/dev/hello"

/* ---------- 前向声明（原代码缺，必加） ---------- */
static int hello_device_close(struct hw_device_t *device);
static int hello_set_val(struct hello_device_t *dev, int val);
static int hello_get_val(struct hello_device_t *dev, int *val);

static int hello_device_open(const struct hw_module_t *module,
                              const char *name, struct hw_device_t **device)
{
    struct hello_device_t *dev =
        (struct hello_device_t *)malloc(sizeof(struct hello_device_t));
    if (!dev)
        return -ENOMEM;

    memset(dev, 0, sizeof(struct hello_device_t));
    dev->common.tag     = HARDWARE_DEVICE_TAG;
    dev->common.version = 0;
    dev->common.module  = (struct hw_module_t *)module;
    dev->common.close   = hello_device_close;
    dev->set_val        = hello_set_val;
    dev->get_val        = hello_get_val;

    dev->fd = open(DEVICE_NAME, O_RDWR);
    if (dev->fd < 0) {
        ALOGE("hello: failed to open %s: %s", DEVICE_NAME, strerror(errno));
        free(dev);
        return -EFAULT;
    }

    *device = &dev->common;
    return 0;
}

static int hello_device_close(struct hw_device_t *device)
{
    struct hello_device_t *dev = (struct hello_device_t *)device;
    if (dev) {
        if (dev->fd >= 0)
            close(dev->fd);
        free(dev);
    }
    return 0;
}

static int hello_set_val(struct hello_device_t *dev, int val)
{
    if (write(dev->fd, &val, sizeof(val)) != sizeof(val))
        return -EFAULT;
    return 0;
}

static int hello_get_val(struct hello_device_t *dev, int *val)
{
    if (read(dev->fd, val, sizeof(*val)) != sizeof(*val))
        return -EFAULT;
    return 0;
}

static struct hw_module_methods_t hello_module_methods = {
    .open = hello_device_open,
};

struct hello_module_t HAL_MODULE_INFO_SYM = {
    .common = {
        .tag           = HARDWARE_MODULE_TAG,
        .version_major = 1,
        .version_minor = 0,
        .id            = HELLO_HARDWARE_MODULE_ID,
        .name          = "Hello Module",
        .author        = "FlyBigPig",
        .methods       = &hello_module_methods,
    },
};
```

---

## 第三层：JNI 方法（优化版）

原 JNI 注册函数 `register_android_server_HelloService` 需要被 `AndroidRuntime` 调用才生效（原模板没说在哪调用）。下面给出完整 JNI 文件，并说明注册挂载点。

**文件：** `frameworks/base/services/jni/com_android_server_HelloService.cpp`

```cpp
#define LOG_TAG "HelloServiceJNI"
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <hardware/hardware.h>
#include <hardware/hello.h>

static struct hello_device_t *hello_device = NULL;

static jboolean hello_init(JNIEnv *env, jclass clazz)
{
    struct hello_module_t *module;
    if (hw_get_module(HELLO_HARDWARE_MODULE_ID,
                      (const struct hw_module_t **)&module) == 0) {
        if (hello_device_open(&module->common, NULL,
                              (struct hw_device_t **)&hello_device) == 0) {
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

static void hello_setVal(JNIEnv *env, jobject clazz, jint value)
{
    if (hello_device)
        hello_device->set_val(hello_device, value);
}

static jint hello_getVal(JNIEnv *env, jobject clazz)
{
    int val = 0;
    if (hello_device)
        hello_device->get_val(hello_device, &val);
    return val;
}

static const JNINativeMethod method_table[] = {
    {"init_native",   "()Z", (void *)hello_init},
    {"setVal_native", "(I)V", (void *)hello_setVal},
    {"getVal_native", "()I", (void *)hello_getVal},
};

int register_android_server_HelloService(JNIEnv *env)
{
    return jniRegisterNativeMethods(env,
        "com/android/server/HelloService", method_table, NELEM(method_table));
}
```

**注册挂载点**（原模板漏掉）：在 `frameworks/base/services/jni/onload.cpp` 里调用：

```cpp
// frameworks/base/services/jni/onload.cpp
namespace android {
int register_android_server_HelloService(JNIEnv *env);  // 声明
};

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    // ... 其他注册 ...
    register_android_server_HelloService(env);  // ← 新增
    return JNI_VERSION_1_4;
}
```

---

## 第四层：Framework 硬件服务（优化版）

**文件：** `frameworks/base/services/java/com/android/server/HelloService.java`

```java
package com.android.server;

import android.os.IHelloService;
import android.util.Slog;

public class HelloService extends IHelloService.Stub {
    private static final String TAG = "HelloService";

    static {
        System.loadLibrary("hello_jni");  // ← 对应 libhello_jni.so
    }

    public HelloService() {
        if (!init_native()) {
            Slog.e(TAG, "Failed to init hello HAL");
        }
    }

    @Override
    public void setVal(int val) {
        setVal_native(val);
    }

    @Override
    public int getVal() {
        return getVal_native();
    }

    private static native boolean init_native();
    private static native void setVal_native(int val);
    private static native int getVal_native();
}
```

**注册到 SystemServer**（原模板漏掉）：

```java
// frameworks/base/services/java/com/android/server/SystemServer.java
// 在 startOtherServices() 中：
try {
    HelloService helloService = new HelloService();
    ServiceManager.addService("hello", helloService);
    Slog.i(TAG, "HelloService started");
} catch (Throwable e) {
    Slog.e(TAG, "Failed to start HelloService", e);
}
```

**AIDL 接口定义**（原模板用 `IHelloService.Stub`，需有对应 AIDL）：

**文件：** `frameworks/base/core/java/android/os/IHelloService.aidl`
```aidl
package android.os;

interface IHelloService {
    void setVal(int val);
    int getVal();
}
```

---

## 第五层补充：APP 调用

```java
import android.os.ServiceManager;
import android.os.IHelloService;

IHelloService svc = IHelloService.Stub.asInterface(
    ServiceManager.getService("hello"));
svc.setVal(42);
int v = svc.getVal();
```

> 第三方 App 需追加 `HelloManager` + `ContextImpl` 注册（见前一份文档《Android 内核驱动开发全链路深度解析》第七层），此处略。

---

## 第六层补充：编译配置

### 6.1 内核驱动编译（Android 14 → 模块）

**`drivers/hello/Kconfig`**
```kconfig
config HELLO_DRIVER
    tristate "Hello hardware driver"
    default n
```

**`drivers/hello/Makefile`**
```makefile
obj-$(CONFIG_HELLO_DRIVER) += hello.o
```

**厂商 defconfig（Android 14 强制 =m）**
```
CONFIG_HELLO_DRIVER=m
```

### 6.2 HAL / JNI 编译（Android.mk）

**`hardware/libhardware/modules/hello/Android.mk`**
```makefile
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := hello.default
LOCAL_MODULE_RELATIVE_PATH := hw
LOCAL_SRC_FILES := hello.c
LOCAL_SHARED_LIBRARIES := liblog libcutils libhardware
LOCAL_MODULE_TAGS := optional
LOCAL_PROPRIETARY_MODULE := true
include $(BUILD_SHARED_LIBRARY)
```

**`frameworks/base/services/jni/Android.mk`** 中把 JNI 文件加进 `LOCAL_SRC_FILES`：
```makefile
LOCAL_SRC_FILES += \
    com_android_server_HelloService.cpp
```

---

## 第七层补充：SELinux 策略（原模板完全缺失）

**`device/xxx/sepolicy/vendor/file_contexts`**
```
/dev/hello    u:object_r:hello_device:s0
```

**`device/xxx/sepolicy/vendor/hello.te`**
```te
type hello_device, dev_type;
# system_server 域访问 /dev/hello
allow system_server hello_device:chr_file rw_file_perms;
```

**`device/xxx/sepolicy/vendor/genfs_contexts`**（若暴露 sysfs）
```
genfscon sysfs /devices/virtual/hello  u:object_r:sysfs_hello:s0
```

---

## 第八层补充：验证步骤

```bash
# 1. 内核模块加载
adb shell lsmod | grep hello
adb shell ls -l /dev/hello
adb shell dmesg | grep hello

# 2. HAL 库存在
adb shell ls /vendor/lib64/hw/hello.default.so

# 3. Framework 服务注册
adb shell service list | grep hello

# 4. SELinux 拒绝排查
adb shell dmesg | grep avc | grep hello
adb shell setenforce 0   # 仅调试
```

---

## 第九节：四层各层职责速查

| 层 | 进程 | 语言 | 关键文件 | 出错表现 |
|----|------|------|---------|---------|
| 内核驱动 | 内核 | C | `drivers/hello/hello.c` | `insmod` 失败 / `/dev/hello` 无节点 |
| HAL | system_server | C | `hardware/.../hello.c` + `hello.h` | `hw_get_module` 返回非 0 |
| JNI | system_server | C++ | `com_android_server_HelloService.cpp` | `UnsatisfiedLinkError` |
| Framework | system_server | Java | `HelloService.java` | `ServiceManager` 取不到服务 |
| App | App 进程 | Java | 调用方 | `RemoteException` |

---

## 第十节：你贴的原代码缺陷清单（必改）

| # | 缺陷 | 位置 | 后果 | 修复 |
|---|------|------|------|------|
| 1 | 使用前未声明 `hello_device_close/set_val/get_val` | HAL `hello.c` | 编译报错 `implicit declaration` | 文件顶部加前向声明 |
| 2 | 缺 `#include <string.h>` | HAL `hello.c` | `memset` 隐式声明 | 补 `#include <string.h>` |
| 3 | 缺 `#include <unistd.h>` | HAL `hello.c` | `read/write/close` 报错 | 补 `#include <unistd.h>` |
| 4 | 缺 HAL 头文件 `hello.h` | 全局 | `hello_device_t` 等类型未定义 | 新建 `hardware/hello.h` |
| 5 | 缺 `#include <linux/device.h>` | 内核 `hello.c` | `class_create` 报错 | 补头文件 |
| 6 | 全局 `hello_val` 无锁 | 内核 `hello.c` | 多进程并发竞态 | 加 `DEFINE_MUTEX` |
| 7 | JNI 注册未挂到 `onload.cpp` | JNI | native 方法不生效 | 在 `AndroidRuntime` 注册 |
| 8 | 缺 SystemServer 注册 | Framework | 服务未启动 | `ServiceManager.addService` |
| 9 | 缺 `Android.mk` / SELinux | 构建/运行 | 编不出 / 权限拒绝 | 补齐 |

---

## 第十一节：Android 14 现代化升级路线

经典四层在 Android 14 上**能跑但不推荐**。新一代做法是把 **C-ABI HAL + JNI** 替换为 **AIDL HAL**（Treble 合规、进程隔离、自动生成 binder 代码，HAL 跑在独立进程）。

```
旧：Framework(JNI) ──dlopen──► C-ABI HAL ──► /dev/hello
新：Framework ──Binder──► AIDL HAL 进程 ──► /dev/hello
```

升级要点：
1. **内核驱动**：不变（仍是 `=m` 模块），但建议补充 `ioctl` 命令与 `uapi` 头文件。
2. **HAL**：删掉 `hello.h`(C-ABI) 和 JNI，改为 `IHello.aidl` + `Hello.cpp` + `service.cpp` + `hello-service.rc`。
3. **Framework**：不再 `System.loadLibrary`，改为 `IHello.Stub.asInterface(ServiceManager.waitForDeclaredService(...))`。
4. **SELinux**：HAL 从 `system_server` 域改为独立 `hal_hello_default` 域（最小权限）。
5. **VINTF**：新增 `hello-service.xml` 声明 AIDL HAL 接口。

详细代码见《Android 内核驱动开发全链路深度解析.md》第五、六、八章。

---

## 一句话总结

经典四层模板**内核→HAL→JNI→Framework** 链路思路正确，但原示例代码有 3 处编译级缺陷、缺 4 个关键文件（HAL 头文件 / onload 注册 / 构建脚本 / SELinux）。在 Android 14 上，内核驱动必须编为 `=m` 模块，且 HAL 层建议升级为 AIDL HAL 以符合 Treble/GKI 规范。

---

> **文档版本：** v1.0（优化版）
> **基于：** 经典 Android 驱动开发四层模板（CSDN）+ Android 14 GKI 2.0 修正
> **最后更新：** 2026-08-10
