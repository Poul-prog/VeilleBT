package com.martin.veillebt.data.local.db

import androidx.room.*
import com.martin.veillebt.data.local.model.BraceletEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BraceletDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBracelet(bracelet: BraceletEntity)

    @Update
    suspend fun updateBracelet(bracelet: BraceletEntity) // Utile pour les mises à jour générales

    @Query("SELECT * FROM bracelets WHERE address = :address")
    suspend fun getBraceletByAddress(address: String): BraceletEntity?

    @Query("SELECT * FROM bracelets ORDER BY assignedName ASC") // Ou un autre ordre
    fun getAllBracelets(): Flow<List<BraceletEntity>>

    @Query("SELECT * FROM bracelets")
    suspend fun getAllBraceletsOnce(): List<BraceletEntity>

    @Query("DELETE FROM bracelets WHERE address = :address")
    suspend fun deleteBraceletByAddress(address: String) // Assurez-vous que le nom correspond à l'appelant

    // --- NOUVELLE MÉTHODE À AJOUTER (Option A) ---
    @Query("UPDATE bracelets SET currentRssi = :rssi, currentDistance = :distance, lastSeenTimestamp = :lastSeen, isCurrentlyVisible = :isVisible WHERE address = :address")
    suspend fun updateScanData(address: String, rssi: Int, distance: Double, lastSeen: Long, isVisible: Boolean)

    @Query("UPDATE bracelets SET isSignalLost = :isSignalLost WHERE address = :address")
    suspend fun updateSignalLostStatus(address: String, isSignalLost: Boolean)

    @Query("UPDATE bracelets SET isOutOfRange = :isOutOfRange WHERE address = :address")
    suspend fun updateOutOfRangeStatus(address: String, isOutOfRange: Boolean)

    @Query("UPDATE bracelets SET isCurrentlyVisible = :isVisible WHERE address = :address") // Notez le nom de la colonne
    suspend fun updateVisibilityStatus(address: String, isVisible: Boolean)

    // Si vous avez besoin de mettre à jour d'autres champs :
    // @Query("UPDATE bracelets SET name = :newName WHERE address = :address")
    // suspend fun updateName(address: String, newName: String)

    // @Query("UPDATE bracelets SET tx_power_at_1m = :txPower WHERE address = :address")
    // suspend fun updateTxPower(address: String, txPower: Int)
}
