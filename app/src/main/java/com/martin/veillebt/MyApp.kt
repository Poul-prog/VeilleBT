package com.martin.veillebt // Ou votre package racine

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyApp : Application() {
    // Vous pouvez ajouter du code d'initialisation ici si nécessaire,
    // mais souvent elle reste vide pour Hilt.
    override fun onCreate() {
        super.onCreate()
        // Initialisations globales ici
    }
}