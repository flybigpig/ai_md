# Android Framework 面试题 · 第十三篇
## 智能系统主线：AppFunctions × AppSearch × Compose-First × 后量子签名

> **日期**：2026-08-03（周一）
> **基线版本**：AOSP **Android 14（UpsideDownCake, API 34）** 为源码路径基准；涉及 Android 15/16/17（API 37, CinnamonBun, 2026-06-16 stable）的新增能力单独标注。
> **本篇定位**：前十二篇累计 111 个专题，讲透了 Binder/AMS/WMS/SF/ART/HAL/内核/TEE/pKVM 等「系统底座」。但 Android 17 官宣的两条纲领 —— **Compose-First** 与 **Intelligence System** —— 对应的 Framework 机制（AppFunctions、AppSearch、Compose 编译器插件、语义树、后量子签名）**在前十二篇中是完整空白**。本篇专门补这条主线。
> **今日热点锚点**：
> 1. Android 17 正式官宣 **Compose-First**：所有新 API/库/工具只面向 Jetpack Compose，Fragment / RecyclerView / ViewPager / `android.widget` 全家进入 **maintenance mode**（只修严重 bug，不加新功能）。
> 2. **AppFunctions** 成为 Android 原生的 MCP（Model Context Protocol）等价物 —— 应用把能力暴露成「可被 AI Agent 发现并执行的函数」，通过 Google Play Services 下发，**Android 16+ 即可用**，不锁定 A17。
> 3. **后量子密码进平台**：安全硬件支持 **ML-DSA**（FIPS 204）密钥生成，新增 **APK 签名方案 v3.2**，在传统签名之上叠加抗量子签名。
> 4. **Memory Limiter 的可观测面**：`ApplicationExitInfo.getDescription()` 新增 `MemoryLimiter:AnonSwap` 死因。
> 5. **隐私范式转向**：从「申请权限」转为「系统托管 UI + 一次性凭证」—— Contacts Picker、Photo Picker、系统渲染位置按钮、EyeDropper API。

---

## 目录

| 章节 | 主题 | 难度 | 面试热度 |
|---|---|---|---|
| §1 | AppFunctions 全链路：注册 / 发现 / 执行三条链 | ★★★★☆ | 🔥🔥🔥🔥🔥（2026 最大增量） |
| §2 | AppSearch 与 Icing：设备端搜索引擎的 Framework 实现 | ★★★★☆ | 🔥🔥🔥🔥 |
| §3 | Compose 编译器插件：`@Composable` 到底被编译成了什么 | ★★★★★ | 🔥🔥🔥🔥🔥 |
| §4 | Compose 运行时：SlotTable / Snapshot / Recomposer 三件套 | ★★★★★ | 🔥🔥🔥🔥🔥 |
| §5 | Compose 与 Framework 的六个接缝（面试最爱追问处） | ★★★★☆ | 🔥🔥🔥🔥 |
| §6 | APK 签名方案 v1→v3.2：后量子密码如何进入 PMS 校验链路 | ★★★★☆ | 🔥🔥🔥🔥 |
| §7 | ApplicationExitInfo：把「进程为什么没了」问到底 | ★★★☆☆ | 🔥🔥🔥🔥🔥 |
| §8 | 系统托管 UI 与 URI 授权：隐私范式从「权限」到「凭证」 | ★★★★☆ | 🔥🔥🔥🔥 |
| §9 | 无障碍语义树与 AI Agent UI 自动化 | ★★★★☆ | 🔥🔥🔥🔥 |
| §10 | 查缺补漏 · 易错点速记 · 十三篇交叉索引 | — | — |

---

# §1 AppFunctions 全链路：注册 / 发现 / 执行

## Q1.1 什么是 AppFunctions？它和 Intent、AIDL、MCP 的本质区别是什么？

**一句话定义**：AppFunctions 让应用把自己的关键业务动作声明成**带 schema 的自描述函数**，由系统建立**中心化索引**，授权的调用方（AI Agent / 助手 / 系统主动式功能）可以**发现并执行**它们，全程**不需要打开目标 App 的 UI**。

**四者对比（面试必答）**：

| 维度 | Intent | AIDL / Binder Service | MCP（服务端） | **AppFunctions** |
|---|---|---|---|---|
| 契约描述 | action + extras（**弱类型、无 schema**） | `.aidl` 接口（编译期强类型，但**机器不可读语义**） | JSON Schema + 自然语言描述 | **KDoc → LLM 可读描述 + 结构化 schema** |
| 发现机制 | PMS `queryIntentActivities`（按 action 匹配） | 需提前知道包名+服务名 | 服务端 `tools/list` | **AppSearch 全设备索引查询** |
| 调用方 | 任意 App | 需要 bind 权限 | 远端 LLM | 持 `EXECUTE_APP_FUNCTIONS` 权限的调用方 |
| 语义可理解性 | ❌ Agent 不知道这个 Intent 干什么 | ❌ 同上 | ✅ | ✅ **为 LLM 工具调用而设计** |
| 是否需拉起 UI | 通常需要 | 否 | — | **否**（纯后台执行） |
| 用户可控性 | 系统选择器 | 无 | — | **每函数级开关**（runtime metadata） |

**关键洞察**：Intent 的失败之处在于**语义不可发现**。Agent 拿到 `ACTION_INSERT` 不知道能插什么、参数含义是什么。AppFunctions 的核心创新就是把 **KDoc 注释提升为一等公民的机器可读契约**（`@AppFunction(isDescribedByKDoc = true)`），这是"为 LLM 设计 API"的范式转变。

## Q1.2 画出 AppFunctions 的三层架构与三条链路

```
┌──────────────────────────┐   ┌───────────────────────────────┐   ┌──────────────────────────┐
│      Agent App           │   │        system_server          │   │      Provider App        │
│      (Caller)            │   │                               │   │                          │
├──────────────────────────┤   ├───────────────────────────────┤   ├──────────────────────────┤
│                          │   │                               │   │  res/xml/                │
│  AppSearchManager        │──query──▶ AppSearchManagerService  │◀─parse─│ app_functions.xml     │
│   .search(SearchSpec)    │   │        (AppSearchImpl/Icing)  │   │  app_function_schema.xml │
│                          │   │                               │   │  （注解处理器编译期生成） │
├──────────────────────────┤   ├───────────────────────────────┤   ├──────────────────────────┤
│                          │   │                               │   │                          │
│  AppFunctionManager      │──call───▶ AppFunctionManagerService│──bind──▶ MyAppFunctionService │
│   .executeAppFunction()  │   │   ① 权限校验 EXECUTE_APP_FUNCTIONS │   │  (extends AppFunctionService)│
│   .setAppFunctionEnabled()│  │   ② JoinSpec 查 static+runtime │   │   onExecuteFunction()    │
│   .isAppFunctionEnabled()│   │   ③ bindService + 超时管控     │   │                          │
│                          │   │   ④ OutcomeReceiver 回调分发   │   │                          │
└──────────────────────────┘   └───────────────────────────────┘   └──────────────────────────┘
```

**三条链路的触发时机**：

| 链路 | 触发时机 | 关键组件 | 做了什么 |
|---|---|---|---|
| **注册链路** | App 安装 / 更新 | `AppsIndexerManagerService` → XML 解析 → `AppSearchManagerService` | 解析 `app_functions.xml`，写入 AppSearch 的 `AppFunctionStaticMetadata` |
| **发现链路** | Agent 主动查询 | `AppSearchManager` → `AppSearchManagerService` | 从 AppSearch 数据库检索已注册函数元数据 |
| **执行链路** | Agent 发起调用 | `AppFunctionManager` → `AppFunctionManagerService` → Provider 的 `AppFunctionService` | Binder IPC 中转 + 权限校验 + 服务绑定 + 回调分发 |

**源码路径（Android 14/15/16 基线，AppSearch 是 Mainline 模块）**：
- `packages/modules/AppSearch/service/java/com/android/server/appsearch/AppSearchManagerService.java`
- `packages/modules/AppSearch/service/java/com/android/server/appsearch/appsindexer/AppsIndexerManagerService.java`
- `packages/modules/AppSearch/service/java/com/android/server/appsearch/appsindexer/AppFunctionStaticMetadataParser.java`
- `packages/modules/AppSearch/framework/java/external/android/app/appsearch/`
- `frameworks/base/core/java/android/app/appfunctions/`（`AppFunctionManager`、`ExecuteAppFunctionRequest/Response`、`AppFunctionService`）
- Jetpack 侧：`androidx.appfunctions:appfunctions-runtime` / `-compiler`（KSP 注解处理器）

## Q1.3 编译期发生了什么？`@AppFunction` 注解是怎么变成 XML 的？

**这是面试区分度最高的追问**。回答要点：

1. **KSP（Kotlin Symbol Processing）注解处理器**扫描 `@AppFunction` 与 `@AppFunctionSerializable`。
2. 从函数签名提取**参数名、类型、是否可空、默认值**；从 KDoc 提取**函数用途描述、每个 `@param` 的自然语言说明、`@return` 说明** —— 这些直接变成 LLM 的 tool description。
3. 生成两份产物：
   - `res/xml/app_functions.xml`：函数清单（`functionId`、`enabledByDefault`、`schemaName/schemaCategory/schemaVersion`）
   - 一个 `AppFunctionService` 子类的**分发桩代码**（把 `functionId` + `GenericDocument` 参数反序列化后调到你的真实函数上）
4. `AndroidManifest.xml` 中通过 `<property android:name="android.app.appfunctions" android:resource="@xml/app_functions"/>` 挂载。
5. 同时声明服务：
```xml
<service android:name=".MyAppFunctionService"
         android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
         android:exported="true">
    <intent-filter>
        <action android:name="android.app.appfunctions.AppFunctionService"/>
    </intent-filter>
</service>
```

**易错点**：`android:permission="BIND_APP_FUNCTION_SERVICE"` 是**给系统看的**——它声明「只有持有该权限的进程才能 bind 我」，而 `system_server` 持有它。这与 `AccessibilityService`、`NotificationListenerService` 的保护模式完全一致：**exported=true 但用 signature 级权限锁死绑定方**。答不出这一条，说明没理解 Android 服务暴露的安全范式。

## Q1.4 AppSearch 里存了几层元数据？为什么要分两层？

**两层元数据 + JoinSpec 关联查询**：

