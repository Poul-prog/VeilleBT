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
import androidx.compose.ui.geometry.isEmpty
import androidx.core.app.ActivityCompat
import androidx.lifecycle.*
import com.martin.veillebt.R // Pour accéder à R.raw.alarm_sound
// Assurez-vous que le chemin vers votre MonitoredBeacon est correct si ce n'est pas dans le même package
// import com.martin.veillebt.data.model.MonitoredBeacon // Exemple si dans data.model
import com.martin.veillebt.data.repository.BraceletRepository // Assurez-vous que le chemin est correct
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlin.math.round
import com.martin.veillebt.ui.dashboard.MonitoredBeacon // Ou le chemin correct vers votre classe

// N'incluez PAS la définition de MonitoredBeacon ici si elle est dans son propre fichier.
// Le ViewModel l'importera.



// Classe pour représenter une alarme
data class AlarmEvent(
    val id: String = UUID.randomUUID().toString(),
    val beaconName: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: AlarmType
)

enum class AlarmType {
    DISTANCE_EXCEEDED,
    SIGNAL_LOST
}

const val SIGNAL_LOSS_THRESHOLD_MS = 20000L
const val MIN_RSSI_FOR_DISTANCE_CALC = -85 // RSSI minimum pour tenter un calcul de distance
private const val TAG_MONITORING_VM = "MonitoringVM_Debug"

