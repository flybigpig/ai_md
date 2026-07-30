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

---

## 八、LLM 接入(OpenAiLlmClient)

`OpenAiLlmClient` 已实现并接入 `AIAgentManagerService.submitGoal`:`useMock=false` 时走
`HttpURLConnection` 调 `${baseUrl}/chat/completions`,**零新依赖**(只用 boot classpath 的
`java.net` + `org.json`)。

### 8.1 调用方式
```java
// AIAgentManagerService.submitGoal 内部:
LlmClient client = new OpenAiLlmClient(request.getBaseUrl(), request.getModel());
```
客户端构造时拿到 `baseUrl`(如 `http://127.0.0.1:8081/v1`)与 `model`(如 `qwen`)。

### 8.2 双协议兼容
- **tool calling**(首选):解析 `choices[0].message.tool_calls[].function.{name,arguments}`。
- **JSON 兜底**(本地小模型 tool calling 不稳时):从 `content` 抠
  `{"tool":"tap_xy","args":{"x":..,"y":..}}` 或 `{"action":..,"args":..}`,自动去 ```json 围栏。
  若模型只回自然语言,则 `finished=true` 并回显,避免死循环。

### 8.3 在设备上接本地 8081(llama.cpp)
```bash
# 设备侧把宿主 8081 映射进设备
adb reverse tcp:8081 tcp:8081
# submitGoal 传 baseUrl=http://127.0.0.1:8081/v1, model=qwen, useMock=false
# 本地端点无鉴权;云端端点可在 OpenAiLlmClient.post() 加 Bearer(代码已留注释位)
```

### 8.4 SELinux:放行 system_server 出网(接本地 8081)
system_server 默认不能随便建出站 socket,需补规则(localhost 也走 tcp_socket):
```te
# system/sepolicy/private/aiagent.te 追加
allow system_server self:tcp_socket { create connect read write };
# 若只连 127.0.0.1:8081,更稳的是定义具体 node:
# type local_llm_port node_type, mlstrustedsubject;
# allow system_server local_llm_port:tcp_socket name_connect;
```
> 注意:给 system_server 开 `tcp_socket` 较宽,正式构建建议收敛到具体端口 node。

### 8.5 为什么不放 OkHttp / 在 native daemon 里跑
- **Java 框架进程加 OkHttp**:会拖重 `services` 模块构建,且易触发 SELinux `neverallow`
  与 `framework` 库依赖冲突。boot classpath 自带 `HttpURLConnection`,够用。
- **native daemon 跑 LLM 运行时**(大脑与手分离):更隔离、可常驻。此时 `aiagent.cpp`
  需用 AIDL 生成的 NDK 代理(`android.os.aiagent.IAIAgentManager::fromBinder`)调本服务执行,
  网络在 `aiagent` 域里走(见 `aiagent.te` 的 `tcp_socket`/`portcache` 规则)。两者二选一,
  MVP 用 Java 侧 `OpenAiLlmClient` 最省事。
