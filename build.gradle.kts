import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.tahakkum"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.tahakkum"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }
    buildFeatures { compose = true }
}

val modelFile = layout.projectDirectory.file("src/main/assets/gesture_recognizer.task")
tasks.register("downloadGestureModel") {
    outputs.file(modelFile)
    doLast {
        val output = modelFile.asFile
        if (!output.exists() || output.length() < 1_000_000L) {
            output.parentFile.mkdirs()
            val url = URL("https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task")
            url.openStream().use { input -> output.outputStream().use { input.copyTo(it) } }
        }
        check(output.exists() && output.length() > 1_000_000L) { "Gesture model download failed" }
    }
}
tasks.named("preBuild").configure { dependsOn("downloadGestureModel") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
