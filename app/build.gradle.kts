import java.io.DataInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "alpiner.app"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "alpiner.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.5"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = file("redt-release.jks")
            storeFile = ksFile
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            // JKS keystores may carry a key password distinct from the store
            // password; PKCS12 (keytool default since JDK 9) mandates they are
            // equal, and apksigner rejects a mismatched key password with
            // "Given final block not properly padded". Detect the format via
            // the magic bytes and pick the key password accordingly.
            val isJks = runCatching {
                DataInputStream(ksFile.inputStream().buffered()).use { it.readInt() }
            }.getOrDefault(0) == 0xFEEDFEED.toInt()
            keyPassword = if (isJks) {
                System.getenv("KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
                    ?: System.getenv("KEYSTORE_PASSWORD")
            } else {
                System.getenv("KEYSTORE_PASSWORD")
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            val hasReleaseKey = System.getenv("KEYSTORE_PASSWORD") != null &&
                file("redt-release.jks").exists()
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.google.android.material:material:1.14.0")

    implementation("org.apache.commons:commons-compress:1.28.0")

    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.3")
    implementation(project(":core:terminal-view"))
    implementation("com.github.anrwatchdog:anrwatchdog:1.4.0")

}
