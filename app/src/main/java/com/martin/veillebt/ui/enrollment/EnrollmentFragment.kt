package com.martin.veillebt.ui.enrollment // Package de votre fragment

// Imports essentiels pour Android, CameraX, ML Kit, Navigation, etc.
import android.Manifest // Pour la permission caméra
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log // Pour les logs de débogage
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast // Pour afficher des messages courts à l'utilisateur
import androidx.activity.result.contract.ActivityResultContracts // Nouvelle API pour gérer les demandes de permission
import androidx.camera.core.* // Classes principales de CameraX (Preview, ImageAnalysis, CameraSelector)
import androidx.camera.lifecycle.ProcessCameraProvider // Pour lier le cycle de vie de la caméra à celui du fragment
import androidx.compose.ui.semantics.text
// import androidx.compose.ui.semantics.text // Commenté, semble être un reste d'un autre essai
import androidx.core.content.ContextCompat // Pour vérifier les permissions et accéder aux ressources de manière compatible
import androidx.fragment.app.Fragment // Classe de base pour les fragments
//import androidx.glance.layout.height
//import androidx.glance.layout.width
import androidx.navigation.activity
import androidx.navigation.fragment.findNavController // Pour gérer la navigation entre les fragments
import com.google.common.util.concurrent.ListenableFuture // Utilisé par CameraX pour les opérations asynchrones
import com.google.mlkit.vision.barcode.BarcodeScanner // Scanner de codes-barres de ML Kit
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode // Contient les formats de codes-barres (ex: FORMAT_QR_CODE)
import com.google.mlkit.vision.common.InputImage // Format d'image requis par ML Kit
import com.martin.veillebt.R // Ressources de votre application (strings, layouts, etc.)
import com.martin.veillebt.databinding.FragmentEnrollmentBinding // Classe de ViewBinding générée pour votre layout
import java.util.concurrent.ExecutorService // Pour exécuter des tâches en arrière-plan (analyse d'image)
import java.util.concurrent.Executors // Pour créer des pools de threads

/**
 * EnrollmentFragment gère l'interface utilisateur et la logique pour l'enregistrement de nouveaux bracelets.
 * Il utilise CameraX pour afficher un aperçu de la caméra et ML Kit pour scanner les codes QR.
 * Les utilisateurs peuvent scanner un QR code, éventuellement nommer le bracelet, puis passer au suivant ou terminer l'enregistrement.
 */
class EnrollmentFragment : Fragment() {

    // View Binding : permet d'accéder aux vues du layout XML de manière sécurisée et concise.
    // _binding est nullable et n'est valide qu'entre onCreateView et onDestroyView.
    private var _binding: FragmentEnrollmentBinding? = null
    // Cette propriété non-nullable est une manière idiomatique d'accéder au binding.
    // Elle lèvera une exception si vous essayez d'y accéder lorsque _binding est null (en dehors du cycle de vie valide).
    private val binding get() = _binding!!

    // --- Propriétés CameraX ---
    // Future qui fournira l'instance de ProcessCameraProvider de manière asynchrone.
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    // Executor pour exécuter les tâches d'analyse d'image en arrière-plan et ne pas bloquer le thread UI.
    private lateinit var cameraExecutor: ExecutorService
    // Instance du fournisseur de caméra une fois qu'il est disponible.
    private var cameraProvider: ProcessCameraProvider? = null
    // L'objet Camera actuellement utilisé.
    private var camera: Camera? = null
    // Use case CameraX pour afficher l'aperçu de la caméra.
    private var preview: Preview? = null
    // Use case CameraX pour l'analyse d'image (ici, pour la détection de QR codes).
    private var imageAnalysis: ImageAnalysis? = null

    // --- Propriétés ML Kit Barcode Scanning ---
    // Instance du scanner de codes-barres ML Kit.
    private lateinit var barcodeScanner: BarcodeScanner

    // --- État du fragment ---
    // Stocke la dernière valeur de QR code scannée pour éviter les traitements multiples du même code.
    private var lastScannedQrValue: String? = null
    // Compteur pour attribuer un numéro séquentiel aux bracelets scannés.
    private var currentBraceletNumber = 0

