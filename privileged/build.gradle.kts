plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.sparkcyf.voicesipagent.privileged"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        aidl = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":voice-api"))
    api(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
