// Intentionally empty. Plugin versions are pinned in gradle/libs.versions.toml and applied per
// module.
//
// Gradle warns that the Kotlin plugin is loaded separately in :app and :core and suggests moving
// it here with `apply false`. Do not: the Kotlin Android plugin needs AGP on the same classpath
// (it fails with NoClassDefFoundError on com/android/build/gradle/api/BaseVariant), so moving one
// here means moving both. That would make the root project require the Android SDK, and with it
// every module - including :core, which is plain Kotlin and is where the DNS wire format, the
// packet handling and the matching logic are tested.
//
// Keeping the root clear is what makes this work with no SDK installed at all:
//
//     ./gradlew :core:test --configure-on-demand
//
// Both modules pin the same Kotlin version, so the duplicate load the warning describes is
// benign.
