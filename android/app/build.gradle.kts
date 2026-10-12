plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.neurolens.eyecare"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.neurolens.eyecare"
        minSdk = 29
        targetSdk = 36
        versionCode = 29
        versionName = "0.15.0-alpha"
        // Phone release artifact: keep the two Android ARM ABIs; x86 emulator libraries add ~38 MiB.
        // -Peyecare.abis=x86_64 builds an emulator-only APK for local UI checks.
        ndk { abiFilters += (project.findProperty("eyecare.abis") as String?)?.split(",")?.map { it.trim() }?.toSet()
            ?: setOf("arm64-v8a", "armeabi-v7a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // Alpha sideload APK uses the existing debug certificate for in-place updates,
    // while excluding debug-only Compose tooling from the package.
    buildTypes { release {
        isMinifyEnabled = false
        signingConfig = signingConfigs.getByName("debug")
    } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.google.mediapipe:tasks-vision:1.1.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
dependencyLocking { lockAllConfigurations() }
