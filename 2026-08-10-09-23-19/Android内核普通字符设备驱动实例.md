# Android 内核「普通字符设备驱动」实例

> **内核：** `android14-6.1` / Linux 6.1
> **形态：** 可加载模块（`=m`）
> **类型：** 标准字符设备驱动（`alloc_chrdev_region` + `cdev` + `class_create`）
> **定位：** 相对于上一节的「platform 驱动 + miscdevice」，这是最经典、最通用的**普通字符设备驱动**写法

上一节给的是 `platform_driver + miscdevice`，适合「有设备树节点、板级硬件」的场景。但很多驱动其实**不绑定具体板级设备**（虚拟设备、纯软件逻辑、通用字符设备），这时用**普通字符设备驱动**更直接——自己管设备号、自己建 `/dev` 节点，不依赖设备树。

---

## 一、三种字符设备驱动方式对比

| 方式 | 设备号 | `/dev` 节点 | 是否需要设备树 | 适用 |
|------|--------|------------|--------------|------|
| **普通字符设备**（本文） | 手动 `alloc_chrdev_region` | `class_create`+`device_create` | 否 | 通用字符设备、虚拟设备 |
| **misc 设备** | 自动分配次设备号 | `misc_register` 自动 | 否 | 简单单设备、快捷 |
| **platform 驱动** | 取决于子设备（misc/cdev） | 取决于子设备 | **是**（compatible 匹配） | 板级硬件、挂总线设备 |

**选型一句话：**
- 想最省事 → `miscdevice`
- 想要标准、可挂多个次设备、自己掌控 → **普通字符设备**（本文）
- 硬件在板子上、靠设备树描述 → `platform_driver`

---

## 二、完整普通字符设备驱动源码

**文件：** `drivers/hello/hello_chr.c`

