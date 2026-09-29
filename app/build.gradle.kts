import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// DEEPSEEK_API_KEY 安全策略（2026-09-27 审计 P0#5 修复）：
// 密钥不再进入 release 构建——release 的 BuildConfig.DEEPSEEK_API_KEY 恒为空串，
// 正式包一律走 BYOK 运行时配置（Onboarding 向导 / 设置页，AES-GCM 加密落盘）。
// debug 构建保留从 local.properties / -P 参数 / 环境变量读取的本机开发便利。
// 使用说明：在项目根目录 local.properties（不入库）添加 `DEEPSEEK_API_KEY=sk-xxxx`
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val devApiKey: String = localProps.getProperty("DEEPSEEK_API_KEY")
    ?: (project.findProperty("DEEPSEEK_API_KEY") as String?)
    ?: System.getenv("DEEPSEEK_API_KEY")
    ?: ""

android {
    namespace = "com.lirica.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lirica.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // R8 混淆与资源收缩：release 包启用代码最小化（真机冒烟待验收：无障碍 / 悬浮窗 / Room / SSE）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // release 恒空：防止密钥烘进可分发的 APK（逆向提取风险）
            buildConfigField("String", "DEEPSEEK_API_KEY", "\"\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            // debug 保留本机开发便利；缺失时回退空串，运行时由 Onboarding 向导引导配置
            buildConfigField("String", "DEEPSEEK_API_KEY", "\"$devApiKey\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Ktor Client (OkHttp Engine for Android + SSE support)
    val ktorVersion = "2.3.12"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // WorkManager (Native Background Autonomous Life)
    val workVersion = "2.9.1"
    implementation("androidx.work:work-runtime-ktx:$workVersion")

    // Google ML Kit Text Recognition (Chinese - Bundled Model, No GMS required)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // ONNX Runtime Android (Edge Neural Embeddings)
    // 2026-09-27 审计 P1 修复：1.19.2 的 .so 未做 16KB page size 对齐（修复线 1.22+），
    // Android 15 16KB 内核设备上会 UnsatisfiedLinkError；升至 1.25（官方明确标注 16KB alignment）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.25.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.25.0")

    // Shizuku (Privileged ADB execution & penetration)
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.turbine:turbine:1.2.0")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

ksp {
    // Room schema 导出：配合 @Database(exportSchema = true)，
    // 每次构建生成 app/schemas/<FQCN>/<version>.json，作为编写 Migration 的依据
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
                it.systemProperty("robolectric.dependency.repo.id", "aliyun")
                it.testLogging {
                    events("passed", "skipped", "failed")
                }
            }
        }
    }
}
