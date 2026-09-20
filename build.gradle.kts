// Plugin versions are pinned in gradle/libs.versions.toml and applied per module, so this file
// stays empty. Keeping the Android plugin out of the root script means :core - which is plain
// Kotlin - can be configured and tested without the Android SDK present:
//
//     gradle :core:test --configure-on-demand
