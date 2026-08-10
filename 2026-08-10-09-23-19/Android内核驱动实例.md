# Android 内核驱动实例：Platform 字符设备驱动（完整可编译）

> **内核：** `android14-6.1` / Linux 6.1
> **形态：** 可加载模块（`=m`，符合 GKI 2.0 厂商驱动要求）
> **类型：** `platform_driver` + `miscdevice` + `ioctl` + `sysfs`
> **场景：** 车载/工控下挂一个板级硬件控制器（此处以虚拟寄存器模拟，可直接改成真实 GPIO/寄存器）

本实例把内核驱动开发的**全部关键点**串成一个真实可编译的驱动：设备树匹配、probe/remove 生命周期、`devm_*` 自动释放、misc 设备、`file_operations`、`ioctl`、`sysfs` 属性、互斥锁、`container_of` 反推结构体。代码基于 Linux 6.1 API 编写。

---

## 一、实例架构

```
设备树 (board.dts)
   │ compatible = "vendor,ctl"
   ▼
platform_driver.hello_driver
   │ .probe  → 分配资源、映射寄存器(模拟)、注册 misc 设备、建 sysfs
   │ .remove → 自动释放 (devm_*)
   ▼
/dev/hello           字符设备 (misc)
/sys/class/misc/hello/val   sysfs 属性
ioctl(HELLO_IOC_*)   扩展控制命令
   ▼
用户空间: open/read/write/ioctl + HAL/App
```

---

## 二、完整驱动源码

**文件：** `drivers/hello/hello_plat.c`

