package com.martin.veillebt.ui.dashboard // Ou votre package approprié

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.*
//import androidx.preference.isNotEmpty
import com.martin.veillebt.R // Pour accéder à R.raw.alarm_sound si vous l'ajoutez
import com.martin.veillebt.data.repository.BraceletRepository // Supposons que vous ayez ce repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow

// Classe pour représenter une balise surveillée avec son état
data class MonitoredBeacon(
    val address: String,
    val assignedName: String,
    var rssi: Int = -100, // Valeur par défaut pour "pas de signal"
    var distance: Double? = null,
    var lastSeenTimestamp: Long = 0L,
    var isOutOfRange: Boolean = false,
    var isSignalLost: Boolean = false
) {
    val isVisible: Boolean
        get() = System.currentTimeMillis() - lastSeenTimestamp < SIGNAL_LOSS_THRESHOLD_MS
}

// Classe pour représenter une alarme
data class AlarmEvent(
    val id: String = java.util.UUID.randomUUID().toString(), // Pour identifier l'alarme
    val beaconName: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: AlarmType
)

enum class AlarmType {
    DISTANCE_EXCEEDED,
    SIGNAL_LOST
}

const val SIGNAL_LOSS_THRESHOLD_MS = 20000L // 20 secondes avant de considérer le signal comme perdu
const val MIN_RSSI_FOR_DISTANCE_CALC = -85

class MonitoringViewModel(application: Application, private val braceletRepository: BraceletRepository) : AndroidViewModel(application) {

    private val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

    private val _monitoredBeacons = MutableLiveData<List<MonitoredBeacon>>(emptyList())
    val monitoredBeacons: LiveData<List<MonitoredBeacon>> = _monitoredBeacons

    private val _activeAlarms = MutableLiveData<List<AlarmEvent>>(emptyList())
    val activeAlarms: LiveData<List<AlarmEvent>> = _activeAlarms

    private val _alarmDistanceThreshold = MutableLiveData(30) // en mètres, valeur par défaut
    val alarmDistanceThreshold: LiveData<Int> = _alarmDistanceThreshold

    private val _alarmVolume = MutableLiveData(80) // en pourcentage (0-100), valeur par défaut
    val alarmVolume: LiveData<Int> = _alarmVolume

    private val _isAlarmSilenced = MutableLiveData(false)
    val isAlarmSilenced: LiveData<Boolean> = _isAlarmSilenced

    private var scanJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null

    // Pour le calcul de la distance (à calibrer !)
    private val TX_POWER = -59 // Puissance de référence à 1 mètre (dBm) - À CALIBRER POUR VOS BALISES
    private val N_PARAMETER = 2.0 // Facteur d'atténuation de l'environnement (2.0 à 4.0)

    init {
        loadEnrolledBeacons()
        // Charger les préférences utilisateur pour les seuils/volume ici si sauvegardées
    }

    private fun loadEnrolledBeacons() {
        viewModelScope.launch {
            // Supposons que braceletRepository.getAllBracelets() retourne List<BleDevice> ou similaire
            // Vous devrez adapter ceci à votre modèle de données persisté (ex: BraceletEntity)
            val enrolled = braceletRepository.getAllBraceletsSuspend().map { entity -> // Adaptez "entity" à votre modèle
                MonitoredBeacon(entity.address, entity.name ?: "Sans nom")
            }
            _monitoredBeacons.postValue(enrolled)
            if (enrolled.isNotEmpty()) {
                startContinuousScan()
            }
        }
    }

    // Dans MonitoringViewModel

