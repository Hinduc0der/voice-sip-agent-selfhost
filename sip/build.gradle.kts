import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    api(project(":voice-api"))
    testImplementation(libs.junit)
}

// Live test against a real PBX from the dev machine. Credentials come from the git-ignored
// ../sip_secret file (line 2: user, line 3: password, line 4: host:port).
//   ./gradlew :sip:sipProbe -PprobeArgs="echo *43 25"
//   ./gradlew :sip:sipProbe -PprobeArgs="listen 60"
tasks.register<JavaExec>("sipProbe") {
    group = "verification"
    description = "Registers against the PBX in sip_secret and runs a live call test."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.sparkcyf.voicesipagent.sip.SipProbeKt")
    args = providers.gradleProperty("probeArgs").getOrElse("echo").split(" ")
    systemProperty("sip.secret", rootProject.file("sip_secret").absolutePath)
}