| 层次 | Schema 类型 | 内容 | 写入者 | 读取者 |
|---|---|---|---|---|
| **静态元数据** | `AppFunctionStaticMetadata` | XML 声明的函数信息：`functionId`、`packageName`、`enabledByDefault`、`schemaCategory`、`schemaName`、`schemaVersion`、`displayNameRes` | `appsindexer`（**安装时**） | `AppFunctionManagerService` |
| **运行时元数据** | `AppFunctionRuntimeMetadata` | 用户/应用控制的状态：`enabled`（ENABLED / DISABLED / DEFAULT） | `AppFunctionManager.setAppFunctionEnabled()` | `AppFunctionManagerService` |

**为什么分两层**（面试标准答案）：
- **注册与授权解耦**。静态元数据由安装决定，App 无法在运行时随意增删函数（防止绕过索引审查）；运行时元数据允许用户/应用**关掉**某个函数，但不能凭空**造出**函数。
- 静态元数据随 APK 卸载自动清理；运行时元数据需要单独的生命周期管理。
- 查询时用 **`JoinSpec`**（AppSearch 的 join 能力）把两个 namespace 的文档关联，最终计算出「这个函数当前是否可被调用」：
  ```
  effectiveEnabled = (runtime.enabled == DEFAULT)
                       ? static.enabledByDefault
                       : (runtime.enabled == ENABLED)
  ```

**这体现了 Android 一个反复出现的设计思路：静态声明（编译/安装期，不可变）+ 运行时覆盖（可变、可撤销）**。同样的思路出现在：
- 权限：manifest 声明 + runtime grant（`PermissionManagerService`）
- 兼容性变更：`@ChangeId` 编译期常量 + `am compat` 运行时覆盖（见 **第十一篇 §1**）
- 通知渠道：代码创建 channel + 用户在设置里关

## Q1.5 执行链路的完整时序，以及三个关键风控点

```
Agent App                system_server                        Provider App
    │                          │                                     │
    │ executeAppFunction(req,  │                                     │
    │   executor, cancelSig,   │                                     │
    │   OutcomeReceiver)       │                                     │
    ├─────Binder──────────────▶│                                     │
    │                          │ ① checkPermission(                  │
    │                          │     EXECUTE_APP_FUNCTIONS)          │
    │                          │    + 校验 caller 是否被允许调用      │
    │                          │      该 targetPackage               │
    │                          │                                     │
    │                          │ ② AppSearch JoinSpec 查询：         │
    │                          │    static ⋈ runtime → enabled?      │
    │                          │    不存在/被禁 → RESULT_DENIED      │
    │                          │                                     │
    │                          │ ③ bindService(                      │
    │                          │     ACTION_APP_FUNCTION_SERVICE,    │
    │                          │     BIND_AUTO_CREATE)               │
    │                          ├────────────────────────────────────▶│
    │                          │                                     │ onBind()
    │                          │ ④ onExecuteFunction(request,        │
    │                          │      callingPackage, cancelSig, cb) │
    │                          ├────────────────────────────────────▶│
    │                          │                                     │ 业务执行
    │                          │◀────── ExecuteAppFunctionResponse ──┤
    │◀──── OutcomeReceiver ────┤ ⑤ 结果透传 + unbind + 超时兜底       │
    │      .onResult/.onError  │                                     │
```

**三个必须讲出来的风控点**：

1. **`EXECUTE_APP_FUNCTIONS` 是 role/privileged 级权限**，不是普通 App 想申请就能拿。Gemini、系统助手、Digital Assistant Role 持有者才有。这防止了「恶意 App 遍历全设备函数并批量执行」。
2. **调用方身份不可伪造**：Provider 在 `onExecuteFunction()` 里拿到的 `callingPackage` 是 **system_server 填的**，不是 Binder 直接透传的 `getCallingUid()`。因为中间隔了一层 system_server 中转，Provider **无法**用 `Binder.getCallingUid()` 判断真实发起方（那会拿到 SYSTEM_UID）。
   > **对照第十二篇 §3**：跨 pVM 的 RPC Binder 同样存在 `getCallingUid()` 不可信问题，但原因不同 —— 那是因为跨 VM 没有内核 Binder 的 uid 注入；这里是因为**多了一跳中转**。面试官很爱把这两个场景放一起问「什么时候 `getCallingUid()` 不可信」。
3. **执行超时与取消**：`CancellationSignal` 全程透传，system_server 侧有 watchdog，Provider 长时间不回调会被 unbind 并返回 `RESULT_TIMED_OUT`。Provider 侧**不应**在 `onExecuteFunction` 里做无限阻塞操作——它跑在 Binder 线程池，会耗尽线程（回顾 **第一篇 Binder 线程池** 的 16 线程上限）。

## Q1.6 高频追问链

- **Q：AppFunctions 和 App Actions / Slices 是什么关系？**
  A：App Actions（shortcuts.xml + BII，Built-in Intents）是**上一代**方案，本质仍是 Intent 分发，语义靠 Google 预定义的 BII 目录穷举，扩展性差。Slices 是 UI 片段远程渲染，已基本废弃。AppFunctions 是**函数级、schema 驱动、可组合**的新范式，覆盖 App Actions 的能力并超出。

- **Q：如果 App 没接 AppFunctions，Agent 怎么办？**
  A：Google 的降级方案是 **AI 代理 UI 自动化框架** —— 系统/助手直接**操作 UI** 完成多步任务，底层依赖无障碍语义树（见 **§9**）。并提供通知 "live view" 让用户随时接管，敏感操作（如支付）执行前提醒。这条降级路径也是为什么 §9 的无障碍框架在 2026 年突然变成面试热点。

- **Q：AppFunctions 为什么能在 Android 16 上用，而不是 A17 独占？**
  A：因为它走 **Google Play Services + Mainline（AppSearch APEX）** 下发，不依赖平台版本。这是 Google 近年的一贯策略：能力尽量放进 Mainline / GMS，摆脱 OEM 升级链的束缚（对照 **第八篇** ART 分代 GC 经 art APEX 热更、**第七篇** NNAPI/LiteRT）。

- **Q：多个 App 都注册了 `createNote`，Agent 怎么选？**
  A：靠 **schema category/name**（标准化语义分类，如 `notes/createNote@1`）+ AppSearch 的相关性排序 + 用户偏好（默认应用）。这也是为什么 Google 在推**标准 schema 目录**——没有标准 schema，Agent 就要靠 LLM 猜，可靠性崩塌。

---

# §2 AppSearch 与 Icing：设备端搜索引擎的 Framework 实现

> **为什么必须讲 AppSearch**：AppFunctions 的索引底座是它；A17 的 **AISeal** 把「AppSearch 个人数据库 + 端侧模型」整个搬进 pVM（**第十二篇 §4**）；Android 的全局搜索、Shortcuts 索引也在往它迁移。它已经是**智能系统的数据中枢**。

## Q2.1 AppSearch 的分层架构

```
┌────────────────────────────────────────────────────────────┐
│ App 进程                                                    │
│  ┌────────────────────┐     ┌──────────────────────────┐  │
│  │ LocalStorage       │     │ PlatformStorage          │  │
│  │ (androidx，进程内)  │     │ (走 Binder 到 system)    │  │
│  └─────────┬──────────┘     └───────────┬──────────────┘  │
└────────────┼────────────────────────────┼─────────────────┘
             │ JNI                        │ Binder
             ▼                            ▼
   ┌──────────────────┐      ┌────────────────────────────────┐
   │ libicing.so      │      │ system_server                  │
   │ (进程内独立实例) │      │  AppSearchManagerService       │
   └──────────────────┘      │    └─ AppSearchUserInstance    │
                             │         └─ AppSearchImpl       │
                             │              └─ IcingSearchEngine (JNI)
                             │                   └─ libicing.so
                             └────────────────────────────────┘
                                        │
                                 /data/misc/apexdata/
                                 com.android.appsearch/<userId>/
```

**核心组件**：

| 组件 | 语言 | 职责 | 源码路径 |
|---|---|---|---|
| `AppSearchManager` | Java | Framework API 门面 | `packages/modules/AppSearch/framework/java/external/android/app/appsearch/AppSearchManager.java` |
| `AppSearchManagerService` | Java | system_server 侧服务，多用户隔离、包可见性、限流 | `packages/modules/AppSearch/service/java/com/android/server/appsearch/AppSearchManagerService.java` |
| `AppSearchImpl` | Java | 核心逻辑层：schema 前缀化、可见性过滤、优化调度 | `.../appsearch/external/localstorage/AppSearchImpl.java` |
| `IcingSearchEngine` | Java+JNI | 绑定到 native | `.../icing/IcingSearchEngine.java` |
| **Icing Lib** | **C++** | 真正的搜索引擎：倒排索引、分词、评分、持久化 | `external/icing/`（`icing/index/`、`icing/store/`、`icing/scoring/`） |

## Q2.2 GenericDocument / Schema 模型 —— 和关系数据库、和 ContentProvider 有什么不同？

**数据模型三要素**：

```java
// Schema：类型定义（相当于建表）
AppSearchSchema noteSchema = new AppSearchSchema.Builder("Note")
    .addProperty(new StringPropertyConfig.Builder("title")
        .setCardinality(PropertyConfig.CARDINALITY_REQUIRED)
        .setTokenizerType(StringPropertyConfig.TOKENIZER_TYPE_PLAIN)  // 分词
        .setIndexingType(StringPropertyConfig.INDEXING_TYPE_PREFIXES) // 前缀索引
        .build())
    .addProperty(new LongPropertyConfig.Builder("createTime")....build())
    .build();

// GenericDocument：一条文档（相当于一行）
GenericDocument doc = new GenericDocument.Builder<>(
        /*namespace=*/"user1", /*id=*/"note-001", /*schemaType=*/"Note")
    .setPropertyString("title", "会议纪要")
    .setCreationTimestampMillis(...)
    .setTtlMillis(7 * 86400_000L)   // 自动过期
    .build();
```

**三个关键区别（面试点）**：

| 对比项 | SQLite / ContentProvider | **AppSearch** |
|---|---|---|
| 查询方式 | 精确匹配 / LIKE（**无分词、无相关性**） | **全文检索**：分词 + 倒排索引 + BM25F 相关性评分 |
| 数据模型 | 强 schema、行列 | **半结构化文档**，property 可嵌套 GenericDocument |
| 跨应用共享 | 需要 ContentProvider + URI 权限 | **原生跨应用可见性控制**（`SetSchemaRequest.setSchemaTypeVisibilityForPackage`，按证书 SHA-256 + 包名） |
| 生命周期 | 手动清理 | **原生 TTL**（`setTtlMillis`）+ 自动 optimize/compaction |
| 索引隔离 | 库文件级 | **prefix 机制**：所有文档在同一 Icing 实例，但 schema/namespace 被加上 `<packageName>$<databaseName>/` 前缀做逻辑隔离 |

