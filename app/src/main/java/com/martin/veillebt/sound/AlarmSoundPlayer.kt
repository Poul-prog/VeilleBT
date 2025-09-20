package com.martin.veillebt.sound // Ou le package où se trouve votre AlarmSoundPlayer

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri // Si vous utilisez un son brut
import android.util.Log
import com.martin.veillebt.R // Assurez-vous d'importer R si vous utilisez R.raw.votre_son
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG_ALARM_PLAYER = "AlarmSoundPlayer"

@Singleton // Il est généralement judicieux d'avoir un seul lecteur de son d'alarme
class AlarmSoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sharedPreferences: SharedPreferences // Injectez SharedPreferences pour lire le volume
) {

    private var mediaPlayer: MediaPlayer? = null

    fun playAlarm() {
        // Lire le volume depuis les SharedPreferences à chaque fois qu'on joue l'alarme
        // pour s'assurer qu'il est à jour.
        val volumePercent = sharedPreferences.getInt("alarm_volume", 80) // Clé et valeur par défaut
        val volumeLevel = volumePercent / 100f // Convertir le pourcentage en float (0.0f à 1.0f)

        if (mediaPlayer?.isPlaying == true) {
            Log.d(TAG_ALARM_PLAYER, "L'alarme est déjà en cours de lecture.")
            // Optionnel: ajuster le volume si l'alarme joue déjà mais que le volume a changé
            try {
                mediaPlayer?.setVolume(volumeLevel, volumeLevel)
            } catch (e: IllegalStateException) {
                Log.e(TAG_ALARM_PLAYER, "Erreur en ajustant le volume sur un mediaPlayer en cours: ${e.message}")
            }
            return
        }

        // Libérer toute instance précédente de MediaPlayer
        stopAlarmInternal(releasePlayer = true)

        try {
            // Remplacez R.raw.default_alarm_sound par votre fichier sonore réel dans res/raw
            // Si vous n'avez pas de son, vous pouvez en ajouter un ou utiliser un son système (plus complexe)
            val alarmSoundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.alarm_sound}")

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM) // Important pour le comportement de l'alarme
                        .build()
                )
                setDataSource(context, alarmSoundUri)
                isLooping = true // L'alarme se répète jusqu'à ce qu'elle soit arrêtée
                setVolume(volumeLevel, volumeLevel) // Appliquer le volume
                prepareAsync() // Préparer de manière asynchrone pour ne pas bloquer le thread UI
            }

            mediaPlayer?.setOnPreparedListener { mp ->
                Log.d(TAG_ALARM_PLAYER, "MediaPlayer préparé, démarrage de l'alarme.")
                try {
                    mp.start()
                } catch (e: IllegalStateException) {
                    Log.e(TAG_ALARM_PLAYER, "Erreur au démarrage du mediaPlayer après préparation: ${e.message}")
                    stopAlarmInternal(releasePlayer = true) // Nettoyer en cas d'erreur
                }
            }

            mediaPlayer?.setOnErrorListener { _, what, extra ->
                Log.e(TAG_ALARM_PLAYER, "Erreur MediaPlayer: what $what, extra $extra")
                stopAlarmInternal(releasePlayer = true) // Nettoyer en cas d'erreur
                true // Indique que l'erreur a été gérée
            }

        } catch (e: Exception) {
            Log.e(TAG_ALARM_PLAYER, "Erreur lors de la configuration de MediaPlayer: ${e.message}", e)
            mediaPlayer = null // S'assurer que mediaPlayer est null si la configuration échoue
        }
    }

    // Méthode pour arrêter l'alarme, peut être appelée de l'extérieur
    fun stopAlarm() {
        stopAlarmInternal(releasePlayer = true)
    }

    // Méthode interne pour arrêter et optionnellement libérer le lecteur
    private fun stopAlarmInternal(releasePlayer: Boolean) {
        if (mediaPlayer?.isPlaying == true) {
            Log.d(TAG_ALARM_PLAYER, "Arrêt de l'alarme.")
            try {
                mediaPlayer?.stop()
            } catch (e: IllegalStateException) {
                Log.e(TAG_ALARM_PLAYER, "Erreur à l'arrêt du mediaPlayer: ${e.message}")
                // Le lecteur pourrait déjà être dans un état où stop() n'est pas valide.
            }
        }
        if (releasePlayer) {
            mediaPlayer?.release()
            mediaPlayer = null
            Log.d(TAG_ALARM_PLAYER, "MediaPlayer libéré.")
        }
    }

    /**
     * Vérifie si l'alarme est actuellement en cours de lecture.
     * @return true si l'alarme joue, false sinon.
     */
    fun isAlarmPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (e: IllegalStateException) {
            // Cela peut arriver si le mediaPlayer n'est pas dans un état initialisé valide
            Log.w(TAG_ALARM_PLAYER, "IllegalStateException en vérifiant isPlaying: ${e.message}")
            false
        }
    }

    // Optionnel: Méthode pour jouer un son de test à un volume spécifique
    // fun playTestSound(volumePercent: Int) {
    //     Log.d(TAG_ALARM_PLAYER, "Lecture du son de test avec volume $volumePercent%")
    //     // Similaire à playAlarm, mais pourrait utiliser un son différent ou ne pas boucler.
    //     // Assurez-vous d'arrêter tout son d'alarme en cours avant de jouer un test.
    //     stopAlarmInternal(releasePlayer = true)
    //
    //     val volumeLevel = volumePercent / 100f
    //     try {
    //         val testSoundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.default_alarm_sound}") // Ou un son de test différent
    //         mediaPlayer = MediaPlayer().apply {
    //             setAudioAttributes(
    //                 AudioAttributes.Builder()
    //                     .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    //                     .setUsage(AudioAttributes.USAGE_MEDIA) // Peut-être USAGE_MEDIA pour un test
    //                     .build()
    //             )
    //             setDataSource(context, testSoundUri)
    //             isLooping = false // Le test ne boucle généralement pas
    //             setVolume(volumeLevel, volumeLevel)
    //             prepareAsync()
    //         }
    //         mediaPlayer?.setOnPreparedListener { mp -> mp.start() }
    //         mediaPlayer?.setOnCompletionListener { mp -> // Se nettoie après la lecture pour un test
    //             Log.d(TAG_ALARM_PLAYER, "Son de test terminé.")
    //             stopAlarmInternal(releasePlayer = true)
    //         }
    //         mediaPlayer?.setOnErrorListener { _, _, _ ->
    //             stopAlarmInternal(releasePlayer = true)
    //             true
    //         }
    //     } catch (e: Exception) {
    //         Log.e(TAG_ALARM_PLAYER, "Erreur lors de la lecture du son de test: ${e.message}", e)
    //         mediaPlayer = null
    //     }
    // }
}
