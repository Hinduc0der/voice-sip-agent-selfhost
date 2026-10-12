plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.sparkcyf.voicesipagent.telephony"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 34
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":voice-api"))
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
}
