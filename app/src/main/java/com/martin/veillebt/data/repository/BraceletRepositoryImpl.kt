// Dans com/martin/veillebt/data/repository/BraceletRepositoryImpl.kt
package com.martin.veillebt.data.repository

import com.martin.veillebt.data.local.db.BraceletDao // Assurez-vous du chemin correct
import com.martin.veillebt.data.local.model.BraceletEntity // Assurez-vous du chemin correct
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject // Important pour l'injection par Hilt si vous le souhaitez
import javax.inject.Singleton // Si vous voulez que l'implémentation soit un singleton

// @Singleton // Optionnel ici, car le @Provides dans AppModule peut le gérer.
// Si vous annotez ici avec @Singleton, Hilt saura le rendre singleton
// même si le @Provides dans AppModule ne le spécifie pas explicitement pour l'implémentation.
class BraceletRepositoryImpl @Inject constructor( // @Inject constructor permet à Hilt de créer cette classe
    private val braceletDao: BraceletDao
    // Injectez d'autres sources de données ici si nécessaire (ex: ApiService)
) : BraceletRepository { // Implémente l'interface

    override suspend fun addBracelet(bracelet: BraceletEntity) {
        braceletDao.addBracelet(bracelet)
    }

    override suspend fun getBraceletByAddress(address: String): BraceletEntity? {
        return braceletDao.getBraceletByAddress(address)
    }

    override fun getAllBracelets(): Flow<List<BraceletEntity>> {
        return braceletDao.getAllBracelets()
    }

    override suspend fun deleteBraceletByAddress(address: String) {
        braceletDao.deleteBracelet(address) // Assurez-vous que cette méthode existe dans votre DAO
    }

    // Implémentez les autres méthodes de l'interface
    // override suspend fun updateBraceletName(address: String, newName: String) { ... }
    // override suspend fun clearAllBracelets() { ... }
}