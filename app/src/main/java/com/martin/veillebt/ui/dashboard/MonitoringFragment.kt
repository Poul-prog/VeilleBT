package com.martin.veillebt.ui.dashboard

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.semantics.text
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.glance.visibility
import androidx.recyclerview.widget.LinearLayoutManager
import com.martin.veillebt.R // Pour les ressources (ex: strings)
import com.martin.veillebt.data.local.model.BraceletEntity // IMPORTATION DU MODÈLE
import com.martin.veillebt.databinding.FragmentMonitoringBinding
import com.martin.veillebt.service.BleScanService // CHEMIN VERS VOTRE SERVICE
import dagger.hilt.android.AndroidEntryPoint
import kotlin.io.path.name

@AndroidEntryPoint
class MonitoringFragment : Fragment() {

    private val viewModel: MonitoringViewModel by viewModels()
    private var _binding: FragmentMonitoringBinding? = null
    private val binding get() = _binding!!
    private lateinit var monitoredBeaconAdapter: BeaconAdapter // Assurez-vous que BeaconAdapter est défini

    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(Manifest.permission.FOREGROUND_SERVICE)
        }
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        //     add(Manifest.permission.ACCESS_BACKGROUND_LOCATION) // Décommentez si nécessaire
        // }
    }.toTypedArray()

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) {
                Log.d("MonitoringFragment", "Toutes les permissions requises sont accordées.")
                updateUiBasedOnServiceState()
            } else {
                Log.w("MonitoringFragment", "Certaines permissions ont été refusées.")
                Toast.makeText(requireContext(), "Permissions requises pour la surveillance.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMonitoringBinding.inflate(inflater, container, false)
        Log.d("MonitoringFragment", "onCreateView: Binding initialisé.")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("MonitoringFragment", "onViewCreated: Fragment view created.")

        setupMonitoredBeaconsRecyclerView()
        setupObservers()
        setupClickListeners()

        checkAndRequestPermissions()
        // updateUiBasedOnServiceState() est appelé dans onResume et après l'octroi des permissions
        Log.d("MonitoringFragment", "onViewCreated: Setup complet.")
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            Log.d("MonitoringFragment", "Demande des permissions: ${permissionsToRequest.joinToString()}")
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d("MonitoringFragment", "Toutes les permissions nécessaires sont déjà accordées.")
        }
    }

    private fun setupMonitoredBeaconsRecyclerView() {
        monitoredBeaconAdapter = BeaconAdapter() // Assurez-vous qu'il gère BraceletEntity
        binding.rvMonitoredBeacons.apply {
            adapter = monitoredBeaconAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
        Log.d("MonitoringFragment", "RecyclerView configuré.")
    }

// Dans MonitoringFragment.kt -> setupObservers()

    private fun setupObservers() {
        viewModel.monitoredBeacons.observe(viewLifecycleOwner) { beacons: List<BraceletEntity>? ->
            Log.d("MonitoringFragment", "Liste des balises surveillées mise à jour: ${beacons?.size ?: "null"}")
            monitoredBeaconAdapter.submitList(beacons?.let { ArrayList(it) } ?: emptyList())
        }

        viewModel.activeAlarms.observe(viewLifecycleOwner) { activeAlarms: List<BraceletEntity>? ->
            Log.d("MonitoringFragment", "Alarmes actives mises à jour: ${activeAlarms?.size ?: "null"}")
            // Pour utiliser tvAlarmStatusIndicator, vous DEVEZ L'AJOUTER à votre fragment_monitoring.xml
            // Exemple :
            // if (activeAlarms.isNullOrEmpty()) {
            //     binding.tvAlarmStatusIndicator.visibility = View.GONE
            // } else {
            //     binding.tvAlarmStatusIndicator.visibility = View.VISIBLE
            //     binding.tvAlarmStatusIndicator.text = "ALARMES (${activeAlarms.size})"
            // }
            // Si vous ne l'ajoutez pas, commentez ou supprimez ces lignes.
        }

        viewModel.alarmDistanceThreshold.observe(viewLifecycleOwner) { distance ->
            Log.d("MonitoringFragment", "Seuil de distance observé: $distance")
            if (binding.sliderDistanceThreshold.value.toInt() != distance) {
                binding.sliderDistanceThreshold.value = distance.toFloat()
            }
            // CORRECTION ICI: Utiliser l'ID du XML
            binding.tvDistanceValue.text = "$distance m"
        }

        viewModel.alarmVolume.observe(viewLifecycleOwner) { volume ->
            Log.d("MonitoringFragment", "Volume d'alarme observé: $volume")
            if (binding.sliderAlarmVolume.value.toInt() != volume) {
                binding.sliderAlarmVolume.value = volume.toFloat()
            }
            // CORRECTION ICI: Utiliser l'ID du XML
            binding.tvVolumeValue.text = "$volume %"
        }

        viewModel.isAlarmSilenced.observe(viewLifecycleOwner) { isSilenced ->
            Log.d("MonitoringFragment", "État du silence de l'alarme observé: $isSilenced")
            binding.btnSilenceAlarm.text = if (isSilenced) "Réactiver Son" else "Silencieux"
        }
    }



    private fun setupClickListeners() {
        binding.btnToggleBackgroundMonitoring.setOnClickListener {
            if (areAllPermissionsGranted()) {
                toggleBleScanService()
            } else {
                Toast.makeText(requireContext(), "Permissions manquantes.", Toast.LENGTH_SHORT).show()
                checkAndRequestPermissions()
            }
        }

        binding.btnSilenceAlarm.setOnClickListener {
            Log.d("MonitoringFragment", "Bouton Silencieux cliqué.")
            viewModel.toggleSilenceAlarm()
        }

        binding.btnSearchMap.setOnClickListener {
            Log.d("MonitoringFragment", "Bouton Recherche Carte cliqué.")
            Toast.makeText(requireContext(), "Fonctionnalité Recherche Carte à implémenter", Toast.LENGTH_SHORT).show()
        }

        binding.sliderDistanceThreshold.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setAlarmDistanceThreshold(value.toInt())
            }
        }

        binding.sliderAlarmVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setAlarmVolume(value.toInt())
            }
        }

        binding.btnTestAlarmVolume.setOnClickListener {
            Log.d("MonitoringFragment", "Bouton Test Volume Alarme cliqué.")
            viewModel.testAlarmSound()
        }
    }

    private fun areAllPermissionsGranted(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = requireActivity().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.name == service.service.className) {
                    Log.d("MonitoringFragment", "${serviceClass.simpleName} est en cours (foreground: ${service.foreground}).")
                    return true
                }
            }
        } catch (e: SecurityException) {
            Log.e("MonitoringFragment", "Erreur en vérifiant le service: ${e.message}")
            return false
        }
        Log.d("MonitoringFragment", "${serviceClass.simpleName} n'est pas en cours.")
        return false
    }

    private fun toggleBleScanService() {
        val serviceClass = BleScanService::class.java
        val serviceIntent = Intent(requireContext(), serviceClass)

        if (isServiceRunning(serviceClass)) {
            Log.d("MonitoringFragment", "Arrêt de BleScanService demandé.")
            requireActivity().stopService(serviceIntent)
        } else {
            Log.d("MonitoringFragment", "Démarrage de BleScanService demandé.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireActivity().startForegroundService(serviceIntent)
            } else {
                requireActivity().startService(serviceIntent)
            }
        }
        // L'UI du bouton sera mise à jour par onResume ou par un observateur plus direct de l'état du service si implémenté
        // Pour une réactivité immédiate, on peut forcer un check:
        updateUiBasedOnServiceState()
    }

    private fun updateUiBasedOnServiceState() {
        val isRunning = isServiceRunning(BleScanService::class.java)
        Log.d("MonitoringFragment", "Mise à jour UI: Service en cours? $isRunning")
        binding.btnToggleBackgroundMonitoring.text = if (isRunning) {
            getString(R.string.stop_intensive_monitoring) // Ex: "Arrêter Surveillance"
        } else {
            getString(R.string.start_intensive_monitoring) // Ex: "Démarrer Surveillance"
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("MonitoringFragment", "onResume: Mise à jour de l'UI.")
        updateUiBasedOnServiceState()
        // Le ViewModel charge les préférences et les balises dans son init et via collect.
        // Il n'y a pas besoin de démarrer un scan explicite du ViewModel ici si le service est le principal acteur.
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        Log.d("MonitoringFragment", "onDestroyView: Binding mis à null.")
    }
}
