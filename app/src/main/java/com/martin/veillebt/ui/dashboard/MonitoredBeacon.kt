package com.martin.veillebt.ui.dashboard // Ou votre package approprié

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize // Pour pouvoir passer des instances entre composants (ex: Fragments, Activities) si nécessaire
data class MonitoredBeacon(
    val address: String, // Adresse MAC unique de la balise
    var assignedName: String, // Nom donné par l'utilisateur lors de l'enrôlement
    var originalName: String?, // Nom original de l'appareil BLE (peut être null)
    var rssi: Int = 0, // Indicateur de force du signal reçu (Received Signal Strength Indicator)
    var distance: Double? = null, // Distance estimée en mètres (peut être null si RSSI trop faible)
    var lastSeenTimestamp: Long = System.currentTimeMillis(), // Quand la balise a été vue pour la dernière fois
    var isVisible: Boolean = true, // L'utilisateur veut-il voir cette balise dans la liste principale ? (peut être géré différemment)
    var isSignalLost: Boolean = false, // Vrai si le signal est considéré comme perdu (timeout)
    var isOutOfRange: Boolean = false, // Vrai si la distance estimée dépasse un certain seuil
    val txPowerAt1m: Int? = null, // Puissance de transmission calibrée à 1 mètre (si disponible et utilisé pour le calcul de distance)

    // Optionnel: Champs pour la gestion des alarmes spécifiques à cette balise
    var hasActiveSignalLostAlarm: Boolean = false,
    var hasActiveDistanceAlarm: Boolean = false
) : Parcelable {

    // Constructeur secondaire si vous voulez initialiser avec moins de champs au début
    constructor(address: String, assignedName: String, originalName: String? = null, txPowerAt1m: Int? = null) : this(
        address = address,
        assignedName = assignedName,
        originalName = originalName,
        rssi = -100, // Valeur initiale indiquant un signal faible ou inconnu
        distance = null,
        lastSeenTimestamp = System.currentTimeMillis(),
        isVisible = true,
        isSignalLost = true, // On considère perdu jusqu'à la première détection
        isOutOfRange = false, // Initialement, on ne sait pas si c'est hors de portée
        txPowerAt1m = txPowerAt1m,
        hasActiveSignalLostAlarm = false,
        hasActiveDistanceAlarm = false
    )

    // Vous pourriez ajouter des fonctions utilitaires ici si nécessaire, par exemple :
    // fun hasBeenSeenRecently(timeoutMillis: Long = 30000): Boolean {
    //     return (System.currentTimeMillis() - lastSeenTimestamp) < timeoutMillis
    // }

    // L'égalité et le hashCode sont automatiquement générés pour les data classes,
    // basés sur les propriétés déclarées dans le constructeur primaire.
    // Si vous avez besoin d'une logique d'égalité personnalisée (par exemple, basée uniquement sur l'adresse MAC),
    // vous devriez surcharger equals() et hashCode().
    // Mais pour ListAdapter et DiffUtil, l'égalité par défaut des data classes est souvent ce que l'on veut
    // pour déterminer si les *contenus* ont changé. L'adresse MAC est utilisée pour areItemsTheSame.
}
