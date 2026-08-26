# Android Framework Learning Guide — AOSP 14

## Table of Contents
1. [Overview](#overview)
2. [Core Services](#core-services)
3. [Key subsystems](#key-subsystems)
4. [Startup Sequence](#startup-sequence)
5. [Learning Resources](#learning-resources)

---

## Overview

**Android 14 (UpsideDownCake, API 34)** is the current baseline for this guide.

### Version Information

| Item | Details |
|------|---------|
| Codename | UpsideDownCake |
| API Level | 34 |
| Release Date | October 2023 |
| Kernel Requirement | GKI 2.0 (6.1 LTS branch) |
| Main Architecture Changes | Project Mainline advancement, ART optimization, multi-user enhancements |

### Key Framework Changes (Related to Research Directions)

- **AMS**: Process priority model adjustments, `AppProcGroup` refactoring
- **WMS**: TaskDisplayArea evolution, foldable screen support enhancement
- **SurfaceFlinger**: CompositionEngine optimization, VSync scheduling improvements
- **Input**: .PointerIcon system refactoring, gesture recognition enhancement
- **Keyguard**: LockSettingsService and KeyguardViewMediator interaction changes
- **Binder**: GKI mandatory specifications, `binder.c` behavior stricter

---

## Core Services

### 1. ActivityManagerService (AMS)

**Path**: `frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java`

**Responsibilities**:
- Process lifecycle management
- Activity stack management
- Process OOM adjustment
- Broadcast scheduling

**Key Methods**:
- `startProcessLocked()` — Start a new process
- `resumeTopActivityInnerLocked()` — Resume top activity
- `updateOomAdjLocked()` — Update OOM adjustment

### 2. WindowManagerService (WMS)

**Path**: `frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java`

**Responsibilities**:
- Window management
- Display management
- Input event routing
- Task/Window animation

**Key Methods**:
- `addWindow()` — Add a window
- `removeWindow()` — Remove a window
- `relayoutWindow()` — Window relayout

### 3. PackageManagerService (PMS)

**Path**: `frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java`

**Responsibilities**:
- App installation/parsing
- Permission management
- Feature detection

### 4. InputManagerService (IMS)

**Path**: `frameworks/base/services/core/java/com/android/server/inputpolicy/InputManagerService.java`

**Responsibilities**:
- Input device management
- Event dispatching
- Key interception

---

## Key Subsystems

### Binder IPC

**Kernel Driver**: `drivers/android/binder.c`
**Userspace**: `frameworks/native/libs/binder/`

**Key Concepts**:
- `binder_transaction` — Transaction structure
- `binder_work` — Work item
- `binder_node` — Binder node
- `binder_ref` — Binder reference

### Zygote

**Path**: `frameworks/base/core/java/com/android/internal/os/ZygoteInit.java`

**Startup Process**:
1. init process starts zygote
2. Preload resources (classes, fonts, etc.)
3. Listen on socket for fork requests

### SurfaceFlinger

**Path**: `frameworks/native/services/surfaceflinger/`

**Key Components**:
- `SurfaceFlinger.cpp` — Main entry
- `Layer.cpp` — Display layer
- `DisplayDevice.cpp` — Display device
- `Scheduler.cpp` — VSync scheduler

---

## Startup Sequence

```
1. init process
   └─ start zygote
2. ZygoteInit.main()
   └─ preload resources
   └─ start system_server
3. SystemServer.main()
   ├─ start CoreService (AMS, PMS, WMS, IMS...)
   └─ start OtherServices
4. AMS.startSystemServer()
   └─ trigger home app launch
5. Launcher starts
```

---

## Learning Resources

### Essential Reading Order

1. **AOSP Source Code**
   - `frameworks/base/` — Java layer
   - `frameworks/native/` — Native layer
   - `drivers/android/` — Binder driver

2. **Key Books**
   - 《Android系统原理与开发重点》
   - 《Android框架解密》

3. **Tools**
   - `dumpsys` — Service status query
   - `monkey` — Stress testing
   - `systrace` — Performance analysis

---

## Quick Reference

| Service | Class | Path |
|---------|-------|------|
| AMS | ActivityManagerService | services/core/java/com/android/server/am/ |
| WMS | WindowManagerService | services/core/java/com/android/server/wm/ |
| PMS | PackageManagerService | services/core/java/com/android/server/pm/ |
| IMS | InputManagerService | services/core/java/com/android/server/inputpolicy/ |
| Power | PowerManagerService | services/core/java/com/android/server/power/ |

---

*Guide for Android 14 (UpsideDownCake) Framework learning*
