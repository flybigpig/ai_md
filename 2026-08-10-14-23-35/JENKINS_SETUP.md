# Jenkins CI/CD 配置与适配说明（Android / AOSP）

本说明配合仓库根目录 `Jenkinsfile` 使用，覆盖「构建机准备 → 凭据配置 → AOSP 系统应用适配」三部分。

---

## 一、Jenkins 构建机 / Agent 准备

### 方案 A：Docker Agent（Jenkinsfile 默认，推荐）
- Jenkins 装 **Docker Pipeline** 插件，构建节点需能跑 Docker。
- 镜像 `cirrusci/android-sdk:latest` 已含 cmdline-tools + 常用 build-tools；如需 NDK / CMake，换用 `reactnativecommunity/react-native-android` 或自建镜像。
- JDK：AGP 8.x（Android 14）要求 **JDK 17**；若用自带节点，确保 `java -version` 是 17。

### 方案 B：专用物理 / VM 构建机（label 模式）
1. 机器装：JDK 17、Android SDK（cmdline-tools + platform-34 + build-tools 34.0.0）、Git、Gradle（或用 wrapper）。
2. Jenkins 装 **agent** 节点，打标签 `android-build`。
3. 将 `Jenkinsfile` 里 `agent { docker {...} }` 改为 `agent { label 'android-build' }`。
4. 环境变量 `ANDROID_HOME` 改成该机实际路径（如 `/opt/android-sdk` 或 `C:\Android\Sdk`）。

### 必要 Jenkins 插件
- Pipeline / Pipeline: Stage View
- Docker Pipeline（方案 A）
- JUnit、HTML Publisher、Warnings NG（报告展示）
- AnsiColor（彩色日志）
- Workspace Cleanup（cleanWs）
- Credentials Binding（credentials() 注入）

---

## 二、凭据配置（Jenkins → Manage Credentials）

在对应作用域添加以下凭据（ID 需与 `Jenkinsfile` 中一致）：

| 凭据 ID               | 类型            | 说明                              |
|-----------------------|-----------------|-----------------------------------|
| `android-keystore`    | Secret file     | release 签名 keystore（.jks）     |
| `android-keystore-pass` | Secret text   | keystore 密码                     |
| `android-key-alias`   | Secret text     | 签名别名                          |
| `android-key-pass`    | Secret text     | 密钥密码                          |
| `deploy-token`        | Secret text     | 内部分发 / Maven 上传令牌         |
| `sonar-token`（可选） | Secret text     | 静态扫描平台令牌                  |

> Debug 构建不需要签名凭据；只有 `BUILD_TYPE=release` 且走正式签名时才消费这些变量。

---

## 三、阶段说明

| 阶段        | Gradle 任务（示意）              | 产物 / 报告                         |
|-------------|----------------------------------|-------------------------------------|
| Prepare     | `./gradlew --version`            | 环境自检                            |
| Build       | `assemble<Flavor><Type>`         | `app/build/outputs/apk|bundle`      |
| Unit Test   | `test<Flavor><Type>UnitTest`     | `build/test-results/**/*.xml`       |
| Lint        | `lint<Flavor><Type>` + checkstyle | `build/reports/lint-results.html`   |
| Deploy      | 归档 + 上传                       | Jenkins Artifacts / 内部分发        |

---

## 四、AOSP 系统应用 / 整编适配

如果你是**在 AOSP 源码树内**构建系统应用（如改 AMS/ATMS 后新增的 system app），或做整编，Gradle 任务不适用，需换成 `envsetup + lunch + m` 流程。

### 4.1 系统应用（单模块 `mm`）
把 Build 阶段替换为：

```groovy
stage('Build (AOSP mm)') {
    steps {
        sh '''
            source build/envsetup.sh
            lunch aosp_x86_64-eng   # 按目标改：aosp_arm64-eng / 你们的产品 lunch
            mm -j$(nproc)           # 在模块目录下编译，等价于 m <module>
        '''
    }
}
```

### 4.2 整编（不常用，谨慎）
整编耗时极长、磁盘占用大，建议单独低频任务，不要和 Test/Lint 混在同一条流水线：

```groovy
stage('Full Build (AOSP)') {
    steps {
        sh '''
            source build/envsetup.sh
            lunch aosp_x86_64-userdebug
            m -j$(nproc)
        '''
    }
}
```

### 4.3 AOSP 下 Test / Lint 的差异
- **单元测试**：AOSP 用 `atest <ModuleName>`（需先 `lunch`），不是 Gradle `test`。
- **静态检查**：AOSP 常用 `make lint` / `hidl-lint` / `cpplint`，而非 Gradle Lint。
- **Deploy**：系统应用产物是 `.apk` 或编译进 `system.img`；如需导出，归档 `out/target/product/<device>/system/` 下对应文件，或用 `adb install` 推到测试机。

> 要点：AOSP 构建机必须预置完整 AOSP 源码树 + 对应 lunch 环境，且 Jenkins agent 建议用实体/VM（Docker 层叠 AOSP 源码树较笨重）。整编流水线务必设长超时（如 240 分钟）与充足磁盘。

---

## 五、如何接入真实 Deploy 目标

`Jenkinsfile` 的 Deploy 阶段目前只做「归档」。接入真实分发，在 `Deploy` 的 `script` 块内按注释替换：

- **Firebase App Distribution**：加 `apply plugin: 'com.google.firebase.appdistribution'`，执行 `./gradlew appDistributionUploadRelease`，凭据用 `FIREBASE_TOKEN`。
- **内部文件服务器**：`curl -H "Token: ${DEPLOY_TOKEN}" -T <apk> https://files.internal/upload`。
- **内部 Maven**（library 模块）：`./gradlew publishReleasePublicationToMavenRepository`，配 `maven-publish` + 仓库凭据。

---

## 六、快速验证

```bash
# 本地先用 Gradle 跑通三阶段，再上 Jenkins
./gradlew clean assembleDebug testDebugUnitTest lintDebug
```
