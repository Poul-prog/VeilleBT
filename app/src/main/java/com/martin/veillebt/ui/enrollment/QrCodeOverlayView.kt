package com.martin.veillebt.ui.enrollment // Assurez-vous que le package est correct

import android.content.Context
import android.graphics.* // Importe les classes graphiques de base (Canvas, Paint, RectF, etc.)
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat // Utilisé pour récupérer les couleurs de manière compatible
//import androidx.glance.layout.height
//import androidx.glance.layout.width
import com.martin.veillebt.R // Importe les ressources de votre application (comme les couleurs définies dans colors.xml)

/**
 * QrCodeOverlayView est une vue personnalisée conçue pour être superposée à un aperçu de caméra.
 * Elle dessine un cadre de visée semi-transparent avec une zone centrale claire (transparente)
 * pour guider l'utilisateur lors du scan de codes QR.
 */
class QrCodeOverlayView @JvmOverloads constructor( // @JvmOverloads permet d'utiliser ce constructeur depuis Java avec moins d'arguments
    context: Context, // Le contexte dans lequel la vue s'exécute, utilisé pour accéder aux ressources et aux thèmes
    attrs: AttributeSet? = null, // Ensemble d'attributs définis dans le layout XML (si la vue y est déclarée)
    defStyleAttr: Int = 0 // Un style par défaut à appliquer à la vue (généralement 0 si non spécifié)
) : View(context, attrs, defStyleAttr) { // Hérite de la classe View de base d'Android

    // --- Propriétés de dessin ---

    // Pinceau (Paint) pour dessiner le contour (la bordure) du cadre de visée
    private val boxPaint: Paint = Paint().apply {
        // Définit la couleur du trait. R.color.qr_code_overlay_stroke doit être défini dans res/values/colors.xml
        color = ContextCompat.getColor(context, R.color.qr_code_overlay_stroke)
        style = Paint.Style.STROKE // Définit le style de dessin sur "contour uniquement" (pas de remplissage)
        strokeWidth = 8f // Épaisseur du trait du contour en pixels
        isAntiAlias = true // Active l'anti-crénelage pour des bords plus lisses
    }

    // Pinceau pour dessiner le fond semi-transparent à l'extérieur du cadre de visée (la zone "scrim")
    private val scrimPaint: Paint = Paint().apply {
        // Définit la couleur de la zone extérieure. R.color.qr_code_overlay_outside doit être défini dans res/values/colors.xml
        // Cette couleur a généralement une composante alpha pour la semi-transparence.
        color = ContextCompat.getColor(context, R.color.qr_code_overlay_outside)
        style = Paint.Style.FILL // Le style est "remplissage" car on colore toute la zone
    }

    // Pinceau spécial utilisé pour "effacer" la zone centrale du cadre, la rendant transparente
    private val eraserPaint: Paint = Paint().apply {
        isAntiAlias = true // Active l'anti-crénelage pour des bords plus lisses lors de l'effacement
        // PorterDuff.Mode.CLEAR "efface" les pixels sur lesquels il est dessiné, les rendant transparents.
        // Cela fonctionne car on dessine d'abord le fond semi-transparent (scrimPaint), puis on "efface" le centre.
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL // Doit être en mode remplissage pour effacer toute la zone du rectangle
    }

    // --- Propriétés géométriques ---

    // Le rectangle (RectF pour les coordonnées en flottants) qui définit la position et la taille du cadre de visée.
    // Il est nullable car il peut ne pas être défini immédiatement à la création de la vue.
    private var boxRect: RectF? = null

    // Rayon utilisé pour les coins arrondis du cadre et de la zone "effacée".
    private val cornerRadius = 32f // Valeur en pixels

    /**
     * Cette méthode est appelée par le système Android lorsque la vue a besoin de se dessiner ou de se redessiner.
     * C'est ici que toute la logique de dessin personnalisée a lieu.
     * @param canvas Le Canvas sur lequel le dessin sera effectué.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas) // Appelle l'implémentation de la classe parente (View)

        // Récupère le rectangle du cadre. Si boxRect est null (pas encore défini), on ne dessine rien du cadre.
        // L'opérateur elvis (?:) avec 'return' fait que la fonction s'arrête ici si boxRect est null.
        val currentBoxRect = boxRect ?: return

        // 1. Dessiner le fond semi-transparent (scrim) sur toute la surface de la vue.
        // Les coordonnées (0,0) à (width, height) couvrent toute la vue.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // 2. "Effacer" la zone centrale (où le QR code sera visible) pour la rendre transparente.
        // On dessine un rectangle arrondi avec le 'eraserPaint' par-dessus le scrim.
        // Les pixels sous ce rectangle arrondi deviendront transparents.
        canvas.drawRoundRect(currentBoxRect, cornerRadius, cornerRadius, eraserPaint)

        // 3. Dessiner le contour (la bordure) du cadre de visée.
        // On dessine un autre rectangle arrondi, cette fois avec le 'boxPaint' (qui est en mode STROKE).
        // Cela dessine uniquement la ligne de contour autour de la zone transparente.
        canvas.drawRoundRect(currentBoxRect, cornerRadius, cornerRadius, boxPaint)
    }

    /**
     * Méthode publique pour définir ou mettre à jour les dimensions et la position du cadre de visée.
     * @param rect Le nouveau rectangle (RectF) pour le cadre.
     */
    fun setFramingRect(rect: RectF) {
        this.boxRect = rect // Met à jour la propriété boxRect
        // Demande au système de redessiner la vue. postInvalidate() est utilisé car cela peut être appelé
        // depuis un thread autre que le thread UI (bien que ce ne soit pas critique ici).
        // invalidate() serait suffisant si appelé depuis le thread UI.
        postInvalidate()
    }

    /**
     * Calcule et met à jour le rectangle de cadrage (boxRect) en fonction des dimensions
     * d'une vue d'aperçu de caméra (par exemple, une PreviewView de CameraX).
     * Le cadre est généralement centré et occupe un pourcentage de la vue d'aperçu.
     * @param previewViewWidth La largeur de la vue d'aperçu.
     * @param previewViewHeight La hauteur de la vue d'aperçu.
     */
    fun updateFramingRectBasedOnPreview(previewViewWidth: Int, previewViewHeight: Int) {
        val viewFinderWidth: Float
        val viewFinderHeight: Float

        // Ajuster la taille du cadre pour qu'il soit plus petit que la preview (par exemple, 75% de la dimension la plus petite)
        // et qu'il ait un ratio proche du carré (typiquement souhaité pour les QR codes).
        if (previewViewWidth < previewViewHeight) { // Mode Portrait de la PreviewView
            viewFinderWidth = previewViewWidth * 0.75f // Le cadre prend 75% de la largeur de la preview
            viewFinderHeight = viewFinderWidth       // Hauteur égale à la largeur pour un cadre carré
        } else { // Mode Paysage de la PreviewView (ou carré)
            viewFinderHeight = previewViewHeight * 0.75f // Le cadre prend 75% de la hauteur de la preview
            viewFinderWidth = viewFinderHeight          // Largeur égale à la hauteur pour un cadre carré
        }

        // Calculer les coordonnées (gauche, haut, droite, bas) pour centrer le cadre dans la vue.
        val left = (previewViewWidth - viewFinderWidth) / 2f
        val top = (previewViewHeight - viewFinderHeight) / 2f
        val right = left + viewFinderWidth
        val bottom = top + viewFinderHeight

        // Mettre à jour le rectangle de cadrage avec les nouvelles dimensions calculées.
        setFramingRect(RectF(left, top, right, bottom))
    }
}