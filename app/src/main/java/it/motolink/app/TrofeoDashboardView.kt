package it.motolink.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.ArrayDeque

/**
 * MotoLink V1.1.
 *
 * CODE-NATIVE GUI rebuild.
 *
 * The old Beta5.x implementation used the approved 941x1672 mockups as full-screen runtime
 * bitmaps and added invisible/visible overlays on top. That made the UI visually fragile and
 * caused duplicated text, non-intuitive touch targets and state layers that could diverge from
 * the real controls.
 *
 * Beta5.9 removes that architecture completely: every interactive UI element (header, cards,
 * START/STOP, status, favorites, pages, support tabs, settings and bottom navigation) is a real
 * Android View created by code. Only normal content images remain (bike/profile artwork and the
 * actual icons of installed favorite apps). No full-screen reference screenshot is used at
 * runtime.
 */
class TrofeoDashboardView(context: Context) : FrameLayout(context) {
    enum class Selection { READY, START, STOP }
    enum class Page { HOME, GARAGE, FAVORITES, SUPPORT, SETTINGS, CREDITS }
    enum class SupportTab { LOG, ASSISTANT }

    data class FavoriteVisual(val label: String, val icon: Drawable?)
    data class BikeVisual(val name: String, val description: String?, val model: String, val active: Boolean, val image: Drawable? = null)

    var onStartClick: (() -> Unit)? = null
    var onStopClick: (() -> Unit)? = null
    var onLogClick: (() -> Unit)? = null
    var onQrClick: (() -> Unit)? = null
    var onPocketModeClick: (() -> Unit)? = null
    var onFavoriteClick: ((Int) -> Unit)? = null
    var onFavoriteManageClick: (() -> Unit)? = null
    var onFavoriteAddClick: (() -> Unit)? = null
    var onFavoriteReplaceClick: ((Int) -> Unit)? = null
    var onFavoriteRemoveClick: ((Int) -> Unit)? = null
    var onOnboardingFinished: (() -> Unit)? = null
    var onGuideClick: (() -> Unit)? = null
    var onBackgroundToggleClick: (() -> Unit)? = null
    var onBikeProfileClick: ((Int) -> Unit)? = null
    var onBikeProfileMenuClick: ((Int) -> Unit)? = null
    var onAssistantSend: ((String) -> Unit)? = null
    var onAssistantInfoClick: (() -> Unit)? = null
    var onAssistantWhatsAppClick: (() -> Unit)? = null
    var onIntroToggleClick: (() -> Unit)? = null
    var onAdaptationClick: (() -> Unit)? = null
    var onClearLogClick: (() -> Unit)? = null
    var onCreditsGroupClick: (() -> Unit)? = null

    private data class GuideTarget(val x: Float, val y: Float, val w: Float, val h: Float)
    private data class GuideStep(
        val page: Page,
        val supportTab: SupportTab? = null,
        val title: String,
        val body: String,
        val target: GuideTarget
    )

    private val guideSteps = listOf(
        GuideStep(
            Page.HOME,
            title = "HOME · Navigazione",
            body = "HOME\nLa schermata principale: avvio del mirroring, stato della connessione e accesso rapido alle app preferite.\n\nGARAGE\nGestisci i profili delle tue moto e le relative impostazioni personali.\n\nPREFERITE\nScegli le app che vuoi avere sempre a portata di mano.\n\nSUPPORTO\nConsulta il Log tecnico o chiedi aiuto all’Assistente MotoLink.\n\nIMPOSTAZIONI\nPersonalizza il comportamento dell’app e del mirroring.\n\nCREDITI\nInformazioni sul progetto e accesso alla community MotoLink.",
            target = GuideTarget(35f, 1500f, 870f, 150f)
        ),
        GuideStep(
            Page.HOME,
            title = "HOME · Collegamento",
            body = "La voce Collegato a indica la moto realmente connessa.\n\nQuando non è presente una connessione mostra Nessun collegamento. Dopo la connessione visualizza esclusivamente il nome della moto attiva.\n\nLo stato operativo della sessione viene mostrato separatamente nel riquadro Stato.",
            target = GuideTarget(145f, 200f, 650f, 112f)
        ),
        GuideStep(
            Page.HOME,
            title = "HOME · START / STOP",
            body = "START avvia la ricerca e il mirroring verso la moto.\n\nPRIMO COLLEGAMENTO\nSe non esiste ancora un profilo, dopo la scelta della Modalità tasca MotoLink chiede HOTSPOT oppure QR CODE. In entrambi i casi si apre il normale pannello Nuovo profilo moto: assegna un nome, una descrizione facoltativa e scegli il modello. Con QR CODE lo scanner viene eseguito prima e i dati di collegamento rilevati restano associati al profilo. Dopo SALVA, START continua automaticamente.\n\nDagli START successivi, finché esiste un profilo salvato, questa scelta non viene più richiesta.\n\nIl colore del pulsante aiuta a riconoscere la fase corrente: pronto, ricerca o riconnessione, collegato.\n\nSTOP termina la sessione e interrompe il collegamento gestito da MotoLink.",
            target = GuideTarget(50f, 495f, 840f, 285f)
        ),
        GuideStep(
            Page.HOME,
            title = "HOME · Stato sessione",
            body = "Il riquadro Stato informa sull’attività corrente di MotoLink, ad esempio Pronto, Avvio, Riconnessione o Video in attesa.\n\nÈ un riquadro informativo: non è necessario toccarlo.",
            target = GuideTarget(250f, 770f, 480f, 195f)
        ),
        GuideStep(
            Page.GARAGE,
            title = "GARAGE · Profili moto",
            body = "Nel GARAGE puoi salvare fino a 3 profili moto.\n\nOgni profilo deve avere un nome. Il bordo verde e l’indicazione Profilo attivo identificano la moto attualmente selezionata.\n\nTocca un profilo per modificarne i dati. Le regolazioni di ADATTAMENTO restano associate a quella specifica moto anche se ne cambi il nome.",
            target = GuideTarget(58f, 285f, 824f, 295f)
        ),
        GuideStep(
            Page.GARAGE,
            title = "GARAGE · Aggiungi moto",
            body = "Usa Aggiungi con QR quando la moto mostra un codice QR compatibile. Dopo la scansione completi lo stesso normale profilo del Garage con nome, descrizione facoltativa e modello.\n\nSe il modello non utilizza il QR, puoi creare un profilo locale con la stessa schermata.\n\nAl primo START senza profili, MotoLink propone HOTSPOT oppure QR CODE e poi apre questo stesso pannello; dopo il salvataggio la connessione continua automaticamente.\n\nL’immagine del profilo dipende dal modello selezionato, non dal nome personalizzato.",
            target = GuideTarget(50f, 820f, 840f, 240f)
        ),
        GuideStep(
            Page.FAVORITES,
            title = "PREFERITE · App",
            body = "In PREFERITE puoi configurare fino a 4 app.\n\nLe app selezionate vengono mostrate anche nella HOME per un accesso rapido.\n\nUsa Cambia per sostituire l’app di uno slot oppure X per rimuoverla.",
            target = GuideTarget(125f, 350f, 700f, 870f)
        ),
        GuideStep(
            Page.SUPPORT,
            SupportTab.LOG,
            title = "SUPPORTO · Log",
            body = "Il Log registra localmente gli eventi tecnici utili alla diagnosi e rimane disponibile finché non scegli Pulisci. Per mantenere MotoLink leggera, questa schermata mostra solo le ultime 50 righe; il file .txt locale e quello condiviso conservano invece il Log completo.\n\nCon Condividi puoi scegliere:\n\nESTERNO\nInvia il Log tramite le normali funzioni di condivisione Android.\n\nASSISTENTE\nInvia all’Assistente MotoLink una copia filtrata per quella singola richiesta. Il Log originale resta sul telefono.",
            target = GuideTarget(90f, 185f, 760f, 1135f)
        ),
        GuideStep(
            Page.SUPPORT,
            SupportTab.ASSISTANT,
            title = "SUPPORTO · Assistente",
            body = "L’Assistente MotoLink è dedicato al supporto dell’app. Scrivi direttamente nel campo della chat e invia la domanda.\n\nIl Log non viene letto automaticamente. Per allegarlo usa SUPPORTO > Log > Condividi > Assistente.\n\nLa ⓘ accanto allo stato dell’Assistente spiega il funzionamento della chat, l’invio del Log e le informazioni sulla privacy.",
            target = GuideTarget(90f, 185f, 760f, 1135f)
        ),
        GuideStep(
            Page.SETTINGS,
            title = "IMPOSTAZIONI · Modalità tasca",
            body = "La Modalità tasca utilizza il sensore di prossimità del telefono per ridurre i tocchi accidentali quando il dispositivo viene coperto.\n\nSchermo nero rapido\nDurante il mirroring, premi due volte Volume Giù per oscurare lo schermo e bloccare i tocchi. Ripeti due volte Volume Giù per riattivarlo.\n\nPuoi attivare o disattivare la Modalità tasca in qualsiasi momento dalle IMPOSTAZIONI.",
            target = GuideTarget(58f, 190f, 824f, 185f)
        ),
        GuideStep(
            Page.SETTINGS,
            title = "IMPOSTAZIONI · Animazione iniziale",
            body = "Determina se mostrare l’animazione MotoLink all’avvio.\n\nON\nL’animazione viene riprodotta prima della schermata principale.\n\nOFF\nMotoLink apre direttamente l’interfaccia principale.",
            target = GuideTarget(58f, 395f, 824f, 185f)
        ),
        GuideStep(
            Page.SETTINGS,
            title = "IMPOSTAZIONI · Guida iniziale",
            body = "Attiva questa opzione quando vuoi visualizzare nuovamente la guida completa alla prossima apertura di MotoLink.\n\nUna volta avviata la guida, l’opzione torna automaticamente disattivata.",
            target = GuideTarget(58f, 600f, 824f, 185f)
        ),
        GuideStep(
            Page.SETTINGS,
            title = "IMPOSTAZIONI · Sfondo",
            body = "Gestisce lo sfondo utilizzato nelle schermate MotoLink.\n\nON\nDopo una connessione reale viene utilizzata l’immagine associata alla moto attiva.\n\nOFF\nRimane sempre lo sfondo standard MotoLink.",
            target = GuideTarget(58f, 805f, 824f, 170f)
        ),
        GuideStep(
            Page.SETTINGS,
            title = "IMPOSTAZIONI · Adattamento",
            body = "ATTIVAZIONE\nADATTAMENTO è disattivato al primo utilizzo. Quando lo attivi, rimane attivo finché non lo disattivi dalle IMPOSTAZIONI.\n\nREGOLAZIONE DEI BORDI\n↑ regola il bordo superiore.\n↓ regola il bordo inferiore.\n← regola il bordo sinistro.\n→ regola il bordo destro.\nOgni pressione modifica il bordo di 5 px.\n\nDIMENSIONE\n+ allarga l’area visibile.\n− restringe l’area visibile.\n\nPANNELLO ADATTAMENTO\nIl pannello di regolazione compare solo quando il telefono è in orizzontale. In verticale Adattamento può restare ON ma l’editor rimane nascosto.\nTrascina il titolo per spostare il pannello. Il pannello viene mantenuto dentro lo schermo.\nⓘ mostra le istruzioni al posto dei comandi, lasciando sempre visibili la barra superiore e la ×. Tocca di nuovo ⓘ per tornare ai comandi.\n× chiude soltanto il pannello: la regolazione personalizzata resta salvata e continua a essere applicata.\n↺ Ripristina torna ai valori iniziali dell’app solo per l’orientamento che stai modificando e richiede due conferme.\n\nPROFILI MOTO\nOgni moto conserva le proprie regolazioni. Anche quando ADATTAMENTO è OFF i valori personali restano salvati; riattivandolo tornano disponibili. Un profilo mai regolato utilizza la base automatica predefinita.",
            target = GuideTarget(58f, 995f, 824f, 175f)
        ),
        GuideStep(
            Page.CREDITS,
            title = "CREDITI · Community",
            body = "In CREDITI trovi le informazioni sul progetto MotoLink e il collegamento alla community ufficiale.\n\nUsa Entra nel gruppo per aprire il gruppo WhatsApp MotoLink Mirroring dedicato a supporto, test e confronto tra utenti.\n\nHai completato la guida. Premi FINE per tornare alla HOME.",
            target = GuideTarget(125f, 190f, 690f, 1165f)
        )
    )

