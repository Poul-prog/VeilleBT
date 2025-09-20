package com.martin.veillebt.ui.dashboard // Ou votre package approprié

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.*
import com.martin.veillebt.data.local.model.BraceletEntity
import com.martin.veillebt.data.repository.BraceletRepository // Assurez-vous que cette interface est correcte
import com.martin.veillebt.sound.AlarmSoundPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

// Si AlarmEvent et AlarmType sont utilisés pour une logique d'historique ou autre, gardez-les.
// Pour l'instant, ils ne sont pas directement utilisés dans le flux principal du ViewModel.
// data class AlarmEvent(...)
// enum class AlarmType { ... }

private const val TAG_MONITORING_VM = "MonitoringVM_Debug"

@HiltViewModel
class MonitoringViewModel @Inject constructor(
    private val braceletRepository: BraceletRepository, // Cette interface a getAllBracelets()
    private val sharedPreferences: SharedPreferences,
    private val alarmSoundPlayer: AlarmSoundPlayer,
    private val application: Application // Peut être utilisé pour le contexte si nécessaire
) : ViewModel() {

    private val _monitoredBeacons = MutableLiveData<List<BraceletEntity>>()
    val monitoredBeacons: LiveData<List<BraceletEntity>> = _monitoredBeacons

    // Ce LiveData peut être utile pour l'UI si vous voulez afficher spécifiquement les alarmes
    private val _activeAlarms = MutableLiveData<List<BraceletEntity>>()
    val activeAlarms: LiveData<List<BraceletEntity>> = _activeAlarms

    // LiveData pour les préférences, mis à jour après écriture et via listener
    private val _alarmDistanceThreshold = MutableLiveData<Int>()
    val alarmDistanceThreshold: LiveData<Int> = _alarmDistanceThreshold

    private val _alarmVolume = MutableLiveData<Int>()
    val alarmVolume: LiveData<Int> = _alarmVolume

    private val _isAlarmSilenced = MutableLiveData<Boolean>()
    val isAlarmSilenced: LiveData<Boolean> = _isAlarmSilenced

    init {
        Log.d(TAG_MONITORING_VM, "ViewModel initialisé.")
        loadInitialPreferences()
        loadAndObserveEnrolledBeacons() // Renommé pour plus de clarté
        observeSharedPreferenceChanges()
    }

    private fun loadInitialPreferences() {
        _alarmDistanceThreshold.value = sharedPreferences.getInt("alarm_distance_threshold", 30)
        _alarmVolume.value = sharedPreferences.getInt("alarm_volume", 80)
        _isAlarmSilenced.value = sharedPreferences.getBoolean("is_alarm_silenced", false)
        Log.d(TAG_MONITORING_VM, "Préférences initiales chargées: Seuil=${_alarmDistanceThreshold.value}, Vol=${_alarmVolume.value}, Silence=${_isAlarmSilenced.value}")
    }

    private fun loadAndObserveEnrolledBeacons() {
        viewModelScope.launch {
            // CORRECTION PRINCIPALE ICI: Utilisation de getAllBracelets()
            braceletRepository.getAllBracelets().collect { beacons ->
                Log.d(TAG_MONITORING_VM, "Balises observées (depuis Repository via getAllBracelets): ${beacons.size}")
                _monitoredBeacons.postValue(beacons)

                // Cet appel à filter est correct si BraceletEntity a isSignalLost et isOutOfRange
                val currentAlarms = beacons.filter { it.isSignalLost || it.isOutOfRange }
                _activeAlarms.postValue(currentAlarms)
                Log.d(TAG_MONITORING_VM, "Alarmes actives (déduites des balises): ${currentAlarms.size}")

                // Gestion du son de l'alarme
                // Le service est le principal responsable du DÉCLENCHEMENT initial du son.
                // Le ViewModel peut ARRÊTER le son si :
                // 1. Le mode silencieux est activé.
                // 2. Il n'y a plus d'alarmes actives.
                // Le ViewModel ne devrait PAS redémarrer le son lui-même ici pour éviter les conflits avec le service,
                // sauf si c'est une action explicite de l'utilisateur (ex: désactiver le mode silencieux PENDANT une alarme active).
                if (_isAlarmSilenced.value == true || currentAlarms.isEmpty()) {
                    if (alarmSoundPlayer.isAlarmPlaying()) { // Ajoutez une méthode isAlarmPlaying() à AlarmSoundPlayer
                        Log.d(TAG_MONITORING_VM, "Arrêt du son de l'alarme demandé (silence activé ou pas d'alarme).")
                        alarmSoundPlayer.stopAlarm()
                    }
                } else { // Alarmes actives ET mode silencieux désactivé
                    // Si l'utilisateur vient de désactiver le mode silencieux et qu'une alarme est active,
                    // le service devrait déjà gérer le son. Si ce n'est pas le cas ou pour plus de réactivité UI:
                    // if (!alarmSoundPlayer.isAlarmPlaying()) {
                    //     Log.d(TAG_MONITORING_VM, "Démarrage du son de l'alarme demandé (silence désactivé, alarme active, son non joué).");
                    //     alarmSoundPlayer.playAlarm();
                    // }
                    // Pour l'instant, on laisse le service gérer le démarrage.
                }
            }
        }
    }

    // Le listener est correct.
    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        Log.d(TAG_MONITORING_VM, "Préférence changée via listener: $key")
        when (key) {
            "alarm_distance_threshold" -> _alarmDistanceThreshold.postValue(prefs.getInt(key, 30))
            "alarm_volume" -> _alarmVolume.postValue(prefs.getInt(key, 80))
            "is_alarm_silenced" -> {
                val silenced = prefs.getBoolean(key, false)
                _isAlarmSilenced.postValue(silenced)
                if (silenced) {
                    if (alarmSoundPlayer.isAlarmPlaying()) {
                        Log.d(TAG_MONITORING_VM, "Listener: Mode silencieux activé, arrêt du son.")
                        alarmSoundPlayer.stopAlarm()
                    }
                } else {
                    // Si le silence est désactivé et qu'il y a des alarmes actives.
                    if (_activeAlarms.value?.isNotEmpty() == true && !alarmSoundPlayer.isAlarmPlaying()) {
                        Log.d(TAG_MONITORING_VM, "Listener: Mode silencieux désactivé, alarmes actives, son non joué. Démarrage du son.")
                        // alarmSoundPlayer.playAlarm() // Le service devrait le gérer, mais pour la réactivité si l'app est au premier plan
                    }
                }
            }
        }
    }

    private fun observeSharedPreferenceChanges() {
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    fun setAlarmDistanceThreshold(distance: Int) {
        viewModelScope.launch {
            sharedPreferences.edit().putInt("alarm_distance_threshold", distance).apply()
            // Le LiveData _alarmDistanceThreshold sera mis à jour par le listener.
            Log.d(TAG_MONITORING_VM, "Seuil de distance d'alarme mis à jour dans SharedPreferences: $distance m")
        }
    }

    fun setAlarmVolume(volume: Int) {
        viewModelScope.launch {
            sharedPreferences.edit().putInt("alarm_volume", volume).apply()
            // _alarmVolume sera mis à jour par le listener.
            Log.d(TAG_MONITORING_VM, "Volume d'alarme mis à jour dans SharedPreferences: $volume%")
        }
    }

    fun toggleSilenceAlarm() {
        viewModelScope.launch {
            val currentSilenceState = _isAlarmSilenced.value ?: false
            val newSilenceState = !currentSilenceState
            sharedPreferences.edit().putBoolean("is_alarm_silenced", newSilenceState).apply()
            // Le LiveData _isAlarmSilenced et la logique sonore seront gérés par le listener.
            Log.d(TAG_MONITORING_VM, "Demande de bascule du mode silencieux. Nouvel état demandé: $newSilenceState")
        }
    }

    fun testAlarmSound() {
        Log.d(TAG_MONITORING_VM, "Test du son de l'alarme demandé.")
        // AlarmSoundPlayer devrait pouvoir récupérer le volume depuis SharedPreferences
        // ou vous pouvez le passer en argument si vous le stockez dans le ViewModel.
        alarmSoundPlayer.playAlarm() // Ou une méthode playTestSound(volume) dédiée dans AlarmSoundPlayer
    }

    override fun onCleared() {
        super.onCleared()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        Log.d(TAG_MONITORING_VM, "ViewModel nettoyé (onCleared).")
        // Précaution : arrêter le son si le ViewModel est détruit.
        alarmSoundPlayer.stopAlarm()
    }
}

