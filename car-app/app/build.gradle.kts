import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is optional so a fresh checkout can still build debug APKs.
// `car-app/make-keystore.sh` creates a self-signed key and writes
// keystore.properties, which is deliberately kept out of version control.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
val hasReleaseKeystore = releaseStoreFile?.exists() == true

android {
    namespace = "com.andy8401.mygo.car"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andy8401.mygo.car"
        // mygobridge.aar was built with -androidapi 24; do not go below it.
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        // The app's own version. Not to be confused with the Go facade's
        // appVersion ("0.1.25"), which is the *host capability* version that
        // plugins gate themselves on via minAppVersion.
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Head unit is arm64-only. Emulator builds keep x86_64 too so the
        // multi-arch AAR's JNI library is packaged for the system image.
        if (!project.hasProperty("emulator")) {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            // R8 stays off: the Go bridge is reached through JNI and the
            // gomobile facade's classes are looked up by name, so shrinking
            // buys little and risks stripping the entry points.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("No keystore.properties: the release APK will be unsigned. Run car-app/make-keystore.sh first.")
                null
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
        // BuildVersion reads VERSION_NAME from BuildConfig so the About section
        // cannot disagree with the published APK.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Media3 marks its DataSource APIs @UnstableApi; we use them with an
        // explicit @OptIn, and Kotlin accepts it. Lint's cross-lambda opt-in
        // analysis is overly strict here, so keep it as a warning.
        disable += "UnsafeOptInUsageError"
        abortOnError = true
    }
}

dependencies {
    val media3Version = "1.4.1"

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Audio path: Media3 consumes music.Stream{URL, Headers} supplied by Go.
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    // Go facade produced by android-bridge/aar.sh. Default is the arm64-only
    // head-unit AAR; pass -Pemulator to use the arm64+x86_64 build so the Go
    // bridge also loads on an x86_64 system image.
    val bridgeAar = if (project.hasProperty("emulator")) {
        "../../build/mygobridge-emulator.aar"
    } else {
        "../../build/mygobridge.aar"
    }
    implementation(files(bridgeAar))

    // Instrumentation tests exercise what unit tests cannot: the JNI library
    // loading, the plugin directory being unpacked from assets, and the plugin
    // runtime actually running on an Android system image.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
