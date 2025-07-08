package com.martin.veillebt.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.semantics.text
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.martin.veillebt.R // Assurez-vous que R est correctement importé
import com.martin.veillebt.ui.dashboard.MonitoredBeacon // Votre data class

class BeaconAdapter : ListAdapter<MonitoredBeacon, BeaconAdapter.BeaconViewHolder>(BeaconDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BeaconViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_monitored_beacon, parent, false)
        return BeaconViewHolder(view)
    }

    override fun onBindViewHolder(holder: BeaconViewHolder, position: Int) {
        val beacon = getItem(position)
        holder.bind(beacon)
    }

    class BeaconViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.tvBeaconName)
        //private val addressTextView: TextView = itemView.findViewById(R.id.tvBeaconAddress)
        private val rssiTextView: TextView = itemView.findViewById(R.id.tvBeaconSignal)
        private val distanceTextView: TextView = itemView.findViewById(R.id.tvBeaconDistance)

        fun bind(beacon: MonitoredBeacon) {
            nameTextView.text = beacon.assignedName
            //addressTextView.text = "Adresse: ${beacon.address}"
            rssiTextView.text = "RSSI: ${beacon.rssi}"
            distanceTextView.text = if (beacon.distance != null) "Distance: ${"%.2f".format(beacon.distance)} m" else "Distance: N/A"
            // Mettez à jour d'autres états ici (ex: couleur de fond si signal perdu)
        }
    }
}

class BeaconDiffCallback : DiffUtil.ItemCallback<MonitoredBeacon>() {
    override fun areItemsTheSame(oldItem: MonitoredBeacon, newItem: MonitoredBeacon): Boolean {
        return oldItem.address == newItem.address
    }

    override fun areContentsTheSame(oldItem: MonitoredBeacon, newItem: MonitoredBeacon): Boolean {
        return oldItem == newItem // Data class compare tous les champs
    }
}
