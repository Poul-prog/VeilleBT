package com.martin.veillebt.data.local.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bracelets")
data class BraceletEntity(
    @PrimaryKey val address: String, // L'adresse MAC est une bonne clé primaire
    val name: String?,
    val enrolledTimestamp: Long = System.currentTimeMillis(),
    // Champ ajouté pour la puissance de transmission calibrée à 1 mètre
    // Il est nullable car il se peut que vous ne l'ayez pas pour toutes les balises
    // ou que vous souhaitiez le définir plus tard.
    @ColumnInfo(name = "tx_power_at_1m") // Nom de la colonne dans la base de données (optionnel mais bonne pratique)
    val txPowerAt1m: Int? = null
    // Vous pouvez ajouter d'autres champs si nécessaire, comme la couleur, l'icône, etc.
)
