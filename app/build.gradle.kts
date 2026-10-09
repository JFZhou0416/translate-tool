import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release 签名：密钥本体在工程目录之外（D:/zcodework/keys/），密码在同级的 keystore.properties。
// 没有这个文件也能构建（release 出未签名包），方便工程单独外发时不带密钥。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.wuming.screentrans"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wuming.screentrans"
        minSdk = 26
        // targetSdk 钉在 34：Android 14 起 MediaProjection / 前台服务类型的规则就固定了，
        // 不去碰 35 的新限制（edge-to-edge 等），减少真机行为差异。
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // 刻意只留一个依赖：网络走 HttpURLConnection + org.json，UI 走原生 View，
    // 不引 Compose / OkHttp / appcompat，把"墙内拉不到包"的风险降到最低。
    implementation("androidx.core:core-ktx:1.13.1")
}
