plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "com.fly.agent"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    // 程序入口:com.fly.agent.MainKt
    mainClass.set("com.fly.agent.MainKt")
    // 强制 UTF-8 输出,避免 Windows 控制台(cp936)下中文/emoji 乱码
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}

tasks.test {
    useJUnitPlatform()
}

// 方便命令行透传参数:./gradlew run --args="--goal '打开设置' --mock"
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