    // Cette annotation peut aussi aider Lint, mais une vérification explicite est meilleure.
// @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN) // Nécessite API S pour celle-ci
    private fun performBleScanGuarded(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(getApplication(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
                Log.d("MonitoringVM", "Scan continu démarré (SDK S+).")
                return true
            } else {
                Log.e("MonitoringVM", "BLUETOOTH_SCAN permission manquante dans performBleScanGuarded.")
                return false
            }
        } else {
            if (ActivityCompat.checkSelfPermission(getApplication(), Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(getApplication(), Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
                bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
                Log.d("MonitoringVM", "Scan continu démarré (pre-SDK S).")
                return true
            } else {
                Log.e("MonitoringVM", "Permissions Bluetooth manquantes dans performBleScanGuarded (pre-SDK S).")
                return false
            }
        }
    }

    fun startContinuousScan() {
        // ... (vos vérifications initiales existantes pour l'adaptateur, l'activation, et les permissions) ...
        // La vérification de permission initiale devrait déjà retourner si la permission n'est pas accordée.

        if (scanJob?.isActive == true) {
            scanJob?.cancel()
        }

        scanJob = viewModelScope.launch {
            try {
                val scanSuccessfullyStarted = performBleScanGuarded()

                if (scanSuccessfullyStarted) {
                    while (true) {
                        delay(5000)
                        checkSignalLossAndDistance()
                    }
                } else {
                    Log.w("MonitoringVM", "Le scan n'a pas été démarré (performBleScanGuarded a échoué).")
                }
            } catch (e: CancellationException) {
                Log.d("MonitoringVM", "Scan job annulé via CancellationException.")
            } finally {
                // ... (votre bloc finally existant) ...
            }
        }
    }


    fun stopContinuousScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(getApplication(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.w("MonitoringVM", "Permission BLUETOOTH_SCAN manquante pour arrêter le scan.")
            // On annule quand même le job
        } else {
            bluetoothLeScanner?.stopScan(scanCallback)
        }
        scanJob?.cancel() // Ceci est crucial
        scanJob = null // Libérer la référence
        Log.d("MonitoringVM", "Scan continu explicitement arrêté.")
    }


    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.address?.let { address ->
                val currentBeacons = _monitoredBeacons.value?.toMutableList() ?: return
                val beaconIndex = currentBeacons.indexOfFirst { it.address == address }

                if (beaconIndex != -1) {
                    val beacon = currentBeacons[beaconIndex]
                    beacon.rssi = result.rssi
                    beacon.distance = if (result.rssi > MIN_RSSI_FOR_DISTANCE_CALC) calculateDistance(result.rssi) else null
                    beacon.lastSeenTimestamp = System.currentTimeMillis()
                    beacon.isSignalLost = false // Reset signal lost flag on new signal

                    // Mettre à jour la liste (important pour que LiveData notifie les observateurs)
                    _monitoredBeacons.postValue(ArrayList(currentBeacons)) // Créer une nouvelle liste
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("MonitoringVM", "Scan BLE échoué: $errorCode")
        }
    }

    private fun calculateDistance(rssi: Int, txPower: Int = TX_POWER): Double {
        // Formule de base pour la distance RSSI
        // distance = 10 ^ ((txPower - rssi) / (10 * N))
        // N est le path-loss exponent (typiquement 2 pour l'espace libre, jusqu'à 4 pour les environnements obstrués)
        if (rssi == 0) {
            return -1.0 // Impossible de calculer
        }
        val ratio = rssi * 1.0 / txPower
        return if (ratio < 1.0) {
            ratio.pow(10.0)
        } else {
            (0.89976) * ratio.pow(7.7095) + 0.111 // Formule alternative (peut nécessiter ajustement)
        }
        // OU la formule plus simple :
        // return 10.0.pow((txPower - rssi) / (10.0 * N_PARAMETER))
    }


    private fun checkSignalLossAndDistance() {
        val currentBeacons = _monitoredBeacons.value ?: return
        val currentTime = System.currentTimeMillis()
        val thresholdDistance = _alarmDistanceThreshold.value ?: 30
        var shouldTriggerSound = false

        currentBeacons.forEach { beacon ->
            val wasSignalLost = beacon.isSignalLost
            val wasOutOfRange = beacon.isOutOfRange

            beacon.isSignalLost = (currentTime - beacon.lastSeenTimestamp > SIGNAL_LOSS_THRESHOLD_MS) && beacon.lastSeenTimestamp != 0L

            beacon.isOutOfRange = if (beacon.isSignalLost || beacon.distance == null) {
                false // Ne pas considérer hors de portée si signal perdu ou distance inconnue
            } else {
                beacon.distance!! > thresholdDistance
            }

            if (beacon.isSignalLost && !wasSignalLost) {
                addAlarm(AlarmEvent(beaconName = beacon.assignedName, message = "${beacon.assignedName} - Signal perdu !", type = AlarmType.SIGNAL_LOST))
                shouldTriggerSound = true
            } else if (!beacon.isSignalLost && wasSignalLost) {
                removeAlarm(beacon.assignedName, AlarmType.SIGNAL_LOST)
            }

            if (beacon.isOutOfRange && !wasOutOfRange) {
                addAlarm(AlarmEvent(beaconName = beacon.assignedName, message = "${beacon.assignedName} à ${beacon.distance?.toInt()}m - Hors de portée !", type = AlarmType.DISTANCE_EXCEEDED))
                shouldTriggerSound = true
            } else if (!beacon.isOutOfRange && wasOutOfRange) {
                removeAlarm(beacon.assignedName, AlarmType.DISTANCE_EXCEEDED)
            }
        }
        _monitoredBeacons.postValue(ArrayList(currentBeacons)) // Notifier les changements d'état

        if (shouldTriggerSound && _isAlarmSilenced.value == false) {
            playAlarmSound()
        }
    }

    private fun addAlarm(alarm: AlarmEvent) {
        val currentAlarms = _activeAlarms.value?.toMutableList() ?: mutableListOf()
        // Éviter les doublons pour le même type d'alarme sur la même balise
        currentAlarms.removeAll { it.beaconName == alarm.beaconName && it.type == alarm.type }
        currentAlarms.add(0, alarm) // Ajouter en haut de la liste
        _activeAlarms.postValue(currentAlarms)
    }

    private fun removeAlarm(beaconName: String, type: AlarmType) {
        val currentAlarms = _activeAlarms.value?.toMutableList() ?: mutableListOf()
        currentAlarms.removeAll { it.beaconName == beaconName && it.type == type }
        _activeAlarms.postValue(currentAlarms)

        if (currentAlarms.isEmpty()) {
            stopAlarmSound()
        }
    }

    fun setAlarmDistanceThreshold(distance: Int) {
        _alarmDistanceThreshold.value = distance
        // Sauvegarder cette préférence
        checkSignalLossAndDistance() // Réévaluer les alarmes avec le nouveau seuil
    }

    fun setAlarmVolume(volumePercent: Int) {
        _alarmVolume.value = volumePercent
        // Sauvegarder cette préférence
    }

    fun toggleSilenceAlarm() {
        val silenced = _isAlarmSilenced.value ?: false
        _isAlarmSilenced.value = !silenced
        if (!silenced) { // Si on vient de mettre en silencieux
            stopAlarmSound()
        } else if (_activeAlarms.value?.isNotEmpty() == true) { // Si on réactive le son et qu'il y a des alarmes
            playAlarmSound()
        }
    }

    fun testAlarmSound() {
        playAlarmSound(true)
        handler.postDelayed({ stopAlarmSound() }, 3000) // Arrêter après 3s
    }

    private fun playAlarmSound(isTest: Boolean = false) {
        if (!isTest && _isAlarmSilenced.value == true) return
        if (mediaPlayer?.isPlaying == true) return

        mediaPlayer?.release() // Libérer la ressource précédente
        mediaPlayer = MediaPlayer.create(getApplication(), R.raw.alarm_sound) // Assurez-vous d'avoir ce fichier dans res/raw
        val volumeLevel = (_alarmVolume.value ?: 80) / 100f
        mediaPlayer?.setVolume(volumeLevel, volumeLevel)
        mediaPlayer?.isLooping = !isTest // En boucle pour les vraies alarmes
        mediaPlayer?.start()
    }

    private fun stopAlarmSound() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onCleared() {
        super.onCleared()
        stopContinuousScan()
        stopAlarmSound()
        handler.removeCallbacksAndMessages(null)
    }

    // Factory pour injecter le repository (si vous utilisez Hilt, ce sera différent)
    class Factory(private val application: Application, private val repository: BraceletRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MonitoringViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return MonitoringViewModel(application, repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
