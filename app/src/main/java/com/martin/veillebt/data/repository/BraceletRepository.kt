// Dans com/martin/veillebt/data/repository/BraceletRepository.kt
package com.martin.veillebt.data.repository

import com.martin.veillebt.data.local.model.BraceletEntity
import kotlinx.coroutines.flow.Flow

interface BraceletRepository {

    // SUPPRIMER TOUT CONSTRUCTEUR D'ICI

    suspend fun addBracelet(bracelet: BraceletEntity)

    suspend fun getBraceletByAddress(address: String): BraceletEntity?

    fun getAllBracelets(): Flow<List<BraceletEntity>>

    suspend fun deleteBraceletByAddress(address: String)

    suspend fun getAllBraceletsSuspend(): List<BraceletEntity> // NOUVELLE FONCTION

    // ... et les autres méthodes que vous avez définies dans le code que vous m'avez montré plus tôt.
    // Par exemple :
    // suspend fun updateBraceletName(address: String, newName: String)
    // suspend fun clearAllBracelets()
}
