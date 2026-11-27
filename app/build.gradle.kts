import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Permanent signing key: created once by setup-signing.sh and kept outside the project (so updates install over each other).
val keyDir: String = System.getenv("SVAGA_KEYS")
    ?: listOf("/root/svaga/svaga1/svaga-keys", "/root/svaga-keys").firstOrNull { File("$it/keystore.properties").exists() }
    ?: "/root/svaga/svaga1/svaga-keys"
val keyProps = Properties().apply {
    val f = File("$keyDir/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// The folder may have been moved: if the stored path is gone, look for the same file next to keystore.properties.
val keyFile: File? = keyProps.getProperty("storeFile")?.let { p ->
    val f = File(p)
    if (f.exists()) f else File(keyDir, f.name).takeIf { it.exists() }
}
val hasKey = keyFile != null

android {
    namespace = "com.svaga.emulator"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.svaga.emulator"
        minSdk = 26
        targetSdk = 36
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 9
        versionName = "1.0"
    }

    if (hasKey) {
        signingConfigs {
            create("svaga") {
                storeFile = keyFile
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasKey) signingConfig = signingConfigs.getByName("svaga")
        }
        release {
            isMinifyEnabled = false
            // without the permanent key (secrets not set yet) the APK is still installable, signed with the debug key
            signingConfig = signingConfigs.getByName(if (hasKey) "svaga" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs { useLegacyPackaging = true }   // cores must exist as real files in nativeLibraryDir
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("com.github.swordfish90:libretrodroid:0.13.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
