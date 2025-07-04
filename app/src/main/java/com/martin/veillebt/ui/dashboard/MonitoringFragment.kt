package com.martin.veillebt.ui.dashboard // Ou com.martin.veillebt.ui.monitoring

import android.content.Context
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.semantics.text
// import androidx.compose.ui.semantics.text // Import Compose non utilisé ici, peut être supprimé
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
// import androidx.lifecycle.ViewModelProvider // Non nécessaire si viewModels est bien configuré
// import androidx.lifecycle.observe // Extension obsolète, utiliser viewLifecycleOwner et .observe {}
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
// import com.google.android.material.slider.Slider // Import spécifique non nécessaire si on accède via binding
import com.martin.veillebt.R // Pour la navigation vers R.id.action_monitoringFragment_to_mapFragment
import com.martin.veillebt.data.repository.BraceletRepository
import com.martin.veillebt.databinding.FragmentMonitoringBinding
// import kotlin.text.isNullOrEmpty // Inclus par défaut
// import kotlin.text.toFloat // Inclus par défaut

// Importez vos Adapters pour RecyclerView ici
// import com.martin.veillebt.ui.monitoring.adapter.MonitoredBeaconAdapter
// import com.martin.veillebt.ui.monitoring.adapter.AlarmEventAdapter


class MonitoringFragment : Fragment() {

    private var _binding: FragmentMonitoringBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MonitoringViewModel by viewModels {
        MonitoringViewModel.Factory(
            requireActivity().application,
            BraceletRepository(requireContext())
        )
    }

