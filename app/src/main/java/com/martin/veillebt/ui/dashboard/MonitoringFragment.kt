package com.martin.veillebt.ui.dashboard // Ou votre package approprié

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.semantics.text
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
// import androidx.recyclerview.widget.RecyclerView // Remplacé par la référence directe au binding
import com.martin.veillebt.databinding.FragmentMonitoringBinding // Importez votre ViewBinding généré
import dagger.hilt.android.AndroidEntryPoint
// Assurez-vous que le chemin d'importation vers BeaconAdapter est correct
// import com.martin.veillebt.ui.dashboard.adapters.BeaconAdapter // Si dans un sous-package
import com.martin.veillebt.ui.dashboard.BeaconAdapter // Si dans le même package

@AndroidEntryPoint
class MonitoringFragment : Fragment() {

    // ViewModel injecté par Hilt
    private val viewModel: MonitoringViewModel by viewModels()

    // View Binding pour accéder facilement aux vues du layout XML
    private var _binding: FragmentMonitoringBinding? = null
    private val binding get() = _binding!! // Cette propriété n'est valide qu'entre onCreateView et onDestroyView

    // Votre adaptateur pour la liste des balises surveillées
    private lateinit var monitoredBeaconAdapter: BeaconAdapter // Renommé pour plus de clarté

    // Si vous avez aussi un adaptateur pour les alarmes, déclarez-le ici
    // private lateinit var alarmEventsAdapter: AlarmEventsAdapter

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

        setupMonitoredBeaconsRecyclerView() // Configuration du RecyclerView pour les balises
        // setupAlarmEventsRecyclerView() // Si vous avez un RecyclerView pour les alarmes
        setupObservers()
        setupClickListeners() // Pour vos boutons et sliders