**易错点**：很多人以为 AppSearch 是「每个 App 一个数据库文件」。**不是**。PlatformStorage 模式下，**整个用户下的所有 App 共用一个 Icing 实例**，靠 `AppSearchImpl` 的 **prefix 前缀 + VisibilityStore 可见性检查**做隔离。这是性能考量（一个引擎实例、一份内存索引），但也意味着**可见性检查一旦有 bug 就是跨应用数据泄露**。理解这一点，才能理解 A17 AISeal 为什么要把 AppSearch 整个搬进 pVM 保护型 VM。

## Q2.3 Icing 的检索链路（native 侧，加分项）

```
IcingSearchEngine::Search(SearchSpecProto, ScoringSpecProto, ResultSpecProto)
  │
  ├─ QueryProcessor::ParseSearch()          // 解析查询语法（AND/OR/NOT/property 限定）
  │    └─ Lexer → Parser → QueryVisitor     // icing/query/
  │
  ├─ 倒排索引查找                             // icing/index/main/ + icing/index/lite/
  │    ├─ LiteIndex（内存中的增量索引，写入快）
  │    └─ MainIndex（磁盘上的合并索引，压缩、posting list）
  │       └─ 二者查询时合并；后台 optimize 时 lite → main
  │
  ├─ Scorer::GetScore()                     // icing/scoring/
  │    └─ BM25F / DOCUMENT_SCORE / CREATION_TIMESTAMP / USAGE_COUNT
  │
  ├─ ResultRetriever::RetrieveResults()     // 从 DocumentStore 取回文档正文
  │    └─ Snippet 生成（高亮片段）
  │
  └─ ResultStateManager                     // 分页游标（next-page token）
```

**面试追问：为什么要 LiteIndex + MainIndex 双索引？**
倒排索引的 posting list 需要**有序压缩存储**才高效，但每写一条文档就重排全量索引不可接受。所以：写入先进 **LiteIndex**（哈希表 + 无序 posting，O(1) 写入），查询时**两个索引都查再合并**；后台 `Optimize()` 时把 LiteIndex 合并进 MainIndex 并压缩。这与 **LSM-Tree（LevelDB/RocksDB）的 memtable + SSTable** 是同一思想，也和 Lucene 的 segment 合并同构。能把这三者类比出来，这道题就满分了。

## Q2.4 高频追问

- **Q：AppSearch 数据存在哪？会不会被 backup？**
  A：`/data/misc/apexdata/com.android.appsearch/<userId>/`。默认**不参与云备份**（属于可重建的索引数据），但 A12+ 有 `AppSearch` 的持久化保证与 recovery（Icing 有 checksum + recovery ground truth 机制，崩溃后能从 DocumentStore 重建索引）。

- **Q：LocalStorage 和 PlatformStorage 怎么选？**
  A：LocalStorage 在 **App 进程内**跑 Icing（JNI），数据只属于自己，**不跨应用**，无 Binder 开销，适合应用内搜索；PlatformStorage 走 system_server，**能被系统全局搜索/Agent 发现**，AppFunctions 元数据必然走这条。代价是 Binder IPC + 需要处理 `AppSearchSession` 异步。

- **Q：AppSearch 的可见性怎么做的？**
  A：`VisibilityStore` 本身也是存在 AppSearch 里的一组文档（自举）。检查时比对**调用方包名 + 签名证书 SHA-256**（`PackageManager.hasSigningCertificate`），或者要求调用方持有指定权限（`SetSchemaRequest.setRequiredPermissionsForSchemaTypeVisibility`）。**用证书而非仅包名**，是为了防止包名抢注。

---

# §3 Compose 编译器插件：`@Composable` 到底被编译成了什么

> **Android 17 官宣 Compose-First 后，这一章从「加分项」变成「必答题」**。面试官不再满足于"重组、状态提升"这种 API 层回答，会直接问：`@Composable` 是注解，注解怎么可能改变函数调用约定？

## Q3.1 `@Composable` 不是普通注解 —— 它改变了函数的调用约定

**核心结论**：`@Composable` 由 **Kotlin 编译器插件（compiler plugin）** 在 **IR（Intermediate Representation）lowering 阶段**处理，效果等价于给函数**加参数、改签名**。

源码写的：
```kotlin
@Composable
fun Greeting(name: String) {
    Text("Hello $name")
}
```

编译器插件转换后（伪代码，实际是 IR 层）：
```kotlin
fun Greeting(name: String, $composer: Composer, $changed: Int) {
    $composer.startRestartGroup(key = <编译期生成的稳定 key>)
    val $dirty = $changed
    if ($changed and 0b1110 == 0) {
        $dirty = $dirty or if ($composer.changed(name)) 0b0100 else 0b0010
    }
    // 关键：若参数未变且已有缓存 → 整体跳过
    if ($dirty and 0b1011 != 0b0010 || !$composer.skipping) {
        Text("Hello $name", $composer, 0)
    } else {
        $composer.skipToGroupEnd()   // ★ 跳过重组的实际发生点
    }
    $composer.endRestartGroup()?.updateScope { c, _ ->
        Greeting(name, c, $changed or 0b0001)   // 重启 lambda
    }
}
```

**必须讲清的四件事**：

1. **`$composer` 参数注入**：每个 `@Composable` 函数隐式多一个 `Composer` 参数，沿调用树**逐层传递**。这就是为什么 `@Composable` 函数**只能被 `@Composable` 函数调用** —— 非 Composable 函数没有 `$composer` 可传。这是编译期的"颜色系统"（function coloring），不是运行时检查。
2. **`$changed` 位掩码**：调用方在**编译期**就知道哪些实参是字面量/稳定值，把"是否可能变化"编码进 `$changed` 的每 3 bit 一组的槽位。被调方据此决定是否需要 `$composer.changed()` 做运行时比较。**这是 Compose 跳过重组的第一道闸门，纯编译期优化**。
3. **group（组）**：`startRestartGroup` / `startReplaceGroup` / `startMovableGroup` 在 SlotTable 上开辟一个带 key 的区间，是**状态存储的定位锚点**。
4. **`updateScope` 重启 lambda**：把「如何重新执行我自己」封装成闭包存进 `RecomposeScope`。当依赖的 `State` 变化时，Recomposer 只需调用这个 lambda —— **这就是"局部重组"的实现基础**。

## Q3.2 三种 group 的区别（高频追问）

| group 类型 | 编译器何时生成 | 语义 | 典型场景 |
|---|---|---|---|
| `startRestartGroup` | 返回 `Unit` 的 `@Composable` 函数体 | 可**独立重启**，注册 `RecomposeScope` | 绝大多数 Composable |
| `startReplaceGroup`（旧名 `startReplaceableGroup`） | `if/else`、`when` 分支、循环体 | 分支切换时**整块丢弃重建**，不保留状态 | 条件渲染 |
| `startMovableGroup` | `key(...) { }` 显式调用 | 带用户 key，**位置变化时状态跟着移动** | 列表项重排（等价于 RecyclerView 的 stable id） |

**易错点 1**：为什么返回值非 `Unit` 的 `@Composable`（如 `@Composable fun rememberFoo(): Foo`）**不生成 restart group**？
因为它无法独立重启——它的返回值被调用方使用，单独重跑自己没有意义，必须由调用方整体重组。所以带返回值的 Composable **不是重组边界**，它的成本会算在调用方头上。这是 `remember` 类函数设计的隐含约束。

**易错点 2**：`key(...)` 和 `LazyColumn` 的 `items(key = ...)` 不是一回事。前者生成 movable group（SlotTable 层面的状态搬移）；后者还额外驱动 LazyLayout 的**复用池与位置记忆**。

## Q3.3 强跳过模式（Strong Skipping Mode）—— 2024 后的默认行为

**背景**：老版本 Compose 中，参数类型若**不稳定**（unstable），编译器会放弃 skip，导致大量无谓重组。最典型的坑：`List<T>` 是 unstable（因为 `List` 接口的实现可能可变），传个 `List` 就让整棵子树重组。

**强跳过模式做了两件事**：
1. **不稳定参数也参与比较**：改用**实例相等性（`===`）**判断不稳定参数是否变化，而不是直接放弃。
2. **lambda 自动 `remember`**：Composable 内定义的 lambda 自动被包装成 `remember { }`，避免每次重组产生新实例导致下游必然重组。

**稳定性判定规则（必背）**：
- 稳定：所有基本类型、`String`、函数类型、`@Stable`/`@Immutable` 标注的类、所有 `val` 且类型均稳定的类。
- 不稳定：含 `var` 的类、集合接口类型（`List`/`Map`/`Set`）、其他模块中未被编译器分析的类。
- **跨模块坑**：如果类定义在**没有应用 Compose 编译器**的模块（如纯 Java/Kotlin 的 domain 层），编译器无法推断稳定性 → 视为不稳定。解法：`@Immutable` 显式标注，或用 `stability_configuration_file`（稳定性配置文件）把 `kotlin.collections.*` 之类批量声明为稳定。

**面试追问：`@Stable` 和 `@Immutable` 的区别？**
- `@Immutable`：**构造后所有属性永不变**。编译器可以完全跳过后续比较。
- `@Stable`：属性**可以变**，但**变了一定会通知 Compose**（通过 `State`/`MutableState`），且 `equals` 行为一致、公开属性变化可观测。
- 二者都是**对编译器的承诺**，编译器不校验。撒谎的后果是 UI 不更新（最难查的 Compose bug 之一）。

## Q3.4 Compose 编译器的产物与调试

- **编译器指标（Compose Compiler Metrics）**：开启后输出 `*-composables.txt`（每个 Composable 是否 restartable/skippable）与 `*-classes.txt`（每个类的稳定性推断结果）。**排查"为什么我的 Composable 不 skip"的唯一可靠手段**。
  ```
  restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun Greeting(
    stable name: String
  )
  ```
  看到 `unstable xxx` 就是问题所在。
- **Compose 编译器已随 Kotlin 2.0 合入 Kotlin 仓库**（`kotlin("plugin.compose")`），不再单独对齐 Compose 版本与 Kotlin 版本 —— 这解决了长期困扰的版本矩阵地狱。

---

# §4 Compose 运行时：SlotTable / Snapshot / Recomposer 三件套

## Q4.1 SlotTable 是什么数据结构？为什么不用树？

**结论**：SlotTable 用**两个平坦数组（gap buffer）**模拟树，而不是对象树。

```
groups: IntArray    // 每个 group 占 5 个 int
        [key, groupInfo, parentAnchor, size, dataAnchor] × N
slots:  Array<Any?> // 实际数据：state、remember 的值、node 引用……
```

