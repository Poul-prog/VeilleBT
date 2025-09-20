package com.martin.veillebt.data.repository

import com.martin.veillebt.data.local.model.BraceletEntity
import kotlinx.coroutines.flow.Flow

interface BraceletRepository {
    suspend fun addBracelet(bracelet: BraceletEntity)
    suspend fun getBraceletByAddress(address: String): BraceletEntity? // Renommé pour clarté, anciennement getBeaconByAddress
    fun getAllBracelets(): Flow<List<BraceletEntity>>
    suspend fun deleteBraceletByAddress(address: String)
    suspend fun getAllBraceletsOnce(): List<BraceletEntity> // Anciennement getAllBeaconsOnce

    // --- NOUVELLE MÉTHODE À AJOUTER ---
    suspend fun updateBeaconScanResult(
        address: String,
        rssi: Int,
        distance: Double,
        lastSeen: Long,
        isVisible: Boolean
    )

    suspend fun updateBeaconSignalLost(address: String, isSignalLost: Boolean)
    suspend fun updateBeaconOutOfRange(address: String, isOutOfRange: Boolean)
    suspend fun updateBeaconVisibility(address: String, isVisible: Boolean)

    // Si vous aviez d'autres méthodes comme celles-ci, assurez-vous qu'elles sont aussi déclarées
    // suspend fun updateBraceletName(address: String, newName: String)
    // suspend fun updateTxPower(address: String, txPower: Int)
}