    // Adapters pour les RecyclerViews (à créer et initialiser)
    // private lateinit var beaconAdapter: MonitoredBeaconAdapter
    // private lateinit var alarmAdapter: AlarmEventAdapter

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) {
                checkBluetoothEnabledAndStartScan()
            } else {
                Toast.makeText(requireContext(), "Permissions BLE requises pour la surveillance.", Toast.LENGTH_LONG).show()
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                viewModel.startContinuousScan()
            } else {
                Toast.makeText(requireContext(), "Bluetooth doit être activé pour la surveillance.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMonitoringBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerViews()
        setupClickListeners()
        setupObservers()
        setupSliders() // Appel de la méthode

        if (allPermissionsGranted()) {
            checkBluetoothEnabledAndStartScan()
        } else {
            requestPermissionsLauncher.launch(getRequiredPermissions())
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Pour les API < 31, BLUETOOTH et BLUETOOTH_ADMIN sont nécessaires pour le scan
            // et potentiellement pour l'activation du Bluetooth via l'intent.
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        return permissions.toTypedArray()
    }

    private fun allPermissionsGranted() = getRequiredPermissions().all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkBluetoothEnabledAndStartScan() {
        // Utiliser requireContext() pour obtenir le contexte non-nul
        val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(requireContext(), "Cet appareil ne supporte pas Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        // ... la suite de la fonction reste la même
        if (!bluetoothAdapter.isEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(requireContext(), "Permission BLUETOOTH_CONNECT manquante pour activer Bluetooth.", Toast.LENGTH_LONG).show()
                return
            }
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            viewModel.startContinuousScan()
        }
    }


    private fun setupRecyclerViews() {
        // TODO: Initialiser et configurer vos adapters ici
        // beaconAdapter = MonitoredBeaconAdapter { beacon -> /* Click listener item */ }
        // binding.rvMonitoredBeacons.layoutManager = LinearLayoutManager(requireContext())
        // binding.rvMonitoredBeacons.adapter = beaconAdapter

        // alarmAdapter = AlarmEventAdapter()
        // binding.rvAlarmEvents.layoutManager = LinearLayoutManager(requireContext())
        // binding.rvAlarmEvents.adapter = alarmAdapter

        // Placeholder si pas encore d'adapters :
        binding.rvMonitoredBeacons.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAlarmEvents.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun setupClickListeners() {
        binding.btnSilenceAlarm.setOnClickListener {
            viewModel.toggleSilenceAlarm()
        }

        binding.btnSearchMap.setOnClickListener {
            // Assurez-vous que cette action est définie dans votre nav_graph.xml
            // findNavController().navigate(R.id.action_monitoringFragment_to_mapFragment)
            Toast.makeText(requireContext(), "Navigation vers la carte (à implémenter)", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestAlarmVolume.setOnClickListener {
            viewModel.testAlarmSound()
        }
    }

    private fun setupObservers() {
        viewModel.monitoredBeacons.observe(viewLifecycleOwner) { beacons ->
            // beaconAdapter.submitList(beacons) // Si vous utilisez ListAdapter
            if (beacons.isNullOrEmpty()) {
                // Afficher un message si aucune balise n'est surveillée (ex: via un TextView)
                Toast.makeText(context, "Aucune balise surveillée pour le moment.", Toast.LENGTH_SHORT).show()
            }
            // Mettre à jour l'adapter même si la liste est vide pour qu'il efface les anciens items
            // Log.d("MonitoringFragment", "Monitored beacons updated: ${beacons.size}")
        }

        viewModel.activeAlarms.observe(viewLifecycleOwner) { alarms ->
            // alarmAdapter.submitList(alarms) // Si vous utilisez ListAdapter
            // Gérer la visibilité du conteneur d'alarmes ou un message "aucune alarme"
            // Log.d("MonitoringFragment", "Active alarms updated: ${alarms.size}")
        }

        viewModel.alarmDistanceThreshold.observe(viewLifecycleOwner) { distance ->
            binding.tvDistanceValue.text = "${distance}m"
            // Vérifier avant de setter pour éviter boucle infinie si le slider notifie aussi le VM
            if (binding.sliderDistanceThreshold.value.toInt() != distance) {
                binding.sliderDistanceThreshold.value = distance.toFloat()
            }
        }

        viewModel.alarmVolume.observe(viewLifecycleOwner) { volume ->
            binding.tvVolumeValue.text = "${volume}%"
            if (binding.sliderAlarmVolume.value.toInt() != volume) {
                binding.sliderAlarmVolume.value = volume.toFloat()
            }
        }

        viewModel.isAlarmSilenced.observe(viewLifecycleOwner) { silenced ->
            binding.btnSilenceAlarm.text = if (silenced) getString(R.string.alarm_silenced_on) else getString(R.string.alarm_silenced_off)
            // Optionnel : Changer l'apparence du bouton (couleur, icône)
            // Exemple :
            // val backgroundColor = if (silenced) Color.GRAY else ContextCompat.getColor(requireContext(), R.color.design_default_color_primary)
            // binding.btnSilenceAlarm.setBackgroundColor(backgroundColor)
        }
    }

    private fun setupSliders() {
        binding.sliderDistanceThreshold.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                viewModel.setAlarmDistanceThreshold(value.toInt())
            }
        }
        // Pour afficher la valeur initiale correctement dès le départ (si ViewModel l'a déjà)
        viewModel.alarmDistanceThreshold.value?.let {
            binding.sliderDistanceThreshold.value = it.toFloat()
            binding.tvDistanceValue.text = "${it}m" // Assurer la cohérence initiale du TextView
        }


        binding.sliderAlarmVolume.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                viewModel.setAlarmVolume(value.toInt())
            }
        }
        viewModel.alarmVolume.value?.let {
            binding.sliderAlarmVolume.value = it.toFloat()
            binding.tvVolumeValue.text = "${it}%" // Assurer la cohérence initiale du TextView
        }
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            // Utiliser requireContext() ici aussi
            val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
            val bluetoothAdapter = bluetoothManager?.adapter
            if (bluetoothAdapter?.isEnabled == true) {
                viewModel.startContinuousScan()
            } else if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled) {
                Toast.makeText(requireContext(), "Bluetooth n'est pas activé.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopContinuousScan() // Arrêter le scan pour économiser la batterie
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Important pour éviter les fuites de mémoire
    }
}