**为什么这么设计**（面试核心）：
1. **内存局部性 / 缓存友好**：UI 树遍历是热路径，平坦 IntArray 的顺序访问远快于指针跳转的对象树，且**没有每节点对象头开销**（对照 **第四篇** ART 对象头 8/12 字节 × 数万节点）。
2. **gap buffer 支持高效插入/删除**：借鉴文本编辑器的 gap buffer —— 在当前写入位置留一个"空隙"，插入时直接填隙，O(1)；移动写入点时才搬移 gap。重组时的插入/删除都发生在局部，代价极低。
3. **读写分离**：`SlotReader`（多个，只读）与 `SlotWriter`（同一时刻**只能有一个**）。重组过程中，先用 reader 遍历，把变更记录成 **change list（lambda 列表）**，最后统一 apply —— 这就是为什么 Compose 能做到"组合阶段不产生副作用"。

**Composition 与 SlotTable 的关系**：
- `Composition` 持有 `slotTable`（当前已生效状态）与 `applier`（把变更施加到真实 UI 树，Android 上是 `UiApplier` → `LayoutNode`）。
- 重组时用一张 **`insertTable`（临时）** 承载新插入的内容，最后 `SlotWriter.moveFrom()` 合并。

## Q4.2 Snapshot 系统 —— Compose 的"MVCC 事务内存"

**这是 Compose 最被低估、也最能拉开面试差距的部分**。

`mutableStateOf()` 返回的 `SnapshotMutableStateImpl` 内部是一条 **版本链表**：

```
StateObject
   └─ firstStateRecord ──▶ StateRecord(snapshotId=5, value="A")
                              └─ next ──▶ StateRecord(snapshotId=3, value="B")
                                             └─ next ──▶ ...
```

**核心概念**：

| 概念 | 说明 |
|---|---|
| `Snapshot` | 一个「时间点视图」，有唯一递增 `snapshotId` 与 `invalid` 集合（记录哪些 id 对本快照不可见） |
| `GlobalSnapshot` | 全局默认快照，普通代码在这里读写 |
| `MutableSnapshot` | 可变子快照，`Snapshot.takeMutableSnapshot()` 创建，**写入对外不可见**直到 `apply()` |
| `readObserver` / `writeObserver` | 读写拦截器 —— **依赖收集的入口** |
| `apply()` | 提交。若与其他快照冲突 → `SnapshotApplyConflict`，可通过 merge policy 解决 |

**读取规则（MVCC）**：读时沿版本链找「`snapshotId <= 当前快照 id` 且不在 `invalid` 集合中的最大 id 记录」。这与数据库的 MVCC（多版本并发控制）**完全同构**。

**依赖收集全链路（必须能画出来）**：
```
1. Recomposer 为每个 Composition 建立 observation
   Composition.composeContent { ... }
     └─ snapshot = Snapshot.takeMutableSnapshot(readObserver, writeObserver)

2. 组合过程中读到 state.value
     └─ readObserver(stateObject) 被回调
        └─ 记录到 SnapshotStateObserver：
             currentRecomposeScope ← 依赖 → stateObject

3. 某处 state.value = newValue（写入）
     └─ writeObserver / GlobalSnapshot.apply 时触发 applyObserver
        └─ 反查 stateObject → 关联的所有 RecomposeScope
           └─ scope.invalidate()  → 加入 Recomposer 的 invalidations 队列

4. Recomposer 在下一帧唤醒，只重跑失效的 RecomposeScope 的重启 lambda
```

**面试杀手锏问题：`mutableStateOf` 和 `LiveData`/`StateFlow` 的本质区别？**
- `LiveData`/`StateFlow` 是**显式订阅**：你必须 `collect`/`observe`，订阅关系由你声明。
- `mutableStateOf` 是**隐式依赖收集**：**读取即订阅**。谁读了、在哪个 scope 读的，由 Snapshot 的 readObserver 自动捕获。这带来两个后果：
  1. 精度极高（读在哪个 group，就只失效哪个 group）；
  2. **在错误的地方读会导致过度重组** —— 比如在父 Composable 顶层读一个高频变化的 state，会让整个子树重组。解法是**延迟读取**（lambda 传递、`Modifier.graphicsLayer { }` 的 lambda 版、`derivedStateOf`）。

**`derivedStateOf` 的作用**：把「多个 state → 一个派生值」的计算包起来，只有**派生结果变化**时才通知下游。经典场景：`val showButton by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }` —— 滚动时 `firstVisibleItemIndex` 每帧都变，但 `showButton` 只在跨过阈值时变一次。**不加 derivedStateOf 就是每帧重组**。

## Q4.3 Recomposer 与帧调度 —— 和 Choreographer 怎么接上的

```
Recomposer.runRecomposeAndApplyChanges()   (挂起函数，跑在 AndroidUiDispatcher 上)
  │
  ├─ 注册 Snapshot.registerApplyObserver → 收集失效
  │
  ├─ suspend 等待 "有失效 或 有等待的 frame"
  │
  ├─ parentFrameClock.withFrameNanos { frameTime ->     // ★ 关键接缝
  │     ① 分发 MonotonicFrameClock 的 awaiters（动画的 withFrameNanos 在此恢复）
  │     ② performRecompose(composition)  → 只跑失效 scope
  │     ③ applyChanges() → UiApplier 修改 LayoutNode 树
  │     ④ applyLateChanges() / changesApplied()
  │  }
  │
  └─ 循环
```

**`MonotonicFrameClock` 在 Android 上的实现是 `AndroidUiFrameClock`，它内部就是 `Choreographer.postFrameCallback()`。**

所以完整链路是：
```
Choreographer VSYNC 回调
   ├─ CALLBACK_INPUT
   ├─ CALLBACK_ANIMATION ──▶ AndroidUiFrameClock 的 frameCallback
   │                            └─ Recomposer 重组 + apply → LayoutNode 树变更
   │                               └─ AndroidComposeView.invalidate() / requestLayout()
   └─ CALLBACK_TRAVERSAL ──▶ ViewRootImpl.doTraversal()
                                └─ measure/layout/draw AndroidComposeView
                                   └─ 驱动 Compose 自己的 measure/layout/draw
```

**这是本篇最重要的一张图**：Compose 的重组发生在 **ANIMATION 回调**，View 的三大流程发生在 **TRAVERSAL 回调**，二者在**同一帧内先后执行**。所以重组产生的布局变化能在当帧生效，不会掉一帧。

> 对照 **第一篇 Handler/Looper 同步屏障** 与 **第四篇/第五篇 Choreographer/VSync**：`ViewRootImpl.scheduleTraversals()` 会插同步屏障 + 异步消息，Compose 的 frame callback 同样受益于这套机制。**面试官很爱问：Compose 有没有绕过 Choreographer？答案是没有，它是 Choreographer 的一个"客户"。**

## Q4.4 Compose 的三阶段 vs View 的三大流程

| 阶段 | View 体系 | Compose | 关键差异 |
|---|---|---|---|
| 第一阶段 | — | **Composition（组合）** | View 没有对应阶段，UI 结构是命令式创建的 |
| 第二阶段 | `measure()` + `layout()` | **Layout**（`LayoutNode.measure()` → `MeasureResult.placeChildren()`） | Compose **强制单遍测量**（single-pass），子只能被测一次 |
| 第三阶段 | `draw()` | **Drawing**（`LayoutNode` → `RenderNode`） | 都最终落到 RenderNode / DisplayList |

**"Compose 强制单遍测量"是高频考点**：
- View 体系允许多次 measure（`MeasureSpec.UNSPECIFIED` 试探 + 再次精确测量），`RelativeLayout`/`LinearLayout`(weight) 会双遍甚至指数级测量。
- Compose 在 `LayoutNode` 层面**禁止对同一子节点测量两次**（会抛异常），从架构上消灭了 O(2^n) 测量爆炸。
- 需要"先知道内容大小再决定布局"的场景，用 **`SubcomposeLayout`**（`BoxWithConstraints`、`LazyColumn` 的实现基础）—— 它把子内容的**组合**也延迟到测量阶段，代价是**额外一次组合**，所以 `BoxWithConstraints` 不能滥用。

**三阶段的独立失效（性能优化核心）**：
- 只改颜色 → 只需 **Drawing** 阶段重跑（用 `Modifier.drawBehind { }`，读 state 在 lambda 内）
- 只改偏移 → 只需 **Layout** 阶段（用 `Modifier.offset { IntOffset(...) }` 的 lambda 版）
- 改结构 → 才需要 **Composition**

**这就是"延迟读取（deferred read）"优化的原理**：把 state 的读取推迟到更晚的阶段的 lambda 里，失效范围就只到那个阶段。这是 Compose 性能面试的必考题。

**源码路径**：
- `frameworks/support/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/`
  （`Composer.kt`、`SlotTable.kt`、`Recomposer.kt`、`snapshots/Snapshot.kt`、`RecomposeScopeImpl.kt`）
- `frameworks/support/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/`
  （`AndroidComposeView.android.kt`、`AndroidUiDispatcher.android.kt`、`AndroidUiFrameClock.android.kt`、`WindowRecomposer.android.kt`）
- `frameworks/support/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/`
  （`LayoutNode.kt`、`MeasureAndLayoutDelegate.kt`、`NodeCoordinator.kt`、`UiApplier.kt`）

---

# §5 Compose 与 Framework 的六个接缝

> 这一章专治「只会写 Compose，不知道它怎么长在 Android 上」。面试官问「Compose 最终是怎么显示到屏幕上的」，要能一口气说完这六个接缝。

## 接缝 1：ComposeView → AndroidComposeView → ViewRootImpl

```
setContent { ... }                       // ComponentActivity.setContent (activity-compose)
  └─ ComposeView (一个普通 ViewGroup)
       └─ AndroidComposeView (唯一真正的 View，承载整棵 Compose 树)
            └─ root: LayoutNode           // Compose 自己的节点树（不是 View）
                 └─ 子 LayoutNode ...
```

**关键结论**：**整棵 Compose UI 树在 View 层级里只是"一个 View"**。所以：
- `Layout Inspector` 需要专门支持才能看到 Compose 层级；
- 传统的 `findViewById`、`ViewTreeObserver` 遍历看不到 Compose 内部；
- **View 的过度绘制、层级过深问题在 Compose 里不存在**（没有嵌套 View），这是 Compose 性能优势的结构性来源。

## 接缝 2：Recomposer 的宿主绑定（`WindowRecomposer`）

`AndroidComposeView` attach 到 window 时，通过 `WindowRecomposerPolicy` 为该 window 创建/获取一个 `Recomposer`，并挂在 **`View.setTag(R.id.androidx_compose_ui_view_composition_context, ...)`** 上。

