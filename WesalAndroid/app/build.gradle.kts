import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.wesal.messenger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wesal.messenger"
        minSdk = 26
        targetSdk = 35
        // Monotonic per CI build: run number + 100 stays above every already-shipped
        // build (all previous builds had the hard-coded versionCode 1), so Android
        // accepts install-over-install and the forced-update check has a real signal.
        // Local builds fall back to 101.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1) + 100
        val buildSha = (System.getenv("GITHUB_SHA") ?: "local").take(7)
        val buildTime = Instant.now().toString().take(16).replace("T", " ") + " UTC"
        versionName = "1.0.0-$buildSha"
        buildConfigField("String", "BUILD_STAMP", "\"$buildSha | $buildTime\"")

        // Phones are arm64: shipping only that ABI shrinks the APK a lot (limited mobile data).
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Configurable TURN/STUN servers via gradle.properties or environment variables (CI Secrets)
        fun readTurnConfig(key: String): String {
            val raw = (project.findProperty(key) as? String)?.takeIf { it.isNotBlank() }
                ?: System.getenv(key)?.takeIf { it.isNotBlank() }
                ?: ""
            return raw.replace("\\", "\\\\").replace("\"", "\\\"")
        }
        buildConfigField("String", "TURN_URL", "\"${readTurnConfig("WESAL_TURN_URL")}\"")
        buildConfigField("String", "TURN_USERNAME", "\"${readTurnConfig("WESAL_TURN_USERNAME")}\"")
        buildConfigField("String", "TURN_CREDENTIAL", "\"${readTurnConfig("WESAL_TURN_CREDENTIAL")}\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = project.findProperty("WESAL_KEYSTORE_PATH") as? String
            val keystorePassword = project.findProperty("WESAL_KEYSTORE_PASSWORD") as? String
            val keyAliasProp = project.findProperty("WESAL_KEY_ALIAS") as? String
            val keyPasswordProp = project.findProperty("WESAL_KEY_PASSWORD") as? String
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
            }
        }
    }

    signingConfigs {
        getByName("debug") {
            val debugKeystore = file("wesal-debug.keystore")
            if (debugKeystore.exists()) {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile != null) {
                signingConfig = releaseSigning
            }
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.storage.ktx)
    implementation(libs.firebase.messaging.ktx)
    implementation(libs.firebase.functions.ktx)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    // GIF decoding so uploaded animated (GIF) frames actually animate; version matches
    // the project's Coil version (2.7.0 in gradle/libs.versions.toml).
    implementation("io.coil-kt:coil-gif:2.7.0")

    implementation(libs.stream.webrtc.android)
    implementation(libs.google.tink.android)
    implementation(libs.play.services.location)
    implementation(libs.google.play.integrity)
    implementation(libs.accompanist.permissions)
    implementation(libs.ucrop)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.zxing.core)
    implementation(libs.mlkit.selfie.segmentation)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

