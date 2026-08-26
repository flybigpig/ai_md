# Android InputManagerService (IMS) — AOSP 14 Reference

## Architecture Overview

```
┌─────────────────────────────────────────────────┐
│  App (InputTransport)                           │
│       ↓                                         │
│  InputDispatcher (native)                       │
│       ↓                                         │
│  InputReader (native) → EventHub                │
│       ↓                                         │
│  Kernel (evdev / android_binder)                │
└─────────────────────────────────────────────────┘
```

## Key Source Paths (AOSP 14)

### Java Layer
- `frameworks/base/core/java/android/view/IInputManager.aidl` — Binder interface
- `frameworks/base/core/java/android/view/InputManager.java` — Public API
- `frameworks/base/services/core/java/com/android/server/inputpolicy/InputManagerService.java` — **Main service entry point** (~2000+ lines)

### Native Layer (Core)
- `frameworks/native/services/inputflinger/InputManager.cpp` — InputManager startup, creates Dispatcher + Reader
- `frameworks/native/services/inputflinger/InputDispatcher.cpp` — Event dispatching (most complex, ~3000 lines)
- `frameworks/native/services/inputflinger/InputReader.cpp` — Event reading/parsing
- `frameworks/native/services/inputflinger/EventHub.cpp` — Low-level device enumeration + evdev reading

## Startup Chain

```
InputManagerService.java:onStart()          ← System service startup
    └─ nativeInit()                         ← Calls native layer
        └─ InputManager::start()            ← frameworks/native/.../InputManager.cpp
            ├─ create InputDispatcher       ← Responsible for dispatching
            └─ create InputReader           ← Responsible for reading device events
```

## InputDispatcher Core Logic

**Dispatch Flow** (`InputDispatcher.cpp`):
1. `notifyTouch()` / `notifyKey()` — Receive events from InputReader
2. `processEventsLocked()` — Event loop
3. `dispatchEventToReadyTargetsLocked()` — Find target window
4. `finishDispatchCycleLocked()` — Complete dispatch

**Key Methods**:
- `findFocusedWindowTargetsLocked()` — Find focused window
- `computePolledDevicesLocked()` — Handle polling devices
- `getFocusedWindowInternal()` — Get focused window information

## InputReader Core Logic

**Event Loop** (`InputReader.cpp`):
- `loopOnce()` — Main loop, blocks waiting for events
- Reads raw input events via `EventHub::getEvent()`
- Parses into `InputEvent`, passes to Dispatcher

**Device Management**:
- `Device` — Encapsulates a single input device
- `Synthesizer` — Synthesizes virtual events (e.g., double-tap, pinch-zoom)

## Interaction with AMS/WMS

```
IMS.dispatchKey_Event()
    └─→ WMS checks if intercepted
        └─→ Keyguard check
            └─→ Final dispatch to target window
```

- `InputManagerService.interceptKeyBeforeQueueing()` — Keyboard event interception hook
- `IWindowManager.injectInputEvent()` — Inject test events

---

## Learning Path Recommendations

1. **Entry Point**: Read `InputManagerService.java` first to understand the Java-side entry
2. **Native Core**: Dive into `InputDispatcher.cpp` for the most complex dispatch logic
3. **Device Layer**: Study `EventHub.cpp` to understand how input devices are enumerated
4. **Integration**: Trace how IMS interacts with WMS and KeyguardViewMediator

## Related Components

| Component | Path | Responsibility |
|-----------|------|----------------|
| EventHub | `frameworks/native/services/inputflinger/EventHub.cpp` | Device enumeration, evdev reading |
| InputDispatcher | `frameworks/native/services/inputflinger/InputDispatcher.cpp` | Event routing to windows |
| InputReader | `frameworks/native/services/inputflinger/InputReader.cpp` | Raw event parsing |
| InputManager | `frameworks/native/services/inputflinger/InputManager.cpp` | Service lifecycle |
| InputManagerService | `frameworks/base/services/.../inputpolicy/InputManagerService.java` | Java service facade |

---

*Generated for AOSP 14 (UpsideDownCake, API 34) baseline*