**易错点**：`ViewCompositionStrategy` 决定 Composition 何时被 dispose：
- `DisposeOnDetachedFromWindowOrReleasedFromPool`（默认，适配 RecyclerView 复用）
- `DisposeOnViewTreeLifecycleDestroyed`
- `DisposeOnLifecycleDestroyed`
- **在 `Fragment` 里用 ComposeView 忘了设 `DisposeOnViewTreeLifecycleDestroyed`，会在 Fragment 视图销毁后 Composition 仍存活 → 内存泄漏**。这是混合栈 App 的经典事故，面试常问。

## 接缝 3：输入事件

```
InputDispatcher (native)  →  ViewRootImpl.WindowInputEventReceiver
   → View.dispatchTouchEvent 链
      → AndroidComposeView.dispatchTouchEvent
         → MotionEvent 转 PointerInputEvent
            → PointerInputEventProcessor.process()
               → HitPathTracker（命中路径缓存）
                  → 各 LayoutNode 的 PointerInputModifierNode
                     → 三个 PointerEventPass：
                        Initial（父→子，拦截机会）
                        Main  （子→父，主处理）
                        Final （父→子，通知消费结果）
```

**面试点：Compose 的三段 pass 如何替代 View 的 `onInterceptTouchEvent`？**
View 体系是「父先拦截（intercept）→ 子处理 → 父可再抢（requestDisallowInterceptTouchEvent）」，逻辑分散且反直觉。Compose 用**同一套事件在三个方向上跑三遍**统一表达：`Initial` 相当于 intercept 机会，`Main` 是正常处理，`Final` 用于「我没抢到，做善后」。`PointerInputChange.consume()` 表示消费。
> 对照 **第四篇 Input 多指拆分（split touch）** 与 **第十篇 Pointer Capture**。

## 接缝 4：绘制 —— LayoutNode 到 RenderNode

每个 `LayoutNode` 通过 `NodeCoordinator` 链持有绘制能力；需要独立图层的节点（`Modifier.graphicsLayer`、`clip`、`alpha < 1`、`shadow`）会创建 **`RenderNode`**（API 29+ 走公开 `android.graphics.RenderNode`，低版本走 `ViewLayer` 兜底）。

最终 `AndroidComposeView.dispatchDraw(canvas)` 被 ViewRootImpl 调用时，把 Compose 的绘制指令录进 **HWUI 的 DisplayList**。

**结论**：**Compose 没有绕过 HWUI**。它只是用自己的树替代了 View 树来生成 DisplayList，之后的 RenderThread → SurfaceFlinger 路径与 View 完全一致（见 **第五篇 §1 HWUI/RenderThread**、**第五篇 §3 SurfaceFlinger**）。

**加分**：`Modifier.graphicsLayer { }` 的 **lambda 版本**只在 Drawing 阶段读 state，改变 alpha/rotation 不触发重组也不触发 layout —— 直接改 RenderNode 属性，由 RenderThread 处理。这是 Compose 动画高性能的关键（等价于 View 的 `setTranslationX` 走 RenderNode 属性动画）。

## 接缝 5：Modifier 链的实现（Modifier.Node）

现代 Compose 的 `Modifier` 不再是每次重组都新建对象链，而是 **`Modifier.Node`** 体系：
- `Modifier.Element`（不可变的**声明**）与 `Modifier.Node`（可变的**实例**，有生命周期 `onAttach`/`onDetach`）分离；
- 重组时对比 Element 链，**复用**已有 Node，只更新变化的属性 —— 这解决了老 `ComposedModifier` 每次重组都重建的性能问题。
- 各能力通过接口混入：`LayoutModifierNode`、`DrawModifierNode`、`PointerInputModifierNode`、`SemanticsModifierNode`。

**面试点**：`Modifier` 的顺序为什么重要？因为它是**从外到内**依次包裹的 `NodeCoordinator` 链。`Modifier.padding(8.dp).background(Red)` 与 `.background(Red).padding(8.dp)` 效果不同 —— 前者 padding 在外，背景只画内容区；后者背景覆盖 padding 区。**这不是 API 怪癖，是链式包裹的必然结果**。

## 接缝 6：CompositionLocal 与系统能力注入

`LocalContext`、`LocalDensity`、`LocalConfiguration`、`LocalLifecycleOwner`、`LocalView` 等由 `ProvideAndroidCompositionLocals` 在 `AndroidComposeView` 创建时注入。

**易错点**：`CompositionLocal` 是**隐式依赖**，`staticCompositionLocalOf` 变化会导致**整个 provider 子树重组**（不做细粒度追踪，换取读取零开销）；`compositionLocalOf` 才做细粒度失效。**用错会导致大面积重组**。

---

# §6 APK 签名方案 v1 → v3.2：后量子密码如何进入 PMS 校验链路

> **A17 热点**：安全硬件支持 **ML-DSA**（FIPS 204，即 CRYSTALS-Dilithium）密钥生成，新增 **APK 签名方案 v3.2**，在传统签名之上叠加抗量子签名。这是 Android 应对 "Harvest Now, Decrypt Later" 的第一步。

## Q6.1 五代签名方案演进（必背表）

| 方案 | 引入版本 | 签名对象 | 核心能力 | 致命弱点 |
|---|---|---|---|---|
| **v1（JAR 签名）** | 一直有 | `META-INF/` 下 `MANIFEST.MF`+`CERT.SF`+`CERT.RSA`，**逐文件摘要** | 兼容一切 | ① 不保护 ZIP 元数据 → **Janus/主人密钥漏洞**土壤 ② 验证需解压全部文件，**慢** ③ `META-INF` 自身不被保护，可加文件 |
| **v2** | Android 7.0 | **整个 APK 文件的字节流**（分成 1MB 块做 Merkle 式摘要） | 全文件完整性、验证快（不解压）、防篡改 ZIP 结构 | 无密钥轮转 |
| **v3** | Android 9.0 | 同 v2 + **签名证书轮转链（proof-of-rotation）** | 可以换签名密钥而不丢失升级资格 | 无法按 SDK 版本区分密钥 |
| **v3.1** | Android 13 | 在 v3 基础上增加 **`minSdkVersion` 定向** | 可以「新系统用新密钥、旧系统用旧密钥」，解决轮转时旧设备不认新证书 | 仍是经典密码学 |
| **v3.2** | **Android 17** | 在 v3.1 基础上叠加 **PQC（ML-DSA）签名** | **抗量子**：混合签名（经典 + 后量子并存） | 签名体积显著增大 |
| （v4） | Android 11 | 独立 `.apk.idsig` 文件，**Merkle 哈希树** | 支持 **增量安装**（`adb install --incremental`，边下边跑） | 必须配合 v2/v3 使用 |

## Q6.2 APK Signing Block 的物理结构

```
┌─────────────────────────────────┐
│ ① ZIP 条目区（实际文件内容）      │
├─────────────────────────────────┤
│ ② APK Signing Block             │  ← v2 之后插入在这里
│   ┌───────────────────────────┐ │
│   │ size of block (8 bytes)   │ │
│   │ ┌───────────────────────┐ │ │
│   │ │ ID-value pair 序列     │ │ │
│   │ │  0x7109871a → v2 签名  │ │ │
│   │ │  0xf05368c0 → v3 签名  │ │ │
│   │ │  0x1b93ad61 → v3.1     │ │ │
│   │ │  <new>      → v3.2 PQC │ │ │
│   │ │  0x42726577 → 「Bre w」 │ │ │
│   │ │      (padding/其它)    │ │ │
│   │ └───────────────────────┘ │ │
│   │ size of block (重复,8B)    │ │
│   │ magic "APK Sig Block 42"  │ │
│   └───────────────────────────┘ │
├─────────────────────────────────┤
│ ③ 中央目录 (Central Directory)   │
├─────────────────────────────────┤
│ ④ EOCD (End of Central Directory)│
└─────────────────────────────────┘
```

**v2 摘要覆盖 ①③④ 三部分**（跳过 ② 自身），并且 **EOCD 里的"中央目录偏移量"字段在计算摘要时被替换成 Signing Block 的起始偏移** —— 这个细节是为了让签名不受 Signing Block 大小影响。**能讲出这一点，说明真读过规范**。

**ID-value pair 的可扩展性**是 v3/v3.1/v3.2 能平滑叠加的原因：老系统看不懂新 ID 就**直接忽略**，仍用它认识的最高版本验签。这是 Android 少见的**优雅向前兼容**设计。

## Q6.3 PMS 安装时的校验链路（源码级）