    /**
     * Enregistre un ActivityResultLauncher pour demander la permission CAMERA.
     * C'est la manière moderne de gérer les résultats des demandes de permission.
     */
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Si la permission est accordée, démarrer la caméra.
                Log.d("EnrollmentFragment", "Permission caméra accordée.")
                startCamera()
            } else {
                // Si la permission est refusée, informer l'utilisateur.
                Log.w("EnrollmentFragment", "Permission caméra refusée.")
                Toast.makeText(requireContext(), "Permission caméra refusée.", Toast.LENGTH_SHORT).show()
                // Gérer le cas où la permission est refusée (ex: afficher un message, désactiver la fonctionnalité)
            }
        }

    /**
     * Appelé pour créer la hiérarchie de vues associée au fragment.
     * C'est ici que le layout du fragment est "gonflé" (inflated).
     */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Gonfle le layout XML (fragment_enrollment.xml) en utilisant ViewBinding.
        _binding = FragmentEnrollmentBinding.inflate(inflater, container, false)
        // Retourne la vue racine du layout gonflé.
        return binding.root
    }

    /**
     * Appelé immédiatement après que onCreateView() a retourné, mais avant que l'état sauvegardé
     * ne soit restauré dans la vue. C'est un bon endroit pour initialiser les composants,
     * configurer les listeners, et démarrer la logique qui interagit avec la hiérarchie de vues.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialise l'ExecutorService pour les tâches de caméra en arrière-plan.
        // newSingleThreadExecutor garantit que les tâches d'analyse d'image sont exécutées séquentiellement.
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Obtient une instance de ProcessCameraProvider. C'est une opération asynchrone.
        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        // Initialisation du scanner de codes-barres ML Kit.
        // Configure le scanner pour ne détecter que les codes QR.
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        // Initialise le message affiché à l'utilisateur pour le scan.
        binding.tvScannedQrCodeInfo.text = getString(R.string.scan_qr_initial_prompt)

        // Poste une action dans la file d'attente des messages du thread UI pour s'exécuter
        // après que la mise en page de cameraPreview soit terminée.
        // Cela permet d'obtenir les dimensions correctes de la PreviewView pour l'overlay.
        binding.cameraPreview.post {
            binding.qrCodeOverlay.updateFramingRectBasedOnPreview(
                binding.cameraPreview.width,
                binding.cameraPreview.height
            )
        }

        // Vérifie la permission caméra et démarre la caméra si la permission est déjà accordée.
        checkCameraPermissionAndStart()

        // Configure le listener pour le bouton "Bracelet Suivant".
        binding.btnNextBracelet.setOnClickListener {
            saveCurrentBraceletAndReset() // Sauvegarde le bracelet actuel et réinitialise pour le suivant.
        }

        // Configure le listener pour le bouton "Terminer Enregistrement".
        binding.btnFinishEnrollment.setOnClickListener {
            saveCurrentBracelet() // Sauvegarde le dernier bracelet scanné s'il y en a un.
            Toast.makeText(requireContext(), "Enregistrement terminé (simulation)", Toast.LENGTH_SHORT).show()
            // Navigue en arrière (par exemple, vers l'écran précédent dans la pile de navigation).
            findNavController().popBackStack()
        }
    }

    /**
     * Vérifie si la permission CAMERA a été accordée.
     * Si oui, démarre la caméra. Sinon, demande la permission.
     */
    private fun checkCameraPermissionAndStart() {
        when {
            // Vérifie si la permission a déjà été accordée.
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d("EnrollmentFragment", "Permission caméra déjà accordée. Démarrage de la caméra.")
                startCamera() // Démarre la caméra directement.
            }
            // Optionnel : Vérifie s'il faut afficher une explication avant de redemander la permission.
            // Utile si l'utilisateur a déjà refusé la permission une fois.
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Log.i("EnrollmentFragment", "Affichage de la justification pour la permission caméra.")
                Toast.makeText(requireContext(), "La permission caméra est nécessaire pour scanner les QR codes.", Toast.LENGTH_LONG).show()
                // Lance la demande de permission.
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            // Si la permission n'a pas été accordée et qu'aucune explication n'est nécessaire (première demande).
            else -> {
                Log.i("EnrollmentFragment", "Demande de la permission caméra.")
                // Lance la demande de permission.
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    /**
     * Initialise et démarre la caméra en configurant les use cases (Preview, ImageAnalysis).
     * Cette fonction est appelée après que la permission caméra a été accordée.
     */
    private fun startCamera() {
        // Ajoute un listener à cameraProviderFuture. Ce listener sera appelé lorsque
        // ProcessCameraProvider sera disponible.
        cameraProviderFuture.addListener({
            try {
                // Obtient l'instance de ProcessCameraProvider. .get() peut bloquer, mais ici
                // c'est dans un listener qui s'exécute quand c'est prêt.
                cameraProvider = cameraProviderFuture.get()
                // Une fois le provider obtenu, lie les use cases de la caméra.
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e("EnrollmentFragment", "Erreur lors de l'obtention du CameraProvider", e)
                Toast.makeText(requireContext(), "Erreur de caméra: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(requireContext())) // S'assure que le listener s'exécute sur le thread UI.
    }

    /**
     * Configure et lie les use cases CameraX (Preview et ImageAnalysis) à un cycle de vie (celui du fragment).
     * Cette fonction est appelée une fois que `cameraProvider` est disponible.
     */
    private fun bindCameraUseCases() {
        // S'assure que cameraProvider n'est pas null.
        val cameraProvider = cameraProvider ?: run {
            Log.e("EnrollmentFragment", "CameraProvider non disponible lors de bindCameraUseCases.")
            return
        }

        // Configuration du use case Preview.
        preview = Preview.Builder()
            // Vous pouvez ajouter des options ici, comme la résolution cible, etc.
            .build()
            .also {
                // Lie la surface de la PreviewView (définie dans le XML) au use case Preview.
                // C'est ce qui permet à l'aperçu de la caméra de s'afficher.
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

        // Ajoute un listener pour les changements de layout de la PreviewView.
        // Utile pour mettre à jour la taille du rectangle de l'overlay si la PreviewView change de taille.
        binding.cameraPreview.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (right - left > 0 && bottom - top > 0) { // S'assurer que les dimensions sont valides
                binding.qrCodeOverlay.updateFramingRectBasedOnPreview(right - left, bottom - top)
            }
        }

        // Configuration du use case ImageAnalysis.
        imageAnalysis = ImageAnalysis.Builder()
            // Définit la stratégie de contre-pression. STRATEGY_KEEP_ONLY_LATEST signifie que si
            // l'analyseur est trop lent, les images intermédiaires sont ignorées pour analyser la plus récente.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Vous pouvez aussi définir une résolution cible pour l'analyse si nécessaire.
            .build()
            .also {
                // Définit l'analyseur d'image. QrCodeAnalyzer est une classe interne qui traite les images.
                // L'analyse se fait sur le `cameraExecutor` (thread d'arrière-plan).
                it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { qrValue ->
                    // Ce bloc de code (lambda listener) est appelé par QrCodeAnalyzer lorsqu'un QR code est détecté.
                    // Il est important de mettre à jour l'UI sur le thread principal.
                    activity?.runOnUiThread {
                        // Vérifie si ce QR code est différent du dernier scanné pour éviter les déclenchements multiples.
                        if (lastScannedQrValue != qrValue) {
                            lastScannedQrValue = qrValue // Met à jour le dernier QR code scanné.
                            currentBraceletNumber++ // Incrémente le numéro du bracelet.

                            Log.d("EnrollmentFragment", "QR Code détecté N°$currentBraceletNumber: $qrValue")
                            // Met à jour le TextView avec les informations du QR code détecté.
                            binding.tvScannedQrCodeInfo.text =
                                getString(R.string.qr_detected_with_number, currentBraceletNumber, qrValue)

                            // Déclenche une animation de flash sur l'overlay pour un retour visuel.
                            triggerFlashOverlayAnimation()
                        }
                    }
                })
            }

        // Sélectionne la caméra à utiliser (par défaut, la caméra arrière).
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            // Détache tous les use cases précédemment liés avant d'en lier de nouveaux.
            // C'est important pour éviter les erreurs si cette fonction est appelée plusieurs fois.
            cameraProvider.unbindAll()

            // Lie les use cases (preview, imageAnalysis) au cycle de vie du fragment (viewLifecycleOwner)
            // avec le sélecteur de caméra. L'objet `camera` retourné permet de contrôler la caméra (ex: flash).
            camera = cameraProvider.bindToLifecycle(
                viewLifecycleOwner, cameraSelector, preview, imageAnalysis
            )
            Log.d("EnrollmentFragment", "Use cases de la caméra liés avec succès.")
        } catch (exc: Exception) {
            Log.e("EnrollmentFragment", "Échec de la liaison des use cases de la caméra", exc)
            Toast.makeText(requireContext(), "Erreur de caméra: ${exc.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Sauvegarde les informations du bracelet actuellement scanné et réinitialise l'interface
     * pour le scan du prochain bracelet.
     */
    private fun saveCurrentBraceletAndReset() {
        saveCurrentBracelet() // Appelle la fonction de sauvegarde.

        // Réinitialise l'état pour le prochain scan.
        lastScannedQrValue = null // Efface la dernière valeur de QR scannée.
        binding.etBraceletName.text?.clear() // Efface le champ de nom du bracelet.
        // Met à jour le message pour inviter à scanner le prochain QR code.
        binding.tvScannedQrCodeInfo.text = getString(R.string.scan_qr_prompt)
        Toast.makeText(requireContext(), "Prêt pour le bracelet suivant", Toast.LENGTH_SHORT).show()
        // Le `currentBraceletNumber` n'est pas réinitialisé ici, il continue de s'incrémenter.
    }

    /**
     * Logique pour sauvegarder les informations du bracelet (QR code et nom).
     * Actuellement, affiche un Toast et des logs. TODO: Implémenter la sauvegarde réelle (ex: base de données, ViewModel).
     */
    private fun saveCurrentBracelet() {
        val qrValue = lastScannedQrValue // Récupère la valeur du dernier QR code scanné.
        val braceletName = binding.etBraceletName.text.toString().trim() // Récupère le nom (optionnel) du bracelet.

        if (qrValue != null) {
            // Si un QR code a été scanné.
            // Génère un nom par défaut si l'utilisateur n'en a pas fourni, en utilisant le numéro du bracelet.
            val finalName = if (braceletName.isNotEmpty()) braceletName else "Bracelet_N${currentBraceletNumber}"
            Log.d("EnrollmentFragment", "Sauvegarde: Bracelet N°$currentBraceletNumber, QR=$qrValue, Nom=$finalName")
            Toast.makeText(requireContext(), "Bracelet N°$currentBraceletNumber '$finalName' (QR: $qrValue) enregistré", Toast.LENGTH_LONG).show()

            // TODO: Ajouter ici la logique pour enregistrer réellement qrValue, finalName, et currentBraceletNumber
            // (par exemple, en les envoyant à un ViewModel qui les stocke dans une base de données Room).
        } else {
            // Si aucun QR code n'a été scanné mais que l'utilisateur a entré un nom.
            if (braceletName.isNotEmpty()) {
                Toast.makeText(requireContext(), "Veuillez d'abord scanner un QR code pour '$braceletName'", Toast.LENGTH_SHORT).show()
            }
            // Si ni QR ni nom, ne rien faire (ou afficher un message si besoin).
        }
    }

    /**
     * Appelé lorsque le fragment n'est plus visible.
     * Peut être utilisé pour libérer des ressources liées à la caméra si nécessaire,
     * bien que CameraX gère beaucoup de choses automatiquement avec le cycle de vie.
     */
    override fun onPause() {
        super.onPause()
        Log.d("EnrollmentFragment", "onPause appelé.")
        // cameraProvider?.unbindAll() // Optionnel (...)
    }

    /**
     * Appelé lorsque le fragment redevient visible.
     * Si la caméra a été libérée dans onPause ou si la vue a été recréée,
     * il faut s'assurer que la caméra est redémarrée.
     */
    override fun onResume() {
        super.onResume()
        Log.d("EnrollmentFragment", "onResume appelé.")

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val currentCameraProvider = cameraProvider // Capture pour le smart cast

            if (currentCameraProvider != null) {
                // Si le cameraProvider existe, mais que notre instance de 'camera' est null,
                // cela signifie que les use cases ne sont probablement pas liés ou ont été déliés.
                // C'est le principal indicateur que nous devons relier.
                if (camera == null) {
                    Log.d("EnrollmentFragment", "camera est null dans onResume. Reliaison des use cases.")
                    bindCameraUseCases()
                } else {
                    // Si camera n'est pas null, on pourrait supposer que les liaisons sont toujours actives.
                    // CameraX gère bien les cycles de vie.
                    // Cependant, si vous appelez unbindAll() explicitement dans onPause,
                    // alors camera deviendra invalide et pourrait être null ou nécessiter une nouvelle liaison.
                    // Si vous avez unbindAll() dans onPause, la condition camera == null devrait suffire.
                    Log.d("EnrollmentFragment", "cameraProvider et camera existent. On suppose que les liaisons sont actives.")
                }
            } else {
                // Si cameraProvider est null, la caméra doit être complètement (ré)initialisée.
                Log.d("EnrollmentFragment", "cameraProvider est null dans onResume. Redémarrage complet de la caméra.")
                startCamera() // Ceci va initialiser cameraProviderFuture puis appeler bindCameraUseCases
            }
        } else {
            Log.w("EnrollmentFragment", "Permission caméra non accordée dans onResume. Demande à nouveau.")
            checkCameraPermissionAndStart() // Redemande la permission et potentiellement démarre la caméra
        }
    }

    /**
     * Appelé lorsque la vue associée au fragment est sur le point d'être détruite.
     * C'est l'endroit idéal pour nettoyer les ressources liées à la vue, comme ViewBinding,
     * et pour arrêter les opérations en arrière-plan comme cameraExecutor.
     */
    override fun onDestroyView() {
        super.onDestroyView()
        Log.d("EnrollmentFragment", "onDestroyView appelé.")
        // Arrête le thread executor de la caméra pour éviter les fuites de mémoire ou les opérations inutiles.
        cameraExecutor.shutdown()
        // Détache explicitement tous les use cases de la caméra pour libérer les ressources.
        // Bien que viewLifecycleOwner s'en charge, une double vérification ici est une bonne pratique.
        cameraProvider?.unbindAll()
        // Libère la référence au binding pour éviter les fuites de mémoire.
        _binding = null
    }

    /**
     * Déclenche une animation de "flash" sur une vue d'overlay (binding.flashOverlayView).
     * Cette vue doit être définie dans le layout XML, initialement invisible ou transparente.
     */
    private fun triggerFlashOverlayAnimation() {
        binding.flashOverlayView.apply {
            // Fondu entrant rapide (la vue devient visible)
            animate()
                .alpha(0.7f) // Opacité désirée pour le flash (0.0 = transparent, 1.0 = opaque)
                .setDuration(100) // Durée courte pour l'apparition (en millisecondes)
                .withEndAction { // Action à exécuter à la fin de cette première animation (le fondu entrant)
                    // Fondu sortant après une courte pause (la vue redevient transparente)
                    animate()
                        .alpha(0f) // Retour à la transparence totale
                        .setDuration(200) // Durée un peu plus longue pour la disparition
                        .setStartDelay(50) // Petite pause avant de commencer le fondu sortant
                        .start() // Démarre l'animation de fondu sortant
                }
                .start() // Démarre l'animation de fondu entrant
        }
    }

    /**
     * Classe interne pour analyser les images de la caméra à la recherche de codes QR.
     * Implémente ImageAnalysis.Analyzer de CameraX.
     * @param listener Une fonction lambda qui sera appelée avec la valeur du QR code détecté.
     */
    private class QrCodeAnalyzer(private val listener: (String) -> Unit) : ImageAnalysis.Analyzer {

        /**
         * Méthode appelée par CameraX pour chaque frame de la caméra disponible pour l'analyse.
         * @param imageProxy L'objet ImageProxy qui encapsule l'image à analyser.
         *                   Il est crucial de le fermer avec imageProxy.close() une fois l'analyse terminée.
         */
        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class) // Nécessaire pour imageProxy.image
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image // Obtient l'objet MediaImage sous-jacent.
            if (mediaImage != null) {
                // Crée un objet InputImage à partir de MediaImage, en spécifiant la rotation correcte.
                // La rotation est importante pour que ML Kit puisse interpréter correctement l'orientation du QR code.
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                // Obtient une instance du scanner de codes-barres.
                // Il est préférable de le réutiliser si possible, mais pour la simplicité, on le recrée ici.
                // Pour l'optimisation, on pourrait initialiser le scanner une seule fois dans le constructeur de QrCodeAnalyzer.
                val scanner = BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE) // S'assurer qu'on ne scanne que les QR codes
                        .build()
                )

                // Lance le processus de scan de l'image. C'est une opération asynchrone.
                scanner.process(image)
                    .addOnSuccessListener { barcodes -> // Appelé si le scan réussit et trouve des codes-barres.
                        for (barcode in barcodes) {
                            barcode.rawValue?.let { qrValue -> // rawValue contient la chaîne de caractères du QR code.
                                Log.d("QrCodeAnalyzer", "QR Code détecté par l'analyseur: $qrValue")
                                listener(qrValue) // Appelle le listener avec la valeur du QR code.
                                return@addOnSuccessListener // Traite un seul QR code à la fois et sort.
                                // Si vous voulez traiter tous les QR codes dans l'image, retirez cette ligne.
                            }
                        }
                    }
                    .addOnFailureListener { e -> // Appelé si une erreur se produit pendant le scan.
                        Log.e("QrCodeAnalyzer", "Erreur lors du scan du code-barres", e)
                    }
                    .addOnCompleteListener { // Appelé lorsque le processus de scan est terminé (succès ou échec).
                        imageProxy.close() // TRÈS IMPORTANT : Ferme imageProxy pour libérer l'image et permettre
                        // à CameraX de fournir la prochaine image pour l'analyse.
                        // Ne pas le faire bloquera le flux d'images.
                    }
            } else {
                // Si mediaImage est null, fermer quand même imageProxy.
                imageProxy.close()
            }
        }
    }
}