    private var selection = Selection.READY
    private var page = Page.HOME
    private var supportTab = SupportTab.LOG
    private var headerStatus = "Pronto"
    private var headerDevice = ""
    private var headerColor = GREEN
    private var stateTitle = "Sistema pronto"
    private var stateSubtitle = "Premi START per connettere"
    private var stateColor = GREEN
    private var pocketModeEnabled = false
    private var introEnabled = true
    private var guideNextLaunchEnabled = true
    private var dynamicBackgroundEnabled = true
    private var adaptationSummary = "OFF"
    private var adaptationEnabled = false
    private var guideActive = false
    private var guideStepIndex = 0
    private var navHeightPx = 0
    private var favoriteApps: List<FavoriteVisual> = emptyList()
    private var bikeProfiles: List<BikeVisual> = emptyList()
    private val supportLogs = ArrayDeque<String>()
    private var assistantQuestion = ""
    private var assistantAnswer = "Ciao, in cosa posso aiutarti?"
    private var assistantShowWhatsApp = false
    private var connectedFlashUntil = 0L

    private var topInset = 0
    private var bottomInset = 0
    private var sx = 1f
    private var sy = 1f
    private var eh = 1f
    private var contentOffsetX = 0
    private var assistantDraft = ""
    private var assistantComposerEdit: EditText? = null