@HiltViewModel
class MonitoringViewModel @Inject constructor(
    private val app: Application,
    private val braceletRepository: BraceletRepository
) : AndroidViewModel(app) {

    private val bluetoothManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private val _monitoredBeacons = MutableLiveData<List<MonitoredBeacon>>(emptyList())
    val monitoredBeacons: LiveData<List<MonitoredBeacon>> = _monitoredBeacons

    private val _activeAlarms = MutableLiveData<List<AlarmEvent>>(emptyList())
    val activeAlarms: LiveData<List<AlarmEvent>> = _activeAlarms

    private val _alarmDistanceThreshold = MutableLiveData(30) // Default 30m
    val alarmDistanceThreshold: LiveData<Int> = _alarmDistanceThreshold

    private val _alarmVolume = MutableLiveData(80) // Default 80%
    val alarmVolume: LiveData<Int> = _alarmVolume

    private val _isAlarmSilenced = MutableLiveData(false)
    val isAlarmSilenced: LiveData<Boolean> = _isAlarmSilenced

    private var scanJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null

    private var isBleScanPhysicallyActive = false

    private val DEFAULT_TX_POWER = -59 // Puissance de référence à 1 mètre (dBm) - À CALIBRER par type de balise
    private val N_PARAMETER = 2.0      // Facteur d'atténuation de l'environnement

    init {
        Log.d(TAG_MONITORING_VM, "ViewModel initialisé.")
        loadEnrolledBeaconsAndStartScan()
    }

    private fun loadEnrolledBeaconsAndStartScan() {
        Log.d(TAG_MONITORING_VM, "loadEnrolledBeaconsAndStartScan: Début.")
        viewModelScope.launch {
            try {
                val enrolledEntities = braceletRepository.getAllBraceletsSuspend()
                Log.d(TAG_MONITORING_VM, "loadEnrolledBeaconsAndStartScan: ${enrolledEntities.size} entités chargées.")
                val beacons = enrolledEntities.map { entity ->
                    // Utilisation du constructeur de MonitoredBeacon qui prend txPowerAt1m
                    MonitoredBeacon(
                        address = entity.address,
                        assignedName = entity.name ?: "Sans nom",
                        originalName = null, // Sera mis à jour par le scan si le device a un nom
                        txPowerAt1m = entity.txPowerAt1m ?: DEFAULT_TX_POWER // Utilise le txPower de l'entité ou un défaut
                    )
                }
                Log.d(TAG_MONITORING_VM, "loadEnrolledBeaconsAndStartScan: ${beacons.size} MonitoredBeacon créés.")
                _monitoredBeacons.postValue(beacons)

                if (beacons.isNotEmpty()) {
                    Log.d(TAG_MONITORING_VM, "loadEnrolledBeaconsAndStartScan: Balises non vides, démarrage du scan continu.")
                    startContinuousScan()
                } else {
                    Log.d(TAG_MONITORING_VM, "loadEnrolledBeaconsAndStartScan: Aucune balise, le scan ne démarrera pas d'ici.")
                    stopContinuousScan() // S'assurer que tout est arrêté
                }
            } catch (e: Exception) {
                Log.e(TAG_MONITORING_VM, "loadEnrolledBeaconsAndStartScan: Erreur", e)
            }
        }
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else { // API < S
            ActivityCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else { // API < S
            true // Pas de permission BLUETOOTH_CONNECT spécifique requise avant S.
        }
    }

    private fun performBleScanGuarded(): Boolean {
        if (!hasBluetoothScanPermission()) {
            Log.e(TAG_MONITORING_VM, "performBleScanGuarded: Permission BLUETOOTH_SCAN manquante.")
            return false
        }
        if (isBleScanPhysicallyActive) {
            Log.d(TAG_MONITORING_VM, "performBleScanGuarded: Scan physique déjà considéré comme actif.")
            return true // Déjà actif selon notre logique
        }
        try {
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
            isBleScanPhysicallyActive = true // Marquer comme actif APRÈS l'appel à startScan
            Log.d(TAG_MONITORING_VM, "performBleScanGuarded: Scan BLE physique démarré et marqué comme actif.")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG_MONITORING_VM, "performBleScanGuarded: SecurityException au démarrage du scan.", e)
            isBleScanPhysicallyActive = false
            return false
        } catch (e: IllegalStateException) {
            Log.e(TAG_MONITORING_VM, "performBleScanGuarded: IllegalStateException (BT désactivé ou autre).", e)
            isBleScanPhysicallyActive = false
            return false
        } catch (e: Exception) {
            Log.e(TAG_MONITORING_VM, "performBleScanGuarded: Exception inattendue au démarrage du scan.", e)
            isBleScanPhysicallyActive = false
            return false
        }
    }


    fun startContinuousScan() {
        Log.d(TAG_MONITORING_VM, "startContinuousScan: Tentative.")
        if (bluetoothAdapter == null) {
            Log.e(TAG_MONITORING_VM, "startContinuousScan: Adaptateur BT non disponible.")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG_MONITORING_VM, "startContinuousScan: BT non activé.")
            // Envisager d'arrêter le scan ici aussi si BT est désactivé
            stopContinuousScan() // S'assurer que tout s'arrête si BT n'est pas prêt
            return
        }
        if (!hasBluetoothScanPermission()) {
            Log.e(TAG_MONITORING_VM, "startContinuousScan: Permissions scan BT manquantes.")
            return
        }

        val scanPhysicallyStarted = performBleScanGuarded()

        if (scanPhysicallyStarted) {
            if (scanJob?.isActive != true) {
                Log.d(TAG_MONITORING_VM, "startContinuousScan: Scan physique OK. Lancement job vérification.")
                scanJob = viewModelScope.launch {
                    try {
                        while (isActive) {
                            delay(5000) // Période de vérification
                            checkSignalLossAndDistance()
                        }
                    } catch (e: CancellationException) {
                        Log.i(TAG_MONITORING_VM, "startContinuousScan: Job vérification annulé.")
                    } catch (e: Exception) {
                        Log.e(TAG_MONITORING_VM, "startContinuousScan: Erreur job vérification", e)
                    } finally {
                        Log.i(TAG_MONITORING_VM, "startContinuousScan: Job vérification terminé (finally).")
                    }
                }
            } else {
                Log.d(TAG_MONITORING_VM, "startContinuousScan: Scan physique démarré, job vérification déjà actif.")
            }
        } else {
            Log.w(TAG_MONITORING_VM, "startContinuousScan: Scan physique BLE non démarré (performBleScanGuarded a échoué).")
            // S'assurer que le job est annulé si le scan physique ne peut pas démarrer
            scanJob?.cancel()
            // isBleScanPhysicallyActive devrait déjà être false ici à cause de performBleScanGuarded
        }
    }

    fun stopContinuousScan() {
        Log.d(TAG_MONITORING_VM, "stopContinuousScan: Tentative.")

        // 1. Arrêter le scan physique BLE
        if (isBleScanPhysicallyActive && hasBluetoothScanPermission()) {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
                Log.d(TAG_MONITORING_VM, "stopContinuousScan: Scan physique BLE arrêté.")
            } catch (e: SecurityException) {
                Log.e(TAG_MONITORING_VM, "stopContinuousScan: SecurityException à stopScan.", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG_MONITORING_VM, "stopContinuousScan: IllegalStateException à stopScan (BT désactivé?).", e)
            } catch (e: Exception) {
                Log.e(TAG_MONITORING_VM, "stopContinuousScan: Exception à stopScan.", e)
            } finally {
                isBleScanPhysicallyActive = false // Marquer inactif après tentative d'arrêt
                Log.d(TAG_MONITORING_VM, "stopContinuousScan: isBleScanPhysicallyActive mis à false.")
            }
        } else {
            if (!isBleScanPhysicallyActive) {
                Log.d(TAG_MONITORING_VM, "stopContinuousScan: Scan physique BLE déjà considéré comme inactif.")
            }
            if (!hasBluetoothScanPermission()) {
                Log.w(TAG_MONITORING_VM, "stopContinuousScan: Permission manquante pour arrêter scan physique (mais on le marque inactif).")
            }
            isBleScanPhysicallyActive = false // Assurer l'état inactif
        }

        // 2. Annuler le job de vérification
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            Log.d(TAG_MONITORING_VM, "stopContinuousScan: Job vérification annulé.")
        }
        scanJob = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val deviceAddress = result?.device?.address ?: return

            var deviceName: String? = null
            // Accès au nom du device
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (hasBluetoothConnectPermission()) {
                    try { deviceName = result.device?.name }
                    catch (e: SecurityException) { Log.w(TAG_MONITORING_VM, "onScanResult: SecEx nom (API >= S).", e) }
                } else {
                    Log.w(TAG_MONITORING_VM, "onScanResult: Permission BLUETOOTH_CONNECT manquante pour nom (API >= S).")
                }
            } else { // API < S
                // BLUETOOTH et BLUETOOTH_ADMIN (dans hasBluetoothScanPermission) devraient suffire
                try { deviceName = result.device?.name }
                catch (e: SecurityException) { Log.w(TAG_MONITORING_VM, "onScanResult: SecEx nom (API < S).", e) }
            }

            val currentBeaconsList = _monitoredBeacons.value ?: return
            val beaconIndex = currentBeaconsList.indexOfFirst { it.address == deviceAddress }

            if (beaconIndex != -1) {
                val oldBeacon = currentBeaconsList[beaconIndex]

                val updatedBeacon = oldBeacon.copy(
                    rssi = result.rssi,
                    distance = if (result.rssi > MIN_RSSI_FOR_DISTANCE_CALC) calculateDistance(result.rssi, oldBeacon.txPowerAt1m ?: DEFAULT_TX_POWER) else null,
                    lastSeenTimestamp = System.currentTimeMillis(),
                    isSignalLost = false, // Signal retrouvé
                    originalName = deviceName ?: oldBeacon.originalName, // Mettre à jour si nouveau nom trouvé
                    // Si le signal était perdu, l'alarme correspondante sera retirée.
                    // hasActiveSignalLostAlarm sera géré par removeAlarm -> updateBeaconAlarmStatus.
                    // On met à false ici pour une cohérence immédiate si oldBeacon.isSignalLost était true.
                    hasActiveSignalLostAlarm = if (oldBeacon.isSignalLost) false else oldBeacon.hasActiveSignalLostAlarm
                )

                if (oldBeacon.isSignalLost) {
                    Log.i(TAG_MONITORING_VM, "onScanResult: Signal retrouvé pour ${updatedBeacon.assignedName}.")
                    removeAlarm(updatedBeacon.assignedName, AlarmType.SIGNAL_LOST)
                }

                val newBeaconsList = currentBeaconsList.toMutableList()
                newBeaconsList[beaconIndex] = updatedBeacon
                _monitoredBeacons.postValue(newBeaconsList)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG_MONITORING_VM, "onScanFailed: Code $errorCode. isBleScanPhysicallyActive AVANT mise à false: $isBleScanPhysicallyActive")
            isBleScanPhysicallyActive = false // Le scan a échoué, donc il n'est plus actif
            Log.d(TAG_MONITORING_VM, "onScanFailed: isBleScanPhysicallyActive APRÈS mise à false: $isBleScanPhysicallyActive")


            when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> {
                    Log.w(TAG_MONITORING_VM, "onScanFailed: SCAN_FAILED_ALREADY_STARTED. Le scan était déjà en cours. Normalement géré par isBleScanPhysicallyActive.")
                    // Dans ce cas, isBleScanPhysicallyActive aurait dû être true et performBleScanGuarded n'aurait pas dû appeler startScan.
                    // Si cela arrive, c'est une potentielle incohérence d'état à investiguer.
                    // On peut choisir de ne pas appeler stopContinuousScan ici si on pense que le scan est toujours actif.
                    // Cependant, pour être sûr, on peut forcer un arrêt et un redémarrage si nécessaire.
                }
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> Log.e(TAG_MONITORING_VM, "onScanFailed: Échec enregistrement application.")
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> Log.e(TAG_MONITORING_VM, "onScanFailed: Erreur interne Bluetooth.")
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> Log.e(TAG_MONITORING_VM, "onScanFailed: Fonctionnalité non supportée.")
                else -> Log.e(TAG_MONITORING_VM, "onScanFailed: Erreur inconnue $errorCode.")
            }
            // Arrêter tout, y compris le job, car le scan physique a échoué.
            stopContinuousScan()

            // Optionnel: Tenter un redémarrage avec délai pour certaines erreurs récupérables
            // if (errorCode != ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED && errorCode != ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
            //    handler.postDelayed({ startContinuousScan() }, 5000) // Tenter après 5s
            // }
        }
    }



    private fun calculateDistance(rssi: Int, txPower: Int): Double? {
        if (rssi == 0 || rssi < MIN_RSSI_FOR_DISTANCE_CALC) { // RSSI de 0 est souvent invalide ou trop faible
            return null
        }
        // Formule standard de calcul de distance basée sur RSSI et TxPower
        // distance = 10 ^ ((TxPower - RSSI) / (10 * N))
        // N est le facteur d'atténuation du signal (path loss exponent), typiquement entre 2.0 (espace libre) et 4.0 (environnements obstrués)
        val distance = 10.0.pow((txPower - rssi) / (10.0 * N_PARAMETER))
        return round(distance * 100.0) / 100.0 // Arrondir à 2 décimales pour l'affichage
    }

    private fun checkSignalLossAndDistance() {
        val currentBeacons = _monitoredBeacons.value ?: return
        if (currentBeacons.isEmpty()) {
            // Log.v(TAG_MONITORING_VM, "checkSignalLossAndDistance: Aucune balise à vérifier.")
            return
        }

        val currentTime = System.currentTimeMillis()
        val thresholdDistance = _alarmDistanceThreshold.value ?: 30 // Utiliser la valeur LiveData ou un défaut
        var shouldTriggerSound = false
        var listNeedsUiUpdate = false // Pour regrouper les postValue si plusieurs balises sont mises à jour

        val updatedBeaconsList = currentBeacons.map { beacon ->
            var tempBeacon = beacon // Copie pour modification

            // 1. Vérification de la perte de signal
            if (!tempBeacon.isSignalLost && tempBeacon.lastSeenTimestamp > 0L && (currentTime - tempBeacon.lastSeenTimestamp > SIGNAL_LOSS_THRESHOLD_MS)) {
                Log.i(TAG_MONITORING_VM, "checkSignalLossAndDistance: ${tempBeacon.assignedName} - Signal PERDU.")
                tempBeacon = tempBeacon.copy(
                    isSignalLost = true,
                    rssi = -100, // Valeur indiquant la perte pour l'UI
                    distance = null, // La distance n'est plus pertinente
                    hasActiveSignalLostAlarm = true // Marquer pour l'état de l'alarme
                )
                addAlarm(AlarmEvent(beaconName = tempBeacon.assignedName, message = "${tempBeacon.assignedName} - Signal perdu !", type = AlarmType.SIGNAL_LOST))
                shouldTriggerSound = true
                listNeedsUiUpdate = true
            }

            // 2. Vérification du dépassement de distance (uniquement si le signal n'est pas perdu)
            if (!tempBeacon.isSignalLost && tempBeacon.distance != null) {
                if (tempBeacon.distance!! > thresholdDistance && !tempBeacon.isOutOfRange) {
                    Log.i(TAG_MONITORING_VM, "checkSignalLossAndDistance: ${tempBeacon.assignedName} - HORS DE PORTÉE (${tempBeacon.distance}m > ${thresholdDistance}m).")
                    tempBeacon = tempBeacon.copy(
                        isOutOfRange = true,
                        hasActiveDistanceAlarm = true
                    )
                    addAlarm(AlarmEvent(beaconName = tempBeacon.assignedName, message = "${tempBeacon.assignedName} à ${tempBeacon.distance?.toInt()}m - Hors de portée !", type = AlarmType.DISTANCE_EXCEEDED))
                    shouldTriggerSound = true
                    listNeedsUiUpdate = true
                } else if (tempBeacon.distance!! <= thresholdDistance && tempBeacon.isOutOfRange) {
                    Log.i(TAG_MONITORING_VM, "checkSignalLossAndDistance: ${tempBeacon.assignedName} - DE RETOUR DANS LA PORTÉE (${tempBeacon.distance}m <= ${thresholdDistance}m).")
                    tempBeacon = tempBeacon.copy(
                        isOutOfRange = false,
                        hasActiveDistanceAlarm = false
                    )
                    removeAlarm(tempBeacon.assignedName, AlarmType.DISTANCE_EXCEEDED)
                    listNeedsUiUpdate = true
                }
            } else if (tempBeacon.isOutOfRange && (tempBeacon.isSignalLost || tempBeacon.distance == null)) {
                // Si la balise était hors portée, mais que maintenant le signal est perdu ou la distance est N/A,
                // l'alarme de distance n'est plus la principale préoccupation. La perte de signal est prioritaire.
                // On annule donc l'alarme de distance si elle était active.
                Log.i(TAG_MONITORING_VM, "checkSignalLossAndDistance: ${tempBeacon.assignedName} - Était hors portée, mais signal perdu/distance N/A. Annulation alarme distance.")
                tempBeacon = tempBeacon.copy(
                    isOutOfRange = false, // N'est plus "hors de portée" car la distance n'est plus fiable/signal perdu
                    hasActiveDistanceAlarm = false
                )
                removeAlarm(tempBeacon.assignedName, AlarmType.DISTANCE_EXCEEDED)
                listNeedsUiUpdate = true
            }
            tempBeacon
        }

        if (listNeedsUiUpdate) {
            _monitoredBeacons.postValue(updatedBeaconsList)
        }

        if (shouldTriggerSound && _isAlarmSilenced.value == false) {
            playAlarmSound()
        } else if (_activeAlarms.value.isNullOrEmpty() && mediaPlayer?.isPlaying == true) {
            // S'il n'y a plus d'alarmes actives et que le son jouait, on l'arrête.
            stopAlarmSound()
        }
    }

    private fun addAlarm(alarm: AlarmEvent) {
        val currentAlarms = _activeAlarms.value?.toMutableList() ?: mutableListOf()
        if (currentAlarms.none { it.beaconName == alarm.beaconName && it.type == alarm.type }) {
            currentAlarms.add(0, alarm) // Ajouter en haut de la liste pour l'UI
            _activeAlarms.postValue(currentAlarms)
            Log.i(TAG_MONITORING_VM, "addAlarm: Ajoutée pour ${alarm.beaconName}, type ${alarm.type}.")
            updateBeaconAlarmStatus(alarm.beaconName, alarm.type, true)
        }
    }

    private fun removeAlarm(beaconName: String, type: AlarmType) {
        val currentAlarms = _activeAlarms.value?.toMutableList() ?: mutableListOf()
        val removed = currentAlarms.removeAll { it.beaconName == beaconName && it.type == type }
        if (removed) {
            _activeAlarms.postValue(currentAlarms)
            Log.i(TAG_MONITORING_VM, "removeAlarm: Retirée pour $beaconName, type $type.")
            updateBeaconAlarmStatus(beaconName, type, false)
        }
        if (currentAlarms.isEmpty() && mediaPlayer?.isPlaying == true) {
            stopAlarmSound() // Arrêter le son si c'était la dernière alarme
        }
    }

    // Met à jour le statut hasActive...Alarm sur le beacon correspondant dans _monitoredBeacons
    private fun updateBeaconAlarmStatus(beaconNameForAlarm: String, alarmType: AlarmType, isActive: Boolean) {
        val currentBeacons = _monitoredBeacons.value?.toMutableList() ?: return
        val beaconIndex = currentBeacons.indexOfFirst { it.assignedName == beaconNameForAlarm }

        if (beaconIndex != -1) {
            val oldBeacon = currentBeacons[beaconIndex]
            val updatedBeacon = when (alarmType) {
                AlarmType.SIGNAL_LOST -> oldBeacon.copy(hasActiveSignalLostAlarm = isActive)
                AlarmType.DISTANCE_EXCEEDED -> oldBeacon.copy(hasActiveDistanceAlarm = isActive)
            }
            if (oldBeacon != updatedBeacon) { // Optimisation: ne mettre à jour que si réellement changé
                currentBeacons[beaconIndex] = updatedBeacon
                _monitoredBeacons.postValue(currentBeacons)
                Log.d(TAG_MONITORING_VM, "updateBeaconAlarmStatus: Beacon ${oldBeacon.assignedName} (type: $alarmType) mis à jour, alarme active: $isActive")
            }
        } else {
            Log.w(TAG_MONITORING_VM, "updateBeaconAlarmStatus: Beacon '$beaconNameForAlarm' non trouvé pour màj statut alarme.")
        }
    }

    fun setAlarmDistanceThreshold(distance: Int) {
        if (distance > 0) {
            _alarmDistanceThreshold.value = distance
            Log.d(TAG_MONITORING_VM, "setAlarmDistanceThreshold: Seuil $distance m.")
            // Réévaluer immédiatement les conditions car le seuil a changé
            checkSignalLossAndDistance()
        }
    }

    fun setAlarmVolume(volumePercent: Int) {
        if (volumePercent in 0..100) {
            _alarmVolume.value = volumePercent
            mediaPlayer?.let {
                // Ajuster le volume seulement si le mediaPlayer est en cours de lecture
                // pour éviter des IllegalStateException si on ajuste le volume d'un player relâché/non préparé.
                // En pratique, setVolume devrait être sûr même si non en lecture, mais une vérification supplémentaire ne nuit pas.
                if (it.isPlaying) { // Ou juste vérifier que mediaPlayer n'est pas null et est initialisé.
                    val volumeLevel = volumePercent / 100f
                    try {
                        it.setVolume(volumeLevel, volumeLevel)
                    } catch (e: IllegalStateException) {
                        Log.e(TAG_MONITORING_VM, "setAlarmVolume: Erreur MediaPlayer setVolume (possiblement pas encore prêt ou relâché)", e)
                    }
                }
            }
            Log.d(TAG_MONITORING_VM, "setAlarmVolume: Volume $volumePercent%.")
        }
    }

    fun toggleSilenceAlarm() {
        val currentlySilenced = _isAlarmSilenced.value ?: false
        _isAlarmSilenced.value = !currentlySilenced
        Log.d(TAG_MONITORING_VM, "toggleSilenceAlarm: Alarme ${if (!currentlySilenced) "SILENCIEUSE" else "RÉACTIVÉE"}.")
        if (!currentlySilenced) { // Si on vient de mettre en silence
            stopAlarmSound()
        } else { // Si on vient de réactiver les alarmes
            // Si des alarmes sont actives, redémarrer le son
            if (_activeAlarms.value?.isNotEmpty() == true) {
                playAlarmSound()
            }
        }
    }

    fun testAlarmSound() {
        Log.d(TAG_MONITORING_VM, "testAlarmSound: Test son.")
        playAlarmSound(isTest = true)
        // Optionnel: arrêter le son de test après quelques secondes
        // si le son de test n'est pas en boucle et qu'aucune autre alarme réelle n'est active.
        handler.postDelayed({
            // S'assurer que ce n'est pas une alarme réelle qui joue et que le son de test est celui qui est arrêté
            if (mediaPlayer?.isLooping == false) { // Le son de test n'est pas en boucle
                stopAlarmSound()
            }
        }, 3000) // Arrêter après 3 secondes
    }

    private fun playAlarmSound(isTest: Boolean = false) {
        if (!isTest && _isAlarmSilenced.value == true) {
            Log.d(TAG_MONITORING_VM, "playAlarmSound: Alarme silencieuse, son non joué.")
            return
        }
        // Éviter de redémarrer si le son est déjà en cours pour une alarme réelle
        // (sauf si c'est un test, auquel cas on veut le redémarrer)
        if (mediaPlayer?.isPlaying == true && !isTest) {
            Log.d(TAG_MONITORING_VM, "playAlarmSound: Son déjà en cours pour une alarme réelle, ne pas redémarrer.")
            return
        }

        try {
            mediaPlayer?.release() // Libérer les ressources précédentes
            mediaPlayer = MediaPlayer.create(getApplication(), R.raw.alarm_sound)
            if (mediaPlayer == null) {
                Log.e(TAG_MONITORING_VM, "playAlarmSound: MediaPlayer.create a échoué (ressource non trouvée ou autre).")
                return
            }
            val volumeLevel = (_alarmVolume.value ?: 80) / 100f
            mediaPlayer?.setVolume(volumeLevel, volumeLevel)
            mediaPlayer?.isLooping = !isTest // En boucle pour les alarmes réelles, pas pour le test
            mediaPlayer?.setOnCompletionListener { mp ->
                if (!mp.isLooping) { // Si ce n'était pas en boucle (ex: test terminé)
                    stopAlarmSound() // Nettoyer
                }
                // Si c'est en boucle (alarme réelle), on ne fait rien ici, elle continue.
            }
            mediaPlayer?.setOnErrorListener { _, what, extra ->
                Log.e(TAG_MONITORING_VM, "playAlarmSound: MediaPlayer Erreur - what: $what, extra: $extra")
                stopAlarmSound() // En cas d'erreur, arrêter et nettoyer
                true // Indique que l'erreur a été gérée
            }
            mediaPlayer?.start()
            Log.d(TAG_MONITORING_VM, "playAlarmSound: Son DÉMARRÉ. Test: $isTest, Looping: ${mediaPlayer?.isLooping}")
        } catch (e: Exception) {
            Log.e(TAG_MONITORING_VM, "playAlarmSound: Exception lors de la préparation/démarrage du MediaPlayer", e)
            mediaPlayer = null // S'assurer qu'il est null en cas d'erreur
        }
    }

    private fun stopAlarmSound() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release() // Toujours appeler release pour libérer les ressources
        } catch (e: Exception) {
            // Surtout IllegalStateException si le MediaPlayer n'était pas dans un état valide pour stop/release
            Log.e(TAG_MONITORING_VM, "stopAlarmSound: Exception lors de l'arrêt/libération du MediaPlayer", e)
        } finally {
            mediaPlayer = null // Mettre à null pour indiquer qu'il n'est plus utilisable/actif
            Log.d(TAG_MONITORING_VM, "stopAlarmSound: MediaPlayer arrêté et nullifié.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG_MONITORING_VM, "onCleared: Nettoyage du ViewModel.")
        stopContinuousScan() // Arrêter le scan BLE et le job associé
        stopAlarmSound()     // Arrêter et libérer le MediaPlayer
        handler.removeCallbacksAndMessages(null) // Nettoyer les messages/runnables du handler
        Log.d(TAG_MONITORING_VM, "onCleared: Nettoyage terminé.")
    }
}