```
PackageInstallerSession.commit()
  └─ PackageManagerService.installPackageLI / InstallPackageHelper
       └─ ParsingPackageUtils.parsePackage()               // frameworks/base/core/java/android/content/pm/parsing/
            └─ ParsingPackageUtils.getSigningDetails()
                 └─ ApkSignatureVerifier.verify(apkPath, minSignatureScheme)
                      ├─ verifyV4()  → 若存在 .idsig
                      ├─ verifyV3AndBelow()
                      │    ├─ ApkSigningBlockUtils.findSignature(APK_SIGNATURE_SCHEME_V3_2_BLOCK_ID)  [A17]
                      │    ├─ ApkSignatureSchemeV3Verifier.verify()
                      │    │    ├─ 解析 signer → signed data → digests
                      │    │    ├─ 校验 minSdk/maxSdk 区间匹配（v3.1）
                      │    │    ├─ 验证签名（RSA/EC/DSA + [A17] ML-DSA）
                      │    │    ├─ 比对内容摘要（CHUNKED_SHA256/512、VERITY_CHUNKED_SHA256）
                      │    │    └─ 构建 proof-of-rotation 轮转链 → SigningDetails
                      │    ├─ 降级 ApkSignatureSchemeV2Verifier.verify()
                      │    └─ 降级 verifyV1Signature()（JarFile 逐条目）
                      └─ 返回 SigningDetails{signatures, publicKeys, signatureSchemeVersion, pastSigningCertificates}
       └─ 与已安装包比对：SigningDetails.checkCapability(oldDetails, CERT_CAPABILITY_INSTALLED_DATA)
            └─ 不匹配 → INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

**源码路径**：
- `frameworks/base/core/java/android/util/apk/ApkSignatureVerifier.java`
- `frameworks/base/core/java/android/util/apk/ApkSignatureSchemeV3Verifier.java`
- `frameworks/base/core/java/android/util/apk/ApkSigningBlockUtils.java`
- `frameworks/base/core/java/android/util/apk/SourceStampVerifier.java`
- `frameworks/base/core/java/android/content/pm/SigningDetails.java`
- 工具侧：`tools/apksig/`（`ApkSigner`、`ApkVerifier`、`V3SchemeSigner`）

## Q6.4 ML-DSA 与混合签名的工程含义

- **ML-DSA（FIPS 204）** 基于格（Module-LWE），签名/公钥体积远大于 ECDSA：ML-DSA-65 公钥约 1952 字节、签名约 3309 字节（ECDSA P-256 分别是 64 / 72 字节）。→ **APK Signing Block 会明显变大**，对超小 APK 和 OTA 差分包有影响。
- **混合（hybrid）签名**：v3.2 不是替换而是**叠加** —— 同时带经典签名与 PQC 签名，两者都必须通过。理由：PQC 算法相对年轻，若被找到经典攻击，还有 ECDSA 兜底；反之量子机来了有 ML-DSA 兜底。
- **与 KeyMint / StrongBox 的衔接**（承 **第十一篇 §3**）：A17 要求安全硬件能生成 ML-DSA 密钥，意味着 `KeyMint` HAL 的 `Algorithm` 枚举新增 PQC 项，`KeyMintDevice.generateKey()` 需支持；Key Attestation 证书链（**第十一篇 §5**）也要能表达 PQC 密钥。
- **面试延伸**：为什么先做签名（数字签名）而不是先做加密（KEM/ML-KEM）？因为签名面临的是 **"未来伪造"** 风险（量子机出现后能伪造今天的签名，威胁 OTA 与应用更新的信任根，且信任根寿命长达十几年）；而加密面临 **"Harvest Now, Decrypt Later"**。二者都紧急，但**信任根的迁移周期最长**（要等全生态设备换代），所以必须最先启动。

---

# §7 ApplicationExitInfo：把「进程为什么没了」问到底

> **A17 新增死因**：`getDescription()` 返回 `MemoryLimiter:AnonSwap` —— 应用超过每进程内存上限被 Memory Limiter 干掉，**没有 crash 回调、没有 ANR 弹窗，进程静默消失**。这让 ApplicationExitInfo 从"锦上添花"变成"唯一线索"。

## Q7.1 ApplicationExitInfo 的采集架构

```
                       ┌────────────────────────────────────────┐
                       │ system_server / ActivityManagerService │
                       │ ProcessList.mAppExitInfoTracker        │
                       │ (AppExitInfoTracker)                   │
                       └───────┬───────────────┬────────────────┘
                               │               │
        ┌──────────────────────┘               └───────────────────────┐
        │ ① 进程死亡通知                          ② 补充死因细节          │
        ▼                                                              ▼
  AppDiedLocked() / DeathRecipient                        ┌────────────────────────┐
  （Binder 死亡回调，最先感知）                              │ Tombstone (native crash)│
        │                                                 │  /data/tombstones/     │
        ▼                                                 │  ← debuggerd/crash_dump │
  记录 pid/uid/pss/rss/importance/reason 骨架               ├────────────────────────┤
                                                          │ lmkd socket 上报        │
                                                          │  ← LMK_PROCKILL 事件    │
                                                          ├────────────────────────┤
                                                          │ Zygote / kernel 信号     │
                                                          │  ← waitpid status       │
                                                          └────────────────────────┘
                               │
                               ▼
                  持久化：/data/system/procexitstore/
                  （protobuf，重启后仍可查；每 UID 最多保留 16 条）
```

**源码路径**：
- `frameworks/base/services/core/java/com/android/server/am/AppExitInfoTracker.java`
- `frameworks/base/core/java/android/app/ApplicationExitInfo.java`
- `frameworks/base/services/core/java/com/android/server/am/ProcessList.java`（`noteAppKill`、`handleProcessStartedLocked`）
- LMKD：`system/memory/lmkd/lmkd.cpp`（`LMK_PROCKILL` 上报）
- Tombstone：`system/core/debuggerd/`

## Q7.2 REASON 常量全表与排障对应关系（面试必背）

| REASON 常量 | 含义 | 典型 `description` | 排障方向 |
|---|---|---|---|
| `REASON_EXIT_SELF` | 自己 `System.exit()` / 正常退出 | — | 检查是否有库偷偷调 exit |
| `REASON_SIGNALED` | 收到信号 | `Signal 9` / `Signal 11` | 9=被杀，11=native 崩溃 |
| `REASON_LOW_MEMORY` | **LMKD 低内存杀** | — | 配合 `getPss()`/`getRss()`、PSI（**第一篇 §LMKD/PSI**） |
| `REASON_CRASH` | Java 未捕获异常 | 异常摘要 | 与 Crash 上报系统对齐 |
| `REASON_CRASH_NATIVE` | native 崩溃 | tombstone 摘要 | `getTraceInputStream()` 拿 tombstone |
| `REASON_ANR` | ANR 后被杀 | ANR 原因 | `getTraceInputStream()` 拿 ANR trace |
| `REASON_INITIALIZATION_FAILURE` | 进程初始化失败 | — | Application 构造/attach 阶段异常 |
| `REASON_PERMISSION_CHANGE` | 权限变更导致重启 | — | 运行时权限撤销 |
| `REASON_EXCESSIVE_RESOURCE_USAGE` | 资源超限（CPU/wakelock） | — | 后台滥用 |
| `REASON_USER_REQUESTED` | 用户主动杀（设置里强停） | — | — |
| `REASON_USER_STOPPED` | 用户被停止（多用户切换） | — | — |
| `REASON_DEPENDENCY_DIED` | 依赖的进程死了（如 ContentProvider 宿主） | — | 检查跨进程依赖 |
| `REASON_OTHER` | 其它（system_server 主动杀） | **`MemoryLimiter:AnonSwap`**（A17）、`isolated not needed` 等 | **A17 新雷区** |
| `REASON_FREEZER` | 被冻结相关 | — | 缓存进程冻结（A14+ cgroup freezer） |
| `REASON_PACKAGE_STATE_CHANGE` / `REASON_PACKAGE_UPDATED` | 包更新 | — | — |

## Q7.3 三条「进程被杀」路径的辨析（承第十二篇 §6）

这是 2026 年最容易混淆、也最容易考的点：

| 杀手 | 层次 | 触发条件 | 死因体现 | 是否有回调 |
|---|---|---|---|---|
| **内核 OOM Killer** | Linux kernel | 全局内存耗尽，`oom_score_adj` 排序 | `REASON_SIGNALED` / Signal 9，dmesg 有 `Out of memory: Killed process` | ❌ 无 |
| **LMKD** | userspace daemon | **PSI 压力阈值**（memory.pressure some/full）触发，按 `oom_score_adj` 从高到低杀 | `REASON_LOW_MEMORY` | ❌ 无（A14 起 `onTrimMemory` 只剩 `TRIM_MEMORY_UI_HIDDEN`/`COMPLETE` 两常量，不再作为内存告警） |
| **Memory Limiter（A17）** | Framework/ART 协同 | **单应用**超过按设备总 RAM 计算的**个体上限**（即使系统整体内存充足） | `REASON_OTHER` + `description = "MemoryLimiter:AnonSwap"` | ❌ 无 crash 回调，但 **ProfilingManager 新触发器可自动抓 heap dump** |

**必须强调的差异**：前两者是**系统整体缺内存**才动手；**Memory Limiter 是"你个人超标"就动手，前台应用也不例外**。这是 A17 最容易踩的坑 —— 测试机内存大不代表安全，因为上限是**按设备总 RAM 比例**算的，大内存机器上限也高，但你的应用如果本来就在大机器上吃更多内存，同样可能超标。

## Q7.4 实战：如何在应用启动时上报"上次为什么死的"

```java
ActivityManager am = getSystemService(ActivityManager.class);
// packageName 传 null 表示当前包；pid 传 0 表示不限；maxNum 最多 16
List<ApplicationExitInfo> infos = am.getHistoricalProcessExitReasons(null, 0, 5);
for (ApplicationExitInfo info : infos) {
    int reason = info.getReason();
    String desc = info.getDescription();          // A17: "MemoryLimiter:AnonSwap"
    long pss = info.getPss();                     // KB，死亡时刻的 PSS
    long rss = info.getRss();
    int importance = info.getImportance();        // 死时前台还是后台 ★ 关键
    if (reason == ApplicationExitInfo.REASON_ANR
        || reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
        try (InputStream is = info.getTraceInputStream()) {  // ANR trace / tombstone
            // 上报
        }
    }
}
```

**面试高频追问**：
- **为什么不用 `UncaughtExceptionHandler` 就够了？** 因为它只能抓 Java 崩溃。**被 LMK 杀、被 Memory Limiter 杀、native 崩溃、ANR 被杀、系统强停 —— 一个都抓不到**。ApplicationExitInfo 是**唯一**能覆盖全部死因的 API。
- **`getImportance()` 为什么重要？** 区分「后台被回收（正常，不该报警）」和「前台被杀（严重事故）」。只统计 `IMPORTANCE_FOREGROUND` 的异常退出，才是有效的稳定性指标。
- **数据保留多久？** 每个 UID 最多 16 条（`APP_EXIT_INFO_HISTORY_LIST_SIZE`），持久化在 `/data/system/procexitstore/`，**重启后仍在**。所以要在**启动时立刻读**并去重上报（用 `getTimestamp()` 做水位线）。

---

# §8 系统托管 UI 与 URI 授权：隐私范式从「权限」到「凭证」

## Q8.1 范式转变：为什么 Google 在系统性地消灭运行时权限？

**A17 的隐私改动列表**（Contacts Picker、Photo Picker 缩略图比例、系统渲染位置按钮、EyeDropper API）看似零散，其实是**同一条主线**：

```
旧范式：广域、长期的权限
   App 申请 READ_CONTACTS  →  能读全部联系人，永久
   ↓ 问题：授权粒度 = 整个数据域；一次授权，长期滥用

新范式：系统托管 UI + 一次性凭证
   App 调起系统 Picker（跑在系统进程/独立进程）
   → 用户在系统 UI 里选择"这一条"
   → 系统回传一个 URI + 临时授权
   → App 只能访问被选中的那一条，且随任务结束回收
```

**关键机制：App 全程没有权限，只有"凭证"**。选择行为本身构成授权。

## Q8.2 URI 授权的底层：UriGrantsManagerService

```
Provider App（如 MediaProvider）
   │  声明 android:grantUriPermissions="true"
   ▼
系统 Picker（PhotoPicker / ContactsPicker，跑在 MediaProvider / Contacts 进程）
   │  用户选中一项
   ▼
setResult(RESULT_OK, Intent().setData(uri)
              .addFlags(FLAG_GRANT_READ_URI_PERMISSION))
   │
   ▼
