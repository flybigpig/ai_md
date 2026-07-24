// 顶层 build 脚本仅声明 plugin 版本，具体 apply 在 :app 模块
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
