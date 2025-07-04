package com.martin.veillebt.ui.dashboard // Ou com.martin.veillebt.ui.monitoring

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels // Correction: S'assurer que c'est le bon import
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.martin.veillebt.R
import com.martin.veillebt.databinding.FragmentMonitoringBinding
import dagger.hilt.android.AndroidEntryPoint // <--- IMPORTANT: Annotation pour Hilt

// Importez vos Adapters pour RecyclerView ici si vous les utilisez
// import com.martin.veillebt.ui.monitoring.adapter.MonitoredBeaconAdapter
// import com.martin.veillebt.ui.monitoring.adapter.AlarmEventAdapter

@AndroidEntryPoint // <--- NÉCESSAIRE POUR L'INJECTION HILT DANS LE FRAGMENT
class MonitoringFragment : Fragment() {

    private var _binding: FragmentMonitoringBinding? = null
    private val binding get() = _binding!!

    // Hilt va maintenant fournir le ViewModel avec ses dépendances injectées.
    // Plus besoin de la Factory personnalisée ici.
    private val viewModel: MonitoringViewModel by viewModels() // <--- CORRECTION IMPORTANTE

    // Adapters pour les RecyclerViews (à créer et initialiser si nécessaire)
    // private lateinit var beaconAdapter: MonitoredBeaconAdapter
    // private lateinit var alarmAdapter: AlarmEventAdapter

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) {
                checkBluetoothEnabledAndStartScan()
            } else {
                Toast.makeText(requireContext(), "Permissions BLE requises pour la surveillance.", Toast.LENGTH_LONG).show()
                // Gérer le cas où les permissions ne sont pas accordées (par exemple, désactiver les fonctionnalités)
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
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
        setupSliders()

        // Déplacer la logique de permission et de démarrage ici pour s'assurer que le ViewModel est prêt
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
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
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        return permissions.toTypedArray()
    }

    private fun allPermissionsGranted() = getRequiredPermissions().all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkBluetoothEnabledAndStartScan() {
        val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (bluetoothManager == null) {
            Toast.makeText(requireContext(), "Bluetooth n'est pas supporté sur cet appareil.", Toast.LENGTH_LONG).show()
            return
        }
        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Toast.makeText(requireContext(), "Adaptateur Bluetooth non trouvé.", Toast.LENGTH_LONG).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            // Vérifier la permission BLUETOOTH_CONNECT avant de demander l'activation pour Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // Si la permission n'est pas accordée, on ne peut pas demander l'activation de manière programmatique.
                // Vous devriez déjà l'avoir demandée via requestPermissionsLauncher.
                // Informer l'utilisateur qu'il doit accorder la permission ET activer le Bluetooth.
                Toast.makeText(requireContext(), "Permission BLUETOOTH_CONNECT et activation Bluetooth requises.", Toast.LENGTH_LONG).show()
                // Optionnellement, vous pourriez relancer la demande de permission ou guider l'utilisateur.
                return // Ne pas continuer si la permission CONNECT est manquante sur S+
            }
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            viewModel.startContinuousScan() // Bluetooth est déjà activé et les permissions sont accordées (vérifié par allPermissionsGranted)
        }
    }


    private fun setupRecyclerViews() {
        // TODO: Initialiser et configurer vos adapters ici si vous en utilisez
        // Exemple :
        // val beaconAdapter = MonitoredBeaconAdapter { beacon -> /* Click listener item */ }
        // binding.rvMonitoredBeacons.layoutManager = LinearLayoutManager(requireContext())
        // binding.rvMonitoredBeacons.adapter = beaconAdapter
        //
        // val alarmAdapter = AlarmEventAdapter()
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
            Toast.makeText(requireContext(), "Navigation vers la carte (TODO)", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestAlarmVolume.setOnClickListener {
            viewModel.testAlarmSound()
        }
    }

    private fun setupObservers() {
        viewModel.monitoredBeacons.observe(viewLifecycleOwner) { beacons ->
            // (beaconAdapter as? MonitoredBeaconAdapter)?.submitList(beacons) // Si vous utilisez ListAdapter
            if (beacons.isNullOrEmpty()) {
                // Optionnel: Afficher un message si aucune balise n'est surveillée
                // binding.tvNoBeaconsMessage.visibility = View.VISIBLE (par exemple)
            } else {
                // binding.tvNoBeaconsMessage.visibility = View.GONE
            }
            // Log.d("MonitoringFragment", "Monitored beacons updated: ${beacons?.size ?: 0}")
        }

        viewModel.activeAlarms.observe(viewLifecycleOwner) { alarms ->
            // (alarmAdapter as? AlarmEventAdapter)?.submitList(alarms) // Si vous utilisez ListAdapter
            // Gérer la visibilité du conteneur d'alarmes ou un message "aucune alarme"
            // Log.d("MonitoringFragment", "Active alarms updated: ${alarms?.size ?: 0}")
        }

        viewModel.alarmDistanceThreshold.observe(viewLifecycleOwner) { distance ->
            binding.tvDistanceValue.text = getString(R.string.distance_meters_format, distance) // Utiliser les ressources string
            if (binding.sliderDistanceThreshold.value.toInt() != distance) {
                binding.sliderDistanceThreshold.value = distance.toFloat()
            }
        }

        viewModel.alarmVolume.observe(viewLifecycleOwner) { volume ->
            binding.tvVolumeValue.text = getString(R.string.volume_percentage_format, volume) // Utiliser les ressources string
            if (binding.sliderAlarmVolume.value.toInt() != volume) {
                binding.sliderAlarmVolume.value = volume.toFloat()
            }
        }

        viewModel.isAlarmSilenced.observe(viewLifecycleOwner) { silenced ->
            binding.btnSilenceAlarm.text = if (silenced) getString(R.string.alarm_silenced_on) else getString(R.string.alarm_silenced_off)
            // Optionnel : Changer l'apparence du bouton (couleur, icône)
        }
    }

    private fun setupSliders() {
        binding.sliderDistanceThreshold.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setAlarmDistanceThreshold(value.toInt())
            }
        }
        viewModel.alarmDistanceThreshold.value?.let {
            binding.sliderDistanceThreshold.value = it.toFloat()
            binding.tvDistanceValue.text = getString(R.string.distance_meters_format, it)
        }


        binding.sliderAlarmVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setAlarmVolume(value.toInt())
            }
        }
        viewModel.alarmVolume.value?.let {
            binding.sliderAlarmVolume.value = it.toFloat()
            binding.tvVolumeValue.text = getString(R.string.volume_percentage_format, it)
        }
    }

    override fun onResume() {
        super.onResume()
        // Le scan est démarré via checkAndRequestPermissions après la vérification des permissions
        // et l'activation du Bluetooth si nécessaire.
        // Si les permissions sont déjà accordées et le Bluetooth activé,
        // on peut envisager de redémarrer le scan ici si ce n'est pas déjà fait.
        // Mais la logique actuelle dans onViewCreated devrait suffire.
        // On pourrait ajouter une vérification pour s'assurer que le scan tourne s'il le devrait :
        if (allPermissionsGranted()) {
            val btManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            if (btManager?.adapter?.isEnabled == true) {
                // Si le ViewModel n'est pas déjà en train de scanner (vous auriez besoin d'un état dans le VM pour cela)
                // viewModel.startContinuousScan() // Attention aux appels multiples
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopContinuousScan() // Important pour économiser la batterie
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Important pour éviter les fuites de mémoire
    }
}

