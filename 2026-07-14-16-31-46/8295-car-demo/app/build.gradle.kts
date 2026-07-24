plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.car8295"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.car8295"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // ---------------------------------------------------------------------
    // android.car 是车载系统库：
    //  * 在 AAOS automotive build target（用 automotive 系统镜像的 android.jar）下自动可用，无需引包。
    //  * 若工程基于普通 phone target 编译，请把车机/模拟器里的 android.car.jar
    //    放到 app/libs/ 并取消下一行注释：
    // compileOnly(files("libs/android.car.jar"))
    // ---------------------------------------------------------------------
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.window:window:1.3.0")          // WindowSizeClass（Jetpack，Compose 也可共用）
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // 车辆信号走系统 android.car（见上方说明），此处不引 androidx.car.app（那是模板化车机 App 库，非车辆信号）
}