    private lateinit var bodyHost: FrameLayout
    private lateinit var navHost: FrameLayout

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = false
        clipToPadding = false
        setOnApplyWindowInsetsListener { _, insets ->
            // Use only stable system-bar insets. The IME must never trigger a full dashboard
            // rebuild because rebuilding removes the focused EditText and instantly closes
            // the keyboard. MainActivity uses adjustPan so the composer remains visible.
            @Suppress("DEPRECATION")
            val newTop = insets.stableInsetTop
            @Suppress("DEPRECATION")
            val newBottom = insets.stableInsetBottom
            if (newTop != topInset || newBottom != bottomInset) {
                topInset = newTop
                bottomInset = newBottom
                post { rebuild() }
            }
            insets
        }
        post { rebuild() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) post { rebuild() }
    }

    fun setSelection(value: Selection) {
        selection = value
        if (page == Page.HOME) rebuild()
    }

    private fun isConnectedStatus(value: String = headerStatus): Boolean {
        val normalized = value.replace("●", "").trim()
        return normalized.equals("Connesso", ignoreCase = true) ||
            normalized.startsWith("Connesso ", ignoreCase = true)
    }

    fun updateHeader(status: String, device: String, color: Int) {
        val old = headerStatus
        val wasConnected = isConnectedStatus(old)
        headerStatus = status.replace("●", "").trim().ifBlank { "Pronto" }
        headerDevice = device.trim()
        headerColor = color
        val nowConnected = isConnectedStatus(headerStatus)
        if (!wasConnected && nowConnected) {
            connectedFlashUntil = SystemClock.uptimeMillis() + 900L
            postDelayed({ if (page == Page.HOME) rebuild() }, 950L)
        }
        // The connection state now controls the global page background. Rebuild any page
        // when crossing the connected/disconnected boundary; HOME still refreshes normally.
        if (page == Page.HOME || wasConnected != nowConnected) rebuild()
    }

    fun updatePocketMode(enabled: Boolean) {
        pocketModeEnabled = enabled
        if (page == Page.SETTINGS) rebuild()
    }

    fun updateIntroEnabled(enabled: Boolean) {
        introEnabled = enabled
        if (page == Page.SETTINGS) rebuild()
    }

    fun updateGuideEnabled(enabled: Boolean) {
        guideNextLaunchEnabled = enabled
        if (page == Page.SETTINGS) rebuild()
    }

    fun updateDynamicBackgroundEnabled(enabled: Boolean) {
        dynamicBackgroundEnabled = enabled
        // The background is global, so redraw the current page immediately.
        rebuild()
    }

    fun updateAdaptation(enabled: Boolean, value: String) {
        adaptationEnabled = enabled
        adaptationSummary = value.ifBlank { if (enabled) "ON" else "OFF" }
        if (page == Page.SETTINGS) rebuild()
    }

    fun updateState(title: String, subtitle: String, color: Int, right: String) {
        stateTitle = title
        stateSubtitle = subtitle
        stateColor = color
        if (page == Page.HOME) rebuild()
    }

    fun updateFavoriteApps(items: List<FavoriteVisual>) {
        favoriteApps = items.take(4)
        if (page == Page.HOME || page == Page.FAVORITES) rebuild()
    }

    fun updateBikeProfiles(items: List<BikeVisual>) {
        bikeProfiles = items.take(3)
        // While connected the active motorcycle artwork is also the global background,
        // so profile/model changes must refresh whichever page is currently visible.
        if (isConnectedStatus() || page == Page.HOME || page == Page.GARAGE) rebuild()
    }

    fun appendSupportLog(line: String) {
        supportLogs.addLast(line)
        while (supportLogs.size > AppLog.UI_VISIBLE_LINE_LIMIT) supportLogs.removeFirst()
        if (page == Page.SUPPORT && supportTab == SupportTab.LOG) rebuild()
    }

    fun replaceSupportLogs(lines: List<String>) {
        supportLogs.clear()
        lines.takeLast(AppLog.UI_VISIBLE_LINE_LIMIT).forEach { supportLogs.addLast(it) }
        if (page == Page.SUPPORT && supportTab == SupportTab.LOG) rebuild()
    }

    fun updateAssistantConversation(question: String, answer: String, showWhatsApp: Boolean = false) {
        if (question.isNotBlank()) assistantQuestion = question.trim()
        if (answer.isNotBlank()) assistantAnswer = cleanAssistantText(answer)
        assistantShowWhatsApp = showWhatsApp
        page = Page.SUPPORT
        supportTab = SupportTab.ASSISTANT
        rebuild()
    }

    private fun cleanAssistantText(raw: String): String = raw
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()

    fun startOnboarding() = startInitialGuide()

    fun startInitialGuide() {
        guideActive = true
        guideStepIndex = 0
        applyGuideStep()
    }

    fun isOnboardingActive(): Boolean = guideActive

    private fun rebuild() {
        if (width <= 0 || height <= 0) return
        assistantComposerEdit?.let { assistantDraft = it.text.toString() }
        assistantComposerEdit = null
        removeAllViews()
        setBackgroundColor(Color.BLACK)

        val availableH = (height - topInset - bottomInset).coerceAtLeast(1)
        val landscape = width > height
        if (landscape) {
            // Landscape uses one uniform scale derived from the complete portrait canvas.
            // This preserves proportions instead of stretching X while compressing Y. The
            // approved portrait UI is centered over a full-width backdrop.
            val uniform = min(width / REF_W, availableH / (BODY_REF_H + NAV_REF_H))
            sx = uniform
            sy = uniform
            eh = uniform
        } else {
            sx = width / REF_W
        }
        val navH = if (landscape) {
            (NAV_REF_H * sx).roundToInt()
        } else {
            (NAV_REF_H * sx).roundToInt().coerceAtMost((height * 0.16f).roundToInt())
        }
        navHeightPx = navH
        val bodyH = (availableH - navH).coerceAtLeast(1)
        if (!landscape) {
            sy = bodyH / BODY_REF_H
            // The reference was drawn on a 941x1672 portrait device. Horizontal geometry is
            // based on its 941px grid; vertical measures use the available body area.
            eh = min(sy, sx * 1.08f)
        }
        contentOffsetX = ((width - REF_W * sx) / 2f).roundToInt().coerceAtLeast(0)

        bodyHost = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = false
            clipToPadding = false
        }
        addView(bodyHost, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, bodyH).apply {
            topMargin = topInset
        })

        addSharedBackdrop()
        when (page) {
            Page.HOME -> buildHome()
            Page.GARAGE -> buildGarage()
            Page.FAVORITES -> buildFavorites()
            Page.SUPPORT -> buildSupport()
            Page.SETTINGS -> buildSettings()
            Page.CREDITS -> buildCredits()
        }

        navHost = FrameLayout(context).apply {
            background = roundedBg(0xFA030504.toInt(), 0xFF343A35.toInt(), 34f, 1f)
        }
        addView(navHost, FrameLayout.LayoutParams(pxX(REF_W - NAV_REF_X * 2f), navH).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = bottomInset
            leftMargin = contentOffsetX + pxX(NAV_REF_X)
        })
        buildNav()
        if (guideActive) buildGuideOverlay()
    }

    private fun addSharedBackdrop() {
        val activeBikeArtwork = if (dynamicBackgroundEnabled && isConnectedStatus()) {
            bikeProfiles.firstOrNull { it.active }?.image
                ?: bikeProfiles.firstOrNull {
                    headerDevice.isNotBlank() && (
                        headerDevice.contains(it.name, ignoreCase = true) ||
                            headerDevice.contains(it.model, ignoreCase = true)
                        )
                }?.image
        } else null

        if (activeBikeArtwork == null) {
            // Standard background: used while idle, searching, stopped, or whenever no
            // connected profile artwork is available.
            val bg = ImageView(context).apply {
                setImageResource(R.drawable.v1_ref_global_background)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.96f
            }
            bodyHost.addView(bg, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            // Connected background: reuse the exact artwork selected for the active Garage
            // profile, but keep the motorcycle deliberately on the left side like the standard
            // MotoLink background instead of centering it behind the UI.
            bodyHost.addView(View(context).apply { setBackgroundColor(Color.BLACK) }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            val drawableCopy = activeBikeArtwork.constantState?.newDrawable()?.mutate() ?: activeBikeArtwork
            val bikeBg = ImageView(context).apply {
                setImageDrawable(drawableCopy)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.72f
            }
            bodyHost.addView(bikeBg, lp(-95f, 85f, 660f, 1240f))

            // Preserve a hint of the existing green/black environment so every model still
            // belongs to the same visual family, without covering the model-specific artwork.
            val ambient = ImageView(context).apply {
                setImageResource(R.drawable.v1_ref_global_background)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.16f
            }
            bodyHost.addView(ambient, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        // Stronger right-side fade keeps titles, cards and dialogs readable while leaving
        // the connected motorcycle visible on the left edge across every page.
        val scrim = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x12000000, 0x28000000, 0x62000000, 0xA8000000.toInt(), 0xE3000000.toInt())
            )
        }
        bodyHost.addView(scrim, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val topFade = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x36000000, 0x10000000, 0x00000000)
            )
        }
        bodyHost.addView(topFade, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(420f)).apply { topMargin = pxY(0f) })

        val bottomFade = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0x30000000, 0x72000000, 0xB0000000.toInt())
            )
        }
        bodyHost.addView(bottomFade, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(580f)).apply { topMargin = pxY(900f) })
    }

    // -----------------------------------------------------------------------------------------
    // HOME
    // -----------------------------------------------------------------------------------------

    private fun buildHome() {
        // The original reference uses a wide, motorsport-style wordmark. A standard Android
        // sans face needs a larger optical size to retain the same visual weight.
        bodyHost.addView(logoView(92f), lp(160f, 47f, 620f, 135f))

        // The bike/profile name is shown only after a real connection is confirmed.
        // A saved/active Garage profile must never be presented as if it were connected.
        val connected = isConnectedStatus()
        val connectedBikeName = if (connected) {
            headerDevice.ifBlank { BikeProfileStore.load(context)?.displayName?.trim().orEmpty() }.ifBlank { "Moto collegata" }
        } else {
            "Nessun collegamento"
        }
        bodyHost.addView(infoPill(
            kind = IconKind.BIKE,
            prefix = "Collegato a:",
            value = connectedBikeName
        ), lp(150f, 210f, 640f, 92f))

        val main = mainButton()
        // Reference metric: x=262, y=576, w=463, h=264 on the 941x1672 screen.
        // The body begins below the 70px status region, hence y=506 here.
        // START is the dominant action: align it with the wide card margins so it reaches
        // almost from edge to edge instead of reading as a small central tile.
        bodyHost.addView(main, lp(58f, 506f, 824f, 264f))
        main.setOnClickListener {
            val stopping = selection != Selection.READY
            AppLog.add("GUI CODE-NATIVE: pulsante principale ${if (stopping) "STOP/CANCEL" else "START"} premuto")
            if (stopping) onStopClick?.invoke() else onStartClick?.invoke()
        }

        val glow = View(context).apply {
            background = roundedBg(0x1B77FF00, 0x3377FF00, 38f, 1f)
        }
        bodyHost.addView(glow, lp(260f, 775f, 462f, 185f))
        bodyHost.addView(statusCard(), lp(274f, 785f, 438f, 165f))

        if (favoriteApps.isEmpty()) {
            bodyHost.addView(
                infoBanner("Le app preferite compariranno qui"),
                lp(176f, 1040f, 590f, 78f)
            )
        } else {
            val count = favoriteApps.size
            val cardW = if (count >= 4) 166f else 185f
            val gap = if (count >= 4) 18f else 30f
            val total = cardW * count + gap * (count - 1)
            var x = (REF_W - total) / 2f
            favoriteApps.forEachIndexed { index, fav ->
                val tile = homeFavorite(fav)
                bodyHost.addView(tile, lp(x, 1050f, cardW, 220f))
                tile.setOnClickListener { onFavoriteClick?.invoke(index) }
                x += cardW + gap
            }
        }
    }

    private fun mainButton(): View {
        val connected = isConnectedStatus()
        val now = SystemClock.uptimeMillis()
        val (label, imageRes) = when {
            selection == Selection.READY -> "START" to R.drawable.btn_start_model_idle
            connected && now < connectedFlashUntil -> "START" to R.drawable.btn_start_model_green
            connected -> "STOP" to R.drawable.btn_start_model_stop
            selection == Selection.STOP -> "STOP" to R.drawable.btn_start_model_stop
            else -> "START" to R.drawable.btn_start_model_search
        }

        return FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            contentDescription = label
            clipChildren = false
            clipToPadding = false
            elevation = pxH(2f).toFloat()

            // The selected futuristic model already contains the visible START/STOP lettering.
            // Do not overlay another central TextView: that was the source of the unwanted
            // black-looking badge/plate behind the label in previous iterations.
            addView(ImageView(context).apply {
                setImageResource(imageRes)
                scaleType = ImageView.ScaleType.FIT_XY
                contentDescription = null
            }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    private fun statusCard(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(0xF8060B07.toInt(), if (stateColor == Color.TRANSPARENT) GREEN else stateColor, 32f, 1.25f)
            setPadding(pxX(25f), 0, pxX(22f), 0)
        }
        row.addView(icon(IconKind.CHECK, GREEN), LinearLayout.LayoutParams(pxX(85f), pxH(106f)))
        val divider = View(context).apply { setBackgroundColor(0xFF535953.toInt()) }
        row.addView(divider, LinearLayout.LayoutParams(pxX(1f).coerceAtLeast(1), pxH(72f)).apply {
            leftMargin = pxX(9f); rightMargin = pxX(21f)
        })
        val value = when {
            selection == Selection.READY -> "Pronto"
            headerStatus.isNotBlank() -> headerStatus
            else -> stateTitle
        }
        val tv = TextView(context).apply {
            val s = SpannableString("Stato: $value")
            val start = "Stato: ".length
            s.setSpan(ForegroundColorSpan(GREEN), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx(27f))
            setTextColor(0xFFE4E4E4.toInt())
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(tv, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        return row
    }

    private fun homeFavorite(fav: FavoriteVisual): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val box = FrameLayout(context).apply {
            background = roundedBg(0xF5080C09.toInt(), 0xFF4F6539.toInt(), 27f, 1f)
        }
        val iv = ImageView(context).apply {
            setImageDrawable(fav.icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pxX(26f), pxH(18f), pxX(26f), pxH(18f))
        }
        box.addView(iv, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        col.addView(box, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(166f)))
        col.addView(text(fav.label.take(14), 21f, Color.WHITE, false).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(54f)))
        return col
    }

    private fun infoPill(kind: IconKind, prefix: String, value: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(0xE8040705.toInt(), GREEN, 46f, 1.1f)
            setPadding(pxX(28f), 0, pxX(24f), 0)
        }
        val pillIcon: View = if (kind == IconKind.BIKE) {
            bikeImageIcon()
        } else {
            icon(kind, GREEN)
        }
        row.addView(pillIcon, LinearLayout.LayoutParams(pxX(104f), pxH(84f)))
        val tv = TextView(context).apply {
            val shownValue = value.take(48)
            val full = "$prefix  ${shownValue}"
            val s = SpannableString(full)
            val at = full.indexOf(shownValue)
            if (at >= 0) s.setSpan(android.text.style.StyleSpan(Typeface.BOLD), at, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = s
            setTextColor(0xFFE8E8E8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx(22f))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        row.addView(tv, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = pxX(6f) })
        return row
    }

    private fun bikeImageIcon(): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_bike_status)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = null
        adjustViewBounds = true
    }

    // -----------------------------------------------------------------------------------------
    // GARAGE
    // -----------------------------------------------------------------------------------------

    private fun buildGarage() {
        bodyHost.addView(sectionTitle("Garage"), lp(58f, 47f, 824f, 135f))

        val topRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(bikeImageIcon(), LinearLayout.LayoutParams(pxX(78f), pxH(62f)))
        topRow.addView(text("I tuoi profili moto", 26f, Color.WHITE, true).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = pxX(18f) })
        val count = bikeProfiles.size
        val countText = SpannableString("$count / 3 salvati").apply {
            setSpan(ForegroundColorSpan(GREEN), 0, count.toString().length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        topRow.addView(TextView(context).apply {
            text = countText
            setTextColor(0xFFBEBEBE.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx(23f))
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }, LinearLayout.LayoutParams(pxX(205f), LayoutParams.MATCH_PARENT))
        bodyHost.addView(topRow, lp(77f, 198f, 790f, 65f))

        var y = 285f
        if (bikeProfiles.isEmpty()) {
            bodyHost.addView(emptyGarageCard(), lp(58f, y, 824f, 295f))
            y += 320f
        } else {
            bikeProfiles.forEachIndexed { index, bike ->
                val card = bikeCard(index, bike)
                val cardH = if (bike.active) 375f else 300f
                bodyHost.addView(card, lp(58f, y, 824f, cardH))
                y += cardH + 25f
            }
        }
        if (bikeProfiles.size < 3 && y < 1160f) {
            val add = addQrCard()
            bodyHost.addView(add, lp(58f, y, 824f, 215f))
            add.setOnClickListener { onQrClick?.invoke() }
            y += 235f
        }
        bodyHost.addView(infoBanner("Massimo 3 profili salvabili"), lp(176f, min(y + 18f, 1288f), 590f, 78f))
    }

    private fun emptyGarageCard(): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg(0xE8060907.toInt(), 0xFF465249.toInt(), 30f, 1f)
            isClickable = true
            setOnClickListener { onQrClick?.invoke() }
        }
        col.addView(bikeImageIcon(), LinearLayout.LayoutParams(pxX(118f), pxH(76f)))
        col.addView(text("Nessun profilo moto", 27f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(52f)))
        col.addView(text("Aggiungi la tua moto con QR o profilo locale", 19f, 0xFFB7B7B7.toInt(), false).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(42f)))
        return col
    }

    private fun bikeCard(index: Int, bike: BikeVisual): View {
        val row = FrameLayout(context).apply {
            background = roundedBg(0xF3060907.toInt(), if (bike.active) GREEN else 0xFF4B524D.toInt(), 30f, if (bike.active) 1.7f else 1f)
            isClickable = true
            isFocusable = true
            contentDescription = "Profilo moto ${bike.name}"
            setOnClickListener { onBikeProfileClick?.invoke(index) }
        }
        val image = ImageView(context).apply {
            setImageDrawable(bike.image)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.96f
        }
        row.addView(image, FrameLayout.LayoutParams(pxX(350f), LayoutParams.MATCH_PARENT, Gravity.START))
        if (bike.active) {
            row.addView(View(context).apply {
                background = roundedBg(GREEN, GREEN, 4f, 0f)
            }, FrameLayout.LayoutParams(pxX(8f), pxH(292f), Gravity.START or Gravity.CENTER_VERTICAL))
        }
        val fade = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00000000, 0xA8060907.toInt(), 0xF3060907.toInt()))
        }
        row.addView(fade, FrameLayout.LayoutParams(pxX(220f), LayoutParams.MATCH_PARENT).apply { leftMargin = pxX(200f) })

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        texts.addView(text(bike.name.take(25), 43f, Color.WHITE, true), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(58f)))
        bike.description?.takeIf { it.isNotBlank() }?.let { description ->
            texts.addView(
                text(description.take(38), 24f, 0xFFE2E2E2.toInt(), false),
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(38f))
            )
        }
        texts.addView(
            text(bike.model.take(38), 21f, 0xFFB8B8B8.toInt(), false),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(36f))
        )
        if (bike.active) {
            val pill = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                background = roundedBg(0x05000000, GREEN, 28f, 1f)
                addView(icon(IconKind.CHECK, GREEN), LinearLayout.LayoutParams(pxX(42f), pxH(42f)))
                addView(text("Profilo attivo", 22f, GREEN, false).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(pxX(175f), LayoutParams.MATCH_PARENT))
            }
            texts.addView(pill, LinearLayout.LayoutParams(pxX(235f), pxH(62f)).apply { topMargin = pxH(13f) })
        }
        row.addView(texts, FrameLayout.LayoutParams(pxX(390f), LayoutParams.MATCH_PARENT).apply { leftMargin = pxX(365f) })

        val menu = TextView(context).apply {
            text = "\u22EE"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx(43f))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Opzioni profilo ${bike.name}"
            setOnClickListener { onBikeProfileMenuClick?.invoke(index) }
        }
        row.addView(menu, FrameLayout.LayoutParams(pxX(80f), LayoutParams.MATCH_PARENT, Gravity.END))
        return row
    }

    private fun addQrCard(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = dashedBg()
            setPadding(pxX(115f), 0, pxX(35f), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Aggiungi una moto con QR"
        }
        row.addView(icon(IconKind.QR, GREEN), LinearLayout.LayoutParams(pxX(112f), pxH(112f)))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        texts.addView(text("Aggiungi con QR", 30f, Color.WHITE, true))
        texts.addView(text("Per moto compatibili", 22f, 0xFFB7B7B7.toInt(), false))
        row.addView(texts, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = pxX(42f) })
        return row
    }

    // -----------------------------------------------------------------------------------------
    // FAVORITES
    // -----------------------------------------------------------------------------------------

    private fun buildFavorites() {
        bodyHost.addView(sectionTitle("App preferite"), lp(58f, 47f, 824f, 135f))
        bodyHost.addView(text("Scegli fino a 4 app", 27f, 0xFFC0C0C0.toInt(), false).apply { gravity = Gravity.CENTER }, lp(265f, 198f, 410f, 55f))

        val slots = arrayOf(
            floatArrayOf(142.5f, 377f), floatArrayOf(486.5f, 377f),
            floatArrayOf(142.5f, 811f), floatArrayOf(486.5f, 811f)
        )
        slots.forEachIndexed { index, pos ->
            val configured = index < favoriteApps.size
            val view = if (configured) favoriteManageCard(index, favoriteApps[index]) else addFavoriteCard()
            bodyHost.addView(view, lp(pos[0], pos[1], 312f, 405f))
            if (!configured) {
                view.setOnClickListener { onFavoriteAddClick?.invoke() ?: onFavoriteManageClick?.invoke() }
            }
        }
        bodyHost.addView(infoBanner("Le app selezionate saranno visibili nella Home."), lp(176f, 1246f, 590f, 78f))
    }

    private fun favoriteManageCard(index: Int, fav: FavoriteVisual): View {
        val card = FrameLayout(context).apply {
            background = roundedBg(0xF7060A07.toInt(), 0xFF53633E.toInt(), 30f, 1f)
            isClickable = true
            isFocusable = true
            contentDescription = "Modifica app preferita ${fav.label}"
            setOnClickListener { onFavoriteReplaceClick?.invoke(index) ?: onFavoriteManageClick?.invoke() }
        }
        card.addView(ImageView(context).apply {
            setImageDrawable(fav.icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pxX(38f), pxH(8f), pxX(38f), pxH(8f))
        }, FrameLayout.LayoutParams(pxX(190f), pxH(182f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = pxH(46f) })
        card.addView(text(fav.label.take(15), 30f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(58f), Gravity.TOP).apply { topMargin = pxH(238f) })

        val change = text("Cambia", 22f, GREEN, false).apply {
            gravity = Gravity.CENTER
            background = roundedBg(0x00000000, GREEN, 23f, 1f)
            isClickable = true
            isFocusable = true
            contentDescription = "Cambia ${fav.label}"
            setOnClickListener { onFavoriteReplaceClick?.invoke(index) ?: onFavoriteManageClick?.invoke() }
        }
        card.addView(change, FrameLayout.LayoutParams(pxX(160f), pxH(58f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = pxH(316f) })

        val remove = FrameLayout(context).apply {
            background = roundedBg(0x08000000, 0xFFE4E4E4.toInt(), 28f, 1f)
            isClickable = true
            isFocusable = true
            contentDescription = "Rimuovi ${fav.label}"
            setOnClickListener { onFavoriteRemoveClick?.invoke(index) }
        }
        remove.addView(icon(IconKind.CLOSE, 0xFFF1F1F1.toInt()), FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = pxX(11f); rightMargin = pxX(11f); topMargin = pxH(11f); bottomMargin = pxH(11f)
        })
        card.addView(remove, FrameLayout.LayoutParams(pxX(50f), pxH(50f), Gravity.TOP or Gravity.END).apply {
            topMargin = pxH(19f); rightMargin = pxX(19f)
        })
        return card
    }

    private fun addFavoriteCard(): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = dashedBg()
            isClickable = true
            isFocusable = true
            contentDescription = "Aggiungi app preferita"
        }
        col.addView(icon(IconKind.PLUS, GREEN), LinearLayout.LayoutParams(pxX(118f), pxH(118f)))
        col.addView(text("Aggiungi app", 26f, GREEN, false).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(70f)))
        return col
    }

    // -----------------------------------------------------------------------------------------
    // SUPPORT
    // -----------------------------------------------------------------------------------------

    private fun buildSupport() {
        bodyHost.addView(sectionTitle("Supporto"), lp(58f, 47f, 824f, 135f))
        bodyHost.addView(supportTabs(), lp(101f, 198f, 740f, 78f))
        if (supportTab == SupportTab.LOG) buildSupportLog() else buildSupportAssistant()
    }

    private fun supportTabs(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(0xF3030504.toInt(), 0xFF5B625D.toInt(), 38f, 1f)
        }
        val log = text("Log", 23f, if (supportTab == SupportTab.LOG) GREEN else 0xFFB7B7B7.toInt(), false).apply {
            gravity = Gravity.CENTER
            background = if (supportTab == SupportTab.LOG) roundedBg(0xFF0A1008.toInt(), GREEN, 38f, 1f) else null
            isClickable = true
            setOnClickListener { supportTab = SupportTab.LOG; rebuild() }
        }
        val ai = text("Assistente", 23f, if (supportTab == SupportTab.ASSISTANT) GREEN else 0xFFB7B7B7.toInt(), false).apply {
            gravity = Gravity.CENTER
            background = if (supportTab == SupportTab.ASSISTANT) roundedBg(0xFF0A1008.toInt(), GREEN, 38f, 1f) else null
            isClickable = true
            setOnClickListener { supportTab = SupportTab.ASSISTANT; rebuild() }
        }
        row.addView(log, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        row.addView(ai, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        return row
    }

    private fun buildSupportLog() {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xF5070A08.toInt(), 0xFF52633E.toInt(), 30f, 1f)
            setPadding(pxX(30f), pxH(20f), pxX(30f), pxH(14f))
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(icon(IconKind.LOG, GREEN), LinearLayout.LayoutParams(pxX(60f), pxH(60f)))
        head.addView(text("Log MotoLink", 27f, Color.WHITE, true).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, pxH(64f), 1f).apply { leftMargin = pxX(20f) })
        head.addView(text("\u25CF  LIVE", 19f, GREEN, true).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }, LinearLayout.LayoutParams(pxX(128f), pxH(64f)))
        card.addView(head, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(74f)))
        card.addView(View(context).apply { setBackgroundColor(0xFF303531.toInt()) }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(1f).coerceAtLeast(1)))

        val logs = supportLogs.toList()
        val logScroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = true
        }
        val logList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (logs.isEmpty()) {
            logList.addView(text("Nessun evento registrato.", 20f, 0xFFB5B5B5.toInt(), false).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(310f)))
        } else {
            logs.forEach { raw ->
                logList.addView(supportLogRow(raw), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                logList.addView(View(context).apply { setBackgroundColor(0xFF303531.toInt()) }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(1f).coerceAtLeast(1)))
            }
        }
        logScroll.addView(logList, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.addView(logScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

        // Use almost all the available Support body, leaving only the action row below.
        bodyHost.addView(card, lp(101f, 303f, 740f, 950f))

        val clear = actionButton(IconKind.TRASH, "Pulisci") { onClearLogClick?.invoke() }
        val share = actionButton(IconKind.SHARE, "Condividi") { onLogClick?.invoke() }
        bodyHost.addView(clear, lp(101f, 1275f, 360f, 92f))
        bodyHost.addView(share, lp(481f, 1275f, 360f, 92f))
    }

    private fun buildSupportAssistant() {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xF5070A08.toInt(), 0xFF52633E.toInt(), 30f, 1f)
            setPadding(pxX(28f), pxH(22f), pxX(28f), pxH(22f))
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(icon(IconKind.ROBOT, GREEN), LinearLayout.LayoutParams(pxX(70f), pxH(70f)))
        val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        names.addView(text("Assistente MotoLink", 25f, Color.WHITE, true))
        names.addView(text("Al tuo servizio", 18f, 0xFFB5B5B5.toInt(), false))
        head.addView(names, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = pxX(14f) })
        head.addView(text("●", 20f, GREEN, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(pxX(38f), LayoutParams.MATCH_PARENT))
        val info = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Come funziona l'Assistente"
            setOnClickListener { onAssistantInfoClick?.invoke() }
            addView(icon(IconKind.INFO, GREEN, 0.92f), FrameLayout.LayoutParams(pxX(48f), pxH(48f), Gravity.CENTER))
        }
        head.addView(info, LinearLayout.LayoutParams(pxX(58f), LayoutParams.MATCH_PARENT))
        card.addView(head, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(82f)))

        if (assistantQuestion.isNotBlank()) {
            val q = text(assistantQuestion, 20f, Color.WHITE, false).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pxX(24f), pxH(10f), pxX(20f), pxH(10f))
                background = roundedBg(0xFF0A1709.toInt(), 0xFF4B7618.toInt(), 22f, 1f)
            }
            card.addView(q, LinearLayout.LayoutParams((BODY_CARD_W * 0.68f * sx).roundToInt(), pxH(145f)).apply {
                gravity = Gravity.END
                topMargin = pxH(18f)
            })
        }

        val a = text(assistantAnswer, 19f, Color.WHITE, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pxX(24f), pxH(12f), pxX(22f), pxH(12f))
            background = roundedBg(0xFF0A0D0B.toInt(), 0xFF4D554E.toInt(), 22f, 1f)
        }
        card.addView(a, LinearLayout.LayoutParams((BODY_CARD_W * 0.82f * sx).roundToInt(), pxH(if (assistantShowWhatsApp) 220f else 250f)).apply {
            topMargin = pxH(18f)
        })

        if (assistantShowWhatsApp) {
            val whatsapp = actionButton(IconKind.WHATSAPP, "Apri gruppo WhatsApp") { onAssistantWhatsAppClick?.invoke() }
            card.addView(whatsapp, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(76f)).apply { topMargin = pxH(12f) })
        }

        card.addView(View(context), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val composer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val edit = EditText(context).apply {
            hint = "Scrivi un messaggio…"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9EA6A1.toInt())
            textSize = 18f
            maxLines = 3
            minLines = 1
            setPadding(pxX(22f), 0, pxX(18f), 0)
            background = roundedBg(0xF5060907.toInt(), 0xFF4F5851.toInt(), 24f, 1f)
            setText(assistantDraft)
            setSelection(text.length)
        }
        assistantComposerEdit = edit
        composer.addView(edit, LinearLayout.LayoutParams(0, pxH(88f), 1f).apply { rightMargin = pxX(12f) })
        val send = actionButton(IconKind.SEND, "Invia") {
            val message = edit.text.toString().trim()
            if (message.isNotBlank()) {
                assistantDraft = ""
                edit.text.clear()
                onAssistantSend?.invoke(message)
            }
        }
        composer.addView(send, LinearLayout.LayoutParams(pxX(190f), pxH(88f)))
        card.addView(composer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(88f)).apply { topMargin = pxH(12f) })
        bodyHost.addView(card, lp(101f, 303f, 740f, 970f))

        // Privacy belongs to the Support page, not to the conversation card.
        // Use the same information treatment already approved in App preferite.
        bodyHost.addView(
            infoBanner(
                "Privacy: le domande sono elaborate online. MotoLink non salva chat o Log nel proprio database. Il Log viene inviato solo con Condividi > Assistente e viene filtrato prima dell'invio.",
                textSizeRef = 14.5f
            ),
            lp(101f, 1284f, 740f, 88f)
        )
    }

    private fun supportLogRow(raw: String): View {
        val timestamp = Regex("\\b\\d{2}:\\d{2}:\\d{2}\\b").find(raw)?.value ?: "--:--:--"
        val plain = logPresentation(raw)
        val (level, accent) = when {
            plain.contains("error", true) || plain.contains("fall", true) || plain.contains("eccezione", true) -> "ERROR" to 0xFFFF5B52.toInt()
            plain.contains("warn", true) || plain.contains("attenzione", true) || plain.contains("timeout", true) -> "WARN" to 0xFFFFDB24.toInt()
            else -> "INFO" to 0xFF56EEA1.toInt()
        }
        val message = plain
            .replace(Regex("^\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?\\s*"), "")
            .replace(Regex("^(INFO|WARN|WARNING|ERROR)[:\\s-]*", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "Evento MotoLink" }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, pxH(10f), 0, pxH(10f))
            minimumHeight = pxH(92f)
        }
        val meta = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        meta.addView(text(timestamp, 17f, 0xFFB8B8B8.toInt(), false).apply {
            gravity = Gravity.START
            maxLines = 1
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(34f)))
        val levelLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        levelLine.addView(View(context).apply { background = roundedBg(accent, accent, 12f, 0f) }, LinearLayout.LayoutParams(pxX(13f), pxH(13f)).apply { rightMargin = pxX(10f) })
        levelLine.addView(text(level, 17f, accent, true).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, pxH(34f), 1f))
        meta.addView(levelLine, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(34f)))
        row.addView(meta, LinearLayout.LayoutParams(pxX(155f), LayoutParams.WRAP_CONTENT).apply { rightMargin = pxX(14f) })
        row.addView(text(message, 18f, 0xFFE5E5E5.toInt(), false).apply {
            gravity = Gravity.TOP or Gravity.START
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.08f)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun actionButton(kind: IconKind, label: String, click: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(0xF5060907.toInt(), 0xFF4F5851.toInt(), 27f, 1f)
            isClickable = true
            setPadding(pxX(18f), 0, pxX(18f), 0)
            setOnClickListener { click() }
        }

        val iconSlot = FrameLayout(context)
        if (kind == IconKind.WHATSAPP) {
            val whatsapp = ImageView(context).apply {
                setImageResource(R.drawable.ic_whatsapp_brand)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                adjustViewBounds = false
                contentDescription = "WhatsApp"
            }
            iconSlot.addView(whatsapp, FrameLayout.LayoutParams(pxX(46f), pxH(46f), Gravity.CENTER))
        } else {
            iconSlot.addView(icon(kind, GREEN), FrameLayout.LayoutParams(pxX(58f), pxH(58f), Gravity.CENTER))
        }
        row.addView(iconSlot, LinearLayout.LayoutParams(pxX(58f), pxH(58f)).apply {
            if (kind == IconKind.WHATSAPP) rightMargin = pxX(16f) else rightMargin = pxX(18f)
        })
        row.addView(text(label, 22f, Color.WHITE, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        return row
    }

    // -----------------------------------------------------------------------------------------
    // SETTINGS
    // -----------------------------------------------------------------------------------------

    private fun buildSettings() {
        bodyHost.addView(sectionTitle("Impostazioni"), lp(58f, 35f, 824f, 125f))
        bodyHost.addView(settingCard(IconKind.POCKET, "Modalità tasca", "Disattiva schermo e comandi per evitare tocchi accidentali.", pocketModeEnabled, true) { onPocketModeClick?.invoke() }, lp(58f, 190f, 824f, 185f))
        bodyHost.addView(settingCard(IconKind.PLAY, "Animazione iniziale", "Riproduci l’animazione di apertura dell’app.", introEnabled, true) { onIntroToggleClick?.invoke() }, lp(58f, 395f, 824f, 185f))
        bodyHost.addView(settingCard(IconKind.BOOK, "Guida iniziale", "Mostra la guida completa alla prossima apertura.", guideNextLaunchEnabled, true) { onGuideClick?.invoke() }, lp(58f, 600f, 824f, 185f))
        bodyHost.addView(settingCard(IconKind.WALLPAPER, "Sfondo", "Cambia lo sfondo in base alla moto collegata.", dynamicBackgroundEnabled, true) { onBackgroundToggleClick?.invoke() }, lp(58f, 805f, 824f, 170f))
        bodyHost.addView(settingCard(IconKind.ZOOM, "Adattamento", MirrorAdaptationConfig.SETTINGS_DESCRIPTION, adaptationEnabled, true) { onAdaptationClick?.invoke() }, lp(58f, 995f, 824f, 175f))
        bodyHost.addView(versionCard(), lp(58f, 1190f, 824f, 145f))
    }

    private fun settingCard(kind: IconKind, title: String, desc: String, enabled: Boolean, toggle: Boolean, click: () -> Unit): View {
        val row = FrameLayout(context).apply {
            background = roundedBg(0xF5070A08.toInt(), 0xFF4D5F40.toInt(), 30f, 1f)
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener { click() }
        }
        val iconWrap = FrameLayout(context).apply { background = roundedBg(0xFF101410.toInt(), 0xFF465346.toInt(), 62f, 1f) }
        iconWrap.addView(icon(kind, GREEN), FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { leftMargin = pxX(21f); rightMargin = pxX(21f); topMargin = pxH(21f); bottomMargin = pxH(21f) })
        row.addView(iconWrap, FrameLayout.LayoutParams(pxX(130f), pxH(130f), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = pxX(30f) })

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        texts.addView(text(title, 32f, Color.WHITE, true), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(54f)))
        texts.addView(text(desc, 22f, 0xFFB7B7B7.toInt(), false), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(82f)))
        row.addView(texts, FrameLayout.LayoutParams(pxX(430f), LayoutParams.MATCH_PARENT).apply { leftMargin = pxX(193f) })

        if (toggle) {
            row.addView(toggleView(enabled), FrameLayout.LayoutParams(pxX(138f), pxH(68f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = pxX(37f) })
        } else {
            row.addView(icon(IconKind.CHEVRON, GREEN), FrameLayout.LayoutParams(pxX(54f), pxH(74f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = pxX(38f) })
        }
        return row
    }

    private fun versionCard(): View {
        val row = FrameLayout(context).apply { background = roundedBg(0xF5070A08.toInt(), 0xFF4D5F40.toInt(), 30f, 1f) }
        val iconWrap = FrameLayout(context).apply { background = roundedBg(0xFF101410.toInt(), 0xFF465346.toInt(), 62f, 1f) }
        iconWrap.addView(icon(IconKind.INFO, GREEN), FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { leftMargin = pxX(21f); rightMargin = pxX(21f); topMargin = pxH(21f); bottomMargin = pxH(21f) })
        row.addView(iconWrap, FrameLayout.LayoutParams(pxX(126f), pxH(126f), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = pxX(32f) })
        row.addView(text("Versione app", 32f, Color.WHITE, true).apply { gravity = Gravity.CENTER_VERTICAL }, FrameLayout.LayoutParams(pxX(335f), LayoutParams.MATCH_PARENT).apply { leftMargin = pxX(193f) })
        row.addView(text(appVersionName(), 25f, GREEN, false).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; maxLines = 1; ellipsize = TextUtils.TruncateAt.START }, FrameLayout.LayoutParams(pxX(270f), LayoutParams.MATCH_PARENT, Gravity.END).apply { rightMargin = pxX(35f) })
        return row
    }

    private fun toggleView(enabled: Boolean): View {
        val root = FrameLayout(context).apply {
            background = roundedBg(if (enabled) GREEN else 0xFF4B514C.toInt(), if (enabled) GREEN else 0xFF626963.toInt(), 36f, 1f)
        }
        val knob = View(context).apply { background = roundedBg(Color.WHITE, Color.WHITE, 38f, 0f) }
        root.addView(knob, FrameLayout.LayoutParams(pxX(58f), pxH(58f), if (enabled) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = pxX(5f); rightMargin = pxX(5f)
        })
        return root
    }

    // -----------------------------------------------------------------------------------------
    // CREDITS
    // -----------------------------------------------------------------------------------------

    private fun buildCredits() {
        bodyHost.addView(sectionTitle("Crediti"), lp(58f, 47f, 824f, 135f))
        bodyHost.addView(authorCard(), lp(140f, 205f, 663f, 580f))
        bodyHost.addView(communityCard(), lp(140f, 805f, 663f, 535f))
        bodyHost.addView(text("© 2026 Emanuele. Tutti i diritti riservati.", 21f, 0xFF9D9D9D.toInt(), false).apply { gravity = Gravity.CENTER }, lp(165f, 1355f, 615f, 45f))
    }

    private fun authorCard(): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xF5070A08.toInt(), 0xFF52633E.toInt(), 30f, 1f)
            setPadding(pxX(40f), pxH(34f), pxX(40f), pxH(32f))
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(iconBadge(IconKind.USER, 120f), LinearLayout.LayoutParams(pxX(120f), pxH(120f)))
        val name = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        name.addView(text("Autore", 27f, 0xFFB7B7B7.toInt(), false))
        name.addView(text("Emanuele", 54f, Color.WHITE, true))
        head.addView(name, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = pxX(30f) })
        col.addView(head, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(132f)))
        col.addView(View(context).apply { setBackgroundColor(0xFF4F7A21.toInt()) }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(1f).coerceAtLeast(1)).apply { topMargin = pxH(14f); bottomMargin = pxH(25f) })
        val about = "MotoLink è un’app pensata per offrire il mirroring del tuo smartphone sul display della tua moto compatibile.\n\nGestisci profili, app preferite, strumenti di supporto e assistenza integrata per un’esperienza di guida sempre connessa."
        col.addView(accentedText(about, 25f, 0xFFE0E0E0.toInt(), "MotoLink").apply {
            gravity = Gravity.TOP
            setLineSpacing(pxH(7f).toFloat(), 1f)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun communityCard(): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xF5070A08.toInt(), 0xFF52633E.toInt(), 30f, 1f)
            setPadding(pxX(40f), pxH(28f), pxX(40f), pxH(28f))
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(iconBadge(IconKind.GROUP, 110f), LinearLayout.LayoutParams(pxX(110f), pxH(110f)))
        head.addView(text("Grazie alla community", 31f, Color.WHITE, true).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = pxX(28f) })
        col.addView(head, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(116f)))
        val msg = "Un ringraziamento speciale a tutti i membri del gruppo WhatsApp “MotoLink Mirroring” per il supporto, i test e i preziosi feedback che hanno reso possibile la creazione di MotoLink."
        col.addView(accentedText(msg, 24f, 0xFFE0E0E0.toInt(), "MotoLink Mirroring").apply {
            setLineSpacing(pxH(6f).toFloat(), 1f)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = pxH(16f) })
        val button = actionButton(IconKind.WHATSAPP, "Entra nel gruppo") { onCreditsGroupClick?.invoke() }
        button.background = roundedBg(0xFF071006.toInt(), GREEN, 34f, 1.3f)
        col.addView(button, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(92f)).apply { topMargin = pxH(20f) })
        return col
    }


    // -----------------------------------------------------------------------------------------
    // GUIDA INIZIALE INTERATTIVA
    // -----------------------------------------------------------------------------------------

    private fun applyGuideStep() {
        if (!guideActive || guideSteps.isEmpty()) return
        guideStepIndex = guideStepIndex.coerceIn(0, guideSteps.lastIndex)
        val step = guideSteps[guideStepIndex]
        page = step.page
        if (step.page == Page.SUPPORT) supportTab = step.supportTab ?: SupportTab.LOG
        else supportTab = SupportTab.LOG
        rebuild()
    }

    private fun guideNext() {
        if (guideStepIndex >= guideSteps.lastIndex) {
            finishGuide()
        } else {
            guideStepIndex++
            applyGuideStep()
        }
    }

    private fun guidePrevious() {
        if (guideStepIndex <= 0) return
        guideStepIndex--
        applyGuideStep()
    }

    private fun finishGuide() {
        if (!guideActive) return
        guideActive = false
        guideStepIndex = 0
        page = Page.HOME
        supportTab = SupportTab.LOG
        rebuild()
        onOnboardingFinished?.invoke()
    }

    private fun guideTargetRect(step: GuideStep): RectF {
        if (step.title == "HOME · Navigazione") {
            val padX = pxX(10f).toFloat()
            val padY = pxH(10f).toFloat()
            val navLeft = pxX(NAV_REF_X).toFloat()
            val navTop = (height - bottomInset - navHeightPx).toFloat()
            val navRight = (width - pxX(NAV_REF_X)).toFloat()
            val navBottom = (height - bottomInset).toFloat()
            return RectF(
                (navLeft - padX).coerceAtLeast(4f),
                (navTop - padY).coerceAtLeast((topInset + 2).toFloat()),
                (navRight + padX).coerceAtMost((width - 4).toFloat()),
                (navBottom + padY).coerceAtMost((height - bottomInset + 4).toFloat())
            )
        }

        val target = if (step.page == Page.GARAGE && step.title == "GARAGE · Profili moto") {
            var profilesHeight = 0f
            if (bikeProfiles.isEmpty()) {
                profilesHeight = 295f
            } else {
                bikeProfiles.forEachIndexed { index, bike ->
                    val cardH = if (bike.active) 375f else 300f
                    profilesHeight += cardH
                    if (index < bikeProfiles.lastIndex) profilesHeight += 25f
                }
            }
            GuideTarget(58f, 285f, 824f, profilesHeight.coerceAtMost(875f))
        } else if (step.page == Page.GARAGE && step.title == "GARAGE · Aggiungi moto") {
            // The QR card moves vertically depending on how many profiles are saved.
            // Mirror the exact Garage layout calculation so the spotlight always follows
            // the real "Aggiungi con QR" card instead of using a fixed coordinate.
            var qrY = 285f
            if (bikeProfiles.isEmpty()) {
                qrY += 320f
            } else {
                bikeProfiles.forEach { bike ->
                    val cardH = if (bike.active) 375f else 300f
                    qrY += cardH + 25f
                }
            }
            // With 3 saved profiles the add-card is intentionally hidden by the Garage.
            // In that edge case highlight the profile-count area instead of an empty region.
            if (bikeProfiles.size >= 3 || qrY >= 1160f) {
                GuideTarget(58f, 188f, 824f, 92f)
            } else {
                GuideTarget(58f, qrY, 824f, 215f)
            }
        } else {
            step.target
        }

        val padX = pxX(10f).toFloat()
        val padY = pxH(10f).toFloat()
        val left = pxX(target.x).toFloat() - padX
        val top = topInset + pxY(target.y).toFloat() - padY
        val right = pxX(target.x + target.w).toFloat() + padX
        val bottom = topInset + pxY(target.y).toFloat() + pxH(target.h).toFloat() + padY
        return RectF(
            left.coerceAtLeast(4f),
            top.coerceAtLeast((topInset + 2).toFloat()),
            right.coerceAtMost((width - 4).toFloat()),
            bottom.coerceAtMost((height - bottomInset - 4).toFloat())
        )
    }

    private fun buildGuideOverlay() {
        if (!guideActive || guideStepIndex !in guideSteps.indices) return
        val step = guideSteps[guideStepIndex]
        val targetRect = guideTargetRect(step)

        val overlay = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Guida iniziale. Tocca lo schermo per continuare."
            // Any normal tap on the overlay advances the tour. Child controls such as
            // SALTA, INDIETRO and AVANTI remain clickable and keep their dedicated action.
            setOnClickListener { guideNext() }
        }
        addView(overlay, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        overlay.addView(
            GuideSpotlightView(context, targetRect, pxX(28f).toFloat()),
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        val skip = TextView(context).apply {
            text = "SALTA"
            setTextColor(GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx(21f))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedBg(0xE9071008.toInt(), GREEN, 25f, 1f)
            isClickable = true
            isFocusable = true
            setOnClickListener { finishGuide() }
        }
        overlay.addView(skip, FrameLayout.LayoutParams(pxX(125f), pxH(60f), Gravity.TOP or Gravity.END).apply {
            rightMargin = pxX(24f)
            topMargin = topInset + pxH(18f)
        })

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xF50A100C.toInt(), GREEN, 30f, 1.4f)
            setPadding(pxX(28f), pxH(24f), pxX(28f), pxH(22f))
        }
        card.addView(guideTitleText(step.title), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(52f)))
        val guideBodyText = accentedText(
            step.body,
            20.5f,
            0xFFE1E5E2.toInt(),
            "HOME", "GARAGE", "PREFERITE", "SUPPORTO", "IMPOSTAZIONI", "CREDITI", "ADATTAMENTO", "MotoLink"
        ).apply {
            setLineSpacing(pxH(4f).toFloat(), 1.10f)
            setPadding(0, 0, pxX(8f), pxH(8f))
        }
        val guideBodyScroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(guideBodyText, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        card.addView(guideBodyScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = pxH(8f) })

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(text("${guideStepIndex + 1} / ${guideSteps.size}", 18f, 0xFFAEB7B1.toInt(), true).apply {
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        if (guideStepIndex > 0) {
            val back = guideButton("INDIETRO", false) { guidePrevious() }
            footer.addView(back, LinearLayout.LayoutParams(pxX(160f), pxH(62f)).apply { rightMargin = pxX(14f) })
        }
        val next = guideButton(if (guideStepIndex == guideSteps.lastIndex) "FINE" else "AVANTI", true) { guideNext() }
        footer.addView(next, LinearLayout.LayoutParams(pxX(160f), pxH(62f)))
        card.addView(footer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(70f)).apply { topMargin = pxH(12f) })

        val cardParams = FrameLayout.LayoutParams(width - pxX(48f), pxH(375f))
        cardParams.leftMargin = pxX(24f)
        val placeTop = targetRect.centerY() > height * 0.58f
        if (placeTop) {
            cardParams.topMargin = topInset + pxH(92f)
        } else {
            cardParams.gravity = Gravity.BOTTOM
            cardParams.bottomMargin = bottomInset + navHeightPx + pxH(20f)
        }
        overlay.addView(card, cardParams)
    }

    private fun guideTitleText(value: String): TextView = TextView(context).apply {
        val styled = SpannableString(value)
        val separator = value.indexOf(" · ")
        if (separator > 0) {
            styled.setSpan(ForegroundColorSpan(GREEN), 0, separator, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(ForegroundColorSpan(Color.WHITE), separator, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text = styled
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx(27f))
        typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
        textScaleX = 1.04f
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun guideButton(label: String, primary: Boolean, click: () -> Unit): View = TextView(context).apply {
        text = label
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx(18f))
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        gravity = Gravity.CENTER
        background = roundedBg(
            if (primary) GREEN else 0xFF101712.toInt(),
            GREEN,
            24f,
            1f
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
    }

    private class GuideSpotlightView(
        context: Context,
        private val hole: RectF,
        private val radius: Float
    ) : View(context) {
        private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC4000000.toInt() }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GREEN
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x665BFF2D
            style = Paint.Style.STROKE
            strokeWidth = 13f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                addRoundRect(hole, radius, radius, Path.Direction.CW)
            }
            canvas.drawPath(path, scrimPaint)
            canvas.drawRoundRect(hole, radius, radius, glowPaint)
            canvas.drawRoundRect(hole, radius, radius, borderPaint)
        }
    }

    // -----------------------------------------------------------------------------------------
    // NAVIGATION / SHARED VIEWS
    // -----------------------------------------------------------------------------------------

    private fun buildNav() {
        val items = arrayOf(
            Triple(Page.HOME, IconKind.HOME, "Home"),
            Triple(Page.GARAGE, IconKind.GARAGE, "Garage"),
            Triple(Page.FAVORITES, IconKind.STAR, "Preferite"),
            Triple(Page.SUPPORT, IconKind.HEADSET, "Supporto"),
            Triple(Page.SETTINGS, IconKind.GEAR, "Impostazioni"),
            Triple(Page.CREDITS, IconKind.INFO, "Crediti")
        )
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        navHost.addView(row, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        items.forEach { (target, kind, label) ->
            val active = page == target
            val item = FrameLayout(context).apply {
                isClickable = true
                isFocusable = true
                contentDescription = label
                setOnClickListener {
                    page = target
                    if (target != Page.SUPPORT) supportTab = SupportTab.LOG
                    rebuild()
                }
            }
            val iconBox = FrameLayout(context)
            iconBox.addView(icon(kind, if (active) GREEN else 0xFFD2D2D2.toInt(), navIconScale(kind)), FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
            item.addView(iconBox, FrameLayout.LayoutParams(pxX(60f), pxH(60f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = pxH(16f)
            })
            item.addView(text(label, 16.5f, if (active) GREEN else 0xFFD0D0D0.toInt(), false).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(40f), Gravity.TOP).apply { topMargin = pxH(95f) })
            if (active) item.addView(View(context).apply {
                background = roundedBg(GREEN, GREEN, 4f, 0f)
            }, FrameLayout.LayoutParams(pxX(34f), pxH(5f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = pxH(160f) })
            row.addView(item, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun navIconScale(kind: IconKind): Float = when (kind) {
        IconKind.HOME -> 1.10f
        IconKind.GARAGE -> 1.06f
        IconKind.STAR -> 1.04f
        IconKind.HEADSET -> 1.12f
        IconKind.GEAR -> 1.00f
        IconKind.INFO -> 1.08f
        else -> 1.08f
    }

    private fun logoView(sizeRef: Float): TextView = TextView(context).apply {
        val s = SpannableString("MotoLink")
        s.setSpan(ForegroundColorSpan(GREEN), 5, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text = s
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx(sizeRef))
        typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
        textScaleX = 1.14f
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    private fun sectionTitle(value: String): TextView = TextView(context).apply {
        text = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx(92f))
        typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
        textScaleX = 1.08f
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun pageTitle(value: String, underline: Boolean = true): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(text(value, 70f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
            textScaleX = 1.08f
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH(86f)))
        if (underline) {
            val line = View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x0077FF00, GREEN, 0x0077FF00))
            }
            col.addView(line, LinearLayout.LayoutParams(pxX(145f), pxH(4f)).apply { topMargin = pxH(4f) })
        }
        return col
    }

    private fun infoBanner(message: String, textSizeRef: Float = 18f): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(0xF3030504.toInt(), 0xFF4B524D.toInt(), 32f, 1f)
            setPadding(pxX(22f), 0, pxX(22f), 0)
        }
        row.addView(icon(IconKind.INFO, GREEN), LinearLayout.LayoutParams(pxX(42f), pxH(42f)))
        row.addView(text(message, textSizeRef, 0xFFD2D2D2.toInt(), false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(0f, 1.03f)
        }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = pxX(18f) })
        return row
    }

    private fun text(value: String, sizeRef: Float, color: Int, bold: Boolean): TextView = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx(sizeRef))
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun accentedText(value: String, sizeRef: Float, color: Int, vararg accents: String): TextView = TextView(context).apply {
        val styled = SpannableString(value)
        accents.forEach { accent ->
            var start = value.indexOf(accent)
            while (start >= 0) {
                styled.setSpan(ForegroundColorSpan(GREEN), start, start + accent.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                start = value.indexOf(accent, start + accent.length)
            }
        }
        text = styled
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx(sizeRef))
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun iconBadge(kind: IconKind, sizeRef: Float): View = FrameLayout(context).apply {
        background = roundedBg(0x08000000, GREEN, sizeRef / 2f, 1f)
        addView(icon(kind, GREEN), FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            val insetX = pxX(sizeRef * 0.19f)
            val insetY = pxH(sizeRef * 0.19f)
            leftMargin = insetX; rightMargin = insetX; topMargin = insetY; bottomMargin = insetY
        })
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "—"

    private fun icon(kind: IconKind, color: Int, visualScale: Float = 1.12f): View =
        if (kind == IconKind.WALLPAPER) {
            ImageView(context).apply {
                setImageResource(R.drawable.ic_wallpaper_layers)
                imageTintList = android.content.res.ColorStateList.valueOf(color)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                adjustViewBounds = false
                contentDescription = "Sfondo"
            }
        } else {
            LineIconView(context, kind, color, visualScale)
        }

    private fun logPresentation(raw: String): String {
        return raw
            .replace(Regex("^\\d{4}-\\d{2}-\\d{2}\\s+"), "")
            .trim()
            .substringBefore(" | Dettagli tecnici:")
            .trim()
            .take(180)
    }

    private fun lp(x: Float, y: Float, w: Float, h: Float): FrameLayout.LayoutParams = FrameLayout.LayoutParams(pxX(w), pxH(h)).apply {
        leftMargin = contentOffsetX + pxX(x)
        topMargin = pxY(y)
    }

    private fun pxX(v: Float): Int = (v * sx).roundToInt().coerceAtLeast(1)
    private fun pxY(v: Float): Int = (v * sy).roundToInt().coerceAtLeast(0)
    private fun pxH(v: Float): Int = (v * eh).roundToInt().coerceAtLeast(1)
    // The reference artwork uses a visually larger type scale than Android's default sans
    // metrics. Applying this once keeps the individual screens consistent.
    private fun fontPx(v: Float): Float = v * sx * TEXT_SCALE

    private fun roundedBg(fill: Int, stroke: Int, radiusRef: Float, strokeRef: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radiusRef * sx
        if (strokeRef > 0f) setStroke((strokeRef * sx).coerceAtLeast(1f).roundToInt(), stroke)
    }

    private fun dashedBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(0xE9050806.toInt())
        cornerRadius = 30f * sx
        setStroke((1.5f * sx).coerceAtLeast(1f).roundToInt(), 0xFF56821D.toInt(), 11f * sx, 8f * sx)
    }

    private enum class IconKind {
        HOME, GARAGE, STAR, HEADSET, GEAR, INFO,
        BIKE, CHECK, QR, PLUS, LOG, TRASH, SHARE, ROBOT, SEND,
        POCKET, PLAY, BOOK, CHEVRON, CHEVRON_DOWN, USER, GROUP, PHONE,
        CLOSE, WHATSAPP, WALLPAPER, ZOOM
    }

    private class LineIconView(
        context: Context,
        private val kind: IconKind,
        private val lineColor: Int,
        private val visualScale: Float
    ) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.STROKE
            strokeWidth = 4f * resources.displayMetrics.density.coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor; style = Paint.Style.FILL }
        private val path = Path()

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width.toFloat(); val h = height.toFloat()
            // Gear teeth already reach almost to the bounds; other glyphs can grow without
            // clipping and therefore match the full-size examples more closely.
            val scale = if (kind == IconKind.GEAR) visualScale.coerceAtMost(1.05f) else visualScale
            val s = min(w, h) * scale
            val cx = w/2f; val cy = h/2f
            p.strokeWidth = (s * 0.06f).coerceAtLeast(2f)
            path.reset()
            when (kind) {
                IconKind.HOME -> {
                    path.moveTo(cx-s*.37f, cy-s*.03f); path.lineTo(cx, cy-s*.36f); path.lineTo(cx+s*.37f, cy-s*.03f)
                    path.lineTo(cx+s*.30f, cy+s*.34f); path.lineTo(cx-s*.30f, cy+s*.34f); path.close(); c.drawPath(path,p)
                    c.drawRect(cx-s*.08f, cy+s*.08f, cx+s*.08f, cy+s*.34f,p)
                    c.drawLine(cx+s*.18f, cy-s*.19f, cx+s*.18f, cy-s*.33f, p)
                }
                IconKind.GARAGE -> {
                    path.moveTo(cx-s*.38f, cy-s*.06f); path.lineTo(cx, cy-s*.36f); path.lineTo(cx+s*.38f, cy-s*.06f); c.drawPath(path,p)
                    c.drawRect(cx-s*.31f, cy-s*.06f, cx+s*.31f, cy+s*.35f,p)
                    c.drawRect(cx-s*.21f, cy+s*.03f, cx+s*.21f, cy+s*.32f,p)
                    c.drawLine(cx-s*.21f, cy+s*.13f, cx+s*.21f, cy+s*.13f,p)
                    c.drawLine(cx-s*.21f, cy+s*.23f, cx+s*.21f, cy+s*.23f,p)
                }
                IconKind.STAR -> drawStar(c,cx,cy,s*.34f,s*.15f)
                IconKind.HEADSET -> {
                    c.drawArc(cx-s*.30f,cy-s*.30f,cx+s*.30f,cy+s*.30f,200f,140f,false,p)
                    c.drawRoundRect(cx-s*.34f,cy, cx-s*.20f,cy+s*.28f,s*.05f,s*.05f,p)
                    c.drawRoundRect(cx+s*.20f,cy, cx+s*.34f,cy+s*.28f,s*.05f,s*.05f,p)
                    c.drawLine(cx+s*.30f,cy+s*.26f,cx+s*.10f,cy+s*.34f,p)
                }
                IconKind.GEAR -> {
                    c.drawCircle(cx,cy,s*.29f,p)
                    c.drawCircle(cx,cy,s*.12f,p)
                    for (i in 0 until 8) {
                        c.save()
                        c.rotate(i * 45f, cx, cy)
                        c.drawRoundRect(cx-s*.075f, cy-s*.43f, cx+s*.075f, cy-s*.26f, s*.025f, s*.025f, p)
                        c.restore()
                    }
                }
                IconKind.INFO -> { c.drawCircle(cx,cy,s*.34f,p); c.drawCircle(cx,cy-s*.16f,s*.03f,fill); c.drawLine(cx,cy-s*.02f,cx,cy+s*.20f,p) }
                IconKind.BIKE -> {
                    val rearX = cx - s*.28f; val frontX = cx + s*.30f; val wheelY = cy + s*.22f
                    c.drawCircle(rearX, wheelY, s*.135f, p); c.drawCircle(frontX, wheelY, s*.135f, p)
                    // Engine, frame, tank, fork, seat and handlebar: a motorcycle silhouette,
                    // deliberately not the two-triangle bicycle glyph used by older builds.
                    c.drawRoundRect(cx-s*.09f, cy-s*.02f, cx+s*.11f, cy+s*.15f, s*.035f, s*.035f, p)
                    path.moveTo(rearX, wheelY); path.lineTo(cx-s*.10f, cy+s*.12f); path.lineTo(cx+s*.10f, cy+s*.12f); path.lineTo(frontX, wheelY); c.drawPath(path,p)
                    path.reset(); path.moveTo(cx-s*.10f, cy+s*.12f); path.lineTo(cx-s*.20f, cy-s*.04f); path.lineTo(cx+s*.04f, cy-s*.03f); path.lineTo(cx+s*.10f, cy+s*.12f); c.drawPath(path,p)
                    c.drawRoundRect(cx-s*.10f, cy-s*.18f, cx+s*.14f, cy-s*.04f, s*.055f, s*.055f, p)
                    c.drawLine(cx+s*.12f, cy-s*.05f, frontX, wheelY-s*.08f, p)
                    c.drawLine(cx+s*.04f, cy-s*.18f, cx+s*.18f, cy-s*.28f, p)
                    c.drawLine(cx+s*.15f, cy-s*.28f, cx+s*.31f, cy-s*.28f, p)
                    c.drawLine(cx-s*.18f, cy-s*.07f, cx-s*.30f, cy-s*.09f, p)
                }
                IconKind.CHECK -> { c.drawCircle(cx,cy,s*.34f,p); path.moveTo(cx-s*.16f,cy); path.lineTo(cx-s*.03f,cy+s*.14f); path.lineTo(cx+s*.20f,cy-s*.16f); c.drawPath(path,p) }
                IconKind.QR -> {
                    val q=s*.13f; val off=s*.22f
                    c.drawRect(cx-off-q,cy-off-q,cx-off+q,cy-off+q,p); c.drawRect(cx+off-q,cy-off-q,cx+off+q,cy-off+q,p); c.drawRect(cx-off-q,cy+off-q,cx-off+q,cy+off+q,p)
                    c.drawRect(cx+s*.08f,cy+s*.08f,cx+s*.28f,cy+s*.28f,p)
                    c.drawRect(cx-off-s*.05f,cy-off-s*.05f,cx-off+s*.05f,cy-off+s*.05f,fill)
                    c.drawRect(cx+off-s*.05f,cy-off-s*.05f,cx+off+s*.05f,cy-off+s*.05f,fill)
                    c.drawRect(cx-off-s*.05f,cy+off-s*.05f,cx-off+s*.05f,cy+off+s*.05f,fill)
                }
                IconKind.PLUS -> { c.drawLine(cx-s*.28f,cy,cx+s*.28f,cy,p); c.drawLine(cx,cy-s*.28f,cx,cy+s*.28f,p) }
                IconKind.LOG -> { path.moveTo(cx-s*.36f,cy); path.lineTo(cx-s*.20f,cy); path.lineTo(cx-s*.12f,cy-s*.25f); path.lineTo(cx,cy+s*.25f); path.lineTo(cx+s*.10f,cy-s*.10f); path.lineTo(cx+s*.18f,cy); path.lineTo(cx+s*.36f,cy); c.drawPath(path,p) }
                IconKind.TRASH -> { c.drawRect(cx-s*.20f,cy-s*.14f,cx+s*.20f,cy+s*.30f,p); c.drawLine(cx-s*.28f,cy-s*.22f,cx+s*.28f,cy-s*.22f,p); c.drawLine(cx-s*.08f,cy-s*.32f,cx+s*.08f,cy-s*.32f,p) }
                IconKind.SHARE -> { c.drawRect(cx-s*.28f,cy-s*.02f,cx+s*.28f,cy+s*.30f,p); c.drawLine(cx,cy+s*.08f,cx,cy-s*.34f,p); path.moveTo(cx-s*.14f,cy-s*.20f); path.lineTo(cx,cy-s*.34f); path.lineTo(cx+s*.14f,cy-s*.20f); c.drawPath(path,p) }
                IconKind.ROBOT -> { c.drawRoundRect(cx-s*.32f,cy-s*.22f,cx+s*.32f,cy+s*.25f,s*.12f,s*.12f,p); c.drawCircle(cx-s*.13f,cy,s*.04f,fill); c.drawCircle(cx+s*.13f,cy,s*.04f,fill); c.drawLine(cx,cy-s*.22f,cx,cy-s*.35f,p); c.drawCircle(cx,cy-s*.39f,s*.04f,fill) }
                IconKind.SEND -> { path.moveTo(cx-s*.32f,cy-s*.22f); path.lineTo(cx+s*.34f,cy); path.lineTo(cx-s*.32f,cy+s*.22f); path.lineTo(cx-s*.14f,cy); path.close(); c.drawPath(path,p) }
                IconKind.POCKET -> { path.moveTo(cx-s*.28f,cy-s*.24f); path.lineTo(cx+s*.28f,cy-s*.24f); path.lineTo(cx+s*.22f,cy+s*.24f); path.lineTo(cx,cy+s*.36f); path.lineTo(cx-s*.22f,cy+s*.24f); path.close(); c.drawPath(path,p); c.drawLine(cx-s*.14f,cy-s*.06f,cx+s*.14f,cy-s*.06f,p) }
                IconKind.PLAY -> { path.moveTo(cx-s*.20f,cy-s*.30f); path.lineTo(cx+s*.28f,cy); path.lineTo(cx-s*.20f,cy+s*.30f); path.close(); c.drawPath(path,p) }
                IconKind.BOOK -> { path.moveTo(cx,cy-s*.28f); path.cubicTo(cx-s*.20f,cy-s*.36f,cx-s*.34f,cy-s*.20f,cx-s*.34f,cy+s*.24f); path.cubicTo(cx-s*.20f,cy+s*.14f,cx-s*.08f,cy+s*.14f,cx,cy+s*.24f); path.cubicTo(cx+s*.08f,cy+s*.14f,cx+s*.20f,cy+s*.14f,cx+s*.34f,cy+s*.24f); path.lineTo(cx+s*.34f,cy-s*.28f); path.cubicTo(cx+s*.16f,cy-s*.34f,cx+s*.06f,cy-s*.32f,cx,cy-s*.28f); c.drawPath(path,p) }
                IconKind.CHEVRON -> { path.moveTo(cx-s*.10f,cy-s*.25f); path.lineTo(cx+s*.12f,cy); path.lineTo(cx-s*.10f,cy+s*.25f); c.drawPath(path,p) }
                IconKind.CHEVRON_DOWN -> { path.moveTo(cx-s*.24f,cy-s*.10f); path.lineTo(cx,cy+s*.14f); path.lineTo(cx+s*.24f,cy-s*.10f); c.drawPath(path,p) }
                IconKind.USER -> { c.drawCircle(cx,cy-s*.16f,s*.14f,p); c.drawArc(cx-s*.25f,cy+s*.02f,cx+s*.25f,cy+s*.42f,200f,140f,false,p) }
                IconKind.GROUP -> { c.drawCircle(cx,cy-s*.16f,s*.12f,p); c.drawCircle(cx-s*.25f,cy-s*.08f,s*.09f,p); c.drawCircle(cx+s*.25f,cy-s*.08f,s*.09f,p); c.drawArc(cx-s*.24f,cy+s*.02f,cx+s*.24f,cy+s*.38f,195f,150f,false,p); c.drawArc(cx-s*.42f,cy+s*.04f,cx-s*.08f,cy+s*.32f,195f,145f,false,p); c.drawArc(cx+s*.08f,cy+s*.04f,cx+s*.42f,cy+s*.32f,200f,145f,false,p) }
                IconKind.PHONE -> { path.moveTo(cx-s*.25f,cy-s*.28f); path.cubicTo(cx-s*.38f,cy-s*.10f,cx-s*.10f,cy+s*.26f,cx+s*.15f,cy+s*.32f); path.cubicTo(cx+s*.27f,cy+s*.35f,cx+s*.36f,cy+s*.18f,cx+s*.22f,cy+s*.08f); path.lineTo(cx+s*.08f,cy+s*.16f); path.cubicTo(cx-s*.04f,cy+s*.08f,cx-s*.13f,cy-s*.02f,cx-s*.18f,cy-s*.14f); path.lineTo(cx-s*.08f,cy-s*.24f); path.close(); c.drawPath(path,p) }
                IconKind.CLOSE -> { c.drawLine(cx-s*.25f, cy-s*.25f, cx+s*.25f, cy+s*.25f, p); c.drawLine(cx+s*.25f, cy-s*.25f, cx-s*.25f, cy+s*.25f, p) }
                IconKind.ZOOM -> {
                    c.drawCircle(cx-s*.07f, cy-s*.07f, s*.24f, p)
                    c.drawLine(cx+s*.10f, cy+s*.10f, cx+s*.34f, cy+s*.34f, p)
                    c.drawLine(cx-s*.18f, cy-s*.07f, cx+s*.04f, cy-s*.07f, p)
                    c.drawLine(cx-s*.07f, cy-s*.18f, cx-s*.07f, cy+s*.04f, p)
                }
                IconKind.WALLPAPER -> Unit
                IconKind.WHATSAPP -> {
                    c.drawCircle(cx, cy, s*.34f, p)
                    path.moveTo(cx-s*.16f, cy+s*.28f); path.lineTo(cx-s*.24f, cy+s*.38f); path.lineTo(cx-s*.05f, cy+s*.31f); c.drawPath(path,p)
                    path.reset(); path.moveTo(cx-s*.14f, cy-s*.17f); path.cubicTo(cx-s*.29f, cy-s*.03f, cx-s*.06f, cy+s*.22f, cx+s*.16f, cy+s*.18f); path.lineTo(cx+s*.23f, cy+s*.05f); path.lineTo(cx+s*.10f, cy-s*.02f); path.cubicTo(cx+s*.01f, cy+s*.07f, cx-s*.08f, cy-s*.02f, cx-s*.12f, cy-s*.12f); path.lineTo(cx-s*.04f, cy-s*.20f); path.close(); c.drawPath(path,p)
                }
            }
        }

        private fun drawStar(c: Canvas, cx: Float, cy: Float, r1: Float, r2: Float) {
            path.reset()
            for (i in 0 until 10) {
                val a = -PI/2 + i*PI/5
                val r = if (i % 2 == 0) r1 else r2
                val x = cx + cos(a).toFloat()*r
                val y = cy + sin(a).toFloat()*r
                if (i == 0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            path.close(); c.drawPath(path,p)
        }

        override fun onTouchEvent(event: MotionEvent?): Boolean = false
    }

    companion object {
        private const val REF_W = 941f
        private const val BODY_REF_H = 1385f
        private const val NAV_REF_H = 185f
        private const val NAV_REF_X = 33f
        private const val BODY_CARD_W = 730f
        private const val TEXT_SCALE = 1.24f
        private const val GREEN = 0xFF77FF00.toInt()
    }
}
