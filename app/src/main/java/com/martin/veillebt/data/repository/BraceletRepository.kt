package com.martin.veillebt.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.martin.veillebt.data.local.db.AppDatabase
import com.martin.veillebt.data.local.db.BraceletDao
import com.martin.veillebt.data.local.model.BraceletEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository pour gérer les données des bracelets.
 * Il sert d'intermédiaire entre les ViewModels (ou UseCases) et les sources de données (ici, Room).
 */
interface BraceletRepository(private val braceletDao: BraceletDao) {

    // Constructeur secondaire pour faciliter l'instanciation depuis un contexte
    // (utile si vous n'utilisez pas d'injection de dépendances comme Hilt)
    // Pour Hilt, vous injecteriez directement BraceletDao.
    constructor(context: Context) : this(AppDatabase.getDatabase(context).braceletDao())

    /**
     * Récupère tous les bracelets enregistrés sous forme de LiveData,
     * ce qui permet à l'UI de réagir aux changements.
     */
    fun getAllBracelets(): LiveData<List<BraceletEntity>> {
        return braceletDao.getAllBracelets()
    }

    /**
     * Récupère tous les bracelets enregistrés de manière synchrone (dans une coroutine).
     * Utile pour les opérations en arrière-plan ou dans les ViewModels.
     */
    suspend fun getAllBraceletsSuspend(): List<BraceletEntity> {
        return withContext(Dispatchers.IO) {
            braceletDao.getAllBraceletsSuspend()
        }
    }

    /**
     * Ajoute un nouveau bracelet à la base de données.
     */
    suspend fun addBracelet(bracelet: BraceletEntity) {
        withContext(Dispatchers.IO) {
            braceletDao.insertBracelet(bracelet)
        }
    }

    /**
     * Ajoute un bracelet en utilisant son adresse et son nom.
     * Crée l'entité BraceletEntity en interne.
     */
    suspend fun addBracelet(address: String, name: String?) {
        withContext(Dispatchers.IO) {
            val newBracelet = BraceletEntity(address = address, name = name)
            braceletDao.insertBracelet(newBracelet)
        }
    }


    /**
     * Récupère un bracelet spécifique par son adresse MAC.
     */
    suspend fun getBraceletByAddress(address: String): BraceletEntity? {
        return withContext(Dispatchers.IO) {
            braceletDao.getBraceletByAddress(address)
        }
    }

    /**
     * Met à jour le nom d'un bracelet existant.
     */
    suspend fun updateBraceletName(address: String, newName: String) {
        withContext(Dispatchers.IO) {
            braceletDao.updateBraceletName(address, newName)
        }
    }

    /**
     * Supprime un bracelet spécifique de la base de données.
     */
    suspend fun deleteBracelet(bracelet: BraceletEntity) {
        withContext(Dispatchers.IO) {
            braceletDao.deleteBracelet(bracelet)
        }
    }

    /**
     * Supprime un bracelet par son adresse MAC.
     */
    suspend fun deleteBraceletByAddress(address: String) {
        withContext(Dispatchers.IO) {
            braceletDao.deleteBraceletByAddress(address)
        }
    }

    /**
     * Supprime tous les bracelets de la base de données.
     * À utiliser avec prudence !
     */
    suspend fun clearAllBracelets() {
        withContext(Dispatchers.IO) {
            braceletDao.clearAllBracelets()
        }
    }
}