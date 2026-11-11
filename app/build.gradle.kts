plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xiaojing.watermarkcamera"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiaojing.watermarkcamera"
        minSdk = 24
        targetSdk = 34
        // 版本规则：默认只递增最后一位（0.0.x）；主次版本号需人工确认才动。
        // RC 标签按顺序往后加：RC1、RC2、RC3……
        versionCode = 4
        versionName = "0.0.2-RC3"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            // 由 CI 或本地环境变量提供；缺省时回退到 debug 签名以便本地出包
            val storePath = System.getenv("WM_SIGN_STORE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("WM_SIGN_STORE_PASSWORD")
                keyAlias = System.getenv("WM_SIGN_KEY_ALIAS")
                keyPassword = System.getenv("WM_SIGN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("WM_SIGN_STORE").isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// 安装包名：WatermarkCamera-<版本号>.apk（如 WatermarkCamera-0.0.4-RC3.apk）
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val vName = variant.outputs.first().versionName.get()
            // AGP 8 的 VariantOutput 接口不暴露 outputFileName，用实现类设置
            val impl = output as? com.android.build.api.variant.impl.VariantOutputImpl
            impl?.outputFileName?.set("WatermarkCamera-" + vName + ".apk")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
