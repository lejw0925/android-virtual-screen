plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

android {
    namespace = "io.github.lejw0925.virtualscreen"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.lejw0925.virtualscreen"
        minSdk = 33
        targetSdk = 37
        versionCode = 4
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    val releaseKeystore = providers.environmentVariable("AVS_KEYSTORE").orNull
    if (!releaseKeystore.isNullOrBlank()) {
        signingConfigs.create("production") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("AVS_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("AVS_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("AVS_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            if (!releaseKeystore.isNullOrBlank()) signingConfig = signingConfigs.getByName("production")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libfrpc.so", "**/libcloudflared.so")
        }
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/*.kotlin_module")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("sun.net.http.allowRestrictedHeaders", "true")
            it.jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.github.roro2239:Stellar-API:1.0.3")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("io.ktor:ktor-server-cio-jvm:3.3.3")
    implementation("io.ktor:ktor-server-core-jvm:3.3.3")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.tomlj:tomlj:1.1.1")

    // Settings keep their existing saved back stack and display lifecycle.
    implementation("androidx.compose.material3:material3:1.5.0-alpha29")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.3.3")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
