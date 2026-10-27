plugins {
    alias(libs.plugins.android.application)
    // AGP 9+ : Kotlin intégré, pas de plugin kotlin.android
}

android {
    namespace = "com.dtrad.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dtrad.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "2.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Les backends CPU de ggml sont chargés depuis le dossier des libs natives :
    // elles doivent être extraites à l'installation.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("li.joye.yakuyomi.engine.InternalEngineApi")
    }
}

dependencies {
    implementation(project(":aichat"))
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
}
