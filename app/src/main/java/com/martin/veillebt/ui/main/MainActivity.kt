package com.martin.veillebt.ui.main

import android.os.Bundle
//import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity //remplace ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.martin.veillebt.databinding.ActivityMainBinding
import com.martin.veillebt.ui.theme.VeilleBTTheme

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding // Si ViewBinding pour ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Si vous utilisez ViewBinding pour le layout de l'activité
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Si vous n'utilisez PAS ViewBinding pour le layout de l'activité :
        // setContentView(R.layout.activity_main)

        // Le NavHostFragment s'occupera d'afficher le startDestination (HomeFragment)
    }

    // Optionnel: Si vous voulez gérer le bouton "Up" (retour) avec la barre d'outils
    // override fun onSupportNavigateUp(): Boolean {
    //     val navController = findNavController(R.id.nav_host_fragment_activity_main)
    //     return navController.navigateUp() || super.onSupportNavigateUp()
    // }
}