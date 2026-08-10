# Android 内核驱动开发全链路深度解析（Android 14 / Kernel 6.1）

> **目标版本：** Android 14 (UpsideDownCake, API 34)，内核分支 `android14-6.1`  
> **编译环境：** Linux (E5-2697A v4 64G)，`make -j32`  
> **验证设备：** `sdk_phone_x86_64` (需 KVM) 或真机

---

## 目录

- [一、Android Kernel 架构总览](#一android-kernel-架构总览)
- [二、Android 特有核心驱动](#二android-特有核心驱动)
- [三、全链路架构总览（驱动→HAL→Framework→App）](#三全链路架构总览驱动halframeworkapp)
- [四、第一层：内核驱动开发](#四第一层内核驱动开发)
- [五、第二层：HAL 层](#五第二层hal-层)
- [六、第三层：Framework 服务层](#六第三层framework-服务层)
- [七、第四层：APP 层调用](#七第四层app-层调用)
- [八、第五层：SELinux 策略](#八第五层selinux-策略)
- [九、SocketCAN 驱动（车载场景）](#九socketcan-驱动车载场景)
- [十、内核模块编译与打包](#十内核模块编译与打包)
- [十一、调试技术](#十一调试技术)
- [十二、完整调用链路时序](#十二完整调用链路时序)
- [十三、新旧方式完整对比总结](#十三新旧方式完整对比总结)
- [十四、开发检查清单](#十四开发检查清单)
- [十五、编译与验证流程](#十五编译与验证流程)
- [附录：速查表](#附录速查表)

---

## 一、Android Kernel 架构总览

Android 14 强制使用 **GKI 2.0（Generic Kernel Image 2.0）**，内核分支为 `android14-6.1`。核心设计目标是将 Google 维护的通用内核与厂商驱动隔离，通过**可加载内核模块**机制实现厂商定制。

```
┌─────────────────────────────────────────────────────┐
│              Android Userspace                        │
│  (System Apps / Framework / Native HAL / Init)       │
└──────────────┬──────────────────────────────────────┘
               │  sysfs / procfs / /dev / ioctl
┌──────────────┴──────────────────────────────────────┐
│              HAL 层 (AIDL HAL)                        │
│  (android.hardware.* @AIDL, vendor partitions)       │
└──────────────┬──────────────────────────────────────┘
               │  ioctl / sysfs / netlink
┌──────────────┴──────────────────────────────────────┐
│     GKI (Generic Kernel Image) — Google 维护         │
│  android14-6.1 / 核心 binder, ashmem, ion, lmk       │
├──────────────────────────────────────────────────────┤
│     Vendor Kernel Modules — 厂商维护                  │
│  (drivers/xxx, 加载到 vendor 分区, 不修改 GKI 二进制)  │
├──────────────────────────────────────────────────────┤
│     SoC / Board Drivers (DTS 描述)                    │
└─────────────────────────────────────────────────────┘
```

### GKI 2.0 核心约束

| 约束                 | 说明                                                |
| ------------------ | ------------------------------------------------- |
| **不可修改 GKI 二进制**   | 厂商不能修改 `vmlinux`，所有定制通过 `.ko` 模块实现                |
| **模块接口冻结**         | GKI 导出的符号通过 `abi_gki_aarch64*` 文件白名单控制，模块只能用白名单符号 |
| **vendor_boot 分区** | 厂商内核模块打包在 `vendor_boot` 或 `vendor` 分区             |
| **KMI 锁定**         | Kernel Module Interface 一旦发布不可变更，保证 OTA 兼容        |

---

## 二、Android 特有核心驱动

### 2.1 Binder IPC 驱动

**文件路径：** `drivers/android/binder.c` / `drivers/android/binder_alloc.c`

Binder 是 Android 进程间通信的基石，基于共享内存实现高性能 IPC。

```c
// drivers/android/binder.c (android14-6.1)

// Binder 设备初始化
static int __init binder_init(void)
{
    int ret;
    char *device_name, *device_names, *device_tmp;
    
    // 注册 miscdevice: /dev/binder, /dev/hwbinder, /dev/vndbinder
    binder_devices_cfg = "binder,hwbinder,vndbinder";
    device_names = kstrdup(binder_devices_cfg, GFP_KERNEL);
    
    while ((device_name = strsep(&device_names, ","))) {
        ret = init_binder_device(device_name);
        if (ret)
            goto err_init_binder_device_failed;
    }
    // ...
}

// 核心 ioctl 入口 — 用户空间通过 ioctl 与 binder 交互
static long binder_ioctl(struct file *filp, unsigned int cmd, unsigned long arg)
{
    int ret;
    struct binder_proc *proc = filp->private_data;
    struct thread *thread;
    unsigned int size = _IOC_SIZE(cmd);
    void __user *ubuf = (void __user *)arg;

    thread = binder_get_thread(proc);  // 获取/创建 binder 线程
    switch (cmd) {
    case BINDER_WRITE_READ:
        ret = binder_ioctl_write_read(filp, cmd, arg, thread);
        break;
    case BINDER_SET_MAX_THREADS:
        // 设置进程最大 binder 线程数
        break;
    case BINDER_SET_CONTEXT_MGR:
        // 注册 ServiceManager
        break;
    case BINDER_FREE_BUFFER:
        // 释放 binder buffer
        break;
    // ...
    }
}
```

**Binder 数据流核心路径：**

```
用户空间 ioctl(BINDER_WRITE_READ)
    │
    ▼
binder_ioctl()  ──►  binder_ioctl_write_read()
    │                        │
    │         ┌──────────────┴───────────────┐
    │         ▼                               ▼
    │  binder_thread_write()         binder_thread_read()
    │   (处理 BC_TRANSACTION            (等待/读取 BR_REPLY
    │    BC_REPLY 等命令)                等返回命令)
    │         │
    │         ▼
    │  binder_transaction()  ◄── 核心：找到目标进程/线程
    │    │                      拷贝数据到目标 buffer
    │    │                      入队到目标线程 todo list
    │    ▼
    │  目标进程被唤醒，执行 binder_thread_read()
    │    └──► BR_TRANSACTION 命令返回到用户空间
```

**binder_alloc.c — 共享内存管理：**

```c
// drivers/android/binder_alloc.c

// mmap 回调 — 用户空间映射 binder buffer
static int binder_alloc_mmap_handler(struct binder_alloc *alloc,
                                      struct vm_area_struct *vma)
{
    // 分配最多 4MB 的连续物理内存
    // 映射到用户空间，实现 zero-copy 传输
    alloc->buffer = (void __user *)vma->vm_start;
    alloc->buffer_size = min_t(unsigned long, vma->vm_end - vma->vm_start,
                               SZ_4M);
    // ...
}
```

### 2.2 Ashmem / Memfd

Android 14 中 ashmem 已被 **memfd** 大量替代，但仍有兼容路径：

- `drivers/staging/android/ashmem.c` — 匿名共享内存
- 新代码应使用 `memfd_create()` 系统调用 + `DMA-BUF`

### 2.3 ION / DMA-BUF Heap

**文件路径：** `drivers/dma-buf/heaps/`

Android 14 使用 Linux 主线 dma-buf heap 替代旧 ION 驱动：

```c
// drivers/dma-buf/heaps/system_heap.c

// 分配系统内存给 GPU / display / camera 共享
static struct dma_buf *system_heap_allocate(struct dma_heap *heap,
                                             unsigned long len,
                                             unsigned long fd_flags,
                                             unsigned long heap_flags)
{
    struct system_heap_buffer_info *info;
    // 通过 sg_table 管理散列页面
    // 支持 contiguous / non-contiguous 分配
}
```

### 2.4 Low Memory Killer (LMKD)

Android 14 的 LMK 实现已移到**用户空间**（`system/memory/lmkd/`），通过 PSI（Pressure Stall Information）监听内存压力，内核侧仅提供 `psi` 接口：

```c
// 内核侧 PSI 支持: kernel/cgroup/psi.c
// 用户空间 lmkd 通过 /proc/pressure/memory 监听
```

---

## 三、全链路架构总览（驱动→HAL→Framework→App）

```
┌─────────────────────────────────────────────────────────┐
│  APP 层  (Third-party App / System App)                  │
│  通过 ServiceManager.getService("hello") 获取 Binder     │
└────────────────────────┬────────────────────────────────┘
                         │ Binder IPC (跨进程)
┌────────────────────────┴────────────────────────────────┐
│  Framework 层  (system_server 进程内)                     │
│  HelloService extends IHelloService.Stub                 │
│  │                                                       │
│  ├─ JNI: hello_setVal_native / hello_getVal_native      │
│  └─ 加载 libhello_jni.so                                  │
└────────────────────────┬────────────────────────────────┘
                         │ JNI 调用 (同进程)
┌────────────────────────┴────────────────────────────────┐
│  HAL 层                                                   │
│  ┌─────────────────────────────────────────────────────┐ │
│  │ 【老方式 C-ABI HAL】        【新方式 AIDL HAL】        │ │
│  │ hw_get_module()             IAIDL HAL Service        │ │
│  │ hw_module_t / hw_device_t   Binder IPC → HAL 进程     │ │
│  │ (与 SystemServer 同进程)     (独立 HAL 进程, Treble)   │ │
│  └─────────────────────────────────────────────────────┘ │
└────────────────────────┬────────────────────────────────┘
                         │ open/read/write/ioctl (系统调用)
┌────────────────────────┴────────────────────────────────┐
│  内核层  (内核空间)                                        │
│  /dev/hello 字符设备                                       │
│  hello_open / hello_read / hello_write                   │
│  (Android 14: 编译为 .ko 模块, 通过 GKI 加载)              │
└─────────────────────────────────────────────────────────┘
```

---

## 四、第一层：内核驱动开发

### 4.1 Android 14 现代化内核驱动

**文件路径：** `kernel/xxx-6.1/drivers/hello/hello.c`

```c
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/fs.h>
#include <linux/cdev.h>
#include <linux/device.h>
#include <linux/uaccess.h>
#include <linux/mutex.h>
#include <linux/platform_device.h>
#include <linux/of.h>
#include <linux/slab.h>

#define DEVICE_NAME "hello"
#define CLASS_NAME  "hello_class"
#define HELLO_BUF_SIZE 256

struct hello_dev {
    struct cdev cdev;
    struct device *dev;
    struct mutex lock;          // 并发保护
    char buf[HELLO_BUF_SIZE];   // 模拟硬件寄存器
    int val;
};

static int hello_major;
static struct class *hello_class;
static struct hello_dev *hello_device;

/* ---------- 文件操作 ---------- */

static int hello_open(struct inode *inode, struct file *file)
{
    struct hello_dev *dev = container_of(inode->i_cdev, struct hello_dev, cdev);
    file->private_data = dev;
    pr_info("hello: device opened\n");
    return 0;
}

static int hello_release(struct inode *inode, struct file *file)
{
    pr_info("hello: device closed\n");
    return 0;
}

static ssize_t hello_read(struct file *file, char __user *buf,
                           size_t count, loff_t *offset)
{
    struct hello_dev *dev = file->private_data;
    int ret;

    if (*offset >= sizeof(dev->val))
        return 0;  // EOF

    mutex_lock(&dev->lock);
    ret = copy_to_user(buf, &dev->val, sizeof(dev->val));
    mutex_unlock(&dev->lock);

    if (ret)
        return -EFAULT;

    *offset += sizeof(dev->val);
    return sizeof(dev->val);
}

static ssize_t hello_write(struct file *file, const char __user *buf,
                            size_t count, loff_t *offset)
{
    struct hello_dev *dev = file->private_data;
    int new_val, ret;

    if (count < sizeof(new_val))
        return -EINVAL;

    ret = copy_from_user(&new_val, buf, sizeof(new_val));
    if (ret)
        return -EFAULT;

    mutex_lock(&dev->lock);
    dev->val = new_val;
    mutex_unlock(&dev->lock);

    pr_info("hello: val set to %d\n", new_val);
    return sizeof(new_val);
}

/* ---------- ioctl 接口 (扩展功能) ---------- */

#define HELLO_IOC_MAGIC 'H'
#define HELLO_IOC_GET_VAL    _IOR(HELLO_IOC_MAGIC, 1, int)
#define HELLO_IOC_SET_VAL    _IOW(HELLO_IOC_MAGIC, 2, int)
#define HELLO_IOC_RESET      _IO(HELLO_IOC_MAGIC, 3)

static long hello_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
    struct hello_dev *dev = file->private_data;
    int val;

    switch (cmd) {
    case HELLO_IOC_GET_VAL:
        mutex_lock(&dev->lock);
        val = dev->val;
        mutex_unlock(&dev->lock);
        if (copy_to_user((void __user *)arg, &val, sizeof(val)))
            return -EFAULT;
        return 0;

    case HELLO_IOC_SET_VAL:
        if (copy_from_user(&val, (void __user *)arg, sizeof(val)))
            return -EFAULT;
        mutex_lock(&dev->lock);
        dev->val = val;
        mutex_unlock(&dev->lock);
        return 0;

    case HELLO_IOC_RESET:
        mutex_lock(&dev->lock);
        dev->val = 0;
        mutex_unlock(&dev->lock);
        return 0;

    default:
        return -ENOTTY;
    }
}

static const struct file_operations hello_fops = {
    .owner          = THIS_MODULE,
    .open           = hello_open,
    .release        = hello_release,
    .read           = hello_read,
    .write          = hello_write,
    .unlocked_ioctl = hello_ioctl,
    .compat_ioctl   = hello_ioctl,  // 32位进程兼容
};

/* ---------- sysfs 属性 ---------- */

static ssize_t val_show(struct device *dev, struct device_attribute *attr,
                         char *buf)
{
    struct hello_dev *hdev = dev_get_drvdata(dev);
    int val;

    mutex_lock(&hdev->lock);
    val = hdev->val;
    mutex_unlock(&hdev->lock);

    return scnprintf(buf, PAGE_SIZE, "%d\n", val);
}

static ssize_t val_store(struct device *dev, struct device_attribute *attr,
                          const char *buf, size_t count)
{
    struct hello_dev *hdev = dev_get_drvdata(dev);
    int ret, val;

    ret = kstrtoint(buf, 10, &val);
    if (ret)
        return ret;

    mutex_lock(&hdev->lock);
    hdev->val = val;
    mutex_unlock(&hdev->lock);

    return count;
}
static DEVICE_ATTR_RW(val);

static struct attribute *hello_attrs[] = {
    &dev_attr_val.attr,
    NULL,
};
ATTRIBUTE_GROUPS(hello);

/* ---------- 模块初始化 ---------- */

static int __init hello_init(void)
{
    dev_t devno;
    int ret;

    /* 1. 分配设备号 */
    ret = alloc_chrdev_region(&devno, 0, 1, DEVICE_NAME);
    if (ret) {
        pr_err("hello: failed to alloc chrdev region\n");
        return ret;
    }
    hello_major = MAJOR(devno);

    /* 2. 创建设备类 */
    hello_class = class_create(THIS_MODULE, CLASS_NAME);
    if (IS_ERR(hello_class)) {
        ret = PTR_ERR(hello_class);
        goto err_class;
    }
    hello_class->dev_groups = hello_groups;  // 绑定 sysfs 属性组

    /* 3. 分配并初始化 cdev */
    hello_device = kzalloc(sizeof(*hello_device), GFP_KERNEL);
    if (!hello_device) {
        ret = -ENOMEM;
        goto err_alloc;
    }
    mutex_init(&hello_device->lock);
    hello_device->val = 0;

    cdev_init(&hello_device->cdev, &hello_fops);
    hello_device->cdev.owner = THIS_MODULE;

    /* 4. 添加 cdev 到系统 */
    ret = cdev_add(&hello_device->cdev, devno, 1);
    if (ret)
        goto err_cdev;

    /* 5. 创建 /dev/hello 设备节点 (udev自动创建) */
    hello_device->dev = device_create(hello_class, NULL, devno,
                                       NULL, DEVICE_NAME);
    if (IS_ERR(hello_device->dev)) {
        ret = PTR_ERR(hello_device->dev);
        goto err_device;
    }

    dev_set_drvdata(hello_device->dev, hello_device);

    pr_info("hello: driver loaded, major=%d\n", hello_major);
    return 0;

err_device:
    cdev_del(&hello_device->cdev);
err_cdev:
    kfree(hello_device);
err_alloc:
    class_destroy(hello_class);
err_class:
    unregister_chrdev_region(MKDEV(hello_major, 0), 1);
    return ret;
}

static void __exit hello_exit(void)
{
    dev_t devno = MKDEV(hello_major, 0);

    device_destroy(hello_class, devno);
    cdev_del(&hello_device->cdev);
    kfree(hello_device);
    class_destroy(hello_class);
    unregister_chrdev_region(devno, 1);

    pr_info("hello: driver unloaded\n");
}

module_init(hello_init);
module_exit(hello_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("FlyBigPig");
MODULE_DESCRIPTION("Hello hardware driver for Android 14");
```

### 4.2 内核侧配套文件

**`drivers/hello/Kconfig`：**

```kconfig
config HELLO_DRIVER
    tristate "Hello hardware driver"
    default n
    help
      Hello driver for Android 14 GKI module.
      Build as module (=m) for GKI compliance.
```

**`drivers/hello/Makefile`：**

```makefile
obj-$(CONFIG_HELLO_DRIVER) += hello.o
```

**用户空间头文件 `include/uapi/linux/hello.h`：**

```c
#ifndef _UAPI_LINUX_HELLO_H
#define _UAPI_LINUX_HELLO_H

#include <linux/ioctl.h>

#define HELLO_IOC_MAGIC 'H'
#define HELLO_IOC_GET_VAL    _IOR(HELLO_IOC_MAGIC, 1, int)
#define HELLO_IOC_SET_VAL    _IOW(HELLO_IOC_MAGIC, 2, int)
#define HELLO_IOC_RESET      _IO(HELLO_IOC_MAGIC, 3)

#endif /* _UAPI_LINUX_HELLO_H */
```

**厂商 defconfig：**

```
CONFIG_HELLO_DRIVER=m
```

### 4.3 设备树配置（可选，如需 platform_driver）

**`arch/arm64/boot/dts/xxx/board.dtsi`：**

```dts
/ {
    hello_node {
        compatible = "mycorp,hello";
        status = "okay";
    };
};
```

---

## 五、第二层：HAL 层

### 5.1 老方式 vs 新方式对比

| 维度         | C-ABI HAL (老方式)  | AIDL HAL (新方式)    |
| ---------- | ---------------- | ----------------- |
| Android 版本 | < 8.0            | >= 10 (14 强制)     |
| 接口描述语言     | C 头文件            | .aidl 文件          |
| HAL 运行方式   | SystemServer 同进程 | 独立 HAL 进程         |
| 跨进程通信      | dlopen (同进程)     | Binder IPC        |
| 接口版本管理     | 无                | VINTF + versioned |
| Treble 合规  | 不合规              | 合规                |
| SELinux    | 宽松               | 严格 (HAL domain)   |
| 代码复杂度      | 低                | 中                 |

### 5.2 方式一：C-ABI HAL（老方式，Android 14 上兼容但不推荐）

**HAL 接口头文件 `hardware/libhardware/include/hardware/hello.h`：**

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

#endif
```

**HAL 实现 `hello.c`：**

```c
#include <hardware/hardware.h>
#include <hardware/hello.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <cutils/log.h>

#define DEVICE_NAME "/dev/hello"

/* 前向声明 */
static int hello_device_close(struct hw_device_t *device);
static int hello_set_val(struct hello_device_t *dev, int val);
static int hello_get_val(struct hello_device_t *dev, int *val);

static int hello_device_open(const struct hw_module_t *module,
                              const char *name, struct hw_device_t **device)
{
    struct hello_device_t *dev;

    dev = (struct hello_device_t *)malloc(sizeof(*dev));
    if (!dev)
        return -ENOMEM;

    memset(dev, 0, sizeof(*dev));
    dev->common.tag     = HARDWARE_DEVICE_TAG;
    dev->common.version = 0;
    dev->common.module  = (struct hw_module_t *)module;
    dev->common.close   = hello_device_close;
    dev->set_val        = hello_set_val;
    dev->get_val        = hello_get_val;

    dev->fd = open(DEVICE_NAME, O_RDWR);
    if (dev->fd < 0) {
        ALOGE("Failed to open %s: %s", DEVICE_NAME, strerror(errno));
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
        close(dev->fd);
        free(dev);
    }
    return 0;
}

static int hello_set_val(struct hello_device_t *dev, int val)
{
    write(dev->fd, &val, sizeof(val));
    return 0;
}

static int hello_get_val(struct hello_device_t *dev, int *val)
{
    read(dev->fd, val, sizeof(*val));
    return 0;
}

static struct hw_module_methods_t hello_module_methods = {
    .open = hello_device_open,
};

struct hello_module_t HAL_MODULE_INFO_SYM = {
    .common = {
        .tag            = HARDWARE_MODULE_TAG,
        .version_major  = 1,
        .version_minor  = 0,
        .id             = HELLO_HARDWARE_MODULE_ID,
        .name           = "Hello Module",
        .author         = "Author",
        .methods        = &hello_module_methods,
    },
};
```

### 5.3 方式二：AIDL HAL（Android 14 推荐方式）

**目录结构：**

```
hardware/interfaces/hello/aidl/
├── android/hardware/hello/
│   └── IHello.aidl           # HAL 接口定义
├── default/
│   ├── Android.bp            # 编译配置
│   ├── Hello.cpp             # HAL 实现
│   ├── Hello.h               # 头文件
│   ├── service.cpp           # HAL service 入口
│   └── hello-service.rc      # init 脚本
└── Android.bp
```

**`IHello.aidl`：**

```aidl
package android.hardware.hello;

@VintfStability
interface IHello {
    void setVal(int val);
    int getVal();
    void reset();
}
```

**`Hello.h`：**

```cpp
#pragma once
#include <aidl/android/hardware/hello/IHello.h>
#include <aidl/android/hardware/hello/BnHello.h>

namespace aidl::android::hardware::hello {

class Hello : public BnHello {
public:
    Hello();
    ~Hello();

    ::ndk::ScopedAStatus setVal(int val) override;
    ::ndk::ScopedAStatus getVal(int* _aidl_return) override;
    ::ndk::ScopedAStatus reset() override;

private:
    int fd_;  // /dev/hello 文件描述符
};

}  // namespace aidl::android::hardware::hello
```

**`Hello.cpp`：**

```cpp
#include "Hello.h"
#include <android-base/logging.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/hello.h>  // 内核 uapi 头文件

namespace aidl::android::hardware::hello {

Hello::Hello() : fd_(-1) {
    fd_ = open("/dev/hello", O_RDWR);
    if (fd_ < 0) {
        LOG(ERROR) << "Failed to open /dev/hello: " << strerror(errno);
    }
}

Hello::~Hello() {
    if (fd_ >= 0)
        close(fd_);
}

::ndk::ScopedAStatus Hello::setVal(int val) {
    if (fd_ < 0)
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);

    ssize_t ret = write(fd_, &val, sizeof(val));
    if (ret != sizeof(val)) {
        LOG(ERROR) << "write failed: " << strerror(errno);
        return ndk::ScopedAStatus::fromExceptionCode(EX_IO);
    }
    return ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus Hello::getVal(int* _aidl_return) {
    if (fd_ < 0)
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);

    int val = 0;
    ssize_t ret = read(fd_, &val, sizeof(val));
    if (ret != sizeof(val)) {
        LOG(ERROR) << "read failed: " << strerror(errno);
        return ndk::ScopedAStatus::fromExceptionCode(EX_IO);
    }

    *_aidl_return = val;
    return ndk::ScopedAStatus::ok();
}

::ndk::ScopedAStatus Hello::reset() {
    if (fd_ < 0)
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);

    ioctl(fd_, HELLO_IOC_RESET);
    return ndk::ScopedAStatus::ok();
}

}  // namespace
```

**`service.cpp` — HAL 进程入口：**

```cpp
#include "Hello.h"
#include <android/binder_manager.h>
#include <android/binder_process.h>

using aidl::android::hardware::hello::Hello;

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(0);

    std::shared_ptr<Hello> hello = ndk::SharedRefBase::make<Hello>();

    const std::string instance = std::string(Hello::descriptor) + "/default";
    binder_status_t status = AServiceManager_addService(
        hello->asBinder().get(), instance.c_str());

    if (status != STATUS_OK)
        return -1;

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;
}
```

**`hello-service.rc`：**

```rc
service vendor.hello-default /vendor/bin/hw/android.hardware.hello-service.default
    interface aidl android.hardware.hello.IHello/default
    class hal
    user nobody
    group nobody
```

**`Android.bp`（顶层）：**

```python
aidl_interface {
    name: "android.hardware.hello",
    vendor_available: true,
    srcs: ["android/hardware/hello/IHello.aidl"],
    stability: "vintf",
    backend: {
        cpp: { enabled: false },
        java: { enabled: false },
        ndk: { enabled: true },
    },
    versions_with_info: [
        { version: "1", imports: [] },
    ],
}
```

**`default/Android.bp`：**

```python
cc_binary {
    name: "android.hardware.hello-service.default",
    relative_install_path: "hw",
    init_rc: ["hello-service.rc"],
    vintf_fragments: ["hello-service.xml"],
    vendor: true,
    srcs: ["Hello.cpp", "service.cpp"],
    shared_libs: ["libbase", "libbinder_ndk", "liblog", "libutils"],
    static_libs: ["android.hardware.hello-ndk"],
}
```

**`hello-service.xml` — VINTF 声明：**

```xml
<manifest version="1.0" type="device">
    <hal format="aidl">
        <name>android.hardware.hello</name>
        <version>1</version>
        <interface>
            <name>IHello</name>
            <instance>default</instance>
        </interface>
    </hal>
</manifest>
```

### 5.4 JNI 方法（仅 C-ABI HAL 方式需要）

```cpp
#include <jni.h>
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

---

## 六、第三层：Framework 服务层

### 6.1 AIDL 接口定义

**`frameworks/base/core/java/android/hello/IHelloService.aidl`：**

```aidl
package android.hello;

interface IHelloService {
    void setVal(int val);
    int getVal();
    void reset();
}
```

### 6.2 Framework Service 实现

**`frameworks/base/services/java/com/android/server/HelloService.java`：**

```java
package com.android.server;

import android.content.Context;
import android.hello.IHelloService;
import android.hardware.hello.IHello;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.util.Slog;

public class HelloService extends IHelloService.Stub {
    private static final String TAG = "HelloService";
    private static final String HAL_INSTANCE_NAME =
        "android.hardware.hello.IHello/default";

    private IHello mHal;

    public HelloService(Context context) {
        // 获取 HAL 服务代理 (通过 Binder)
        mHal = IHello.Stub.asInterface(
            ServiceManager.waitForDeclaredService(HAL_INSTANCE_NAME));

        if (mHal == null) {
            Slog.e(TAG, "Failed to get IHello HAL service");
        }
    }

    @Override
    public void setVal(int val) throws RemoteException {
        if (mHal != null) {
            mHal.setVal(val);
        }
    }

    @Override
    public int getVal() throws RemoteException {
        if (mHal != null) {
            return mHal.getVal();
        }
        return -1;
    }

    @Override
    public void reset() throws RemoteException {
        if (mHal != null) {
            mHal.reset();
        }
    }
}
```

### 6.3 C-ABI HAL 方式的 Service（带 JNI）

```java
package com.android.server;

import android.os.IHelloService;

public class HelloService extends IHelloService.Stub {
    private static final String TAG = "HelloService";

    static {
        System.loadLibrary("hello_jni");
    }

    public HelloService() {
        init_native();
    }

    public void setVal(int val) {
        setVal_native(val);
    }

    public int getVal() {
        return getVal_native();
    }

    private static native boolean init_native();
    private static native void setVal_native(int val);
    private static native int getVal_native();
}
```

### 6.4 注册到 SystemServer

**`frameworks/base/services/java/com/android/server/SystemServer.java`：**

```java
// 在 startOtherServices() 方法中添加:
t.traceBegin("StartHelloService");
try {
    HelloService helloService = new HelloService(context);
    ServiceManager.addService("hello", helloService);
    Slog.i(TAG, "HelloService started");
} catch (Throwable e) {
    Slog.e(TAG, "Failed to start HelloService", e);
}
t.traceEnd();
```

---

## 七、第四层：APP 层调用

### 7.1 System App 调用（有系统权限）

```java
import android.os.ServiceManager;
import android.hello.IHelloService;

public class HelloClient {
    private IHelloService mService;

    public void init() {
        mService = IHelloService.Stub.asInterface(
            ServiceManager.getService("hello"));
    }

    public void setVal(int val) throws RemoteException {
        if (mService != null) {
            mService.setVal(val);
        }
    }

    public int getVal() throws RemoteException {
        if (mService != null) {
            return mService.getVal();
        }
        return -1;
    }
}
```

### 7.2 第三方 App 调用（需暴露 Manager API）

**`frameworks/base/core/java/android/app/HelloManager.java`：**

```java
package android.app;

import android.content.Context;
import android.hello.IHelloService;
import android.os.RemoteException;
import android.util.Log;

public class HelloManager {
    private static final String TAG = "HelloManager";
    private final IHelloService mService;

    public HelloManager(Context ctx) {
        mService = IHelloService.Stub.asInterface(
            ServiceManager.getService("hello"));
    }

    public void setVal(int val) {
        try {
            mService.setVal(val);
        } catch (RemoteException e) {
            Log.e(TAG, "setVal failed", e);
        }
    }

    public int getVal() {
        try {
            return mService.getVal();
        } catch (RemoteException e) {
            Log.e(TAG, "getVal failed", e);
            return -1;
        }
    }
}
```

在 `ContextImpl.java` 中注册：

```java
@Override
public Object getSystemService(String name) {
    if (HELLO_SERVICE.equals(name)) {
        return getHelloManager();
    }
    // ...
}
```

App 中使用：

```java
HelloManager hm = (HelloManager) context.getSystemService(Context.HELLO_SERVICE);
hm.setVal(42);
int val = hm.getVal();
```

---

## 八、第五层：SELinux 策略

### 8.1 设备节点标签

**`device/xxx/sepolicy/vendor/genfs_contexts`：**

```
genfscon sysfs /devices/virtual/hello  u:object_r:sysfs_hello:s0
```

**`device/xxx/sepolicy/vendor/file_contexts`：**

```
/dev/hello           u:object_r:hello_device:s0
/vendor/bin/hw/android\.hardware\.hello-service-default  u:object_r:hal_hello_default_exec:s0
```

### 8.2 类型与权限

**`device/xxx/sepolicy/vendor/hello.te`：**

```te
# ─── 类型声明 ───
type hello_device, dev_type;
type sysfs_hello, sysfs_type, fs_type;
type hal_hello_default, domain;
type hal_hello_default_exec, file_type, exec_type;

# ─── HAL 进程 domain ───
init_daemon_domain(hal_hello_default)
hal_server_domain(hal_hello_default, hal_hello)

# ─── HAL 访问 /dev/hello ───
allow hal_hello_default hello_device:chr_file rw_file_perms;

# ─── HAL 访问 sysfs ───
allow hal_hello_default sysfs_hello:dir search;
allow hal_hello_default sysfs_hello:file rw_file_perms;
```

### 8.3 验证 SELinux

```bash
# 查看 HAL 进程的 SELinux domain
adb shell ps -Z | grep hello

# 查看 /dev/hello 的标签
adb shell ls -Z /dev/hello

# 查看 SELinux 拒绝日志
adb shell dmesg | grep avc | grep hello

# 临时关闭 SELinux (仅调试)
adb shell setenforce 0
```

---

## 九、SocketCAN 驱动（车载场景）

### 9.1 CAN 驱动注册流程

```
┌──────────────────────────────────────┐
│      CAN 应用层 (canutils, app)       │
│  socket(AF_CAN, SOCK_RAW, CAN_RAW)   │
└───────────────┬──────────────────────┘
                │  socket API
┌───────────────┴──────────────────────┐
│      CAN 协议栈 (net/can/)            │
│  can_raw.ko / can_bcm.ko             │
└───────────────┬──────────────────────┘
                │  netdev_ops
┌───────────────┴──────────────────────┐
│      CAN 设备层 (drivers/net/can/)    │
│  c_can / m_can / flexcan 驱动         │
└───────────────┬──────────────────────┘
                │  寄存器 / SPI / MMIO
┌───────────────┴──────────────────────┐
│      CAN 硬件控制器 (SoC / 外设)       │
└──────────────────────────────────────┘
```

### 9.2 设备树配置 CAN 控制器

**`arch/arm64/boot/dts/xxx/board.dtsi`：**

```dts
&can0 {
    compatible = "bosch,m_can";
    reg = <0x0 0x4400E000 0x0 0x400>,
          <0x0 0x44011000 0x0 0x4000>;  // Message RAM
    interrupts = <GIC_SPI 26 IRQ_TYPE_LEVEL_HIGH>,
                 <GIC_SPI 27 IRQ_TYPE_LEVEL_HIGH>;
    clocks = <&clks CAN_CLK>, <&clks CAN_RAM_CLK>;
    clock-names = "hclk", "cclk";
    bosch,mram-cfg = <0x0 0 0 32 0 0 0 1>;
    status = "okay";
};
```

### 9.3 CAN 驱动核心代码结构

```c
// drivers/net/can/m_can/m_can.c (android14-6.1)

static const struct net_device_ops m_can_netdev_ops = {
    .ndo_open       = m_can_open,
    .ndo_stop       = m_can_close,
    .ndo_start_xmit = m_can_start_xmit,
    .ndo_change_mtu = can_change_mtu,
};

static int m_can_plat_probe(struct platform_device *pdev)
{
    struct net_device *dev;
    struct m_can_priv *priv;
    
    dev = alloc_candev(sizeof(struct m_can_priv), 32);
    
    priv = netdev_priv(dev);
    priv->base = devm_platform_ioremap_resource_byname(pdev, "m_can");
    priv->mram_base = devm_platform_ioremap_resource_byname(pdev, "message_ram");
    
    devm_request_irq(&pdev->dev, irq, m_can_isr, IRQF_SHARED,
                     dev->name, dev);
    
    register_candev(dev);
}
```

### 9.4 用户空间 CAN 测试

```bash
# 配置 CAN 接口
ip link set can0 type can bitrate 500000
ip link set can0 up

# 发送 CAN 帧
cansend can0 123#DEADBEEF

# 接收 CAN 帧
candump can0

# 查看统计
ip -s link show can0
```

---

## 十、内核模块编译与打包

### 10.1 Bazel 构建配置

**`kernel/mydevice/BUILD.bazel`：**

```python
load("//build/kernel/kleaf:kernel.bzl", "kernel_module", "vendor_boot_modules_install")

kernel_module(
    name = "hello.ko",
    srcs = glob([
        "drivers/hello/*.c",
        "drivers/hello/*.h",
    ]),
    outs = ["hello.ko"],
    kernel_build = "//kernel/mydevice:mydevice_kernel_build",
    visibility = ["//visibility:public"],
)

vendor_boot_modules_install(
    name = "mydevice_vendor_boot_modules",
    kernel_modules = [
        "//kernel/mydevice:hello.ko",
        "//kernel/mydevice:can_driver.ko",
    ],
    kernel_build = "//kernel/mydevice:mydevice_kernel_build",
)
```

### 10.2 模块加载顺序

**`vendor_boot/etc/init.modules.rc`：**

```rc
on early-init
    insmod /vendor_boot/lib/modules/hello.ko
    insmod /vendor_boot/lib/modules/can_dev.ko
    insmod /vendor_boot/lib/modules/can_raw.ko
```

---

## 十一、调试技术

### 11.1 内核日志

```bash
# 实时查看内核日志
adb shell dmesg -w

# 过滤特定驱动
adb shell dmesg | grep hello

# 带时间戳和级别
adb shell dmesg -T -l err,warn
```

### 11.2 模块加载调试

```bash
# 查看已加载模块
adb shell lsmod

# 查看模块信息
adb shell modinfo hello

# 手动加载/卸载
adb shell insmod /vendor_boot/lib/modules/hello.ko
adb shell rmmod hello

# 查看模块参数
adb shell ls /sys/module/hello/parameters/
```

### 11.3 sysfs 调试

```bash
# 查看设备树节点
adb shell ls /proc/device-tree/

# 查看 GPIO 状态
adb shell cat /sys/kernel/debug/gpio

# 查看平台设备
adb shell ls /sys/bus/platform/devices/ | grep hello
```

### 11.4 ftrace 动态跟踪

```bash
adb shell 'echo function > /sys/kernel/tracing/current_tracer'
adb shell 'echo hello_open > /sys/kernel/tracing/set_ftrace_filter'
adb shell 'echo 1 > /sys/kernel/tracing/tracing_on'
adb shell cat /sys/kernel/tracing/trace
```

### 11.5 pstore 分析

```bash
adb shell ls /sys/fs/pstore/
adb shell cat /sys/fs/pstore/console-ramoops-0
```

---

## 十二、完整调用链路时序

### 写入流程

```
App: context.getSystemService(HELLO_SERVICE).setVal(42)
  │
  ▼  Binder IPC → system_server
Framework: HelloService.setVal(42)
  │
  ▼  Binder IPC → HAL 进程 (vendor.hello-default)
HAL: Hello::setVal(42)
  │
  ▼  write(fd, &val, 4)  /  ioctl(fd, HELLO_IOC_SET_VAL, &val)
内核: hello_write() / hello_ioctl()
  │
  ▼  mutex_lock → dev->val = 42 → mutex_unlock
硬件: (模拟设备，写入内存)
```

### 读取流程

```
硬件: dev->val = 42
  │
  ▼  read(fd, &val, 4) / ioctl(fd, HELLO_IOC_GET_VAL, &val)
内核: hello_read() / hello_ioctl()
  │
  ▼  copy_to_user
HAL: Hello::getVal() → 返回 42
  │
  ▼  Binder IPC 回传
Framework: HelloService.getVal() → 返回 42
  │
  ▼  Binder IPC 回传
App: 得到返回值 42
```

---

## 十三、新旧方式完整对比总结

| 维度            | C-ABI HAL (老方式)                    | AIDL HAL (Android 14)        |
| ------------- | ---------------------------------- | ---------------------------- |
| **JNI**       | 手写 `hello_jni.cpp`，`hw_get_module` | 不需要，Stable AIDL 自动生成         |
| **HAL 运行位置**  | SystemServer 进程内（`dlopen`）         | 独立进程（`vendor.hello-default`） |
| **进程隔离**      | 无，HAL 崩溃 → SystemServer 崩溃         | 有，HAL 崩溃不影响 SystemServer     |
| **接口定义**      | `hardware/hello.h` C 头文件           | `IHello.aidl`                |
| **版本管理**      | 无                                  | VINTF + versioned interface  |
| **编译系统**      | `Android.mk`                       | `Android.bp`                 |
| **SELinux**   | SystemServer domain 访问设备           | 独立 HAL domain，最小权限           |
| **Treble 合规** | 不合规                                | 合规                           |
| **GKI 兼容**    | 内核部分需改为 `=m`                       | 内核部分 `=m`，HAL 在 vendor 分区    |

---

## 十四、开发检查清单

### 内核层

```
□ Kconfig / Makefile (tristate, =m)
□ 驱动代码 (cdev + file_operations)
□ mutex 并发保护
□ sysfs 属性
□ ioctl 命令 (uapi 头文件)
□ uapi 头文件导出
□ defconfig CONFIG=m
□ Bazel 构建配置
```

### HAL 层

```
□ IHello.aidl 接口定义
□ Hello.cpp 实现 (open /dev/hello)
□ service.cpp 进程入口
□ hello-service.rc init 脚本
□ hello-service.xml VINTF 声明
□ Android.bp 编译配置
```

### Framework 层

```
□ IHelloService.aidl
□ HelloService.java
□ SystemServer 注册
□ HelloManager.java (可选，第三方 App 用)
□ ContextImpl 注册 (可选)
```

### SELinux

```
□ file_contexts (/dev/hello, HAL binary)
□ hello.te (类型 + 权限)
□ genfs_contexts (sysfs)
```

### 验证

```
□ lsmod | grep hello (模块加载)
□ ls /dev/hello (设备节点)
□ cat /sys/class/hello/val (sysfs)
□ dmesg | grep hello (内核日志)
□ logcat | grep HelloService (Framework 日志)
□ adb shell dumpsys hello (服务状态)
□ getenforce / setenforce 0 (SELinux 调试)
```

---

## 十五、编译与验证流程

```bash
# 1. 准备 AOSP 内核源码
repo init -u https://aosp.tuna.tsinghua.edu.cn/kernel/manifest -b common-android14-6.1
repo sync

# 2. 配置厂商内核
export KERNEL_BUILD_CONFIG=//kernel/mydevice:mydevice_defconfig

# 3. 使用 Bazel 构建
tools/bazel run //kernel/mydevice:mydevice_kernel_build_dist -- --dist_dir=out/dist

# 4. 构建 vendor_boot (包含模块)
tools/bazel run //kernel/mydevice:mydevice_vendor_boot_dist -- --dist_dir=out/dist

# 5. 刷入设备
fastboot flash vendor_boot out/dist/vendor_boot.img
fastboot reboot

# 6. 验证模块加载
adb shell lsmod | grep hello
adb shell dmesg | grep hello
adb shell cat /sys/class/hello/val

# 7. 编译 AOSP 系统 (含 HAL / Framework)
source build/envsetup.sh
lunch sdk_phone_x86_64-eng
make -j32

# 8. 验证 HAL 服务
adb shell service list | grep hello

# 9. 验证 Framework 服务
adb shell dumpsys hello
```

---

## 附录：速查表

### Android 特有驱动文件路径

| 驱动           | 文件路径                                     | 说明            |
| ------------ | ---------------------------------------- | ------------- |
| Binder       | `drivers/android/binder.c`               | IPC 核心        |
| Binder Alloc | `drivers/android/binder_alloc.c`         | 共享内存管理        |
| Ashmem       | `drivers/staging/android/ashmem.c`       | 旧版共享内存（兼容）    |
| DMA-BUF Heap | `drivers/dma-buf/heaps/`                 | 替代 ION        |
| Logger       | `drivers/staging/android/logger.c`       | 已废弃，用 tracing |
| Timed Output | `drivers/staging/android/timed_output.c` | vibrator 等    |
| Timed GPIO   | `drivers/staging/android/timed_gpio.c`   | 定时 GPIO       |
| UHID         | `drivers/hid/uhid.c`                     | 用户空间 HID      |

### 内核调试命令速查

| 场景           | 命令                                       |
| ------------ | ---------------------------------------- |
| 内核日志         | `dmesg -wT \| grep <driver>`             |
| 模块列表         | `lsmod`                                  |
| 模块信息         | `modinfo <module>`                       |
| 设备树          | `ls /proc/device-tree/`                  |
| GPIO 状态      | `cat /sys/kernel/debug/gpio`             |
| 中断统计         | `cat /proc/interrupts`                   |
| ftrace 函数    | `echo function > tracing/current_tracer` |
| pstore panic | `cat /sys/fs/pstore/console-ramoops-0`   |
| 寄存器 dump     | `devmem <addr>` (需内核支持)                  |
| netdev 统计    | `ip -s link show <iface>`                |

### HAL 与驱动的交互通道

```
┌───────────────────────────────────────────────────┐
│              AIDL HAL Service                      │
│  (vendor partition, android.hardware.mydevice)    │
└──────┬─────────┬──────────┬───────────────────────┘
       │         │          │
       ▼         ▼          ▼
   /dev/xxx   sysfs      ioctl()
   (char dev) /sys/...   
       │         │          │
       ▼         ▼          ▼
┌───────────────────────────────────────────────────┐
│              Kernel Driver (.ko)                   │
│         (vendor_boot / vendor 分区加载)            │
└───────────────────────────────────────────────────┘
```

---

> **文档版本：** v1.0  
> **适用 AOSP 版本：** Android 14 (API 34, android-14.0.0_rXX)  
> **内核分支：** android14-6.1 (GKI 2.0)  
> **最后更新：** 2026-08-10

