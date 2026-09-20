import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Target 17 bytecode to match what the Android module compiles against, but do not pin a
// toolchain: any JDK 17 or newer can build this, which keeps the module easy to run on its own.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
