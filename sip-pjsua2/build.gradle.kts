plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.voicesipagent.pjsua2"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 34
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    sourceSets {
        getByName("main") {
            // Produced by native/build-pjsua2.sh (git-ignored).
            java.srcDir("src/generated/java")
            jniLibs.srcDir("src/generated/jniLibs")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":voice-api"))
    testImplementation(libs.junit)
}

val checkPjsua2 by tasks.registering {
    val marker = file("src/generated/java/org/pjsip/pjsua2/Endpoint.java")
    doLast {
        if (!marker.exists()) throw GradleException("pjsua2 binding missing: run sip-pjsua2/native/build-pjsua2.sh first")
    }
}
tasks.named("preBuild") { dependsOn(checkPjsua2) }
