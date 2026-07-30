# android-aosp-agent — AOSP 系统层 agent harness 骨架

把 agent 回环从「App 进程内(AccessibilityService)」抬到「系统层(system_server 服务 + 可选 native daemon)」。
这是 `android-aosp-ai-agent.md` 设计文档的**可落地骨架**:核心分法是 **daemon=大脑(LLM/编排),system_service=手(特权执行)**。

目标 Android 版本:**Android 14 (UpsideDownCake, API 34)**。

---

## 一、架构

```
┌─────────────────────────────────────────────┐
│  App / 客户端 (MANAGE_AI_AGENTS 权限)         │
│   Context.getSystemService("aiagent")         │
└───────────────┬───────────────────────────────┘
                │ Binder (/dev/binder, 框架域)
┌───────────────▼───────────────────────────────┐
│  system_server                                 │
│   AIAgentManagerService  (IAIAgentManager.Stub)│
│    ├─ DeviceActions: 系统特权"手"              │
│    │    • InputManager.injectInputEvent (tap)  │
│    │    • AMS startActivityAsUser (open_app)    │
│    │    • Settings.Global/Secure (set_setting)  │
│    │    • ActivityTaskManager.getTasks (getUI)  │
│    ├─ ToolRegistry / DeviceTools (9 个工具)     │
│    └─ AgentLoop (感知→决策→行动) + MockLlmClient│
└───────────────┬───────────────────────────────┘
                │ Binder (可选)
┌───────────────▼───────────────────────────────┐
│  native daemon: /system/bin/aiagent (rc 启动)  │
│   "大脑":LLM 推理 + Orchestrator + workers     │
│   当前为占位骨架,接真实模型时填这里            │
└───────────────────────────────────────────────┘
```

关键差异(相对 in-app agent):
- **手** 从 `AccessibilityService.dispatchGesture` / `ACTION_SET_TEXT` 换成 system_server 特权 API,**不再需要无障碍权限**。
- **眼睛** 仍建议保留一个 `AccessibilityService`(或 `uiautomator dump`)提供完整可交互树;`getUI()` 先返回包名/Activity/尺寸做 MVP 验证。
- **脑** 可在 native daemon 跑(更隔离、可常驻),也可先放 system_server(Mock 验证)。

---

## 二、目录结构(镜像 AOSP 树,可直接 port)

```
android-aosp-agent/
├── frameworks/base/core/java/android/os/aiagent/
│   ├── IAIAgentManager.aidl      # Binder 接口
│   ├── AIAgentRequest.java       # Parcelable 入参
│   └── AIAgentManager.java       # 公开包装类
├── frameworks/base/services/core/java/com/android/server/aiagent/
│   ├── AIAgentManagerService.java # 系统服务 + 特权动作(DeviceActions)
│   ├── AgentState.java            # 回环状态
│   ├── Tool.java / ToolRegistry.java
│   ├── DeviceActions.java / DeviceTools.java   # 系统特权工具
│   ├── LlmClient.java / MockLlmClient.java      # LLM 抽象 + Mock
│   └── AgentLoop.java             # 感知-决策-行动回环
├── frameworks/native/services/aiagent/
│   ├── aiagent.cpp / Android.bp / aiagent.rc   # 大脑 daemon 骨架
├── patches/
│   ├── SystemServer.patch         # startOtherServices 启动服务
│   ├── Context.patch              # AI_AGENT_SERVICE 常量
│   ├── SystemServiceRegistry.patch# 注册 AIAgentManager
│   └── AndroidManifest.patch      # MANAGE_AI_AGENTS 权限
├── system/sepolicy/
│   ├── private/service_contexts.append
│   ├── private/aiagent.te
│   └── public/service.te.append
└── README.md
```

---

## 三、Apply 步骤

1. **拷贝框架文件**到你的 AOSP 树(路径已对齐):
   ```
   cp -r frameworks/base  <AOSP>/frameworks/
   cp -r frameworks/native/services/aiagent <AOSP>/frameworks/native/services/
   ```
2. **打补丁**(用 `git apply` 或手动合):
   ```
   cd <AOSP>/frameworks/base
   git apply patches/Context.patch
   git apply patches/SystemServiceRegistry.patch
   git apply patches/AndroidManifest.patch
   git apply patches/SystemServer.patch
   ```