```c
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/fs.h>
#include <linux/cdev.h>
#include <linux/device.h>
#include <linux/uaccess.h>
#include <linux/mutex.h>
#include <linux/slab.h>

#define HELLO_CLASS    "hello_chr"
#define HELLO_DEVICE   "hello"

/* ---------- ioctl 命令 ---------- */
#define HELLO_IOC_MAGIC 'H'
#define HELLO_IOC_GET_VAL _IOR(HELLO_IOC_MAGIC, 1, int)
#define HELLO_IOC_SET_VAL _IOW(HELLO_IOC_MAGIC, 2, int)
#define HELLO_IOC_MAXNR    2

/* ---------- 设备私有数据 ---------- */
struct hello_dev {
    struct cdev      cdev;
    struct device   *device;
    struct mutex     lock;
    int              val;
};

static int              hello_major;   // 动态分配的主设备号
static struct class    *hello_class;
static struct hello_dev hello_devs[1];  // 本例单设备

/* ---------- file_operations ---------- */

static int hello_open(struct inode *inode, struct file *file)
{
    struct hello_dev *dev = container_of(inode->i_cdev, struct hello_dev, cdev);
    file->private_data = dev;
    return 0;
}

static ssize_t hello_read(struct file *file, char __user *buf,
                           size_t count, loff_t *off)
{
    struct hello_dev *dev = file->private_data;
    int val;

    if (*off >= sizeof(val))
        return 0;

    mutex_lock(&dev->lock);
    val = dev->val;
    mutex_unlock(&dev->lock);

    if (copy_to_user(buf, &val, sizeof(val)))
        return -EFAULT;

    *off += sizeof(val);
    return sizeof(val);
}

static ssize_t hello_write(struct file *file, const char __user *buf,
                            size_t count, loff_t *off)
{
    struct hello_dev *dev = file->private_data;
    int val;

    if (count < sizeof(val))
        return -EINVAL;
    if (copy_from_user(&val, buf, sizeof(val)))
        return -EFAULT;

    mutex_lock(&dev->lock);
    dev->val = val;
    mutex_unlock(&dev->lock);

    return sizeof(val);
}

static long hello_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
    struct hello_dev *dev = file->private_data;
    int val;

    if (_IOC_TYPE(cmd) != HELLO_IOC_MAGIC || _IOC_NR(cmd) > HELLO_IOC_MAXNR)
        return -ENOTTY;

    switch (cmd) {
    case HELLO_IOC_GET_VAL:
        mutex_lock(&dev->lock); val = dev->val; mutex_unlock(&dev->lock);
        if (copy_to_user((void __user *)arg, &val, sizeof(val)))
            return -EFAULT;
        return 0;
    case HELLO_IOC_SET_VAL:
        if (copy_from_user(&val, (void __user *)arg, sizeof(val)))
            return -EFAULT;
        mutex_lock(&dev->lock); dev->val = val; mutex_unlock(&dev->lock);
        return 0;
    default:
        return -ENOTTY;
    }
}

static const struct file_operations hello_fops = {
    .owner          = THIS_MODULE,
    .open           = hello_open,
    .read           = hello_read,
    .write          = hello_write,
    .unlocked_ioctl = hello_ioctl,
    .compat_ioctl   = hello_ioctl,
};

/* ---------- 模块初始化（标准 cdev 流程） ---------- */

static int __init hello_init(void)
{
    dev_t devno;
    int ret;

    /* 1. 动态申请设备号 */
    ret = alloc_chrdev_region(&devno, 0, 1, HELLO_DEVICE);
    if (ret < 0) {
        pr_err("hello: alloc_chrdev_region failed\n");
        return ret;
    }
    hello_major = MAJOR(devno);

    /* 2. 创建设备类（/sys/class/hello_chr） */
    hello_class = class_create(THIS_MODULE, HELLO_CLASS);
    if (IS_ERR(hello_class)) {
        ret = PTR_ERR(hello_class);
        goto err_class;
    }

    /* 3. 初始化 cdev 并加入系统 */
    mutex_init(&hello_devs[0].lock);
    hello_devs[0].val = 0;
    cdev_init(&hello_devs[0].cdev, &hello_fops);
    hello_devs[0].cdev.owner = THIS_MODULE;

    ret = cdev_add(&hello_devs[0].cdev, devno, 1);
    if (ret < 0) {
        pr_err("hello: cdev_add failed\n");
        goto err_cdev;
    }

    /* 4. 创建设备节点 /dev/hello（udev 自动） */
    hello_devs[0].device = device_create(hello_class, NULL, devno,
                                          NULL, HELLO_DEVICE);
    if (IS_ERR(hello_devs[0].device)) {
        ret = PTR_ERR(hello_devs[0].device);
        goto err_dev;
    }

    pr_info("hello: loaded, major=%d, /dev/%s\n", hello_major, HELLO_DEVICE);
    return 0;

err_dev:
    cdev_del(&hello_devs[0].cdev);
err_cdev:
    class_destroy(hello_class);
err_class:
    unregister_chrdev_region(devno, 1);
    return ret;
}

static void __exit hello_exit(void)
{
    dev_t devno = MKDEV(hello_major, 0);

    device_destroy(hello_class, devno);
    cdev_del(&hello_devs[0].cdev);
    class_destroy(hello_class);
    unregister_chrdev_region(devno, 1);

    pr_info("hello: unloaded\n");
}

module_init(hello_init);
module_exit(hello_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("FlyBigPig");
MODULE_DESCRIPTION("Ordinary character device driver for Android 14");
```

---

## 三、配套文件

### 3.1 Kconfig

**`drivers/hello/Kconfig`**
```kconfig
config HELLO_CHR_DRIVER
    tristate "Hello ordinary char device driver"
    default n
    help
      Standard character device driver instance for Android 14.
```

### 3.2 Makefile

**`drivers/hello/Makefile`**
```makefile
obj-$(CONFIG_HELLO_CHR_DRIVER) += hello_chr.o
```

### 3.3 厂商 defconfig

```
CONFIG_HELLO_CHR_DRIVER=m
```

> 注意：普通字符设备驱动**不依赖设备树**，所以不需要在 `board.dtsi` 里加节点。这是它和 platform 驱动最大的区别。

---

## 四、用户空间测试

