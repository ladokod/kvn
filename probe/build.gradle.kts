plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.ladokod.kvn.probe"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.ladokod.kvn.probe"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-family-diagnostic"
        val revision = System.getenv("GITHUB_SHA")?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "local"
        buildConfigField("String", "REVISION", "\"$revision\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
