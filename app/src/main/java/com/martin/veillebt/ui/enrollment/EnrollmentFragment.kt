package com.martin.veillebt.ui.enrollment

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
// Import manquant pour ViewBinding
import com.martin.veillebt.databinding.FragmentEnrollmentBleBinding // <--- IMPORTATION AJOUTÉE ICI
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.martin.veillebt.R // Important pour accéder à l'ID de l'action
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class EnrollmentFragment : Fragment() {

    private var _binding: FragmentEnrollmentBleBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EnrollmentViewModel by viewModels()

    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }.toTypedArray()

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) {
                checkBluetoothEnabledAndStartScan()
            } else {
                Toast.makeText(requireContext(), "Permissions BLE requises pour l'enregistrement.", Toast.LENGTH_LONG).show()
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.startBleScan()
            } else {
                Toast.makeText(requireContext(), "Bluetooth doit être activé.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEnrollmentBleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupObservers()
        setupClickListeners()

        if (allPermissionsGranted()) {
            checkBluetoothEnabledAndStartScan()
        } else {
            requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkBluetoothEnabledAndStartScan() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(requireContext(), "Cet appareil ne supporte pas Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            // Vérifier la permission BLUETOOTH_CONNECT avant de lancer l'intent sur API 31+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // La permission BLUETOOTH_CONNECT est nécessaire pour ACTION_REQUEST_ENABLE sur API 31+
                // Normalement, elle devrait être demandée avec BLUETOOTH_SCAN
                Toast.makeText(requireContext(), "Permission BLUETOOTH_CONNECT manquante pour activer Bluetooth.", Toast.LENGTH_LONG).show()
                // Vous pourriez vouloir redemander les permissions ici si ce cas arrive.
                return
            }
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            viewModel.startBleScan()
        }
    }


    private fun setupObservers() {
        viewModel.statusMessage.observe(viewLifecycleOwner) { message ->
            binding.tvStatus.text = message
        }

        viewModel.candidateDevice.observe(viewLifecycleOwner) { candidate ->
            if (candidate != null) {
                binding.tilBeaconName.isEnabled = true
                binding.btnNextBeacon.isEnabled = true // Vous pouvez rendre ceci dépendant du fait que etBeaconName ne soit pas vide aussi
                binding.tvCandidateInfo.text = "Balise: ${candidate.originalName ?: candidate.address} (RSSI: ${candidate.rssi})"

                // Modification ici :
                // Seulement pré-remplir le nom si le champ est actuellement vide ET
                // que le candidat a un nom assigné OU un nom original.
                // Ou, encore mieux, si le candidat vient de changer pour un NOUVEL appareil.

                // Approche simple : Ne pas toucher au texte si l'EditText a le focus
                // ou s'il contient déjà du texte entré par l'utilisateur pour le MÊME candidat.

                // Pour éviter d'écraser la saisie utilisateur, on peut être plus prudent :
                // Si l'EditText n'a pas le focus et qu'il est vide, on peut pré-remplir.
                // Ou, si le candidat actuel est DIFFÉRENT du précédent.
                // Pour l'instant, la modification la plus simple pour éviter le problème est de
                // ne mettre à jour le texte que si le texte actuel du etBeaconName ne correspond PAS
                // à ce qu'on voudrait y mettre (et potentiellement seulement si le etBeaconName est vide).

                val currentNameInEditText = binding.etBeaconName.text.toString()
                val nameToSet = candidate.assignedName ?: "" // Pour l'instant, on ne pré-remplit pas avec originalName

                // Si le candidat est nouveau (différent de celui pour lequel le texte a été tapé)
                // OU si le champ est vide et qu'on a un nom à assigner.
                // Cette logique peut devenir complexe.
                // LA SOLUTION LA PLUS SIMPLE POUR VOTRE PROBLÈME IMMÉDIAT :
                // Si le nom assigné existe et que le champ de texte ne l'affiche pas déjà, mettez-le.
                // Mais cela n'empêchera pas l'effacement si assignedName est vide.

                // *** LA CAUSE PRINCIPALE DE VOTRE PROBLÈME EST CETTE LIGNE SI ELLE EST MAL GÉRÉE ***
                // binding.etBeaconName.setText(candidate.assignedName ?: "")

                // Solution plus robuste : Stocker l'adresse du "dernier candidat pour lequel le texte a été défini"
                // et ne mettre à jour que si le nouveau candidat a une adresse différente.
                // Mais pour l'instant, essayons de ne PAS appeler setText si le champ a déjà quelque chose
                // et que le candidat est le même.

                // Si c'est un nouveau candidat (on peut le vérifier en comparant son adresse avec une variable membre
                // stockant l'adresse du précédent candidat affiché), alors on peut appeler setText.
                // Sinon, si c'est le même candidat, on ne touche pas à ce que l'utilisateur tape.

                // SOLUTION PLUS SIMPLE ET DIRECTE POUR L'INSTANT :
                // On va juste s'assurer qu'on ne le remplit pas à chaque observation si le nom assigné est vide.
                // L'utilisateur tape, et on ne pré-remplit que si un nom a été explicitement sauvegardé.
                if (binding.etBeaconName.text.toString() != candidate.assignedName && !candidate.assignedName.isNullOrEmpty()) {
                    binding.etBeaconName.setText(candidate.assignedName)
                } else if (binding.etBeaconName.text.isNullOrEmpty() && candidate.assignedName.isNullOrEmpty()) {
                    // Optionnel : si le nom assigné est vide et le champ est vide, on s'assure qu'il est vide.
                    // Mais setText("") à chaque fois est le problème.
                    // binding.etBeaconName.setText("") // C'est ce qui cause l'effacement
                }
                // Pour l'instant, pour résoudre le problème d'effacement immédiat,
                // commentez temporairement le `setText` pour voir si la saisie persiste.
                // Puis, affinez la logique.

                // **MEILLEURE APPROCHE POUR CE FRAGMENT SPÉCIFIQUE (NON-COMPOSE) :**
                // Ne mettez à jour le `etBeaconName` via `setText` que lorsque le *candidat lui-même change* (c'est-à-dire, une nouvelle adresse MAC).

            } else { // candidate == null
                binding.tilBeaconName.isEnabled = false
                // N'effacez PAS le texte ici si l'utilisateur était en train de taper pour un candidat précédent
                // qui vient de disparaître temporairement. Effacez-le plutôt lorsque l'utilisateur
                // clique sur "Balise Suivante" ou si aucun candidat n'est présent PENDANT UN CERTAIN TEMPS.
                // Pour l'instant, commentons cela pour voir si ça aide :
                // binding.etBeaconName.text?.clear()
                binding.btnNextBeacon.isEnabled = false
                binding.tvCandidateInfo.text = ""
            }
        }

        viewModel.enrolledDevicesThisSession.observe(viewLifecycleOwner) { enrolledList ->
            binding.tvEnrolledCount.text = "Balises enregistrées: ${enrolledList?.size ?: 0}"
        }
    }

    private fun setupClickListeners() {
        binding.btnNextBeacon.setOnClickListener {
            val name = binding.etBeaconName.text.toString().trim()
            if (viewModel.candidateDevice.value != null) {
                if (name.isNotBlank()) {
                    viewModel.assignNameToCandidate(name)
                    binding.etBeaconName.text?.clear()
                    viewModel.prepareForNextBeacon()
                } else {
                    Toast.makeText(requireContext(), "Veuillez entrer un nom pour la balise.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Aucune balise candidate sélectionnée.", Toast.LENGTH_SHORT).show()
                viewModel.prepareForNextBeacon()
            }
        }

        binding.btnFinishEnrollment.setOnClickListener {
            val currentName = binding.etBeaconName.text.toString().trim()
            if (viewModel.candidateDevice.value != null && currentName.isNotBlank()) {
                viewModel.assignNameToCandidate(currentName)
            }
            viewModel.finishEnrollment()
            findNavController().navigate(R.id.action_enrollmentFragment_to_monitoringFragment)        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopBleScan()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted() && BluetoothAdapter.getDefaultAdapter()?.isEnabled == true) {
            // Relancer le scan si la liste des enregistrés est vide ou si le viewModel le décide
            if (viewModel.enrolledDevicesThisSession.value.isNullOrEmpty() || viewModel.isScanning.value == false) {
                viewModel.startBleScan()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}