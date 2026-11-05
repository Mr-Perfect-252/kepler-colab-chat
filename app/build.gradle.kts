plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Injected by the build workflow from repo secrets; blank in source so the
// checked-in tree never carries credentials.
fun secret(name: String, fallback: String = ""): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: fallback

android {
    namespace = "com.kepler.colabchat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kepler.colabchat"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Where the app reads {base_url, api_key, model} when the ping fails.
        buildConfigField(
            "String", "RENDEZVOUS_URL",
            "\"${secret("RENDEZVOUS_URL", "https://raw.githubusercontent.com/PLACEHOLDER/endpoint.json")}\"",
        )
        // Credentials baked at build time so a fresh install connects immediately.
        buildConfigField("String", "BOOTSTRAP_URL", "\"${secret("BOOTSTRAP_URL")}\"")
        buildConfigField("String", "BOOTSTRAP_KEY", "\"${secret("BOOTSTRAP_KEY")}\"")
        buildConfigField("String", "BOOTSTRAP_MODEL", "\"${secret("BOOTSTRAP_MODEL", "mistral-small-3.1-24b")}\"")

        // Revive: fire repository_dispatch so Actions provisions a fresh T4.
        buildConfigField("String", "GH_PAT", "\"${secret("GH_PAT")}\"")
        buildConfigField(
            "String", "GH_REPO",
            "\"${secret("GH_REPO", "Mr-Perfect-252/kepler-colab-chat")}\"",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
