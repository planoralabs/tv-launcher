package com.planoralabs.tvlauncher.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.planoralabs.tvlauncher.Adb
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Serviço sempre ligado (acessibilidade) com duas funções:
 * 1. Relógio flutuante: o botão escolhido mostra hora, data e temperatura por cima de qualquer app.
 * 2. Ambilight na tela inicial: abre os menus oficiais da Philips, aciona o item pedido e lê o estado.
 *    A Philips não oferece API para outros apps; brilho e saturação só mudam com ← → do controle,
 *    então essas duas teclas são enviadas pelo ADB da própria TV (comando fixo "input keyevent").
 * Só lê as telas de Ambilight da Philips; das outras teclas, nada é guardado.
 */
class ClockOverlayService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("tvl", MODE_PRIVATE) }

    override fun onServiceConnected() {
        instance = this
        prefs.edit().putBoolean("osd_capturing", false).apply()   // nunca reinicia preso no modo de escolha
        registerReceiver(screenOn, android.content.IntentFilter(Intent.ACTION_SCREEN_ON))
        if (android.os.SystemClock.elapsedRealtime() < 5 * 60_000) homeAfterPowerOn()   // TV acabou de ligar (boot completo)
    }

    // MARK: - Ligar na tela inicial

    /** Pacote da janela em primeiro plano (pelos eventos de janela; só o nome do pacote). */
    @Volatile private var topPackage: String? = null

    private val screenOn = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) = homeAfterPowerOn()
    }

    /**
     * Ao ligar, a Philips abre a TV ao vivo / última fonte (ex.: HDMI 1). Se a opção estiver ligada e quem estiver
     * na frente for o app da TV, volta para a tela inicial. Duas tentativas, porque a TV abre o app dela com atraso.
     */
    private fun homeAfterPowerOn() {
        if (!osd.optBoolean("homeOnWake", true)) return
        val go = Runnable {
            val top = topPackage
            if (top == null || top == "com.mediatek.wwtv.tvcenter" || top == "com.mediatek.tvinput") {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        main.postDelayed(go, 2500)
        main.postDelayed(go, 7000)
    }
    override fun onDestroy() {
        hideBar(); instance = null
        try { unregisterReceiver(screenOn) } catch (e: Exception) { }
        super.onDestroy()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) event.packageName?.let { topPackage = it.toString() }
    }
    override fun onInterrupt() = hideBar()

    // MARK: - Relógio flutuante

    private var bar: View? = null
    private var timeView: TextView? = null
    private var progress: View? = null
    private var held = false

    private val osd get() = try { JSONObject(prefs.getString("osd_cfg", "{}")!!) } catch (e: Exception) { JSONObject() }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Soltar uma tecla: só fica com o serviço se o aperto também ficou. Engolir a soltura de uma tecla cujo aperto
        // chegou ao app faz o Android achar que ela continua apertada e repetir 20 vezes por segundo (o "flicker").
        if (event.action == KeyEvent.ACTION_UP) {
            if (!consumedDown.remove(event.keyCode)) return false
            if (held && event.keyCode in keys(prefs)) { held = false; countdown() }   // soltou o botão do relógio
            return true
        }
        val take = handleDown(event)
        if (take) consumedDown.add(event.keyCode)
        return take
    }

    private val consumedDown = mutableSetOf<Int>()

    /** Só recebe ACTION_DOWN (inclusive repetições de tecla segurada). true = a tecla fica com o serviço. */
    private fun handleDown(event: KeyEvent): Boolean {
        // Janela "Adicionar botões": cada botão apertado é anotado (sem salvar ainda) e a janela decide.
        // Voltar encerra a escuta. Home, energia e volume seguem para a TV; o resto fica só com a janela.
        if (prefs.getBoolean("osd_capturing", false)) {
            if (System.currentTimeMillis() - prefs.getLong("osd_cap_since", 0) > 120_000) {
                prefs.edit().putBoolean("osd_capturing", false).apply()      // escuta esquecida: solta o controle
            } else {
                val code = event.keyCode
                if (event.repeatCount == 0) {
                    if (code == KeyEvent.KEYCODE_BACK) {
                        prefs.edit().putBoolean("osd_capturing", false).apply()
                        return true
                    }
                    val seen = seen(prefs)
                    if (code !in seen) prefs.edit().putString("osd_seen", (seen + code).joinToString(",")).apply()
                }
                return code !in PASS_THROUGH
            }
        }

        if (event.keyCode !in keys(prefs)) return false                 // outras teclas seguem para o app
        if (event.repeatCount == 0) {
            if (bar != null && !held) hideBar()                         // aperto com a barra visível: esconde
            else { held = true; showBar() }                             // segurando: fica fixa até soltar
        }
        return true                                                     // consome o botão
    }

    /** "Testar agora" do Personalizar. */
    fun test() = main.post { showBar(); countdown() }

    private fun countdown() {
        val ms = osd.optInt("dur", 5) * 1000L
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, ms)
        // Barrinha que esvazia até a barra sumir (segurando o botão ela fica cheia e parada).
        progress?.let { p -> p.animate().cancel(); p.scaleX = 1f; p.animate().scaleX(0f).setDuration(ms).setInterpolator(LinearInterpolator()).start() }
    }

    private val hideRunnable = Runnable { hideBar() }
    private val tick = object : Runnable {
        override fun run() { timeView?.text = clockText(); main.postDelayed(this, 1000) }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, sp: Float, alpha: Int = 255, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor((alpha shl 24) or 0xFFFFFF)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun clockText(): String =
        SimpleDateFormat(if (osd.optString("clock", "24") == "12") "h:mm a" else "HH:mm", Locale("pt", "BR")).format(Date())

    private fun showBar() {
        main.removeCallbacks(hideRunnable)
        if (bar != null) return
        val o = osd
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xE0141519.toInt()); setStroke(1, 0x33FFFFFF) }
        }
        val clock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timeView = text(clockText(), 24f, bold = true).also { clock.addView(it) }
        if (o.optBoolean("date", true)) {
            val d = SimpleDateFormat("EEEE, d 'de' MMM", Locale("pt", "BR")).format(Date())
            clock.addView(text(d.replaceFirstChar { it.uppercase() }, 11f, 190))
        }
        root.addView(clock)
        val temp = o.optString("temp")
        if (o.optBoolean("showTemp", true) && temp.isNotEmpty()) {
            root.addView(View(this).apply { setBackgroundColor(0x33FFFFFF) }, LinearLayout.LayoutParams(1, dp(30)).apply { setMargins(dp(16), 0, dp(16), 0) })
            val w = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            w.addView(text(temp, 20f, bold = true))
            if (o.optBoolean("showCity", true)) w.addView(text(o.optString("city"), 11f, 190))
            root.addView(w)
        }
        // Coluna: conteúdo em cima, barrinha de contagem embaixo, dentro do mesmo fundo arredondado.
        val bg = root.background; root.background = null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = bg; clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        box.addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        progress = View(this).apply {
            setBackgroundColor(0xB3FFFFFF.toInt()); pivotX = 0f
        }.also { box.addView(it, LinearLayout.LayoutParams(0, dp(2))) }
        // A barrinha acompanha a largura do conteúdo. Com MATCH_PARENT ela esticava a janela até a largura padrão
        // que o Android dá a janelas sobrepostas, e a caixa ficava larga demais.
        root.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val p = progress ?: return@addOnLayoutChangeListener
            if (p.layoutParams.width != r - l) { p.layoutParams.width = r - l; p.requestLayout() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (o.optString("pos") == "superior") Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            y = dp(36)
        }
        getSystemService(WindowManager::class.java).addView(box, params)
        bar = box
        main.post(tick)
    }

    private fun hideBar() {
        main.removeCallbacks(hideRunnable); main.removeCallbacks(tick)
        held = false
        bar?.let { getSystemService(WindowManager::class.java).removeView(it) }
        bar = null; timeView = null; progress = null
    }

    // MARK: - Ambilight

    @Volatile private var ambiBusy = false

    /** cmd: read | on | off | style:<nome> | bright+ | bright- | sat+ | sat-. Responde com o estado em JSON. */
    fun ambilight(cmd: String, done: (String) -> Unit) {
        if (ambiBusy) return
        ambiBusy = true
        thread {
            val r = try { runAmbilight(cmd) } catch (e: Exception) { JSONObject().put("error", e.message ?: "erro") }
            val saved = try { JSONObject(prefs.getString("ambi", "{}")!!) } catch (e: Exception) { JSONObject() }
            r.keys().forEach { saved.put(it, r.get(it)) }
            if (!r.has("error")) { saved.remove("error"); prefs.edit().putString("ambi", saved.toString()).apply() }
            ambiBusy = false
            main.post { done(saved.toString()) }
        }
    }

    private fun runAmbilight(cmd: String): JSONObject {
        val stylePanel = cmd == "on" || cmd == "off" || cmd.startsWith("style:")
        // O menu de estilo abre rolado até o estilo atual: reconhece pelo título e volta ao topo.
        // Se o menu anterior ainda estava fechando, a primeira abertura pode se perder: tenta de novo.
        val title = if (stylePanel) "Estilo do Ambilight" else "Brilho"
        val opened = (1..2).any {
            startActivity(Intent(if (stylePanel) STYLE else QUICK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            find(title, 2500) != null
        }
        if (!opened) throw IllegalStateException("o menu do Ambilight não abriu")
        if (stylePanel) scrollToTop()
        val out = JSONObject()
        try {
            if (stylePanel) {
                val on = switchOn()
                if ((cmd == "on" && !on) || (cmd == "off" && on) || (cmd.startsWith("style:") && !on)) { click("Ativar"); Thread.sleep(300) }
                if (cmd.startsWith("style:")) {
                    // O menu grava o estilo em foco ao fechar (e fecha sozinho no clique): foca antes de clicar.
                    val name = cmd.substringAfter(':')
                    clickScrolling(name)
                    Thread.sleep(400)
                    out.put("on", true).put("style", name)
                } else {
                    out.put("on", switchOn())
                    checkedStyle()?.let { out.put("style", it) }
                }
            } else {
                if (cmd != "read") {
                    val row = row(if (cmd.startsWith("bright")) "Brilho" else "Saturação") ?: throw IllegalStateException("item não encontrado")
                    row.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    Thread.sleep(150)
                    Adb.connect(this, 20_000, null).use { it.shell("input keyevent " + if (cmd.endsWith("+")) "DPAD_RIGHT" else "DPAD_LEFT") }
                    Thread.sleep(700)   // o menu grava e redesenha o número
                }
                val style = summary("Estilo do Ambilight")
                out.put("on", style != null && style != "Desativado")
                if (style != null && style != "Desativado") out.put("style", style)
                value("Brilho")?.let { out.put("bright", it) }
                value("Saturação")?.let { out.put("sat", it) }
                summary("Cor da parede atrás da TV")?.let { out.put("wall", it) }
            }
        } finally {
            // Fecha o menu, se ele ainda estiver aberto.
            if (nodes(if (stylePanel) "Estilo do Ambilight" else "Brilho").isNotEmpty()) performGlobalAction(GLOBAL_ACTION_BACK)
        }
        return out
    }

    private fun nodes(text: String): List<AccessibilityNodeInfo> =
        windows.mapNotNull { it.root }.flatMap { it.findAccessibilityNodeInfosByText(text) }.filter { it.text?.toString() == text }

    private fun find(text: String, timeoutMs: Long): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            nodes(text).firstOrNull()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    /** Linha (item clicável) que contém o título. */
    private fun row(title: String): AccessibilityNodeInfo? {
        var n = nodes(title).firstOrNull()
        while (n != null && !n.isClickable) n = n.parent
        return n
    }

    private fun click(title: String) = row(title)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false

    private fun child(row: AccessibilityNodeInfo?, id: String): AccessibilityNodeInfo? {
        if (row == null) return null
        for (i in 0 until row.childCount) { val c = row.getChild(i) ?: continue; if (c.viewIdResourceName == id) return c }
        return null
    }

    private fun summary(title: String) = child(row(title), "android:id/summary")?.text?.toString()
    private fun value(title: String) = child(row(title), "com.android.tv.settings.tpv:id/preference_progress_value")?.text?.toString()?.toIntOrNull()
    private fun switchOn() = child(row("Ativar"), "android:id/switch_widget")?.isChecked ?: false

    private fun list() = windows.mapNotNull { it.root }.firstNotNullOfOrNull {
        it.findAccessibilityNodeInfosByViewId("com.android.tv.settings.tpv:id/list").firstOrNull()
    }

    private fun scrollToTop() {
        repeat(6) {
            if (nodes("Ativar").isNotEmpty()) return
            list()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id)
            Thread.sleep(250)
        }
    }

    private fun checkedStyle(): String? {
        repeat(4) {
            val l = list() ?: return null
            for (i in 0 until l.childCount) {
                val r = l.getChild(i) ?: continue
                if (child(r, "android:id/checkbox")?.isChecked == true) return child(r, "android:id/title")?.text?.toString()
            }
            if (!l.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)) return null
            Thread.sleep(250)
        }
        return null
    }

    /** Clica num estilo, rolando a lista até ele aparecer (os de "Lounge light" ficam mais abaixo). */
    private fun clickScrolling(name: String) {
        repeat(5) {
            val r = row(name)
            if (r != null) {
                r.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                Thread.sleep(250)
                if (r.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            }
            list()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
            Thread.sleep(250)
        }
        throw IllegalStateException("estilo \"$name\" não encontrado")
    }

    companion object {
        @Volatile var instance: ClockOverlayService? = null
        /**
         * Teclas que nunca viram gatilho do relógio: navegação, Home, volume, energia, números, canais, mídia e
         * menus. São as que o launcher e os apps usam o tempo todo; prendê-las quebraria a TV.
         */
        val RESERVED: Set<Int> = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST,
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        ) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)

        /** Durante a escuta, estas continuam indo para a TV (não dá para prender Home, energia nem volume). */
        val PASS_THROUGH = setOf(
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE
        )

        /** Botões apertados durante a escuta, na ordem. */
        fun seen(prefs: android.content.SharedPreferences): List<Int> =
            prefs.getString("osd_seen", "")!!.split(',').mapNotNull { it.trim().toIntOrNull() }

        /** Botões do relógio salvos (o formato antigo, com um só botão em "osd_key", é migrado). */
        fun keys(prefs: android.content.SharedPreferences): Set<Int> {
            val csv = prefs.getString("osd_keys", null)
            if (csv == null) {
                val old = prefs.getInt("osd_key", 0)
                return if (old != 0 && old !in RESERVED) setOf(old) else emptySet()
            }
            return csv.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it !in RESERVED }.toSet()
        }

        const val STYLE = "org.droidtv.ambilight.AMBILIGHTSTYLE"
        const val QUICK = "org.droidtv.ambilight.AMBILIGHTOSD"
    }
}