```c
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/platform_device.h>
#include <linux/of.h>
#include <linux/miscdevice.h>
#include <linux/fs.h>
#include <linux/uaccess.h>
#include <linux/mutex.h>
#include <linux/slab.h>
#include <linux/device.h>
#include <linux/io.h>

/* ---------- uapi：ioctl 命令（用户空间也要有一份） ---------- */
#define HELLO_IOC_MAGIC 'H'
#define HELLO_IOC_GET_VAL   _IOR(HELLO_IOC_MAGIC, 1, int)
#define HELLO_IOC_SET_VAL   _IOW(HELLO_IOC_MAGIC, 2, int)
#define HELLO_IOC_RESET     _IO(HELLO_IOC_MAGIC, 3)
#define HELLO_IOC_MAXNR     3

/* ---------- 设备私有数据结构 ---------- */
struct hello_dev {
    struct device       *dev;       // 平台设备
    struct miscdevice    misc;      // misc 设备（自动分配次设备号）
    struct mutex         lock;      // 并发保护
    void __iomem        *regs;      // 映射后的寄存器基址（此处模拟）
    u32                  val;       // 模拟硬件寄存器值
    bool                 enabled;   // 设备使能状态
};

/* ---------- file_operations ---------- */

static int hello_open(struct inode *inode, struct file *file)
{
    struct hello_dev *hdev =
        container_of(file->private_data, struct hello_dev, misc);
    file->private_data = hdev;
    dev_info(hdev->dev, "open\n");
    return 0;
}

static ssize_t hello_read(struct file *file, char __user *buf,
                           size_t count, loff_t *off)
{
    struct hello_dev *hdev = file->private_data;
    u32 val;
    int ret;

    if (*off >= sizeof(val))
        return 0;  // EOF

    mutex_lock(&hdev->lock);
    val = hdev->val;
    mutex_unlock(&hdev->lock);

    ret = copy_to_user(buf, &val, sizeof(val));
    if (ret)
        return -EFAULT;

    *off += sizeof(val);
    return sizeof(val);
}

static ssize_t hello_write(struct file *file, const char __user *buf,
                            size_t count, loff_t *off)
{
    struct hello_dev *hdev = file->private_data;
    u32 val;
    int ret;

    if (count < sizeof(val))
        return -EINVAL;

    ret = copy_from_user(&val, buf, sizeof(val));
    if (ret)
        return -EFAULT;

    mutex_lock(&hdev->lock);
    hdev->val = val;
    mutex_unlock(&hdev->lock);

    dev_info(hdev->dev, "write val=%u\n", val);
    return sizeof(val);
}

static long hello_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
    struct hello_dev *hdev = file->private_data;
    u32 val;
    int ret = 0;

    if (_IOC_TYPE(cmd) != HELLO_IOC_MAGIC)
        return -ENOTTY;
    if (_IOC_NR(cmd) > HELLO_IOC_MAXNR)
        return -ENOTTY;

    switch (cmd) {
    case HELLO_IOC_GET_VAL:
        mutex_lock(&hdev->lock);
        val = hdev->val;
        mutex_unlock(&hdev->lock);
        if (copy_to_user((void __user *)arg, &val, sizeof(val)))
            return -EFAULT;
        break;

    case HELLO_IOC_SET_VAL:
        if (copy_from_user(&val, (void __user *)arg, sizeof(val)))
            return -EFAULT;
        mutex_lock(&hdev->lock);
        hdev->val = val;
        mutex_unlock(&hdev->lock);
        break;

    case HELLO_IOC_RESET:
        mutex_lock(&hdev->lock);
        hdev->val = 0;
        hdev->enabled = false;
        mutex_unlock(&hdev->lock);
        break;

    default:
        ret = -ENOTTY;
    }
    return ret;
}

static const struct file_operations hello_fops = {
    .owner          = THIS_MODULE,
    .open           = hello_open,
    .read           = hello_read,
    .write          = hello_write,
    .unlocked_ioctl = hello_ioctl,
    .compat_ioctl   = hello_ioctl,   // 32 位用户进程兼容
};

/* ---------- sysfs 属性 ---------- */

static ssize_t val_show(struct device *dev, struct device_attribute *attr,
                         char *buf)
{
    struct hello_dev *hdev = dev_get_drvdata(dev);
    u32 val;
    mutex_lock(&hdev->lock);
    val = hdev->val;
    mutex_unlock(&hdev->lock);
    return scnprintf(buf, PAGE_SIZE, "%u\n", val);
}

static ssize_t val_store(struct device *dev, struct device_attribute *attr,
                          const char *buf, size_t count)
{
    struct hello_dev *hdev = dev_get_drvdata(dev);
    u32 val;
    int ret;

    ret = kstrtou32(buf, 10, &val);
    if (ret)
        return ret;

    mutex_lock(&hdev->lock);
    hdev->val = val;
    mutex_unlock(&hdev->lock);
    return count;
}
static DEVICE_ATTR_RW(val);

static ssize_t enabled_show(struct device *dev, struct device_attribute *attr,
                             char *buf)
{
    struct hello_dev *hdev = dev_get_drvdata(dev);
    return scnprintf(buf, PAGE_SIZE, "%d\n", hdev->enabled);
}
static DEVICE_ATTR_RO(enabled);

static struct attribute *hello_attrs[] = {
    &dev_attr_val.attr,
    &dev_attr_enabled.attr,
    NULL,
};
ATTRIBUTE_GROUPS(hello);

/* ---------- platform_driver 生命周期 ---------- */

static int hello_probe(struct platform_device *pdev)
{
    struct hello_dev *hdev;
    struct resource *res;
    int ret;

    hdev = devm_kzalloc(&pdev->dev, sizeof(*hdev), GFP_KERNEL);
    if (!hdev)
        return -ENOMEM;

    mutex_init(&hdev->lock);
    hdev->dev = &pdev->dev;
    hdev->val = 0;
    hdev->enabled = true;

    /* 映射寄存器（真实硬件用 IORESOURCE_MEM；此处无真实寄存器，容错跳过） */
    res = platform_get_resource(pdev, IORESOURCE_MEM, 0);
    if (res) {
        hdev->regs = devm_ioremap_resource(&pdev->dev, res);
        if (IS_ERR(hdev->regs))
            return PTR_ERR(hdev->regs);
    } else {
        dev_info(&pdev->dev, "no MEM resource, run in virtual mode\n");
    }

    /* misc 设备：自动分配次设备号，节点 /dev/hello */
    hdev->misc.minor = MISC_DYNAMIC_MINOR;
    hdev->misc.name  = "hello";
    hdev->misc.fops  = &hello_fops;
    hdev->misc.groups = hello_groups;   // 绑定 sysfs 属性组

    ret = misc_register(&hdev->misc);
    if (ret) {
        dev_err(&pdev->dev, "misc_register failed: %d\n", ret);
        return ret;
    }

    /* 把私有数据挂到 device，sysfs show/store 可反取 */
    platform_set_drvdata(pdev, hdev);
    dev_set_drvdata(hdev->misc.this_device, hdev);

    dev_info(&pdev->dev, "probe ok, /dev/%s created\n", hdev->misc.name);
    return 0;
}

static int hello_remove(struct platform_device *pdev)
{
    struct hello_dev *hdev = platform_get_drvdata(pdev);
    misc_deregister(&hdev->misc);   // devm_* 自动释放 hdev / regs
    dev_info(&pdev->dev, "remove\n");
    return 0;
}

/* ---------- 设备树匹配表 ---------- */
static const struct of_device_id hello_of_match[] = {
    { .compatible = "vendor,ctl", },
    { /* sentinel */ }
};
MODULE_DEVICE_TABLE(of, hello_of_match);

static struct platform_driver hello_driver = {
    .probe  = hello_probe,
    .remove = hello_remove,
    .driver = {
        .name = "hello-plat",
        .of_match_table = hello_of_match,
        .owner = THIS_MODULE,
    },
};
module_platform_driver(hello_driver);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("FlyBigPig");
MODULE_DESCRIPTION("Android 14 platform char device driver instance");
```

