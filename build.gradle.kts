plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}
android {
    namespace = "com.example.modelviewer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.modelviewer"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "glb" }
    buildTypes {
        getByName("release") {
            // Keep JNI bindings intact; enable shrinking only with verified Filament keep rules.
            isMinifyEnabled = false
        }
    }
}
dependencies {
    val filamentVersion = "1.56.0" // All native Filament components must use the same version.
    implementation("com.google.android.filament:filament-android:$filamentVersion")
    implementation("com.google.android.filament:gltfio-android:$filamentVersion")
    implementation("com.google.android.filament:filament-utils-android:$filamentVersion")
}