ActivityManagerService.finishActivity → 处理 result Intent
   └─ UriGrantsManagerService.grantUriPermissionFromIntent()
        └─ grantUriPermissionUnchecked(callingUid, targetPkg, GrantUri, modeFlags)
             └─ 建立 UriPermission 记录：{sourceUid, targetUid, uri, modeFlags, owner}
                 owner = UriPermissionOwner（通常绑定到 ActivityRecord / 进程）
   │
   ▼
目标 App 用 ContentResolver.openInputStream(uri)
   └─ ContentProvider.enforceReadPermission
        └─ ActivityManager.checkUriPermission → UGMS 查表命中 → 放行
```

**源码路径**：
- `frameworks/base/services/core/java/com/android/server/uri/UriGrantsManagerService.java`
- `frameworks/base/services/core/java/com/android/server/uri/UriPermission.java`
- `frameworks/base/services/core/java/com/android/server/uri/UriPermissionOwner.java`
- Photo Picker：`packages/providers/MediaProvider/`（`PhotoPickerActivity`、`PickerDataLayer`）

**三个易错点**：
1. **临时授权 vs 持久授权**：默认授权生命周期绑定 `UriPermissionOwner`（Activity 销毁即回收）。要长期持有必须 `takePersistableUriPermission()`，且 Provider 端要用 `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`。持久授权记录落盘在 `/data/system/urigrants.xml`，**每个 App 有数量上限**（超了会被 LRU 淘汰，这是 SAF 长期使用的隐性坑）。
2. **A17 收紧隐式 URI 授权**（承 **第十篇**）：以前把 URI 塞进 Intent 传给第三方，系统会宽松地隐式授权；现在必须显式 flag + 目标明确。
3. **`grantUriPermission` 的传递性**：被授权方**不能**再转授给第三方（除非它本身有读权限）。防的就是 confused deputy（对照 **第十篇 §3 BAL** 的 `callingUid` vs `realCallingUid`）。

## Q8.3 系统渲染 UI 的安全边界：为什么「系统按钮」能免权限？

以 A17 的**系统渲染位置按钮**为例：

- 按钮由 **SystemUI 进程**渲染并叠加在应用窗口之上（类似 `Notification` 的 media control）。
- App 拿不到按钮的输入事件，也无法伪造点击 —— 因为窗口属于系统，`WindowManagerService` 会给它 `FLAG_NOT_TOUCH_MODAL` + 系统层级（`TYPE_APPLICATION_OVERLAY` 之上的私有层级），并开启 **`HIDE_NON_SYSTEM_OVERLAY_WINDOWS`** 保护。
- 用户点击后，系统直接给 App 一次 **session 级精确位置**，不落成权限。

**核心安全前提：点击的真实性（tapjacking 防护）**。系统必须保证这个按钮**没有被应用的透明浮窗遮挡或伪造**。WMS 的 `FLAG_WINDOW_IS_OBSCURED` / `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` 与 `InputDispatcher` 的遮挡检测就是为此存在。

> **面试延伸**：这与 **第十一篇** 提到的 **Protected Confirmation（ConfirmationUI）** 是同一思想的不同强度版本 —— 后者更极端，把确认 UI 整个放到 **TEE**（安全世界）里渲染，连内核被攻破都无法伪造用户确认。**从「系统渲染」到「TEE 渲染」，是可信 UI 的两个安全等级**。

---

# §9 无障碍语义树与 AI Agent UI 自动化

> **为什么 2026 年这章突然变热点**：Google 明说了，对没有接入 AppFunctions 的应用，走 **「AI 代理 UI 自动化框架」** —— 系统/助手直接操作 UI 完成多步任务。它的底层就是无障碍体系。**AI Agent 时代，无障碍语义树从"辅助功能"升级成"机器理解 UI 的唯一通用接口"**。

## Q9.1 无障碍事件与节点的完整链路

```
应用进程                              system_server                    AccessibilityService 进程
   │                                       │                                    │
   │ View.sendAccessibilityEvent()         │                                    │
   │  └─ ViewRootImpl 转发                  │                                    │
   ├───────Binder──────────────────────────▶│ AccessibilityManagerService        │
   │                                       │  └─ 按 feedbackType/eventTypes 过滤 │
   │                                       ├───────────────────────────────────▶│ onAccessibilityEvent()
   │                                       │                                    │
   │                                       │◀── findAccessibilityNodeInfo… ─────┤ 服务反向查询节点树
   │◀── AccessibilityInteractionController ─┤    (通过 IAccessibilityInteraction │
   │     .findAccessibilityNodeInfoByAccessibilityId()                          │     Connection)
   │      └─ 在 App 的 UI 线程上执行                                              │
   │      └─ View.createAccessibilityNodeInfo()                                 │
   │           └─ onInitializeAccessibilityNodeInfo()                           │
   ├──────── AccessibilityNodeInfo（含 bounds/text/actions/…）─────────────────▶│
   │                                                                            │
   │◀─── performAccessibilityAction(ACTION_CLICK / ACTION_SET_TEXT / …) ────────┤ Agent 执行动作
```

**源码路径**：
- `frameworks/base/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java`
- `frameworks/base/core/java/android/view/accessibility/AccessibilityNodeInfo.java`
- `frameworks/base/core/java/android/view/AccessibilityInteractionController.java`
- `frameworks/base/core/java/android/accessibilityservice/AccessibilityService.java`

**三个关键点**：
1. **节点查询是跨进程 + 跳到目标 App 的 UI 线程执行的**。所以 App 主线程卡顿会直接拖慢无障碍/Agent 操作，甚至超时。这是"无障碍服务导致卡顿"投诉的根因之一。
2. **`AccessibilityNodeInfo` 有对象池**（历史上 `recycle()` 是必须的，A13 后已废弃回收要求）。海量节点查询会产生大量 Binder 事务 —— 这也是为什么 Agent 做 UI 自动化时性能敏感。
3. **`ACTION_CLICK` 不是注入 MotionEvent**，而是直接调 `View.performClick()`；而 `dispatchGesture()` 才是真正注入手势（走 `InputManager` 的 native 注入）。**二者的可拦截性和真实性完全不同** —— 前者绕过了 `onTouchEvent`，很多自定义 View 不响应。

## Q9.2 Compose 的语义树 —— 为什么它对 AI Agent 更友好

Compose 没有 View，所以无法靠 `View.createAccessibilityNodeInfo()`。它维护一棵独立的 **语义树（Semantics Tree）**：

```
LayoutNode 树（布局/绘制用）
     │
     │  每个带 SemanticsModifierNode 的节点贡献语义
     ▼
SemanticsNode 树（语义用）
     │  SemanticsConfiguration：
     │    contentDescription、text、role、stateDescription、
     │    onClick(label, action)、editableText、toggleableState…
     ▼
AndroidComposeViewAccessibilityDelegateCompat
     │  把 SemanticsNode 映射为 AccessibilityNodeInfo
     │  用 virtual view id（SemanticsNode.id）表示"虚拟子 View"
     ▼
