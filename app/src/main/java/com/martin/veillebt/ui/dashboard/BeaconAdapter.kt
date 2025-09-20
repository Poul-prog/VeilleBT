package com.martin.veillebt.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.semantics.text
// import androidx.compose.ui.semantics.text // Cet import n'est pas utilisé ici, peut être enlevé
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.martin.veillebt.R
import com.martin.veillebt.data.local.model.BraceletEntity // IMPORTATION CHANGÉE

// MODIFICATION: Utiliser BraceletEntity ici
class BeaconAdapter : ListAdapter<BraceletEntity, BeaconAdapter.BeaconViewHolder>(BraceletEntityDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BeaconViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_monitored_beacon, parent, false)
        return BeaconViewHolder(view)
    }

    override fun onBindViewHolder(holder: BeaconViewHolder, position: Int) {
        val beacon = getItem(position) // getItem() retourne maintenant un BraceletEntity
        holder.bind(beacon)
    }

    class BeaconViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.tvBeaconName)
        private val rssiTextView: TextView = itemView.findViewById(R.id.tvBeaconSignal)
        private val distanceTextView: TextView = itemView.findViewById(R.id.tvBeaconDistance)
        // Vous pourriez vouloir afficher d'autres infos de BraceletEntity comme isSignalLost, isOutOfRange

        // MODIFICATION: La méthode bind attend maintenant un BraceletEntity
        fun bind(beacon: BraceletEntity) {
            nameTextView.text = beacon.assignedName ?: "N/A" // Utiliser assignedName de BraceletEntity, gérer la nullité
            rssiTextView.text = "RSSI: ${beacon.currentRssi}" // Utiliser currentRssi de BraceletEntity
            distanceTextView.text = if (beacon.currentDistance >= 0) { // Adapter la condition si -1.0 est utilisé pour "N/A"
                "Distance: ${"%.2f".format(beacon.currentDistance)} m"
            } else {
                "Distance: N/A"
            }

            // Exemple pour afficher l'état de perte de signal (ajoutez un TextView pour cela dans item_monitored_beacon.xml)
            // val statusTextView: TextView = itemView.findViewById(R.id.tvBeaconStatus)
            // if (beacon.isSignalLost) {
            //     statusTextView.text = "Signal Perdu"
            //     statusTextView.setTextColor(itemView.context.getColor(R.color.colorWarning)) // Définissez une couleur
            // } else if (beacon.isOutOfRange) {
            //     statusTextView.text = "Hors de Portée"
            //     statusTextView.setTextColor(itemView.context.getColor(R.color.colorWarning))
            // } else {
            //     statusTextView.text = "Connecté"
            //     statusTextView.setTextColor(itemView.context.getColor(R.color.colorOk)) // Définissez une couleur
            // }
        }
    }
}

// MODIFICATION: DiffCallback pour BraceletEntity
class BraceletEntityDiffCallback : DiffUtil.ItemCallback<BraceletEntity>() {
    override fun areItemsTheSame(oldItem: BraceletEntity, newItem: BraceletEntity): Boolean {
        return oldItem.address == newItem.address // L'adresse est une bonne clé unique
    }

    override fun areContentsTheSame(oldItem: BraceletEntity, newItem: BraceletEntity): Boolean {
        return oldItem == newItem // Data class compare tous les champs
    }
}