3. **SELinux**:把 `service_contexts.append` 行追加到 `<AOSP>/system/sepolicy/private/service_contexts`,
   把 `service.te.append` 追加到 `public/service.te`,把 `aiagent.te` 放到 `private/aiagent.te`。
4. **编译**:
   ```
   source build/envsetup.sh && lunch aosp_<device>-userdebug
   m frameworks-minus-apex services   # 编 framework + services.jar
   m aiagent                          # 编 native daemon(可选)
   ```
   `frameworks/base/core` 下的 AIDL 会被 framework 构建自动编译进 `android.os.aiagent` 包。
5. **刷机 / 推包**:
   ```
   fastboot flash system system.img      # 或 m snod 重新打包后刷
   adb push out/target/.../services.jar /system/framework/   # 单编后 adb push
   ```

---

## 四、与 in-app agent 的逐文件映射(约 70% 可复用)

| in-app agent (Kotlin) | AOSP 版 (Java) | 变化 |
|---|---|---|
| `agent/AgentLoop.kt` | `aiagent/AgentLoop.java` | 原样搬,Java 化,跑在 system_server |
| `tools/Tool.kt` | `aiagent/Tool.java` | 同构 |
| `tools/ToolRegistry.kt` | `aiagent/ToolRegistry.java` | 同构,+`toLlmToolsJson()` |
| `tools/DeviceTools.kt` | `aiagent/DeviceTools.java` | 动作体从 AccessibilityService 换 DeviceActions |
| `llm/LlmClient.kt` | `aiagent/LlmClient.java` | 同构 |
| `llm/MockLlmClient.kt` | `aiagent/MockLlmClient.java` | 同构 |
| `perception/Perception.kt`(UiNode 解析) | 暂以 `getUI()` 返回包名/尺寸 | MVP 验证用,完整树后续接 AccessibilityService |
| `AgentAccessibilityService.kt`(手) | `AIAgentManagerService` + `DeviceActions` | **核心替换**:特权 API 取代无障碍动作 |
| `MainActivity.kt`(UI) | 客户端经 `Context.getSystemService("aiagent")` | 改为系统 API 调用 |
| `OpenAiLlmClient.kt` | (TODO) 接真实模型 | 后续把 OkHttp 客户端搬进 daemon |

---

## 五、adb 调试命令

```bash
# 1. 看服务是否注册成功
adb shell service list | grep aiagent

# 2. 看回环状态(Mock 跑通后)
adb shell service call aiagent 9   # getState 的序号需按 AIDL 方法序确认

# 3. 接本地 8081 模型(在设备上跑 daemon 时)
adb reverse tcp:8081 tcp:8081

# 4. 看 daemon 日志
adb logcat -s aiagent

# 5. 验证特权动作:
#    submitGoal 后,getUI 应返回前台 pkg/activity,
#    tap_xy 应能在屏幕上产生真实点击(无需无障碍权限)。
```

---

## 六、MVP 验证路径

1. 编过 + 刷机 → `service list` 里出现 `aiagent`。
2. 用系统/特权 App(或 `adb shell` 经 `service call`)调 `submitGoal(useMock=true)`。
3. MockLlmClient 第一步 `get_ui` → 观察 `getState()` 里 `lastObservation` 含前台包名/尺寸;第二步 `finish` → `running=false`。
4. 替换 `MockLlmClient` 为真实 LLM 客户端(接 8081 或端侧 NNAPI),跑真实决策回环。

---

## 七、已知坑 / 注意

- **SELinux 最易翻车**:漏 `service_contexts` 会导致服务查不到;权限开太大触发 `neverallow` 编译失败。
- **`MANAGE_AI_AGENTS` 必须 `signature|privileged`**,只有系统/特权 App 能拿到;调试可临时给 shell 或 `adb shell` 加 domain 例外(不进正式构建)。
- **native AIDL**:`aiagent.cpp` 依赖 AIDL 生成的 NDK 头,需把 `IAIAgentManager.aidl` 作为 `aidl_interface` 声明后再编 daemon。
- **注入输入**:`InputManager.injectInputEvent` 在 system_server(uid 1000)内直接调用不受 `INJECT_EVENTS` 限制;若经 Binder 从外部调,需确保调用方有该权限或走豁免。
