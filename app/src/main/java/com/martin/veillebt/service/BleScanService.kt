package com.martin.veillebt.service // Ou le nom de votre package réel

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.martin.veillebt.data.local.model.BraceletEntity
import com.martin.veillebt.data.repository.BraceletRepository
import com.martin.veillebt.ui.main.MainActivity // Assurez-vous que c'est le bon chemin pour MainActivity
import com.martin.veillebt.R
import com.martin.veillebt.sound.AlarmSoundPlayer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class BleScanService : Service() {

    @Inject lateinit var braceletRepository: BraceletRepository
    @Inject lateinit var sharedPreferences: SharedPreferences
    @Inject lateinit var alarmSoundPlayer: AlarmSoundPlayer

    // ... (le reste de vos variables membres)
    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var isBleScanPhysicallyActive = false
    private var currentScanJob: Job? = null
    private var signalLossCheckJob: Job? = null

    private val lastSeenTimestamps = mutableMapOf<String, Long>()
    private val rssiValuesMap = mutableMapOf<String, MutableList<Int>>()

    private val SIGNAL_LOSS_THRESHOLD_MS = 30000L

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setReportDelay(0)
        .build()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.address ?: return

            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Log.w(TAG_SERVICE, "Permission BLUETOOTH_CONNECT manquante pour obtenir le nom dans onScanResult.")
            }
            val deviceAddress = result.device.address
            val deviceName = result.device.name ?: "N/A"
            val rssi = result.rssi
            val txPower = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.txPower else ScanResult.TX_POWER_NOT_PRESENT

            serviceScope.launch {
                // CORRECTION ICI: Utilisation du nom de méthode mis à jour
                val enrolledBeacon = braceletRepository.getBraceletByAddress(deviceAddress)
                if (enrolledBeacon != null) {
                    Log.d(TAG_SERVICE, "Balise connue détectée: ${enrolledBeacon.assignedName} ($deviceAddress), RSSI: $rssi") // Utilisation de .name
                    updateBeaconState(enrolledBeacon, rssi, txPower)
                } else {
                    // Log.v(TAG_SERVICE, "Balise inconnue ignorée: $deviceAddress ($deviceName)")
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG_SERVICE, "onScanFailed: Code $errorCode")
            isBleScanPhysicallyActive = false
            currentScanJob?.cancel()
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) {
                Log.e(TAG_SERVICE, "SCAN_FAILED_ALREADY_STARTED - Problème de logique, un autre scan est actif.")
            }
        }
    }

    // ... (onCreate, onStartCommand, initializeBluetooth, startServiceLogic, startContinuousScan, performBleScanGuarded, stopContinuousScan restent les mêmes) ...
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG_SERVICE, "BleScanService onCreate")
        initializeBluetooth()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG_SERVICE, "BleScanService onStartCommand")
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        startServiceLogic()
        return START_STICKY
    }

    private fun initializeBluetooth() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG_SERVICE, "Bluetooth non supporté ou non activé.")
            stopSelf()
            return
        }
        bluetoothLeScanner = bluetoothAdapter!!.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            Log.e(TAG_SERVICE, "Impossible d'obtenir le BluetoothLeScanner.")
            stopSelf()
        }
    }

    private fun startServiceLogic() {
        if (bluetoothLeScanner == null) {
            Log.e(TAG_SERVICE, "Scanner non initialisé, tentative de réinitialisation.")
            initializeBluetooth()
            if (bluetoothLeScanner == null) {
                Log.e(TAG_SERVICE, "Échec de la réinitialisation du scanner. Arrêt du service.")
                stopSelf()
                return
            }
        }
        Log.d(TAG_SERVICE, "Démarrage de la logique du service (scan et vérification de perte de signal).")
        startContinuousScan()
        startSignalLossChecker()
    }


    private fun startContinuousScan() {
        currentScanJob?.cancel()
        currentScanJob = serviceScope.launch {
            while (isActive) {
                if (!isBleScanPhysicallyActive) {
                    if (performBleScanGuarded()) {
                        Log.d(TAG_SERVICE, "Scan BLE démarré/repris physiquement.")
                    } else {
                        Log.e(TAG_SERVICE, "Échec du démarrage/reprise du scan BLE physique.")
                        delay(5000)
                    }
                }
                delay(10000)
            }
        }
        Log.d(TAG_SERVICE, "Job de scan continu démarré.")
    }

    private fun performBleScanGuarded(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.e(TAG_SERVICE, "Permission BLUETOOTH_SCAN manquante pour démarrer le scan.")
            return false
        }
        if (bluetoothLeScanner == null) {
            Log.e(TAG_SERVICE, "BluetoothLeScanner est null. Impossible de scanner.")
            return false
        }
        if (isBleScanPhysicallyActive) {
            Log.d(TAG_SERVICE, "Scan déjà actif physiquement.")
            return true
        }

        try {
            bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
            isBleScanPhysicallyActive = true
            Log.i(TAG_SERVICE, "Scan BLE physique démarré.")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG_SERVICE, "SecurityException lors du démarrage du scan: ${e.message}", e)
            isBleScanPhysicallyActive = false
        } catch (e: IllegalStateException) {
            Log.e(TAG_SERVICE, "IllegalStateException lors du démarrage du scan: ${e.message}", e)
            isBleScanPhysicallyActive = false
        } catch (e: Exception) {
            Log.e(TAG_SERVICE, "Exception inattendue lors du démarrage du scan: ${e.message}", e)
            isBleScanPhysicallyActive = false
        }
        return false
    }

    private fun stopContinuousScan() {
        currentScanJob?.cancel()
        currentScanJob = null
        if (isBleScanPhysicallyActive) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Log.w(TAG_SERVICE, "Permission BLUETOOTH_SCAN manquante pour arrêter le scan (API ${Build.VERSION.SDK_INT}).")
                isBleScanPhysicallyActive = false
                return
            }
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
                Log.i(TAG_SERVICE, "Scan BLE physique arrêté.")
            } catch (e: SecurityException) {
                Log.e(TAG_SERVICE, "SecurityException lors de l'arrêt du scan: ${e.message}", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG_SERVICE, "IllegalStateException lors de l'arrêt du scan: ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG_SERVICE, "Exception inattendue lors de l'arrêt du scan: ${e.message}", e)
            } finally {
                isBleScanPhysicallyActive = false
            }
        }
    }


    private suspend fun updateBeaconState(beacon: BraceletEntity, rssi: Int, txPower: Int) {
        val currentTime = System.currentTimeMillis()
        lastSeenTimestamps[beacon.address] = currentTime

        val rssiList = rssiValuesMap.getOrPut(beacon.address) { mutableListOf() }
        rssiList.add(rssi)
        if (rssiList.size > 5) rssiList.removeAt(0)
        val smoothedRssi = rssiList.average().toInt()

        val actualTxPower = if (txPower != ScanResult.TX_POWER_NOT_PRESENT) txPower else beacon.txPowerAt1m ?: -59

        val distance = calculateDistance(smoothedRssi, actualTxPower)

        Log.d(TAG_SERVICE, "Balise ${beacon.assignedName}: RSSI $rssi (lissée $smoothedRssi), TxPower utilisé $actualTxPower, Distance estimée: $distance m") // Utilisation de .name

        // Cet appel est CORRECT car la méthode existe dans votre interface Repository
        braceletRepository.updateBeaconScanResult(
            address = beacon.address,
            rssi = smoothedRssi,
            distance = distance,
            lastSeen = currentTime,
            isVisible = true
        )
    }

    private fun startSignalLossChecker() {
        signalLossCheckJob?.cancel()
        signalLossCheckJob = serviceScope.launch {
            while (isActive) {
                delay(5000)
                checkSignalLossAndDistance()
            }
        }
        Log.d(TAG_SERVICE, "Job de vérification de perte de signal démarré.")
    }

    private suspend fun checkSignalLossAndDistance() {
        // CORRECTION ICI: Utilisation du nom de méthode mis à jour
        val enrolledBeacons = braceletRepository.getAllBraceletsOnce()
        val currentTime = System.currentTimeMillis()
        val alarmDistanceThreshold = sharedPreferences.getInt("alarm_distance_threshold", 30).toDouble()

        for (beacon in enrolledBeacons) {
            val lastSeen = lastSeenTimestamps[beacon.address] ?: beacon.lastSeenTimestamp // Assurez-vous que lastSeenTimestamp existe dans BraceletEntity et est mis à jour
            var triggerAlarm = false
            var alarmReason = ""

            val timeSinceLastSeen = currentTime - lastSeen
            // Assurez-vous que isSignalLost et isVisible sont des champs de votre BraceletEntity
            // et qu'ils sont correctement mis à jour par le DAO
            val currentSignalLost = timeSinceLastSeen > SIGNAL_LOSS_THRESHOLD_MS

            if (beacon.isSignalLost != currentSignalLost) {
                Log.d(TAG_SERVICE, "Balise ${beacon.assignedName}: état isSignalLost changé de ${beacon.isSignalLost} à $currentSignalLost (temps écoulé: $timeSinceLastSeen ms)") // Utilisation de .name
                braceletRepository.updateBeaconSignalLost(beacon.address, currentSignalLost) // CORRECT
                if (currentSignalLost && beacon.isCurrentlyVisible) { // Assurez-vous que isCurrentlyVisible existe dans BraceletEntity
                    triggerAlarm = true
                    alarmReason = "Signal perdu pour ${beacon.assignedName}" // Utilisation de .name
                }
            }

            // Assurez-vous que currentDistance et isOutOfRange sont des champs de BraceletEntity
            val currentOutOfRange = if (!currentSignalLost && beacon.isCurrentlyVisible) { // Assurez-vous que isCurrentlyVisible existe
                beacon.currentDistance > alarmDistanceThreshold // Assurez-vous que currentDistance existe
            } else {
                false
            }

            if (beacon.isOutOfRange != currentOutOfRange) { // Assurez-vous que isOutOfRange existe
                Log.d(TAG_SERVICE, "Balise ${beacon.assignedName}: état isOutOfRange changé de ${beacon.isOutOfRange} à $currentOutOfRange (distance: ${beacon.currentDistance}m, seuil: ${alarmDistanceThreshold}m)") // Utilisation de .name et currentDistance
                braceletRepository.updateBeaconOutOfRange(beacon.address, currentOutOfRange) // CORRECT
                if (currentOutOfRange) {
                    triggerAlarm = true
                    alarmReason = if (alarmReason.isNotEmpty()) "$alarmReason ET Hors de portée" else "Hors de portée pour ${beacon.assignedName}" // Utilisation de .name
                }
            }

            if (currentSignalLost && beacon.isCurrentlyVisible) { // Assurez-vous que isCurrentlyVisible existe
                braceletRepository.updateBeaconVisibility(beacon.address, false) // CORRECT
            }

            val isAlarmSilenced = sharedPreferences.getBoolean("is_alarm_silenced", false)
            if (triggerAlarm && !isAlarmSilenced) {
                Log.w(TAG_SERVICE, "ALARME: $alarmReason")
                alarmSoundPlayer.playAlarm()
                sendAlarmNotification(beacon, alarmReason)
            }
        }
    }

    private fun calculateDistance(rssi: Int, txPowerAt1m: Int): Double {
        if (rssi == 0) return -1.0
        val ratio = rssi * 1.0 / txPowerAt1m
        return if (ratio < 1.0) {
            Math.pow(ratio, 10.0)
        } else {
            (0.89976) * Math.pow(ratio, 7.7095) + 0.111
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG_SERVICE, "BleScanService onDestroy")
        stopContinuousScan()
        signalLossCheckJob?.cancel()
        serviceJob.cancel()
        alarmSoundPlayer.stopAlarm()
    }

    private fun createNotification(): Notification {
        val channelId = "BLE_SCAN_SERVICE_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "Canal du Service de Scan BLE",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Surveillance des Balises Active")
            .setContentText("Scan BLE en cours...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun sendAlarmNotification(beacon: BraceletEntity, reason: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alarmChannelId = "BLE_ALARM_CHANNEL"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alarmChannel = NotificationChannel(
                alarmChannelId,
                "Alertes Balises BLE",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications pour les alarmes de balises BLE"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            notificationManager.createNotificationChannel(alarmChannel)
        }

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val pendingIntent = PendingIntent.getActivity(this, beacon.address.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, alarmChannelId)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Alerte Balise: ${beacon.assignedName}") // Utilisation de .name
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(beacon.address.hashCode(), notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG_SERVICE = "BleScanService"
        private const val NOTIFICATION_ID = 12345
    }
}