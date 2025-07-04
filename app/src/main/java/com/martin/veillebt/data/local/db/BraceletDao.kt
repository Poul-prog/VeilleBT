// Dans com/martin/veillebt/data/local/dao/BraceletDao.kt
package com.martin.veillebt.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.martin.veillebt.data.local.model.BraceletEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BraceletDao {

    // Pour APPEL 1: braceletDao.addBracelet(bracelet)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBracelet(bracelet: BraceletEntity) // DOIT correspondre : suspend, nom, paramètre

    // Pour une méthode getBraceletByAddress (que vous aviez aussi)
    @Query("SELECT * FROM bracelets WHERE address = :address")
    suspend fun getBraceletByAddress(address: String): BraceletEntity?

    // Pour APPEL 2: braceletDao.getAllBracelets()
    @Query("SELECT * FROM bracelets ORDER BY name ASC")
    fun getAllBracelets(): Flow<List<BraceletEntity>> // DOIT correspondre : nom, type de retour Flow

    // Pour APPEL 3: braceletDao.deleteBracelet(address)
    @Query("DELETE FROM bracelets WHERE address = :address")
    suspend fun deleteBracelet(address: String) // DOIT correspondre : suspend, nom, paramètre

    @Query("SELECT * FROM bracelets ORDER BY name ASC")
    suspend fun getAllBraceletsSuspend(): List<BraceletEntity> // NOUVELLE FONCTION

    // Ajoutez d'autres méthodes si nécessaire
}

