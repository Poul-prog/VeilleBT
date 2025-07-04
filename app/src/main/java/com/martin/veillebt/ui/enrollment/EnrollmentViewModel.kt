package com.martin.veillebt.ui.enrollment // Ou votre package de ViewModel

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.size
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.martin.veillebt.data.model.BleDevice
import com.martin.veillebt.data.local.model.BraceletEntity
import com.martin.veillebt.data.repository.BraceletRepository
import kotlin.collections.sortByDescending
import androidx.lifecycle.viewModelScope // Pour lancer des coroutines
import kotlinx.coroutines.launch         // Pour lancer des coroutines
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class EnrollmentViewModel @Inject constructor(
    application: Application,
    private val braceletRepository: BraceletRepository // Injection du Repository
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) { // <--- FIN DE LA DÉCLARATION DE CLASSE, L'ACCOLADE OUVRE LE CORPS

    // === TOUT LE CORPS DE VOTRE VIEWMODEL VIENT ICI ===
    private val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _detectedDevices = MutableLiveData<List<BleDevice>>(emptyList())
    val detectedDevices: LiveData<List<BleDevice>> = _detectedDevices

    private val _candidateDevice = MutableLiveData<BleDevice?>()
    val candidateDevice: LiveData<BleDevice?> = _candidateDevice

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage

    private val _enrolledDevicesThisSession = MutableLiveData<MutableList<BleDevice>>(mutableListOf())
    val enrolledDevicesThisSession: LiveData<MutableList<BleDevice>> = _enrolledDevicesThisSession

    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanStopDelayMillis = 10000L
    private var isContinuousScan = false

    private val RSSI_THRESHOLD_DIFFERENCE = 10
    private val MIN_RSSI_VALID = -80

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { device ->
                val isAlreadyEnrolledInSession = _enrolledDevicesThisSession.value?.any { it.address == device.address } == true
                if (!isAlreadyEnrolledInSession && result.rssi > MIN_RSSI_VALID) {
                    val existingDeviceIndex = _detectedDevices.value?.indexOfFirst { it.address == device.address } ?: -1
                    val currentList = _detectedDevices.value?.toMutableList() ?: mutableListOf()
                    if (existingDeviceIndex != -1) {
                        currentList[existingDeviceIndex] = BleDevice(device, result.rssi, currentList[existingDeviceIndex].assignedName)
                    } else {
                        currentList.add(BleDevice(device, result.rssi))
                    }
                    currentList.sortByDescending { it.rssi }
                    _detectedDevices.postValue(currentList)
                    evaluateCandidateDevice(currentList)
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { result ->
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            _statusMessage.postValue("Erreur de scan BLE: $errorCode")
            _isScanning.postValue(false)
        }
    }

    private fun evaluateCandidateDevice(devices: List<BleDevice>) {
        if (devices.isEmpty()) {
            _candidateDevice.postValue(null)
            _statusMessage.postValue("Approchez une balise...")
            return
        }
        val strongestDevice = devices.first()
        if (devices.size > 1) {
            val secondStrongestDevice = devices[1]
            if (strongestDevice.rssi - secondStrongestDevice.rssi < RSSI_THRESHOLD_DIFFERENCE) {
                _candidateDevice.postValue(null)
                _statusMessage.postValue("Plusieurs balises détectées avec une force similaire. Isolez la balise à enregistrer.")
                return
            }
        }
        _candidateDevice.postValue(strongestDevice)
        _statusMessage.postValue("Balise candidate : ${strongestDevice.originalName ?: strongestDevice.address}")
    }

    fun startBleScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _statusMessage.postValue("Bluetooth n'est pas activé.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    getApplication(), Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                _statusMessage.postValue("Permission BLUETOOTH_SCAN manquante.")
                return
            }
        }
        if (_isScanning.value == true && isContinuousScan) return
        val filters = mutableListOf<ScanFilter>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bluetoothLeScanner?.startScan(filters, settings, scanCallback)
        _isScanning.postValue(true)
        isContinuousScan = true
        _statusMessage.postValue("Scan en cours... Approchez une balise.")
    }

    fun stopBleScan(triggeredByTimeout: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    getApplication(), Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bluetoothLeScanner?.stopScan(scanCallback)
        _isScanning.postValue(false)
        if (!triggeredByTimeout) {
            isContinuousScan = false
            scanHandler.removeCallbacksAndMessages(null)
        }
        if (isContinuousScan && triggeredByTimeout) {
            isContinuousScan = false
        }
    }

    fun assignNameToCandidate(name: String) {
        _candidateDevice.value?.let { currentCandidate ->
            if (name.isNotBlank()) {
                currentCandidate.assignedName = name
                viewModelScope.launch {
                    try {
                        val braceletEntity = BraceletEntity(
                            address = currentCandidate.address,
                            name = currentCandidate.assignedName,
                        )
                        // CET APPEL DEVRAIT MAINTENANT FONCTIONNER CAR braceletRepository EST MEMBRE DE CETTE CLASSE
                        braceletRepository.addBracelet(braceletEntity)
                        Log.d("EnrollmentVM", "Balise enregistrée dans Room: Nom=${braceletEntity.name}, Adresse=${braceletEntity.address}")

                        val updatedSessionList = _enrolledDevicesThisSession.value?.toMutableList() ?: mutableListOf()
                        if (!updatedSessionList.any { it.address == currentCandidate.address }) {
                            updatedSessionList.add(currentCandidate)
                        } else {
                            val index = updatedSessionList.indexOfFirst { it.address == currentCandidate.address }
                            if (index != -1) {
                                updatedSessionList[index] = currentCandidate
                            }
                        }
                        _enrolledDevicesThisSession.postValue(updatedSessionList)
                        _statusMessage.postValue("Balise '${name}' enregistrée (${currentCandidate.address}).")
                        _candidateDevice.postValue(null)
                        _detectedDevices.postValue(emptyList())
                    } catch (e: Exception) {
                        Log.e("EnrollmentVM", "Erreur lors de la sauvegarde de la balise dans Room: ${e.message}", e)
                        _statusMessage.postValue("Erreur lors de l'enregistrement de la balise.")
                    }
                }
            } else {
                _statusMessage.postValue("Veuillez entrer un nom pour la balise.")
            }
        }
    }

    fun prepareForNextBeacon() {
        if (_isScanning.value == false || !isContinuousScan) {
            startBleScan()
        }
    }

    fun finishEnrollment() {
        stopBleScan()
        val enrolled = _enrolledDevicesThisSession.value
        if (enrolled.isNullOrEmpty()) {
            _statusMessage.postValue("Aucune balise n'a été enregistrée.")
        } else {
            _statusMessage.postValue("Enregistrement terminé. ${enrolled.size} balise(s) enregistrée(s).")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopBleScan()
    }

}