---

## 三、配套文件

### 3.1 Kconfig

**`drivers/hello/Kconfig`**
```kconfig
config HELLO_PLAT_DRIVER
    tristate "Hello platform char device driver"
    depends on HAS_IOMEM
    help
      Example platform char device driver for Android 14 GKI.
      Build as module (=m).
```

### 3.2 Makefile

**`drivers/hello/Makefile`**
```makefile
obj-$(CONFIG_HELLO_PLAT_DRIVER) += hello_plat.o
```

### 3.3 设备树（真实板级）

**`arch/arm64/boot/dts/vendor/board.dtsi`**
```dts
/ {
    hello_ctl: hello-ctl@0 {
        compatible = "vendor,ctl";
        /* 真实硬件解开下面这行，指向控制器寄存器区间 */
        /* reg = <0x0 0x40010000 0x0 0x1000>; */
        status = "okay";
    };
};
```

### 3.4 厂商 defconfig

```
CONFIG_HELLO_PLAT_DRIVER=m
```

### 3.5 Bazel 构建（Android 14 标准）

```python
# kernel/vendor/BUILD.bazel
load("//build/kernel/kleaf:kernel.bzl", "kernel_module",
     "vendor_boot_modules_install")

kernel_module(
    name = "hello_plat_module",
    srcs = ["drivers/hello/hello_plat.c"],
    kernel_build = "//kernel/vendor:vendor_kernel_build",
)

vendor_boot_modules_install(
    name = "vendor_boot_modules",
    kernel_modules = ["//kernel/vendor:hello_plat_module"],
    kernel_build = "//kernel/vendor:vendor_kernel_build",
)
```

---

## 四、用户空间测试程序

**`tools/hello_test.c`**（宿主机交叉编译或设备上编译）
```c
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <string.h>

#define HELLO_IOC_MAGIC 'H'
#define HELLO_IOC_GET_VAL _IOR(HELLO_IOC_MAGIC, 1, int)
#define HELLO_IOC_SET_VAL _IOW(HELLO_IOC_MAGIC, 2, int)
#define HELLO_IOC_RESET   _IO(HELLO_IOC_MAGIC, 3)

int main(int argc, char *argv[])
{
    int fd = open("/dev/hello", O_RDWR);
    if (fd < 0) { perror("open"); return 1; }

    int w = 1234;
    write(fd, &w, sizeof(w));
    printf("write: %d\n", w);

    int r = 0;
    read(fd, &r, sizeof(r));
    printf("read:  %d\n", r);

    int set = 5678;
    ioctl(fd, HELLO_IOC_SET_VAL, &set);
    int got = 0;
    ioctl(fd, HELLO_IOC_GET_VAL, &got);
    printf("ioctl get: %d\n", got);

    ioctl(fd, HELLO_IOC_RESET);
    printf("reset done\n");

    close(fd);
    return 0;
}
```

