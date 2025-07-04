package com.martin.veillebt.ui.dashboard // Ou votre package approprié

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.isEmpty
// import androidx.compose.ui.geometry.isEmpty // Semble inutilisé, commenter si c'est le cas
import androidx.core.app.ActivityCompat
import androidx.lifecycle.*
import com.martin.veillebt.R // Pour accéder à R.raw.alarm_sound
import com.martin.veillebt.data.repository.BraceletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlin.math.round

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
    val id: String = UUID.randomUUID().toString(), // Pour identifier l'alarme
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
private const val TAG_MONITORING_VM = "MonitoringVM_Debug" // Tag pour les logs

@HiltViewModel
class MonitoringViewModel @Inject constructor(
    private val app: Application, // Hilt injecte l'Application
    private val braceletRepository: BraceletRepository // Hilt injecte le Repository
) : AndroidViewModel(app) {

    private val bluetoothManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

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
        Log.d(TAG_MONITORING_VM, "ViewModel initialisé.")
        loadEnrolledBeacons()
        // Charger les préférences utilisateur pour les seuils/volume ici si sauvegardées
        // (Ex: via DataStore ou SharedPreferences)
    }

    private fun loadEnrolledBeacons() {
        Log.d(TAG_MONITORING_VM, "loadEnrolledBeacons: Début du chargement.")
        viewModelScope.launch {
            try {
                val enrolledEntities = braceletRepository.getAllBraceletsSuspend()
                Log.d(TAG_MONITORING_VM, "loadEnrolledBeacons: ${enrolledEntities.size} entités chargées depuis le repository.")
                val beacons = enrolledEntities.map { entity ->
                    MonitoredBeacon(
                        address = entity.address,
                        assignedName = entity.name ?: "Sans nom",
                    )
                }
                Log.d(TAG_MONITORING_VM, "loadEnrolledBeacons: ${beacons.size} MonitoredBeacon créés. Adresses: ${beacons.joinToString { it.address }}")
                _monitoredBeacons.postValue(beacons)
                Log.d(TAG_MONITORING_VM, "loadEnrolledBeacons: _monitoredBeacons postValue appelé avec ${beacons.size} balises.")
                if (beacons.isNotEmpty()) {
                    Log.d(TAG_MONITORING_VM, "loadEnrolledBeacons: Balises non vides, démarrage du scan continu.")
                    startContinuousScan()
                } else {
                    Log.d(TAG_MONITORING_VM, "loadEnrolledBeacons: Aucune balise enregistrée, le scan ne démarrera pas automatiquement depuis ici.")
                }
            } catch (e: Exception) {
                Log.e(TAG_MONITORING_VM, "loadEnrolledBeacons: Erreur lors du chargement des balises enregistrées", e)
            }
        }
    }

    private fun hasBluetoothScanPermission(): Boolean {
        // ... (code inchangé)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        }
    }
    private fun hasBluetoothConnectPermission(): Boolean {
        // ... (code inchangé)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }


    private fun performBleScanGuarded(): Boolean {
        if (!hasBluetoothScanPermission()) {
            Log.e(TAG_MONITORING_VM, "performBleScanGuarded: Permission de scan Bluetooth manquante.")
            return false
        }
        try {
            bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            Log.d(TAG_MONITORING_VM, "performBleScanGuarded: Scan continu démarré via startScan().")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG_MONITORING_VM, "performBleScanGuarded: SecurityException lors du démarrage du scan BLE.", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG_MONITORING_VM, "performBleScanGuarded: Exception inattendue lors du démarrage du scan BLE.", e)
            return false
        }
    }

    fun startContinuousScan() {
        Log.d(TAG_MONITORING_VM, "startContinuousScan: Tentative de démarrage.")
        if (bluetoothAdapter == null) {
            Log.e(TAG_MONITORING_VM, "startContinuousScan: Adaptateur Bluetooth non disponible.")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG_MONITORING_VM, "startContinuousScan: Bluetooth n'est pas activé.")
            return
        }
        if (!hasBluetoothScanPermission()) {
            Log.e(TAG_MONITORING_VM, "startContinuousScan: Permissions de scan Bluetooth manquantes.")
            return
        }
        if (scanJob?.isActive == true) {
            Log.d(TAG_MONITORING_VM, "startContinuousScan: Le scan est déjà actif.")
            return
        }

        Log.d(TAG_MONITORING_VM, "startContinuousScan: Démarrage du scanJob.")
        scanJob = viewModelScope.launch {
            try {
                val scanSuccessfullyStarted = performBleScanGuarded()
                if (scanSuccessfullyStarted) {
                    Log.d(TAG_MONITORING_VM, "startContinuousScan: Scan démarré avec succès, entrée dans la boucle de vérification.")
                    while (isActive) {
                        delay(5000)
                        Log.d(TAG_MONITORING_VM, "startContinuousScan: Boucle de scan - appel de checkSignalLossAndDistance.")
                        checkSignalLossAndDistance()
                    }
                } else {
                    Log.w(TAG_MONITORING_VM, "startContinuousScan: Le scan n'a pas été démarré (performBleScanGuarded a échoué).")
                }
            } catch (e: CancellationException) {
                Log.i(TAG_MONITORING_VM, "startContinuousScan: Scan job annulé via CancellationException.")
                // ... (gestion de l'arrêt du scan physique) ...
                if (hasBluetoothScanPermission()) {
                    try {
                        bluetoothLeScanner?.stopScan(scanCallback)
                        Log.d(TAG_MONITORING_VM, "Scan arrêté dans catch (CancellationException).")
                    } catch (secEx: SecurityException) {
                        Log.e(TAG_MONITORING_VM, "SecurityException en arrêtant le scan dans catch (CancellationException).", secEx)
                    }
                } else {
                    Log.w(TAG_MONITORING_VM, "Impossible d'arrêter le scan dans catch (CancellationException), permission manquante.")
                }
            } catch (e: Exception) {
                Log.e(TAG_MONITORING_VM, "startContinuousScan: Erreur inattendue dans le scan job", e)
                // ... (gestion de l'arrêt du scan physique) ...
                if (hasBluetoothScanPermission()) {
                    try {
                        bluetoothLeScanner?.stopScan(scanCallback)
                        Log.d(TAG_MONITORING_VM, "Scan arrêté dans catch (Exception).")
                    } catch (secEx: SecurityException) {
                        Log.e(TAG_MONITORING_VM, "SecurityException en arrêtant le scan dans catch (Exception).", secEx)
                    }
                }
            } finally {
                Log.i(TAG_MONITORING_VM, "startContinuousScan: Scan job terminé (bloc finally).")
                // ... (gestion de l'arrêt du scan physique) ...
                if (hasBluetoothScanPermission()) {
                    try {
                        bluetoothLeScanner?.stopScan(scanCallback)
                        Log.d(TAG_MONITORING_VM, "Scan arrêté dans finally.")
                    } catch (secEx: SecurityException) {
                        Log.e(TAG_MONITORING_VM, "SecurityException en arrêtant le scan dans finally.", secEx)
                    }
                } else {
                    Log.w(TAG_MONITORING_VM, "Impossible d'arrêter le scan dans finally, permission manquante.")
                }
            }
        }
    }

    fun stopContinuousScan() {
        Log.d(TAG_MONITORING_VM, "stopContinuousScan: Tentative d'arrêt.")
        // ... (code inchangé avec logs existants) ...
        if (!hasBluetoothScanPermission()) {
            Log.w(TAG_MONITORING_VM, "stopContinuousScan: Permission Bluetooth manquante pour arrêter le scan. Annulation du job uniquement.")
        } else {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
                Log.d(TAG_MONITORING_VM, "stopContinuousScan: Scan physique arrêté via bluetoothLeScanner.stopScan().")
            } catch (e: SecurityException) {
                Log.e(TAG_MONITORING_VM, "stopContinuousScan: SecurityException lors de l'appel à stopScan.", e)
            }
        }
        scanJob?.cancel()
        scanJob = null
        Log.d(TAG_MONITORING_VM, "stopContinuousScan: Scan job annulé et potentiellement le scan physique arrêté.")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.address?.let { address ->
                // NOUVEAU: Vérifier la permission BLUETOOTH_CONNECT avant d'accéder au nom
                var deviceName: String? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(
                            app, // Utilisez le contexte de l'application
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        try {
                            deviceName = result.device?.name
                        } catch (e: SecurityException) {
                            Log.w(TAG_MONITORING_VM, "onScanResult: SecurityException en accédant au nom de l'appareil (API >= S). Permission manquante ou révoquée en cours d'exécution.", e)
                            // deviceName restera null
                        }
                    } else {
                        Log.w(TAG_MONITORING_VM, "onScanResult: Permission BLUETOOTH_CONNECT manquante pour accéder au nom de l'appareil (API >= S).")
                        // deviceName restera null
                    }
                } else {
                    // Pour les versions antérieures à S, pas besoin de BLUETOOTH_CONNECT pour le nom,
                    // mais il est toujours bon de se prémunir contre une SecurityException inattendue, bien que moins probable ici.
                    try {
                        deviceName = result.device?.name
                    } catch (e: SecurityException) {
                        Log.w(TAG_MONITORING_VM, "onScanResult: SecurityException en accédant au nom de l'appareil (API < S).", e)
                        // deviceName restera null
                    }
                }

                Log.d(TAG_MONITORING_VM, "onScanResult: Reçu pour adresse: $address, RSSI: ${result.rssi}, Nom: ${deviceName ?: "N/A"}")

                val currentBeacons = _monitoredBeacons.value?.toMutableList() ?: run {
                    Log.w(TAG_MONITORING_VM, "onScanResult: _monitoredBeacons.value est null, retour.")
                    return
                }
                if (currentBeacons.isEmpty()){
                    Log.w(TAG_MONITORING_VM, "onScanResult: currentBeacons est vide. Aucune balise à mettre à jour.")
                    return
                }

                val beaconIndex = currentBeacons.indexOfFirst { it.address == address }
                Log.d(TAG_MONITORING_VM, "onScanResult: Recherche de '$address'. Index trouvé: $beaconIndex. Balises surveillées actuelles: ${currentBeacons.joinToString { it.address + "("+it.assignedName+")" }}")

                if (beaconIndex != -1) {
                    val beacon = currentBeacons[beaconIndex]
                    Log.d(TAG_MONITORING_VM, "onScanResult: Balise trouvée: ${beacon.assignedName} (adresse: ${beacon.address}). Mise à jour des infos.")

                    beacon.rssi = result.rssi
                    beacon.distance = if (result.rssi > MIN_RSSI_FOR_DISTANCE_CALC) calculateDistance(result.rssi) else null
                    beacon.lastSeenTimestamp = System.currentTimeMillis()

                    if (beacon.isSignalLost) {
                        Log.d(TAG_MONITORING_VM, "onScanResult: Signal retrouvé pour ${beacon.assignedName}, réinitialisation de isSignalLost.")
                        beacon.isSignalLost = false
                        removeAlarm(beacon.assignedName, AlarmType.SIGNAL_LOST)
                    }
                    if (beacon.isOutOfRange && beacon.distance != null && beacon.distance!! <= (_alarmDistanceThreshold.value ?: 30)) {
                        Log.d(TAG_MONITORING_VM, "onScanResult: ${beacon.assignedName} de retour dans la portée, réinitialisation de isOutOfRange.")
                        beacon.isOutOfRange = false
                        removeAlarm(beacon.assignedName, AlarmType.DISTANCE_EXCEEDED)
                    }

                    _monitoredBeacons.postValue(ArrayList(currentBeacons))
                    Log.d(TAG_MONITORING_VM, "onScanResult: _monitoredBeacons postValue après mise à jour de ${beacon.assignedName}. RSSI: ${beacon.rssi}, Dist: ${beacon.distance}")
                } else {
                    Log.d(TAG_MONITORING_VM, "onScanResult: L'adresse '$address' (Nom: ${deviceName ?: "N/A"}) n'est PAS dans la liste des balises surveillées.")
                }
            } ?: run {
                Log.w(TAG_MONITORING_VM, "onScanResult: ScanResult, device ou address est null.")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG_MONITORING_VM, "onScanFailed: Scan BLE échoué avec code: $errorCode")
            stopContinuousScan()
        }
    }


    private fun calculateDistance(rssi: Int, txPower: Int = TX_POWER): Double {
        if (rssi == 0) {
            Log.w(TAG_MONITORING_VM, "calculateDistance: RSSI est 0, impossible de calculer.")
            return -1.0
        }
        var distance = 10.0.pow((txPower - rssi) / (10.0 * N_PARAMETER))
        distance = round(distance * 100.0) / 100.0
        // Log.d(TAG_MONITORING_VM, "calculateDistance: RSSI=$rssi, TxPower=$txPower, N_PARAM=$N_PARAMETER -> Distance calculée=$distance") // Peut être très verbeux
        return distance
    }

    private fun checkSignalLossAndDistance() {
        Log.d(TAG_MONITORING_VM, "checkSignalLossAndDistance: Vérification...")
        val currentBeacons = _monitoredBeacons.value?.toMutableList() ?: run {
            Log.w(TAG_MONITORING_VM, "checkSignalLossAndDistance: _monitoredBeacons.value est null.")
            return
        }
        if (currentBeacons.isEmpty()) {
            Log.d(TAG_MONITORING_VM, "checkSignalLossAndDistance: Aucune balise à vérifier.")
            return
        }

        val currentTime = System.currentTimeMillis()
        val thresholdDistance = _alarmDistanceThreshold.value ?: 30
        var shouldTriggerSoundForAnyBeacon = false
        var needsUiUpdateForBeaconStates = false // Renommé pour plus de clarté

        currentBeacons.forEach { beacon ->
            val initialSignalLost = beacon.isSignalLost
            val initialOutOfRange = beacon.isOutOfRange

            // Vérification de la perte de signal
            if (beacon.lastSeenTimestamp > 0L && (currentTime - beacon.lastSeenTimestamp > SIGNAL_LOSS_THRESHOLD_MS)) {
                if (!beacon.isSignalLost) {
                    Log.i(TAG_MONITORING_VM, "checkSignalLossAndDistance: ${beacon.assignedName} - Signal PERDU. LastSeen: ${beacon.lastSeenTimestamp}, Current: $currentTime")
                    beacon.isSignalLost = true
                    addAlarm(AlarmEvent(beaconName = beacon.assignedName, message = "${beacon.assignedName} - Signal perdu !", type = AlarmType.SIGNAL_LOST))
                    shouldTriggerSoundForAnyBeacon = true
                }
            } else if (beacon.isSignalLost && beacon.lastSeenTimestamp > 0L) { // Signal retrouvé
                Log.i(TAG_MONITORING_VM, "checkSignalLossAndDistance: ${beacon.assignedName} - Signal RETROUVÉ.")
                beacon.isSignalLost = false
                removeAlarm(beacon.assignedName, AlarmType.SIGNAL_LOST)
            }

            // Vérification du dépassement de distance
            if (!beacon.isSignalLost && beacon.distance != null) {
                if (beacon.distance!! > thresholdDistance) {
                    if (!beacon.isOutOfRange) {
                        Log.i(TAG_MONITORING_VM, "checkSignalLossAndDistance: ${beacon.assignedName} - HORS DE PORTÉE (Distance: ${beacon.distance}, Seuil: $thresholdDistance).")
                        beacon.isOutOfRange = true
                        addAlarm(AlarmEvent(beaconName = beacon.assignedName, message = "${beacon.assignedName} à ${beacon.distance?.toInt()}m - Hors de portée !", type = AlarmType.DISTANCE_EXCEEDED))
                        shouldTriggerSoundForAnyBeacon = true
                    }
                } else if (beacon.isOutOfRange) { // De retour dans la portée
                    Log.i(TAG_MONITORING_VM, "checkSignalLossAndDistance: ${beacon.assignedName} - DE RETOUR DANS LA PORTÉE (Distance: ${beacon.distance}, Seuil: $thresholdDistance).")
                    beacon.isOutOfRange = false
                    removeAlarm(beacon.assignedName, AlarmType.DISTANCE_EXCEEDED)
                }
            } else if (beacon.isOutOfRange && (beacon.isSignalLost || beacon.distance == null)) {
                Log.i(TAG_MONITORING_VM, "checkSignalLossAndDistance: ${beacon.assignedName} - Était hors portée, mais signal perdu ou distance non calculable. Retrait alarme distance.")
                beacon.isOutOfRange = false
                removeAlarm(beacon.assignedName, AlarmType.DISTANCE_EXCEEDED)
            }

            if (initialSignalLost != beacon.isSignalLost || initialOutOfRange != beacon.isOutOfRange) {
                needsUiUpdateForBeaconStates = true
                Log.d(TAG_MONITORING_VM, "checkSignalLossAndDistance: Changement d'état pour ${beacon.assignedName}: SignalPerdu=${beacon.isSignalLost}, HorsPortée=${beacon.isOutOfRange}")
            }
        }

        if (needsUiUpdateForBeaconStates) {
            Log.d(TAG_MONITORING_VM, "checkSignalLossAndDistance: Au moins un état de balise a changé, _monitoredBeacons.postValue appelé.")
            _monitoredBeacons.postValue(ArrayList(currentBeacons))
        } else {
            Log.d(TAG_MONITORING_VM, "checkSignalLossAndDistance: Aucun changement d'état de balise nécessitant une mise à jour de l'UI via postValue ici.")
        }

        if (shouldTriggerSoundForAnyBeacon && _isAlarmSilenced.value == false) {
            Log.d(TAG_MONITORING_VM, "checkSignalLossAndDistance: Déclenchement du son d'alarme.")
            playAlarmSound()
        } else if (_activeAlarms.value.isNullOrEmpty()) {
            Log.d(TAG_MONITORING_VM, "checkSignalLossAndDistance: Aucune alarme active, arrêt du son.")
            stopAlarmSound()
        }
    }


    private fun addAlarm(alarm: AlarmEvent) {
        val currentAlarms = _activeAlarms.value?.toMutableList() ?: mutableListOf()
        if (currentAlarms.none { it.beaconName == alarm.beaconName && it.type == alarm.type }) {
            currentAlarms.add(0, alarm)
            _activeAlarms.postValue(currentAlarms)
            Log.i(TAG_MONITORING_VM, "addAlarm: Alarme ajoutée pour ${alarm.beaconName}, type ${alarm.type}. Total alarmes: ${currentAlarms.size}")
        } else {
            Log.d(TAG_MONITORING_VM, "addAlarm: Alarme DÉJÀ EXISTANTE pour ${alarm.beaconName}, type ${alarm.type}. Non ajoutée.")
        }
    }

    private fun removeAlarm(beaconName: String, type: AlarmType) {
        val currentAlarms = _activeAlarms.value?.toMutableList() ?: mutableListOf()
        val initialSize = currentAlarms.size
        val removed = currentAlarms.removeAll { it.beaconName == beaconName && it.type == type }
        if (removed) {
            _activeAlarms.postValue(currentAlarms)
            Log.i(TAG_MONITORING_VM, "removeAlarm: Alarme retirée pour $beaconName, type $type. Changement de taille: $initialSize -> ${currentAlarms.size}")
        } else {
            Log.d(TAG_MONITORING_VM, "removeAlarm: Aucune alarme à retirer pour $beaconName, type $type.")
        }
        if (currentAlarms.isEmpty() && mediaPlayer?.isPlaying == true) {
            Log.d(TAG_MONITORING_VM, "removeAlarm: Plus aucune alarme, arrêt du son.")
            stopAlarmSound()
        }
    }

    fun setAlarmDistanceThreshold(distance: Int) {
        // ... (code inchangé, logs peuvent être ajoutés si besoin) ...
        if (distance > 0) {
            _alarmDistanceThreshold.value = distance
            checkSignalLossAndDistance()
        }
    }

    fun setAlarmVolume(volumePercent: Int) {
        // ... (code inchangé, logs peuvent être ajoutés si besoin) ...
        if (volumePercent in 0..100) {
            _alarmVolume.value = volumePercent
            mediaPlayer?.let {
                if(it.isPlaying) {
                    val volumeLevel = volumePercent / 100f
                    it.setVolume(volumeLevel, volumeLevel)
                }
            }
        }
    }

    fun toggleSilenceAlarm() {
        // ... (code inchangé avec logs existants) ...
        val currentlySilenced = _isAlarmSilenced.value ?: false
        _isAlarmSilenced.value = !currentlySilenced

        if (!currentlySilenced) {
            stopAlarmSound()
            Log.d(TAG_MONITORING_VM,"Alarme mise en silence.")
        } else {
            Log.d(TAG_MONITORING_VM,"Alarme réactivée.")
            if (_activeAlarms.value?.isNotEmpty() == true) {
                playAlarmSound()
            }
        }
    }


    fun testAlarmSound() {
        // ... (code inchangé avec logs existants) ...
        playAlarmSound(isTest = true)
        handler.postDelayed({ stopAlarmSound() }, 3000)
    }

    private fun playAlarmSound(isTest: Boolean = false) {
        // ... (code inchangé avec logs existants) ...
        if (!isTest && _isAlarmSilenced.value == true) {
            Log.d(TAG_MONITORING_VM, "playAlarmSound: Tentative de jouer l'alarme, mais elle est en mode silencieux.")
            return
        }
        if (mediaPlayer?.isPlaying == true && !isTest) {
            Log.d(TAG_MONITORING_VM, "playAlarmSound: L'alarme est déjà en cours de lecture.")
            return
        }
        // ... (le reste du code de playAlarmSound avec ses logs existants) ...
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(getApplication(), R.raw.alarm_sound)

            if (mediaPlayer == null) {
                Log.e(TAG_MONITORING_VM, "playAlarmSound: Impossible de créer MediaPlayer. Vérifiez que R.raw.alarm_sound existe.")
                return
            }

            val volumeLevel = (_alarmVolume.value ?: 80) / 100f
            mediaPlayer?.setVolume(volumeLevel, volumeLevel)
            mediaPlayer?.isLooping = !isTest
            mediaPlayer?.setOnCompletionListener { /* ... */ }
            mediaPlayer?.setOnErrorListener { mp, what, extra ->
                Log.e(TAG_MONITORING_VM, "playAlarmSound: MediaPlayer Error: what $what, extra $extra")
                stopAlarmSound()
                true
            }
            mediaPlayer?.start()
            Log.d(TAG_MONITORING_VM, "playAlarmSound: Son d'alarme démarré. Test: $isTest")
        } catch (e: Exception) {
            Log.e(TAG_MONITORING_VM, "playAlarmSound: Exception lors de la lecture du son d'alarme", e)
            mediaPlayer = null
        }
    }

    private fun stopAlarmSound() {
        // ... (code inchangé avec logs existants) ...
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG_MONITORING_VM, "stopAlarmSound: Exception lors de l'arrêt/libération de MediaPlayer", e)
        } finally {
            mediaPlayer = null
            Log.d(TAG_MONITORING_VM, "stopAlarmSound: Son d'alarme arrêté et MediaPlayer libéré.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG_MONITORING_VM, "onCleared: ViewModel en cours de nettoyage.")
        stopContinuousScan()
        stopAlarmSound()
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG_MONITORING_VM, "onCleared: ViewModel nettoyé.")
    }
}
