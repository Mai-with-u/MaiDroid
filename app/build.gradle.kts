plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 发布签名：密钥库路径、库密码、别名、密钥密码都从环境变量读取（CI 里来自 GitHub Secrets），
// 密钥库本身不进仓库。持有密钥的人在本地发布时设置同名环境变量即可。
// 环境变量为空串（CI 里 Secret 没配时就是这样）按没配处理。
fun signingEnv(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

val releaseKeystorePath = signingEnv("MAIDROID_KEYSTORE_PATH")
val releaseKeystorePassword = signingEnv("MAIDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingEnv("MAIDROID_KEY_ALIAS")
val releaseKeyPassword = signingEnv("MAIDROID_KEY_PASSWORD")

// 路径、库密码、别名三项齐全才启用发布签名；任何一项缺失时 release 保持未签名，
// 日常 debug 构建和本地 assembleRelease 都照旧，不会因为没有密钥而失败。
val hasReleaseSigning = releaseKeystorePath != null &&
    releaseKeystorePassword != null &&
    releaseKeyAlias != null

// 应用自更新查询的 Release 接口。只有本地真机调试时才用 -PappUpdateApiUrl=... 指到电脑上的假 Release 源
// （经 adb reverse 访问），CI 和日常构建都用默认的 GitHub 地址。
val appUpdateApiUrl = providers.gradleProperty("appUpdateApiUrl").orNull?.takeIf { it.isNotBlank() }
    ?: "https://api.github.com/repos/Mai-with-u/MaiDroid/releases/latest"

// 远程公告、6 位求助码诊断快照上传与匿名遥测的 Cloudflare Worker 根地址，以及静态签名清单的 GitHub 兜底地址。
// 本地真机联调可用 -PremoteOpsBaseUrl=http://127.0.0.1:18787 -PremoteSigningPublicKeys="2026-k1=..." 指到 wrangler dev。
val remoteOpsBaseUrl = providers.gradleProperty("remoteOpsBaseUrl").orNull?.trim()?.trimEnd('/')
    ?: "https://ops.8u.nz"
val remoteManifestFallbackUrl = providers.gradleProperty("remoteManifestFallbackUrl").orNull?.trim()
    ?: "https://raw.githubusercontent.com/Mai-with-u/MaiDroid/ops-manifest/manifest.json"
val remoteSigningPublicKeys = providers.gradleProperty("remoteSigningPublicKeys").orNull?.trim()
    ?: ""

android {
    namespace = "org.maiwithu.maidroid"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "org.maiwithu.maidroid"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.3.0"
        buildConfigField("String", "APP_UPDATE_API_URL", "\"$appUpdateApiUrl\"")
        buildConfigField("String", "REMOTE_OPS_BASE_URL", "\"$remoteOpsBaseUrl\"")
        buildConfigField("String", "REMOTE_MANIFEST_FALLBACK_URL", "\"$remoteManifestFallbackUrl\"")
        buildConfigField("String", "REMOTE_SIGNING_PUBLIC_KEYS", "\"$remoteSigningPublicKeys\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(checkNotNull(releaseKeystorePath))
                storePassword = checkNotNull(releaseKeystorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                // 密钥密码没单独提供时沿用库密码（PKCS12 密钥库本来就要求两者一致）
                keyPassword = releaseKeyPassword ?: checkNotNull(releaseKeystorePassword)
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // 有发布签名时 release 直接产出已签名的 APK，没有时仍是未签名包
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += listOf("xz", "zip", "deb")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Kotlin
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Storage — MMKV (Tencent, Maven Central)
    implementation(libs.mmkv)

    // Networking
    implementation(libs.okhttp)

    // Runtime asset extraction
    implementation(libs.commons.compress)
    implementation(libs.xz)

    // Blur
    implementation(libs.blurview)

    // Testing：只有 JVM 单元测试，没有设备上的仪器测试
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