编译运行：
```bash
# 设备端
aarch64-linux-android-gcc hello_test.c -o hello_test
adb push hello_test /data/local/tmp/
adb shell chmod +x /data/local/tmp/hello_test
adb shell /data/local/tmp/hello_test
```

---

## 五、加载与验证

```bash
# 1. 推模块并加载
adb push hello_plat.ko /data/local/tmp/
adb shell insmod /data/local/tmp/hello_plat.ko

# 2. 检查设备及 sysfs
adb shell ls -l /dev/hello
adb shell ls /sys/class/misc/hello/
adb shell cat /sys/class/misc/hello/val
adb shell cat /sys/class/misc/hello/enabled
adb shell "echo 99 > /sys/class/misc/hello/val"

# 3. 内核日志
adb shell dmesg | grep hello

# 4. 模块列表
adb shell lsmod | grep hello_plat

# 5. 卸载
adb shell rmmod hello_plat
```

---

## 六、关键 API 速查（本实例用到）

| API | 作用 | 说明 |
|-----|------|------|
| `module_platform_driver()` | 注册 platform 驱动 | 自动展开 module_init/exit |
| `of_device_id` + `MODULE_DEVICE_TABLE(of,)` | 设备树匹配 | compatible 字符串匹配 |
| `devm_kzalloc()` | 申请内存 | 设备解绑时自动释放 |
| `devm_ioremap_resource()` | 映射寄存器 | 自动 unmap |
| `misc_register()` | 注册 misc 设备 | 自动建 `/dev/xxx`，免手动 alloc_chrdev_region |
| `container_of()` | 反推结构体 | 从成员指针取宿主结构体 |
| `DEVICE_ATTR_RW/RO` | 定义 sysfs 属性 | 配合 `ATTRIBUTE_GROUPS` |
| `mutex_lock/unlock` | 并发保护 | 替代裸全局变量 |
| `_IOR/_IOW/_IO` | 定义 ioctl 命令 | 含方向/大小/魔数校验 |

---

## 七、与原「hello.c」对比（为什么这样写更好）

| 点 | 原 hello.c | 本实例 |
|----|-----------|--------|
| 设备号 | `register_chrdev` 手动 | `miscdevice` 自动分配 |
| 生命周期 | `module_init/exit` 裸写 | `platform_driver` + `devm_*` 自动释放 |
| 硬件来源 | 无 | 设备树 `compatible` 匹配 |
| 寄存器 | 无 | `devm_ioremap_resource`（可选） |
| 并发 | 无锁 | `mutex` 保护 |
| 接口 | 仅 read/write | read/write + ioctl + sysfs |
| 触发 | 加载即生效 | probe 时由设备树节点触发 |

---

## 八、实战要点（踩坑）

1. **`container_of` 的 private_data**：`misc_register` 后，`file->private_data` 在 `open` 前已被设为 `&misc`，所以用 `container_of(file->private_data, struct hello_dev, misc)` 反推——不要直接覆盖成别的。
2. **sysfs 与 misc 绑定**：把 `groups` 设到 `misc.groups`，属性会出现在 `/sys/class/misc/hello/` 下；`dev_get_drvdata` 取的是 `dev_set_drvdata(misc.this_device, hdev)` 挂的数据。
3. **ioctl 魔数冲突**：`HELLO_IOC_MAGIC` 选未占用的字母；`_IOC_TYPE`/`_IOC_NR` 校验能拦掉大部分非法命令。
4. **GKI 符号**：本实例用的 `misc_register`、`devm_ioremap_resource`、`mutex_*` 都在 GKI 白名单内，可放心作为 `=m` 模块。
5. **`remove` 不重复释放**：`devm_*` 资源不要手动 `kfree`/`iounmap`，否则双击 `rmmod` 会 double-free。

---

> **文档版本：** v1.0
> **适用：** Android 14 (android14-6.1 GKI 2.0) 厂商可加载内核模块
> **最后更新：** 2026-08-10
