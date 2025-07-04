// Dans com/martin/veillebt/data/repository/BraceletRepositoryImpl.kt
package com.martin.veillebt.data.repository

import com.martin.veillebt.data.local.db.BraceletDao
import com.martin.veillebt.data.local.model.BraceletEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

class BraceletRepositoryImpl @Inject constructor(
    private val braceletDao: BraceletDao
) : BraceletRepository {

    override suspend fun addBracelet(bracelet: BraceletEntity) {
        // Envelopper les opérations de base de données dans un contexte IO est une bonne pratique
        // si votre DAO n'utilise pas déjà les dispatchers en interne (la plupart le font avec suspend)
        withContext(Dispatchers.IO) {
            braceletDao.addBracelet(bracelet)
        }
    }

    override suspend fun getBraceletByAddress(address: String): BraceletEntity? {
        return withContext(Dispatchers.IO) {
            braceletDao.getBraceletByAddress(address)
        }
    }

    override fun getAllBracelets(): Flow<List<BraceletEntity>> {
        // Les fonctions Flow de Room s'exécutent généralement sur un dispatcher approprié.
        return braceletDao.getAllBracelets()
    }

    override suspend fun deleteBraceletByAddress(address: String) {
        withContext(Dispatchers.IO) {
            // Assurez-vous que votre DAO a la méthode correspondante
            // Par exemple, si le DAO s'appelle deleteBracelet :
            braceletDao.deleteBracelet(address)
        }
    }

    override suspend fun getAllBraceletsSuspend(): List<BraceletEntity> { // NOUVELLE FONCTION
        return withContext(Dispatchers.IO) { // Bonne pratique pour les appels suspendus au DAO
            braceletDao.getAllBraceletsSuspend()
            // Implémentez les autres méthodes du code que vous aviez (getAllBraceletsSuspend, updateBraceletName, etc.)
            // en utilisant withContext(Dispatchers.IO) pour les opérations suspendues.
        }
    }
}