        Log.d("MonitoringFragment", "onViewCreated: Setup complet.")
    }

    private fun setupMonitoredBeaconsRecyclerView() {
        monitoredBeaconAdapter = BeaconAdapter() // Créez une instance de votre adaptateur
        binding.rvMonitoredBeacons.apply { // Utilisez l'ID de votre RecyclerView dans fragment_monitoring.xml
            adapter = monitoredBeaconAdapter
            layoutManager = LinearLayoutManager(requireContext())
            // Optionnel : ajouter des ItemDecoration si besoin
        }
        Log.d("MonitoringFragment", "setupMonitoredBeaconsRecyclerView: RecyclerView pour balises configuré.")
    }

    /*
    // Exemple si vous avez un RecyclerView pour les alarmes
    private fun setupAlarmEventsRecyclerView() {
        alarmEventsAdapter = AlarmEventsAdapter() // Créez l'adaptateur pour les alarmes
        binding.rvAlarmEvents.apply { // Utilisez l'ID de votre RecyclerView d'alarmes
            adapter = alarmEventsAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
        Log.d("MonitoringFragment", "setupAlarmEventsRecyclerView: RecyclerView pour alarmes configuré.")
    }
    */

    private fun setupObservers() {
        // Observateur pour les balises surveillées
        viewModel.monitoredBeacons.observe(viewLifecycleOwner) { beacons ->
            Log.d("MonitoringFragment_UI_Observer", "LiveData monitoredBeacons a émis. Taille: ${beacons.size}")
            if (beacons.isNotEmpty()) {
                beacons.forEachIndexed { index, beacon ->
                    Log.d("MonitoringFragment_UI_Observer", "Balise[$index]: ${beacon.assignedName}, RSSI: ${beacon.rssi}, Dist: ${beacon.distance}, Visible: ${beacon.isVisible}, Lost: ${beacon.isSignalLost}, OutRange: ${beacon.isOutOfRange}")
                }
            } else {
                Log.d("MonitoringFragment_UI_Observer", "La liste des balises surveillées est vide.")
            }
            monitoredBeaconAdapter.submitList(beacons) // Mettre à jour l'adaptateur des balises
        }

        // Observateur pour les alarmes actives
        viewModel.activeAlarms.observe(viewLifecycleOwner) { alarms ->
            Log.d("MonitoringFragment_UI_Observer", "LiveData activeAlarms a émis. Taille: ${alarms.size}")
            // Mettez à jour votre UI pour les alarmes (ex: adapter d'un autre RecyclerView)
            // alarmEventsAdapter.submitList(alarms)
            // Ou afficher un message, changer la couleur d'un bouton, etc.
            binding.tvLabelAlarms.text = "Alarmes Actives (${alarms.size})" // Exemple simple
        }

        // Observateur pour le seuil de distance d'alarme
        viewModel.alarmDistanceThreshold.observe(viewLifecycleOwner) { distance ->
            Log.d("MonitoringFragment_UI_Observer", "LiveData alarmDistanceThreshold a émis: $distance m")
            binding.tvDistanceValue.text = "${distance}m"
            // Mettre à jour la position du Slider si ce n'est pas l'utilisateur qui l'a changé
            if (binding.sliderDistanceThreshold.value.toInt() != distance) {
                binding.sliderDistanceThreshold.value = distance.toFloat()
            }
        }

        // Observateur pour le volume d'alarme
        viewModel.alarmVolume.observe(viewLifecycleOwner) { volume ->
            Log.d("MonitoringFragment_UI_Observer", "LiveData alarmVolume a émis: $volume%")
            binding.tvVolumeValue.text = "$volume%"
            // Mettre à jour la position du Slider si ce n'est pas l'utilisateur qui l'a changé
            if (binding.sliderAlarmVolume.value.toInt() != volume) {
                binding.sliderAlarmVolume.value = volume.toFloat()
            }
        }

        // Observateur pour l'état silencieux de l'alarme
        viewModel.isAlarmSilenced.observe(viewLifecycleOwner) { isSilenced ->
            Log.d("MonitoringFragment_UI_Observer", "LiveData isAlarmSilenced a émis: $isSilenced")
            binding.btnSilenceAlarm.text = if (isSilenced) "Silencieux ON" else "Silencieux OFF"
            // Vous pourriez vouloir changer l'apparence du bouton
        }
        Log.d("MonitoringFragment", "setupObservers: Observateurs LiveData configurés.")
    }

    private fun setupClickListeners() {
        binding.btnSilenceAlarm.setOnClickListener {
            Log.d("MonitoringFragment", "Bouton Silencieux cliqué.")
            viewModel.toggleSilenceAlarm()
        }

        binding.btnSearchMap.setOnClickListener {
            Log.d("MonitoringFragment", "Bouton Recherche Carte cliqué.")
            // Naviguer vers l'écran de la carte ou implémenter la logique de recherche
            // findNavController().navigate(R.id.action_monitoringFragment_to_mapFragment) // Exemple
            Toast.makeText(requireContext(), "Fonctionnalité Recherche Carte à implémenter", Toast.LENGTH_SHORT).show()
        }

        binding.sliderDistanceThreshold.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                Log.d("MonitoringFragment", "Slider Distance changé par l'utilisateur: ${value.toInt()}m")
                viewModel.setAlarmDistanceThreshold(value.toInt())
            }
        }

        binding.sliderAlarmVolume.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                Log.d("MonitoringFragment", "Slider Volume changé par l'utilisateur: ${value.toInt()}%")
                viewModel.setAlarmVolume(value.toInt())
            }
        }

        binding.btnTestAlarmVolume.setOnClickListener {
            Log.d("MonitoringFragment", "Bouton Tester Volume cliqué.")
            viewModel.testAlarmSound()
        }
        Log.d("MonitoringFragment", "setupClickListeners: Listeners pour les vues configurés.")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Important pour éviter les fuites de mémoire avec ViewBinding
        Log.d("MonitoringFragment", "onDestroyView: Binding mis à null.")
    }

    // Si vous aviez déjà des méthodes onResume/onPause pour gérer le scan,
    // assurez-vous qu'elles appellent bien viewModel.startContinuousScan() et viewModel.stopContinuousScan()
    // en fonction des permissions et de l'état du Bluetooth.
    override fun onResume() {
        super.onResume()
        Log.d("MonitoringFragment", "onResume: Démarrage du scan continu si nécessaire.")
        // Ici, vous voudrez probablement vérifier les permissions Bluetooth et si le BT est activé
        // avant d'appeler startContinuousScan. Le ViewModel gère déjà certaines de ces vérifications,
        // mais c'est une bonne pratique de s'en assurer aussi au niveau du Fragment avant d'initier.
        // Par exemple, vous pourriez avoir une fonction checkPermissionsAndStartScan()
        viewModel.startContinuousScan() // Le ViewModel vérifiera en interne les permissions/état BT
    }

    override fun onPause() {
        super.onPause()
        Log.d("MonitoringFragment", "onPause: Arrêt du scan continu.")
        viewModel.stopContinuousScan()
    }
}
