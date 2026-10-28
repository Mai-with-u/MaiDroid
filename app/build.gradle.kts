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
        versionCode = 2
        versionName = "0.1.1"
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
