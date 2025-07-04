package com.martin.veillebt.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bracelets")
data class BraceletEntity(
    @PrimaryKey val address: String, // L'adresse MAC est une bonne clé primaire
    val name: String?,
    val enrolledTimestamp: Long = System.currentTimeMillis()
    // Vous pouvez ajouter d'autres champs si nécessaire, comme la couleur, l'icône, etc.
)