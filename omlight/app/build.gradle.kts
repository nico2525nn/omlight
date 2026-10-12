plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.omlight"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.omlight"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        // Compose compiler is driven by the Kotlin compose plugin.
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Personal project: sign with the debug key so the APK installs
            // on a handset as-is. Replace with a real keystore for any
            // distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // Keep debug fast; no minification.
            // Emulator + mockcam: host 10.0.2.2, port 8080.
            // Real camera: 192.168.0.10:80 (release default below).
            buildConfigField("String", "CAM_HOST", "\"10.0.2.2\"")
            buildConfigField("int", "CAM_PORT", "8080")
        }
    }
    // Release talks to the real camera AP.
    buildTypes.named("release") {
        buildConfigField("String", "CAM_HOST", "\"192.168.0.10\"")
        buildConfigField("int", "CAM_PORT", "80")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

configurations.all {
    resolutionStrategy {
        // M3 1.4.0 wants compose 1.11.2; cache has 1.11.4. Align up.
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:2.2.10",
            "androidx.compose.ui:ui:1.11.4",
            "androidx.compose.ui:ui-text:1.11.4",
            "androidx.compose.ui:ui-util:1.11.4",
            "androidx.compose.ui:ui-geometry:1.11.4",
            "androidx.compose.ui:ui-graphics:1.11.4",
            "androidx.compose.ui:ui-unit:1.11.4",
            "androidx.compose.animation:animation:1.11.4",
            "androidx.compose.runtime:runtime:1.11.4",
            "androidx.compose.foundation:foundation:1.11.4",
            "androidx.compose.material:material-ripple:1.11.4",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.9.0"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android")

    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.material3:material3:1.5.0-alpha17")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
