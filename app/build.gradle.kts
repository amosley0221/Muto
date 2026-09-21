plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Version, from the release tag when CI sets it.
 *
 * versionCode has to increase on every release or Android refuses to install the update over the
 * one already on the device ("app not installed"). Deriving it from the semantic version keeps it
 * monotonic and reproducible - the same tag always produces the same build - which a CI run
 * number would not.
 */
val mutoVersionName: String = System.getenv("MUTO_VERSION_NAME") ?: "0.1.0"
val mutoVersionCode: Int = System.getenv("MUTO_VERSION_CODE")?.toIntOrNull()
    ?: mutoVersionName.substringBefore('-').split('.').let { parts ->
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        major * 10_000 + minor * 100 + patch
    }.coerceAtLeast(1)

/**
 * Release signing, supplied by the environment.
 *
 * The signing key is what lets an update install over an existing copy: Android treats an APK
 * signed with a different key as a different app and refuses the upgrade. So the same key has to
 * sign every release, which is why it lives in CI secrets rather than in this repository.
 *
 * When the key is absent - any local build, or a fork without the secrets - the release variant
 * simply builds unsigned rather than failing.
 */
val keystoreFile: String? = System.getenv("MUTO_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val hasReleaseKey: Boolean = keystoreFile != null && file(keystoreFile).exists()

android {
    namespace = "dev.muto.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.muto.app"
        minSdk = 26
        targetSdk = 35
        versionCode = mutoVersionCode
        versionName = mutoVersionName
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = System.getenv("MUTO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MUTO_KEY_ALIAS")
                keyPassword = System.getenv("MUTO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // A separate application id, so a debug build from CI can sit alongside a release
            // without one masquerading as an update to the other. They are two apps on the device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
}
