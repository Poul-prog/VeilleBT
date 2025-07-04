
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

    // val cameraxVersion = "1.3.0" // Vous pouvez supprimer cette ligne si cameraxVersion est aussi dans libs.versions.toml (ce qui est le cas)
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
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.play.services.mlkit.barcode.scanning.v1830) // Considérez d'utiliser l'alias sans .v1830 si libs.play.services.mlkit.barcode.scanning est la version souhaitée
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // Dépendances Room - Utilisation des alias de libs.versions.toml
    implementation(libs.androidx.room.runtime) // Correction : Utiliser l'alias du catalogue
    implementation(libs.androidx.room.ktx)     // Déjà correct

    // Pour le processeur d'annotations de Room (kapt)
    // Vous devez avoir un alias pour room-compiler dans votre libs.versions.toml
    // Si ce n'est pas déjà le cas, je vais vous montrer comment l'ajouter ci-dessous.
    // En supposant que l'alias s'appelle libs.androidx.room.compiler :
    kapt(libs.androidx.room.compiler) // <--- C'est ici que le problème se situait (utilisation de $roomVersion au lieu de l'alias)


    // Pour le scan de QR Codes (ML Kit)
    implementation(libs.play.services.mlkit.barcode.scanning)

    // Pour Google Maps
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)

    // Bluetooth (pas de dépendance spécifique pour les API BLE natives, elles font partie du SDK Android)

    // Supprimez ces lignes car roomVersion est maintenant géré par libs.versions.toml:
    // val roomVersion = "2.6.1" // Vérifiez la dernière version  <--- SUPPRIMER
    // implementation("androidx.room:room-runtime:$roomVersion") <--- DÉJÀ CORRIGÉ CI-DESSUS
    // implementation("androidx.room:room-ktx:$roomVersion")     <--- DÉJÀ CORRIGÉ CI-DESSUS
}
