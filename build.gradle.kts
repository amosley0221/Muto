// The Kotlin plugins are declared here, without being applied, so both modules share one loaded
// copy - Gradle warns that loading it separately per subproject is unsupported.
//
// The Android plugin is deliberately NOT here. Keeping it out means :core, which is plain Kotlin,
// can be configured and tested without the Android SDK installed:
//
//     ./gradlew :core:test --configure-on-demand
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
}
