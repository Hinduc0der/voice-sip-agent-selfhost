plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.sparkcyf.voicesipagent.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.sparkcyf.voicesipagent"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
    }

    // Release signing comes from ~/.gradle/gradle.properties (never from the repo); without it the
    // release APK is built unsigned.
    val signingProps = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { providers.gradleProperty("vsa.signing.$it").orNull }
    val releaseSigning = if (signingProps.values.all { it != null }) {
        signingConfigs.create("release") {
            storeFile = file(signingProps.getValue("storeFile")!!)
            storePassword = signingProps["storePassword"]
            keyAlias = signingProps["keyAlias"]
            keyPassword = signingProps["keyPassword"]
        }
    } else null

    buildTypes {
        release {
            signingConfig = releaseSigning
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "META-INF/*.kotlin_module"
    }
}

dependencies {
    implementation(project(":voice-api"))
    implementation(project(":sip"))
    implementation(project(":sip-pjsua2"))
    implementation(project(":gateway"))
    implementation(project(":telephony"))
    implementation(project(":privileged"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
}
