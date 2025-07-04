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
        loadEnrolledBeacons()
        // Charger les préférences utilisateur pour les seuils/volume ici si sauvegardées
        // (Ex: via DataStore ou SharedPreferences)
    }

    private fun loadEnrolledBeacons() {
        viewModelScope.launch {
            try {
                val enrolledEntities = braceletRepository.getAllBraceletsSuspend()
                val beacons = enrolledEntities.map { entity ->
                    MonitoredBeacon(
                        address = entity.address,
                        assignedName = entity.name ?: "Sans nom",
                        // Les autres champs (rssi, distance, lastSeenTimestamp)
                        // seront initialisés à leurs valeurs par défaut ou mis à jour par le scan.
                    )
                }
                _monitoredBeacons.postValue(beacons)
                if (beacons.isNotEmpty()) {
                    startContinuousScan()
                }
            } catch (e: Exception) {
                Log.e("MonitoringVM", "Erreur lors du chargement des balises enregistrées", e)
                // Gérer l'erreur, par exemple afficher un message à l'utilisateur
            }
        }
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            // Pour les versions antérieures, BLUETOOTH et BLUETOOTH_ADMIN sont généralement suffisantes pour le scan
            // et ACCESS_FINE_LOCATION est la permission principale pour la découverte de services BLE.
            ActivityCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        }
    }
    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Pas de permission CONNECT distincte avant S
        }
    }


    private fun performBleScanGuarded(): Boolean {
        if (!hasBluetoothScanPermission()) {
            Log.e("MonitoringVM", "Permission de scan Bluetooth manquante.")
            return false
        }

        try {
            // L'appel qui nécessite la permission
            bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            Log.d("MonitoringVM", "Scan continu démarré.")
            return true
        } catch (e: SecurityException) {
            Log.e("MonitoringVM", "SecurityException lors du démarrage du scan BLE. Permissions manquantes ou révoquées.", e)
            // Vous pourriez vouloir notifier l'utilisateur ou gérer cette erreur plus formellement.
            return false
        } catch (e: Exception) { // Attraper d'autres exceptions possibles liées au scan
            Log.e("MonitoringVM", "Exception inattendue lors du démarrage du scan BLE.", e)
            return false
        }
    }

    fun startContinuousScan() {
        if (bluetoothAdapter == null) {
            Log.e("MonitoringVM", "Adaptateur Bluetooth non disponible.")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Log.w("MonitoringVM", "Bluetooth n'est pas activé. Le scan ne démarrera pas.")
            // Pourrait notifier l'UI pour demander à l'utilisateur d'activer le BT
            return
        }

        if (!hasBluetoothScanPermission()) {
            Log.e("MonitoringVM", "Permissions de scan Bluetooth manquantes. Le scan ne peut pas démarrer.")
            // Pourrait notifier l'UI pour demander les permissions
            return
        }


        if (scanJob?.isActive == true) {
            Log.d("MonitoringVM", "Le scan est déjà actif.")
            return
        }

        scanJob = viewModelScope.launch {
            try {
                val scanSuccessfullyStarted = performBleScanGuarded()
                if (scanSuccessfullyStarted) {
                    while (isActive) { // Assurez-vous de l'import kotlinx.coroutines.isActive
                        delay(5000)
                        checkSignalLossAndDistance()
                    }
                } else {
                    Log.w("MonitoringVM", "Le scan n'a pas été démarré (performBleScanGuarded a échoué).")
                }
            } catch (e: CancellationException) {
                Log.d("MonitoringVM", "Scan job annulé via CancellationException.")
                // S'assurer que le scan physique est aussi arrêté en cas d'annulation externe
                if (hasBluetoothScanPermission()) { // Votre vérification existante est bien
                    try {
                        bluetoothLeScanner?.stopScan(scanCallback)
                        Log.d("MonitoringVM", "Scan arrêté dans catch (CancellationException).")
                    } catch (secEx: SecurityException) {
                        Log.e("MonitoringVM", "SecurityException en arrêtant le scan dans catch (CancellationException).", secEx)
                    }
                } else {
                    Log.w("MonitoringVM", "Impossible d'arrêter le scan dans catch (CancellationException), permission manquante.")
                }
            } catch (e: Exception) {
                Log.e("MonitoringVM", "Erreur inattendue dans le scan job", e)
                // Vous pourriez aussi vouloir arrêter le scan ici si une erreur inattendue se produit et que le scan est actif
                if (hasBluetoothScanPermission()) {
                    try {
                        bluetoothLeScanner?.stopScan(scanCallback)
                        Log.d("MonitoringVM", "Scan arrêté dans catch (Exception).")
                    } catch (secEx: SecurityException) {
                        Log.e("MonitoringVM", "SecurityException en arrêtant le scan dans catch (Exception).", secEx)
                    }
                }
            } finally {
                Log.d("MonitoringVM", "Scan job terminé (bloc finally).")
                // S'assurer que le scan physique est arrêté si le job se termine pour une raison autre que l'annulation
                // ou si la boucle se termine normalement (ce qui n'est pas le cas avec while(isActive) sauf si le job est annulé)
                if (hasBluetoothScanPermission()) {
                    try {
                        bluetoothLeScanner?.stopScan(scanCallback)
                        Log.d("MonitoringVM", "Scan arrêté dans finally.")
                    } catch (secEx: SecurityException) {
                        Log.e("MonitoringVM", "SecurityException en arrêtant le scan dans finally.", secEx)
                    }
                } else {
                    Log.w("MonitoringVM", "Impossible d'arrêter le scan dans finally, permission manquante.")
                }
            }
        }
    }

    fun stopContinuousScan() {
        if (!hasBluetoothScanPermission()) { // Vérification initiale
            Log.w("MonitoringVM", "Permission Bluetooth manquante pour arrêter le scan. Annulation du job uniquement.")
            // Si BLUETOOTH_SCAN n'est pas là sur S+, on ne peut pas appeler stopScan() sans risque de crash.
            // Sur les versions antérieures, les permissions classiques sont nécessaires.
        } else {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
                Log.d("MonitoringVM", "Scan physique arrêté via bluetoothLeScanner.stopScan().")
            } catch (e: SecurityException) {
                Log.e("MonitoringVM", "SecurityException lors de l'appel à stopScan dans stopContinuousScan().", e)
            }
        }

        scanJob?.cancel() // Annuler la coroutine du job
        scanJob = null    // Libérer la référence au job
        Log.d("MonitoringVM", "Scan job annulé et potentiellement le scan physique arrêté.")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.address?.let { address ->
                val currentBeacons = _monitoredBeacons.value?.toMutableList() ?: return

                val beaconIndex = currentBeacons.indexOfFirst { it.address == address }

                if (beaconIndex != -1) {
                    val beacon = currentBeacons[beaconIndex]
                    val previousRssi = beacon.rssi
                    val previousDistance = beacon.distance

                    beacon.rssi = result.rssi
                    beacon.distance = if (result.rssi > MIN_RSSI_FOR_DISTANCE_CALC) calculateDistance(result.rssi) else null
                    beacon.lastSeenTimestamp = System.currentTimeMillis()

                    // Réinitialiser les états d'alarme si le signal est revenu ou si la distance est revenue dans la norme
                    // Note: checkSignalLossAndDistance s'en occupera plus globalement, mais on peut être proactif
                    if (beacon.isSignalLost) { // Si on reçoit un signal, il n'est plus perdu
                        beacon.isSignalLost = false
                        removeAlarm(beacon.assignedName, AlarmType.SIGNAL_LOST)
                    }
                    if (beacon.isOutOfRange && beacon.distance != null && beacon.distance!! <= (_alarmDistanceThreshold.value ?: 30)) {
                        beacon.isOutOfRange = false
                        removeAlarm(beacon.assignedName, AlarmType.DISTANCE_EXCEEDED)
                    }


                    // Mettre à jour la liste uniquement si quelque chose a changé pour éviter des notifications inutiles
                    // Ici, on crée toujours une nouvelle liste pour LiveData, mais on pourrait optimiser
                    _monitoredBeacons.postValue(ArrayList(currentBeacons))

                    // Log pour débogage
                    // Log.d("MonitoringVM", "Balise mise à jour: ${beacon.assignedName}, RSSI: ${beacon.rssi}, Dist: ${beacon.distance}")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("MonitoringVM", "Scan BLE échoué: $errorCode")
            // Peut-être arrêter le job de scan et notifier l'utilisateur
            stopContinuousScan()
        }
    }

    private fun calculateDistance(rssi: Int, txPower: Int = TX_POWER): Double {
        if (rssi == 0) {
            return -1.0 // Impossible de calculer si RSSI est 0
        }
        // Formule standard : distance = 10 ^ ((TxPower - RSSI) / (10 * N))
        // N est le path-loss exponent (typiquement 2 pour l'espace libre, 2-3 pour intérieur, jusqu'à 4 pour obstrué)
        val distance = 10.0.pow((txPower - rssi) / (10.0 * N_PARAMETER))
        return "%.2f".format(distance).toDouble() // Arrondir à 2 décimales
    }

    private fun checkSignalLossAndDistance() {
        val currentBeacons = _monitoredBeacons.value?.toMutableList() ?: return // Travail sur une copie
        if (currentBeacons.isEmpty()) return

        val currentTime = System.currentTimeMillis()
        val thresholdDistance = _alarmDistanceThreshold.value ?: 30
        var shouldTriggerSoundForAnyBeacon = false
        var needsUpdate = false

        currentBeacons.forEach { beacon ->
            val initialSignalLost = beacon.isSignalLost
            val initialOutOfRange = beacon.isOutOfRange

            // Vérification de la perte de signal
            if (beacon.lastSeenTimestamp > 0L && (currentTime - beacon.lastSeenTimestamp > SIGNAL_LOSS_THRESHOLD_MS)) {
                if (!beacon.isSignalLost) {
                    beacon.isSignalLost = true
                    addAlarm(AlarmEvent(beaconName = beacon.assignedName, message = "${beacon.assignedName} - Signal perdu !", type = AlarmType.SIGNAL_LOST))
                    shouldTriggerSoundForAnyBeacon = true
                    Log.i("MonitoringVM", "ALARME PERTE SIGNAL: ${beacon.assignedName}")
                }
            } else if (beacon.isSignalLost) { // Signal retrouvé
                beacon.isSignalLost = false
                removeAlarm(beacon.assignedName, AlarmType.SIGNAL_LOST)
                Log.i("MonitoringVM", "Signal retrouvé pour: ${beacon.assignedName}")
            }

            // Vérification du dépassement de distance (uniquement si le signal n'est pas perdu et que la distance est calculable)
            if (!beacon.isSignalLost && beacon.distance != null) {
                if (beacon.distance!! > thresholdDistance) {
                    if (!beacon.isOutOfRange) {
                        beacon.isOutOfRange = true
                        addAlarm(AlarmEvent(beaconName = beacon.assignedName, message = "${beacon.assignedName} à ${beacon.distance?.toInt()}m - Hors de portée !", type = AlarmType.DISTANCE_EXCEEDED))
                        shouldTriggerSoundForAnyBeacon = true
                        Log.i("MonitoringVM", "ALARME DISTANCE: ${beacon.assignedName} à ${beacon.distance}m")
                    }
                } else if (beacon.isOutOfRange) { // De retour dans la portée
                    beacon.isOutOfRange = false
                    removeAlarm(beacon.assignedName, AlarmType.DISTANCE_EXCEEDED)
                    Log.i("MonitoringVM", "De retour dans la portée: ${beacon.assignedName}")
                }
            } else if (beacon.isOutOfRange && (beacon.isSignalLost || beacon.distance == null)) {
                // Si était hors portée et qu'on perd le signal ou que la distance n'est plus calculable, on retire l'alarme de distance
                // L'alarme de perte de signal prendra le relais si applicable.
                beacon.isOutOfRange = false
                removeAlarm(beacon.assignedName, AlarmType.DISTANCE_EXCEEDED)
            }


            if (initialSignalLost != beacon.isSignalLost || initialOutOfRange != beacon.isOutOfRange) {
                needsUpdate = true
            }
        }

        if (needsUpdate) {
            _monitoredBeacons.postValue(ArrayList(currentBeacons)) // Notifier les changements d'état (créer une nouvelle liste)
        }

        if (shouldTriggerSoundForAnyBeacon && _isAlarmSilenced.value == false) {
            playAlarmSound()
        } else if (_activeAlarms.value.isNullOrEmpty()) {
            // S'il n'y a plus d'alarmes actives, arrêter le son
            stopAlarmSound()
        }
    }


    private fun addAlarm(alarm: AlarmEvent) {
        val currentAlarms = _activeAlarms.value?.toMutableList() ?: mutableListOf()
        // Éviter les doublons pour le même type d'alarme sur la même balise
        if (currentAlarms.none { it.beaconName == alarm.beaconName && it.type == alarm.type }) {
            currentAlarms.add(0, alarm) // Ajouter en haut de la liste
            _activeAlarms.postValue(currentAlarms)
        }
    }

    private fun removeAlarm(beaconName: String, type: AlarmType) {
        val currentAlarms = _activeAlarms.value?.toMutableList() ?: mutableListOf()
        val removed = currentAlarms.removeAll { it.beaconName == beaconName && it.type == type }
        if (removed) {
            _activeAlarms.postValue(currentAlarms)
        }
        // Si plus aucune alarme n'est active, on peut arrêter le son.
        // checkSignalLossAndDistance s'en occupe déjà, mais on peut être plus direct ici.
        if (currentAlarms.isEmpty() && mediaPlayer?.isPlaying == true) {
            stopAlarmSound()
        }
    }

    fun setAlarmDistanceThreshold(distance: Int) {
        if (distance > 0) {
            _alarmDistanceThreshold.value = distance
            // Sauvegarder cette préférence (ex: DataStore)
            checkSignalLossAndDistance() // Réévaluer les alarmes avec le nouveau seuil
        }
    }

    fun setAlarmVolume(volumePercent: Int) {
        if (volumePercent in 0..100) {
            _alarmVolume.value = volumePercent
            // Sauvegarder cette préférence (ex: DataStore)
            // Appliquer le volume si le mediaPlayer joue
            mediaPlayer?.let {
                if(it.isPlaying) {
                    val volumeLevel = volumePercent / 100f
                    it.setVolume(volumeLevel, volumeLevel)
                }
            }
        }
    }

    fun toggleSilenceAlarm() {
        val currentlySilenced = _isAlarmSilenced.value ?: false
        _isAlarmSilenced.value = !currentlySilenced

        if (!currentlySilenced) { // Si on vient de mettre en silencieux (true)
            stopAlarmSound()
            Log.d("MonitoringVM","Alarme mise en silence.")
        } else { // Si on réactive le son (false) et qu'il y a des alarmes actives
            Log.d("MonitoringVM","Alarme réactivée.")
            if (_activeAlarms.value?.isNotEmpty() == true) {
                playAlarmSound()
            }
        }
    }


    fun testAlarmSound() {
        playAlarmSound(isTest = true) // Jouer le son même s'il est en mode silencieux pour le test
        handler.postDelayed({ stopAlarmSound() }, 3000) // Arrêter après 3s
    }

    private fun playAlarmSound(isTest: Boolean = false) {
        if (!isTest && _isAlarmSilenced.value == true) {
            Log.d("MonitoringVM", "Tentative de jouer l'alarme, mais elle est en mode silencieux.")
            return
        }
        if (mediaPlayer?.isPlaying == true && !isTest) { // Si c'est un test, on le redémarre
            Log.d("MonitoringVM", "L'alarme est déjà en cours de lecture.")
            return
        }

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(getApplication(), R.raw.alarm_sound)

            if (mediaPlayer == null) {
                Log.e("MonitoringVM", "Impossible de créer MediaPlayer. Vérifiez que R.raw.alarm_sound existe.")
                return
            }

            val volumeLevel = (_alarmVolume.value ?: 80) / 100f
            mediaPlayer?.setVolume(volumeLevel, volumeLevel)
            mediaPlayer?.isLooping = !isTest // En boucle pour les vraies alarmes, pas pour le test
            mediaPlayer?.setOnCompletionListener {
                if (!isTest) { // Pour les vraies alarmes, si isLooping est false (ne devrait pas arriver avec la config actuelle)
                    // On pourrait vouloir le relancer si l'alarme est toujours active
                }
            }
            mediaPlayer?.setOnErrorListener { mp, what, extra ->
                Log.e("MonitoringVM", "MediaPlayer Error: what $what, extra $extra")
                stopAlarmSound() // Nettoyer en cas d'erreur
                true // Indique que l'erreur a été gérée
            }
            mediaPlayer?.start()
            Log.d("MonitoringVM", "Son d'alarme démarré. Test: $isTest")
        } catch (e: Exception) {
            Log.e("MonitoringVM", "Exception lors de la lecture du son d'alarme", e)
            mediaPlayer = null // S'assurer qu'il est null en cas d'échec
        }
    }

    private fun stopAlarmSound() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e("MonitoringVM", "Exception lors de l'arrêt/libération de MediaPlayer", e)
        } finally {
            mediaPlayer = null
            Log.d("MonitoringVM", "Son d'alarme arrêté et MediaPlayer libéré.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopContinuousScan()
        stopAlarmSound()
        handler.removeCallbacksAndMessages(null) // Nettoyer le handler
        Log.d("MonitoringVM", "ViewModel cleared.")
    }
}
