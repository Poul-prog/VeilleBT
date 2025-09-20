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
        return braceletDao.getAllBracelets()
    }

    override suspend fun deleteBraceletByAddress(address: String) {
        withContext(Dispatchers.IO) {
            braceletDao.deleteBraceletByAddress(address) // Assurez-vous que le nom correspond dans le DAO
        }
    }

    override suspend fun getAllBraceletsOnce(): List<BraceletEntity> {
        return withContext(Dispatchers.IO) {
            braceletDao.getAllBraceletsOnce() // Assurez-vous que le nom correspond dans le DAO
        }
    }

    // --- NOUVELLE MÉTHODE IMPLÉMENTÉE ---
    override suspend fun updateBeaconScanResult(
        address: String,
        rssi: Int,
        distance: Double,
        lastSeen: Long,
        isVisible: Boolean
    ) {
        withContext(Dispatchers.IO) {
            // Vous aurez besoin d'une méthode dans votre DAO pour cela.
            // Par exemple, elle pourrait mettre à jour directement l'entité
            // ou des champs spécifiques.
            // Si vous mettez à jour plusieurs champs, il est souvent plus simple
            // de récupérer l'entité, la modifier, puis la remettre à jour.
            // Ou, si vous voulez être plus performant, mettre à jour uniquement les champs nécessaires.

            // Option 1: Mettre à jour des champs spécifiques (nécessite une requête @Query spécifique dans le DAO)
            braceletDao.updateScanData(address, rssi, distance, lastSeen, isVisible)

            // Option 2: Récupérer, modifier, mettre à jour l'entité (plus simple si le DAO ne fait que @Update)
            /*
            val beacon = braceletDao.getBraceletByAddress(address)
            if (beacon != null) {
                val updatedBeacon = beacon.copy(
                    // Attention: si 'name' et 'txPowerAt1m' sont dans BraceletEntity,
                    // assurez-vous de ne pas les écraser avec null si vous ne les mettez pas à jour ici.
                    // Il est préférable d'avoir des méthodes DAO dédiées pour mettre à jour des champs spécifiques.
                    // Pour l'instant, je vais supposer que vous avez des champs pour rssi, distance, etc.
                    // dans BraceletEntity ou que vous allez les ajouter.
                    // Pour l'instant, je crée des champs hypothétiques dans l'entité.
                    // Vous devrez ajuster BraceletEntity en conséquence.

                    // Supposons que BraceletEntity ait ces champs :
                    // currentRssi: Int,
                    // currentDistance: Double,
                    // lastSeenTimestamp: Long,
                    // isCurrentlyVisible: Boolean

                    currentRssi = rssi,
                    currentDistance = distance,
                    lastSeenTimestamp = lastSeen,
                    isCurrentlyVisible = isVisible
                )
                braceletDao.updateBracelet(updatedBeacon) // Supposant que vous avez une méthode updateBracelet(entity)
            }
            */
        }
    }

    override suspend fun updateBeaconSignalLost(address: String, isSignalLost: Boolean) {
        withContext(Dispatchers.IO) {
            braceletDao.updateSignalLostStatus(address, isSignalLost)
        }
    }

    override suspend fun updateBeaconOutOfRange(address: String, isOutOfRange: Boolean) {
        withContext(Dispatchers.IO) {
            braceletDao.updateOutOfRangeStatus(address, isOutOfRange)
        }
    }

    override suspend fun updateBeaconVisibility(address: String, isVisible: Boolean) {
        withContext(Dispatchers.IO) {
            braceletDao.updateVisibilityStatus(address, isVisible)
        }
    }
}