AccessibilityNodeProvider  →  系统无障碍框架
```

**面试点：为什么说语义树对 AI Agent 更友好？**

| 维度 | View 体系 | Compose 语义树 |
|---|---|---|
| 语义来源 | **从渲染结构反推**（View 类型 → role），常常缺失 | **显式声明**（`Modifier.semantics { }`、`role = Role.Button`） |
| 动作暴露 | 靠 `isClickable` 猜 | `onClick(label = "提交订单") { ... }` —— **带自然语言 label 的动作** |
| 结构噪音 | 布局容器（LinearLayout 嵌套）全在树里 | `mergeDescendants = true` **合并子树**，只暴露语义单元 |
| 与业务语义的距离 | 远（"一个 TextView"） | 近（"一个价格标签，值 ¥99"，通过 `stateDescription`） |

**这就是 Compose-First 与 Intelligence System 的深层耦合**：Compose 的声明式语义天然产出**机器可读的 UI 语义图**，而这正是 AI Agent 理解和操作 UI 所需要的。Google 推 Compose-First 不只是为了开发体验，**也是在为 Agent 时代准备 UI 的语义基础设施**。这个观点在面试里说出来，是很强的加分项。

**易错点**：
- `Modifier.clickable { }` 会**自动**加上 `onClick` 语义；但 `Modifier.pointerInput { detectTapGestures { } }` **不会** —— 手写手势检测会让无障碍/Agent 完全"看不见"这个可点击区域。**这是 Compose 无障碍最常见的 bug**。
- `mergeDescendants = true` 会把子节点语义合并上来，但 `clearAndSetSemantics { }` 是**清空并重设**。混用会导致语义丢失。
- 测试里的 `onNodeWithText` / `onNodeWithContentDescription` 查的就是这棵语义树 —— **所以"能被测试找到"和"能被 Agent/读屏找到"是同一件事**，语义写得好，测试和无障碍一起解决。

## Q9.3 Agent UI 自动化的安全模型（延伸思考）

Google 提到的降级方案要求：
- 通知栏 **"live view"** 让用户随时接管；
- 敏感操作（如支付）**执行前提醒**。

**为什么必须这样设计**：AccessibilityService 是 Android 上**权限最大的能力之一** —— 能读全屏内容 + 能模拟任意操作，等价于「设备完全控制」。历史上无数恶意软件用它做银行木马、自动授权。所以：
1. 开启需要用户在**设置里手动**打开（`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`），且 A11+ 对**非无障碍用途**的滥用有 Play 政策打击。
2. A13 起对**旁加载（sideload）** 的应用限制开启无障碍（`Settings > Restricted setting`）。
3. Agent 场景下还要叠加：可视化提示（用户知道机器在操作）+ 敏感动作确认（回到人类决策）。

**面试延伸**：把这条和 **§8 的可信 UI**、**第十一篇 Protected Confirmation** 串起来 —— **当机器可以代替人点击时，"如何证明这次点击是人做的"就成了核心安全问题**。答案分三层：系统渲染 UI（防应用伪造）→ TEE 渲染 UI（防内核伪造）→ Agent 场景的显式人工确认（防 AI 越权）。

---

# §10 查缺补漏 · 易错点速记 · 十三篇交叉索引

## 10.1 本篇 18 条易错点速记（考前十分钟看这个）

1. `@Composable` **不是运行时注解** —— 是编译器插件在 IR 阶段注入 `$composer` / `$changed` 参数，改变了调用约定。
2. **带返回值的 `@Composable` 不生成 restart group**，不是重组边界。
3. `startReplaceGroup`（分支）状态**不保留**，`startMovableGroup`（`key{}`）状态**跟着移动**。
4. 强跳过模式下不稳定参数用 **`===` 实例相等**判断；跨模块类若模块未应用 Compose 编译器 → 默认不稳定。
5. `@Immutable` / `@Stable` 是**对编译器的承诺，编译器不校验**，撒谎会导致 UI 不更新。
6. SlotTable 是 **gap buffer + 平坦数组**，不是对象树；同一时刻只能有**一个 `SlotWriter`**。
7. Compose 的状态订阅是 **"读取即订阅"**（Snapshot readObserver），不是显式 collect。**在错误的作用域读 state = 过度重组**。
8. `derivedStateOf` 用于**抑制高频中间态**，滚动相关状态几乎必用。
9. Compose 重组跑在 Choreographer 的 **ANIMATION** 回调，View traversal 在 **TRAVERSAL** 回调，同一帧内先后执行 —— **Compose 没有绕过 Choreographer / HWUI**。
10. Compose **强制单遍测量**；需要"先量后布"必须用 `SubcomposeLayout`，代价是**额外一次组合**。
11. `ComposeView` 在 Fragment 里不设 `DisposeOnViewTreeLifecycleDestroyed` → **Composition 泄漏**。
12. `staticCompositionLocalOf` 变化会让**整个 provider 子树重组**；`compositionLocalOf` 才细粒度。
13. AppFunctions 中，Provider 侧 **`Binder.getCallingUid()` 拿到的是 SYSTEM_UID**，真实调用方由 system_server 以参数形式传入。
14. AppSearch 的 PlatformStorage 是**全用户共用一个 Icing 实例**，靠 prefix + VisibilityStore 隔离，不是每 App 一个库。
15. APK v2 摘要覆盖「ZIP 条目区 + 中央目录 + EOCD」，**跳过 Signing Block 自身**，且 EOCD 的中央目录偏移在算摘要时被替换。
16. v3.2 是 **hybrid 签名**（经典 + ML-DSA 并存），不是替换；PQC 签名体积大一个数量级。
17. 三条杀进程路径：**内核 OOM（Signal 9）/ LMKD（REASON_LOW_MEMORY，PSI 驱动）/ A17 Memory Limiter（REASON_OTHER + `MemoryLimiter:AnonSwap`，个体超标即杀，前台也杀）**。
18. Compose 里 `Modifier.pointerInput { detectTapGestures { } }` **不产生 onClick 语义** → 无障碍与 AI Agent 都"看不见"。

## 10.2 面试高频追问链（本篇专属）

**链条 A：Compose 性能三连问**
> 「Compose 比 View 快吗？」→「不一定。Compose 省掉了 View 层级嵌套与多遍测量，但增加了组合阶段和 Snapshot 开销。」
> →「那怎么定位 Compose 性能问题？」→「Compose 编译器指标看 skippable/restartable + Layout Inspector 的重组计数 + Perfetto 上的 `Recomposer` / `AndroidOwner:measure` slice。」
> →「怎么优化？」→「① 让参数稳定（`@Immutable`/稳定性配置文件）② 延迟读取（lambda 版 Modifier）③ `derivedStateOf` ④ `LazyColumn` 加 key ⑤ 避免在顶层读高频 state。」

**链条 B：AI Agent 三连问**
> 「AppFunctions 相比 Intent 强在哪？」→ 语义可发现 + schema 化 + 免拉起 UI。
> →「没接 AppFunctions 的 App 怎么办？」→ Agent UI 自动化，走无障碍语义树。
> →「这不危险吗？」→ 危险，所以要 live view + 敏感操作确认；根本问题是「如何证明操作出自人类」，答案是系统渲染 UI → TEE 渲染 UI → 显式确认三层。

**链条 C：进程死因三连问**
> 「怎么知道上次为什么被杀？」→ `getHistoricalProcessExitReasons`。
> →「LMK 杀和 A17 Memory Limiter 杀怎么区分？」→ REASON_LOW_MEMORY vs REASON_OTHER + description。
> →「为什么 A17 之后 `onTrimMemory` 不管用了？」→ A14 起只剩两个常量，且 Memory Limiter 是**静默杀**、无回调；唯一手段是 ApplicationExitInfo + ProfilingManager 自动 heap dump。

## 10.3 十三篇交叉索引

| # | 日期 | 主题 | 专题数 |
|---|---|---|---|
| 1 | 07-23 | 主篇：Handler/Binder/启动/AMS/WMS/View/ANR/LMKD/Compose/HAL/内核/MTK | 16 |
| 2 | 07-23 | 热点拓展：Input/PMS/ART/SystemUI/折叠屏/SELinux/OTA/JNI/Binder 安全/Perfetto | 10 |
| 3 | 07-23 | 深挖篇：ART 对象头/CMC GC/deopt/Binder 驱动调试/Rust Binder/VSync/Camera/Audio/GKI | 11 |
| 4 | 07-24 | 图形多媒体通信：HWUI/SF/Gralloc/多刷新率/MediaCodec/Codec2/Thermal/Power/RIL/WiFi/BT | 12 |
| 5 | 07-27 | 系统基建可观测性：16KB 页/ClassLoader/权限/Keystore2/AVB/Vold/logd/RRO/Doze | 11 |
| 6 | 07-28 | 端侧 AI 与 A17 演进：NNAPI/LiteRT/CarService/Vulkan/ART 产物/virtual A/B | 10 |
| 7 | 07-29 | A17 新雷区：Lock-free MessageQueue/分代 GC/hiddenapi/ProfilingManager/NFC/Media3/端侧 LLM | 8 |
| 8 | 07-30 | 渲染合成深水区：SF RenderEngine/Codec2 vendor/Memory Limiter/DCL 加固/Keystore 限额/ART 镜像 | 7 |
| 9 | 07-31 | 兼容性框架主线：platform_compat/letterbox/BAL/Bubbles/Handoff/Pointer Capture/OTP/ECH/SQL 严格模式/hiddenapi 流水线 | 10 |
| 10 | 08-01 | 安全世界 TEE：Trusty/TIPC/Keystore2-KeyMint/Gatekeeper-Weaver/Attestation/Widevine/FBE/DMA-BUF heaps | 8 |
| 11 | 08-02 | EL2 机密计算：pKVM/AVF/RPC Binder/AISeal/威胁模型/三条杀路径/eBPF/Ravenwood | 8 |
| **13** | **08-03** | **智能系统主线：AppFunctions/AppSearch-Icing/Compose 编译器/Compose 运行时/Compose 接缝/APK v3.2 PQC/ApplicationExitInfo/系统托管 UI/无障碍语义树** | **9** |

> **累计 120 个专题**。四层执行世界（EL0 App / EL1 内核 / EL2 pKVM / EL3 TEE）已在第十、十一篇闭环；本篇补齐的是**纵向的"智能层"** —— 从 UI 语义（Compose 语义树）到能力索引（AppSearch/AppFunctions）再到信任根演进（PQC 签名）。

## 10.4 本篇与既有篇章的强关联点

- **§1 AppFunctions ↔ 第十一篇 §4 AISeal**：AppFunctions 索引在 AppSearch，而 AISeal 把 AppSearch 个人数据库搬进 pVM。**Agent 要访问受保护数据时，执行路径要跨 EL1→EL2 边界**。
- **§4 Recomposer ↔ 第一篇 Handler/同步屏障 + 第四篇 Choreographer/VSync**：`AndroidUiDispatcher` 的消息处理与 `MonotonicFrameClock` 都建立在 Looper + Choreographer 之上。
- **§5 绘制 ↔ 第四篇 §1 HWUI/RenderThread + §3 SurfaceFlinger**：Compose 只是换了 DisplayList 的生产者。
- **§6 PQC ↔ 第十篇 §3 KeyMint + §5 Key Attestation**：ML-DSA 需要 KeyMint HAL 与证书链支持。
- **§7 ApplicationExitInfo ↔ 第八篇 Memory Limiter + 第十一篇 §6 三条杀路径**：本篇给出了三条路径的**可观测抓手**。
- **§8 URI 授权 ↔ 第九篇 §3 BAL confused deputy**：都是「代理被诱导越权」问题的两种表现。
- **§9 无障碍 ↔ 第九篇 §6 Pointer Capture + 第二篇 Input 全链路**：`dispatchGesture` 走的是同一套 InputManager 注入路径。

## 10.5 下一轮可轮换的真·未覆盖角度

- LiteRT NPU delegate 源码走读（`external/tensorflow/lite/delegates/`）
- CarService 电源状态机完整状态图（`CarPowerManagementService` + VHAL `AP_POWER_STATE_REQ`）
- Codec2 vendor 组件调试实战（`C2SoftXxx` 插件加载与 `codec2 store`）
- 端侧 LLM 量化工程化（INT4/INT8、KV cache 内存布局、NPU 算子回退）
- Protected Confirmation / ConfirmationUI（TEE 渲染确认 UI 全链路）
- StrongBox / Secure Element 深水区（`ESE HAL`、applet、`IOmapiService`）
- AVF 隔离编译（`odrefresh` in pVM，`compos`）
- A17 Verified Financial Calls / Live Threat Detection
- Kotlin Multiplatform 与 Compose Multiplatform 在 Android 侧的运行时差异
- `Ravenwood` 之外的 host 侧测试：`Robolectric` shadow 机制与二者取舍

---

## 延伸阅读

- **AppFunctions**：`developer.android.com/ai/appfunctions`；`packages/modules/AppSearch/` 源码；AppFunctions agent skill 仓库
- **AppSearch / Icing**：`external/icing/` 的 `icing/index/main/main-index.h`（posting list 压缩）、`icing/scoring/bm25f-calculator.h`
- **Compose 编译器**：Kotlin 仓库 `plugins/compose/compiler-hosted/`；`ComposableFunctionBodyTransformer.kt` 是核心 lowering
- **Compose 运行时**：`androidx` 的 `compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/snapshots/Snapshot.kt`（读懂这一个文件，Compose 状态系统就通了）
- **APK 签名**：`source.android.com/docs/security/features/apksigning`（v2/v3/v4 规范）；`tools/apksig/`
- **ApplicationExitInfo**：`AppExitInfoTracker.java` + `system/memory/lmkd/lmkd.cpp` 的 `LMK_PROCKILL` 上报路径
- **无障碍**：`AccessibilityManagerService.java`；Compose 侧 `AndroidComposeViewAccessibilityDelegateCompat.android.kt`

---

*本材料为第十三篇，可与前十二篇合并复习。源码路径以 Android 14（API 34）为基准；Mainline 模块（AppSearch、MediaProvider、ART 等）实际实现可能随 Play 系统更新演进。*
