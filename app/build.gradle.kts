plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.kapt)

}

android {
    namespace = "com.martin.veillebt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.martin.veillebt"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    val cameraxVersion = "1.3.0"
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.play.services.mlkit.barcode.scanning.v1830)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.hilt.android) // Hilt Android runtime
    kapt(libs.hilt.compiler)          // Compilateur Hilt (pour le traitement des annotations)

    // Pour le scan de QR Codes (ML Kit)
    implementation(libs.play.services.mlkit.barcode.scanning) // Vérifiez la dernière version

    // Pour Google Maps
    implementation(libs.play.services.maps) // Vérifiez la dernière version
    implementation(libs.play.services.location) // Vérifiez la dernière version pour la localisation

    // Bluetooth (pas de dépendance spécifique pour les API BLE natives, elles font partie du SDK Android)

    // Si vous utilisez Room pour la base de données locale
    val roomVersion = "2.6.1" // Vérifiez la dernière version
    implementation(libs.androidx.room.runtime)

    // Optionnel : support Kotlin Coroutines pour Room
    implementation(libs.androidx.room.ktx)



}