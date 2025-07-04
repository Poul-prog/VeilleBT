package com.martin.veillebt.data.local.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.martin.veillebt.data.local.model.BraceletEntity

@Dao
interface BraceletDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBracelet(bracelet: BraceletEntity)

    @Query("SELECT * FROM bracelets ORDER BY name ASC")
    fun getAllBracelets(): LiveData<List<BraceletEntity>> // Observable par l'UI

    @Query("SELECT * FROM bracelets ORDER BY name ASC")
    suspend fun getAllBraceletsSuspend(): List<BraceletEntity> // Pour usage dans coroutines

    @Query("SELECT * FROM bracelets WHERE address = :address")
    suspend fun getBraceletByAddress(address: String): BraceletEntity?

    @Delete
    suspend fun deleteBracelet(bracelet: BraceletEntity)

    @Query("DELETE FROM bracelets WHERE address = :address")
    suspend fun deleteBraceletByAddress(address: String)

    @Query("UPDATE bracelets SET name = :newName WHERE address = :address")
    suspend fun updateBraceletName(address: String, newName: String)

    @Query("DELETE FROM bracelets")
    suspend fun clearAllBracelets()
}