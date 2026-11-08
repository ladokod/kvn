import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val coreAar = rootProject.file("native/husi/libcore/libcore.aar")
val verifyCore by tasks.registering {
    inputs.file(coreAar)
    doLast { check(coreAar.isFile) { "Build the pinned core first: scripts/build-core.sh" } }
}
tasks.named("preBuild") { dependsOn(verifyCore) }

android {
    namespace = "ru.ladokod.kvn"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.ladokod.kvn"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.2.0"
        ndk { abiFilters += providers.gradleProperty("kvnTestAbi").getOrElse("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Installable GitHub build; no private production signing key is committed.
            // This temporary CI certificate is documented in the release notes.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation(files(coreAar))
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Preserve any upstream NOTICE/LICENSE resources from the actual Android runtime
// artifacts, including classes.jar nested inside AARs. Never scan unrelated cache entries.
tasks.register("collectRuntimeNotices") {
    doLast {
        val destination = rootProject.file("dependency-sources/android-notices").apply { mkdirs() }
        fun collect(input: InputStream, output: File, nested: Boolean = false): Int {
            var count = 0
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/').uppercase()
                    if (name.startsWith("LICENSE") || name.startsWith("NOTICE") || name.startsWith("COPYING")) {
                        output.mkdirs()
                        File(output, entry.name.replace(Regex("[^A-Za-z0-9._-]"), "_")).writeBytes(zip.readBytes())
                        count++
                    } else if (!nested && (entry.name == "classes.jar" || entry.name.startsWith("libs/")) && entry.name.endsWith(".jar")) {
                        count += collect(ByteArrayInputStream(zip.readBytes()), output, true)
                    }
                }
            }
            return count
        }
        val inventory = configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .sortedBy { it.moduleVersion.id.toString() }.map { artifact ->
                val id = artifact.moduleVersion.id.toString()
                val directory = File(destination, id.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                val notices = if (artifact.file.extension in listOf("jar", "aar")) collect(artifact.file.inputStream(), directory) else 0
                "$id notices=$notices"
            }
        File(destination, "inventory.txt").writeText(inventory.joinToString("\n", postfix = "\n"))
    }
}
