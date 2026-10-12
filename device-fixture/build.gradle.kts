plugins { id("com.android.application") }
android {
    namespace = "io.github.lejw0925.virtualscreen.fixture"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.lejw0925.virtualscreen.fixture"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_25; targetCompatibility = JavaVersion.VERSION_25 }
}
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