```c
// tools/hello_chr_test.c
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>

#define HELLO_IOC_MAGIC 'H'
#define HELLO_IOC_GET_VAL _IOR(HELLO_IOC_MAGIC, 1, int)
#define HELLO_IOC_SET_VAL _IOW(HELLO_IOC_MAGIC, 2, int)

int main(void) {
    int fd = open("/dev/hello", O_RDWR);
    if (fd < 0) { perror("open"); return 1; }

    int w = 100;
    write(fd, &w, sizeof(w));

    int r = 0;
    read(fd, &r, sizeof(r));
    printf("read=%d\n", r);

    int s = 200;
    ioctl(fd, HELLO_IOC_SET_VAL, &s);
    int g = 0;
    ioctl(fd, HELLO_IOC_GET_VAL, &g);
    printf("ioctl get=%d\n", g);

    close(fd);
    return 0;
}
```

```bash
aarch64-linux-android-gcc tools/hello_chr_test.c -o hello_chr_test
adb push hello_chr.ko /data/local/tmp/
adb push hello_chr_test /data/local/tmp/
adb shell insmod /data/local/tmp/hello_chr.ko
adb shell /data/local/tmp/hello_chr_test
```

---

## 五、加载与验证

```bash
adb shell insmod /data/local/tmp/hello_chr.ko

# 设备节点（class_create + device_create 自动生成）
adb shell ls -l /dev/hello
adb shell ls /sys/class/hello_chr/

# 内核日志
adb shell dmesg | grep hello

# 模块信息
adb shell lsmod | grep hello_chr

# 卸载
adb shell rmmod hello_chr
```

---

## 六、初始化流程拆解（标准 cdev 四步）

```
hello_init()
   │
   ├─ 1. alloc_chrdev_region()   申请主/次设备号（动态）
   │
   ├─ 2. class_create()          建设备类 → /sys/class/hello_chr
   │
   ├─ 3. cdev_init() + cdev_add() 注册字符设备到内核
   │
   └─ 4. device_create()         建设备实例 → /dev/hello（udev 自动）
```

退出时**逆序释放**（`err_*` 标号做错误处理展开）：
```
hello_exit()  →  device_destroy → cdev_del → class_destroy → unregister_chrdev_region
```

---

## 七、与普通「hello.c」（CSDN 原版）的区别

| 点 | CSDN 原版 | 本实例 |
|----|----------|--------|
| 设备号 | `register_chrdev`（旧接口，整主设备） | `alloc_chrdev_region`（仅单设备，现代） |
| cdev | 无（register_chrdev 内部隐式） | 显式 `cdev_init`+`cdev_add` |
| 头文件 | 缺 `linux/device.h` | 补齐，编译通过 |
| 错误处理 | 无回滚 | `goto err_*` 逆序释放 |
| 并发 | 无锁 | `mutex` 保护 `val` |
| ioctl | 无 | 完整 `_IOC` 命令 + 校验 |

---

## 八、关键 API 速查

| API | 作用 |
|-----|------|
| `alloc_chrdev_region()` | 动态申请字符设备号（推荐） |
| `unregister_chrdev_region()` | 释放设备号 |
| `class_create()` | 创建设备类（Linux 6.1 仅 2 参数） |
| `class_destroy()` | 销毁类 |
| `cdev_init()` / `cdev_add()` | 初始化并注册 cdev |
| `cdev_del()` | 注销 cdev |
| `device_create()` | 创建设备节点 `/dev/xxx` |
| `device_destroy()` | 销毁设备节点 |
| `container_of()` | 从 `inode->i_cdev` 反推 `hello_dev` |

---

## 九、三种驱动选型速查

```
你的驱动是否绑定板级硬件（有寄存器/挂总线）？
│
├─ 是 → 用 platform_driver（设备树 compatible 匹配）
│         └─ 内部子设备选 misc（简单）或 cdev（多设备/自控）
│
└─ 否（虚拟设备 / 纯软件 / 通用字符接口）
    │
    ├─ 单设备、想最省事 → miscdevice
    │
    └─ 标准写法、可扩展 → 普通字符设备驱动（本文）
```

---

> **文档版本：** v1.0
> **适用：** Android 14 (android14-6.1 GKI 2.0)，厂商 `=m` 模块
> **最后更新：** 2026-08-10
