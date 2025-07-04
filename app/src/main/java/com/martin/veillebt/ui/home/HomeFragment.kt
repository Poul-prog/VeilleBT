package com.martin.veillebt.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController // Si vous utilisez Navigation Component
import com.martin.veillebt.R
import com.martin.veillebt.databinding.FragmentHomeBinding // Généré par ViewBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    // Cette propriété n'est valide qu'entre onCreateView et onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnScanBracelet.setOnClickListener {
            // TODO: Naviguer vers l'écran de scan des bracelets
            // Exemple avec Navigation Component:
            findNavController().navigate(R.id.action_homeFragment_to_enrollmentFragment)
            Toast.makeText(context, "Scanner Bracelets cliqué", Toast.LENGTH_SHORT).show()
        }

        /*binding.btnAssociatePhone.setOnClickListener {
            // TODO: Implémenter la logique d'association de téléphone
            Toast.makeText(context, "Associer Téléphone cliqué", Toast.LENGTH_SHORT).show()
        }*/

        binding.btnResumeScan.setOnClickListener {
            // TODO: Implémenter la logique pour reprendre un scan
            Toast.makeText(context, "Reprendre Scan cliqué", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Important pour éviter les fuites de mémoire avec ViewBinding
    }
}

// private val viewModel: HomeViewModel by viewModels() // Nécessite la dépendance fragment-ktx