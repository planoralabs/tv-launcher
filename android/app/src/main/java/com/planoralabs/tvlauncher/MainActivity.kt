package com.planoralabs.tvlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.planoralabs.tvlauncher.overlay.ClockOverlayService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Tela inicial: mostra o index.html do protótipo em tela cheia e expõe ao JavaScript
 * a ponte `TVBridge` para abrir apps, entradas HDMI e ajustes de verdade.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true   // localStorage guarda as opções do Personalizar
            settings.mediaPlaybackRequiresUserGesture = false
            // Respeita o <meta viewport> da página; sem isso a página se acha com 1920 px e aparece ampliada.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            isFocusable = true
            isFocusableInTouchMode = true
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "TVBridge")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        web.requestFocus()
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Home: volta ao topo e fecha painéis. Voltando de um app (Voltar): mantém o foco onde estava.
        js(if (homePressed) "window.tvHome && tvHome()" else "window.tvResume && tvResume()")
        homePressed = false
    }

    private var homePressed = false

    // Chega antes do onResume quando o botão Home é apertado.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homePressed = true
    }

    // Voltar nunca fecha a tela inicial: vira "Esc" dentro da página.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                js("document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape'}))")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun js(code: String) {
        if (::web.isInitialized) web.evaluateJavascript(code, null)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun launch(intent: Intent?): Boolean {
        if (intent == null) return false
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runOnUiThread { startActivity(intent) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun installed(pkg: String) = try {
        packageManager.getApplicationInfo(pkg, 0); true
    } catch (e: Exception) {
        false
    }

    // id pode ser um apelido do protótipo ("yt") ou o próprio nome do pacote ("com.netflix.ninja").
    private fun appPackage(id: String) =
        if ('.' in id) id.takeIf(::installed) else APPS[id]?.firstOrNull(::installed)

    private val bannerCache = HashMap<String, String>()

    /** Banner oficial do app (o mesmo da tela inicial original), como imagem JPEG em data URL. */
    private fun banner(ai: ActivityInfo, label: String): String = bannerCache.getOrPut(ai.packageName) {
        val w = 640; val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val art = ai.loadBanner(packageManager) ?: ai.applicationInfo.loadBanner(packageManager)
        if (art != null) {
            art.setBounds(0, 0, w, h); art.draw(canvas)
        } else {
            // Sem banner: ícone do app e o nome, sobre fundo escuro.
            canvas.drawColor(Color.rgb(38, 40, 46))
            val icon = ai.loadIcon(packageManager)
            icon.setBounds(w / 2 - 80, 60, w / 2 + 80, 220); icon.draw(canvas)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 44f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
            }
            canvas.drawText(label, w / 2f, 300f, paint)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun launchIntent(pkg: String) =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)

    /** Entradas HDMI do sistema, em ordem (HDMI 1, 2, 3, 4). */
    private fun hdmiInputs(): List<TvInputInfo> {
        val tim = getSystemService(TvInputManager::class.java) ?: return emptyList()
        return tim.tvInputList
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
            .sortedBy { it.id.substringAfterLast("HW").toIntOrNull() ?: Int.MAX_VALUE }
    }

    inner class Bridge {
        @JavascriptInterface
        fun hasApp(id: String): Boolean = appPackage(id) != null

        /** Apps que aparecem na tela inicial da TV: [{id, name, banner}]. */
        @JavascriptInterface
        fun listApps(): String {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val arr = JSONArray()
            packageManager.queryIntentActivities(intent, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != packageName }
                .forEach {
                    val label = it.loadLabel(packageManager).toString()
                    arr.put(JSONObject().put("id", it.activityInfo.packageName).put("name", label)
                        .put("banner", banner(it.activityInfo, label)))
                }
            return arr.toString()
        }

        /** Busca um endereço https em segundo plano e devolve com tvFetchDone(id, ok, texto). */
        @JavascriptInterface
        fun fetch(id: Int, url: String) = get(id, url, null)

        /** POST com corpo JSON (consulta do JustWatch). */
        @JavascriptInterface
        fun fetchPost(id: Int, url: String, json: String) = get(id, url, null, json)

        /** O mesmo, com "Authorization: Bearer" (token de leitura do TMDB). */
        @JavascriptInterface
        fun fetchAuth(id: Int, url: String, token: String) = get(id, url, token)

        private fun get(id: Int, url: String, token: String?, post: String? = null) {
            thread {
                val (ok, body) = try {
                    require(url.startsWith("https://")) { "só https" }
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 12000
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) TVLauncher")
                    if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
                    if (post != null) {
                        c.requestMethod = "POST"; c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/json")
                        c.outputStream.use { it.write(post.toByteArray()) }
                    }
                    val code = c.responseCode
                    val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                    (code < 400) to (if (code < 400) text else "HTTP $code")
                } catch (e: Exception) {
                    false to (e.message ?: "erro")
                }
                runOnUiThread { js("tvFetchDone($id, $ok, ${JSONObject.quote(body)})") }
            }
        }

        // MARK: Bluetooth

        /** Dispositivos pareados: {on, devices: [{name, kind, connected}]}. */
        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun btDevices(): String {
            val ad = getSystemService(BluetoothManager::class.java)?.adapter ?: return """{"on":false,"devices":[]}"""
            val arr = JSONArray()
            try {
                ad.bondedDevices?.sortedBy { it.name ?: "" }?.forEach { d ->
                    val connected = try { d.javaClass.getMethod("isConnected").invoke(d) as Boolean } catch (e: Exception) { false }
                    val kind = when (d.bluetoothClass?.majorDeviceClass) {
                        BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio"
                        BluetoothClass.Device.Major.PERIPHERAL -> "input"
                        BluetoothClass.Device.Major.PHONE -> "phone"
                        BluetoothClass.Device.Major.COMPUTER -> "computer"
                        else -> "other"
                    }
                    arr.put(JSONObject().put("name", d.name ?: d.address).put("kind", kind).put("connected", connected))
                }
            } catch (e: SecurityException) { }
            return JSONObject().put("on", ad.isEnabled).put("devices", arr).toString()
        }

        /** Tela do sistema para parear um novo controle, fone, teclado… */
        @JavascriptInterface
        fun btPair(): Boolean = launch(Intent("com.google.android.intent.action.CONNECT_INPUT"))
            || launch(Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity"))

        // MARK: Entradas e rede

        /** Entradas HDMI que a TV tem: [{id, label, device, state, hidden}]. state: 0 conectado, 1 em espera, 2 sem cabo. */
        @JavascriptInterface
        fun inputs(): String {
            val tim = getSystemService(TvInputManager::class.java) ?: return "[]"
            val all = tim.tvInputList
            val arr = JSONArray()
            hdmiInputs().forEachIndexed { i, inp ->
                val device = all.firstOrNull { it.parentId == inp.id }?.loadLabel(this@MainActivity)?.toString() ?: ""
                val state = try { tim.getInputState(inp.id) } catch (e: Exception) { -1 }
                arr.put(JSONObject().put("id", "hdmi${i + 1}").put("label", inp.loadLabel(this@MainActivity).toString())
                    .put("custom", inp.loadCustomLabel(this@MainActivity)?.toString() ?: "")
                    .put("device", device).put("state", state).put("hidden", inp.isHidden(this@MainActivity)))
            }
            return arr.toString()
        }

        /** Conexão atual: {type: ethernet|wifi|none, name, internet, ip}. */
        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun network(): String {
            val cm = getSystemService(ConnectivityManager::class.java)
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val o = JSONObject()
            when {
                caps == null -> o.put("type", "none")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> o.put("type", "ethernet")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    o.put("type", "wifi")
                    val ssid = getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.trim('"')
                    if (ssid != null && ssid != "<unknown ssid>") o.put("name", ssid)
                }
                else -> o.put("type", "other")
            }
            o.put("internet", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            net?.let { cm.getLinkProperties(it) }?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address }?.let { o.put("ip", it.address.hostAddress) }
            return o.toString()
        }

        // MARK: Relógio flutuante e Ambilight (serviço de acessibilidade)

        private val service get() = ClockOverlayService.instance
        private val prefs get() = getSharedPreferences("tvl", MODE_PRIVATE)

        /** O serviço está ligado nas configurações de acessibilidade? */
        @JavascriptInterface
        fun serviceOn(): Boolean = service != null

        /** Opções do relógio flutuante vindas do Personalizar (posição, tempo, temperatura…). */
        @JavascriptInterface
        fun setOsd(json: String) { prefs.edit().putString("osd_cfg", json).apply() }

        /** Começa (limpando a lista) ou encerra a escuta de botões da janela "Adicionar botões". */
        @JavascriptInterface
        fun osdCapture(start: Boolean) {
            val e = prefs.edit().putBoolean("osd_capturing", start)
            if (start) e.putString("osd_seen", "").putLong("osd_cap_since", System.currentTimeMillis())
            e.apply()
        }

        /** {keys: [{code, name}], capturing, seen: [{code, name, ok}]}. name é o KEYCODE sem o prefixo. */
        @JavascriptInterface
        fun osdKeys(): String {
            val key = { c: Int -> JSONObject().put("code", c).put("name", KeyEvent.keyCodeToString(c).removePrefix("KEYCODE_"))
                .put("ok", c !in ClockOverlayService.RESERVED) }
            val keys = JSONArray().also { a -> ClockOverlayService.keys(prefs).forEach { a.put(key(it)) } }
            val seen = JSONArray().also { a -> ClockOverlayService.seen(prefs).forEach { a.put(key(it)) } }
            return JSONObject().put("keys", keys).put("seen", seen).put("capturing", prefs.getBoolean("osd_capturing", false)).toString()
        }

        /** Troca a lista de botões do relógio (códigos separados por vírgula). */
        @JavascriptInterface
        fun osdSetKeys(csv: String) { prefs.edit().putString("osd_keys", csv).apply() }

        @JavascriptInterface
        fun osdTest(): Boolean = service?.test() != null

        /** Último estado conhecido do Ambilight (sem abrir o menu). */
        @JavascriptInterface
        fun ambiState(): String = prefs.getString("ambi", "{}")!!

        /** Executa no menu oficial e responde com tvAmbiDone(estado). */
        @JavascriptInterface
        fun ambi(cmd: String): Boolean {
            val s = service ?: return false
            s.ambilight(cmd) { r -> js("window.tvAmbiDone && tvAmbiDone($r)") }
            return true
        }

        @JavascriptInterface
        fun tmdbKey(): String = BuildConfig.TMDB_KEY

        /** Telas oficiais de Ambilight da Philips: "style" (estilo e ativar) ou "osd" (ajustes rápidos). */
        @JavascriptInterface
        fun openAmbilight(kind: String): Boolean = launch(Intent(
            if (kind == "osd") "org.droidtv.ambilight.AMBILIGHTOSD" else "org.droidtv.ambilight.AMBILIGHTSTYLE"
        ))

        /** Abre o título direto no app (link do próprio serviço); se o app não aceitar, abre o app. */
        @JavascriptInterface
        fun openTitle(url: String, id: String): Boolean {
            val pkg = appPackage(id) ?: return false
            val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(pkg)
            return (view.resolveActivity(packageManager) != null && launch(view)) || launch(launchIntent(pkg))
        }

        @JavascriptInterface
        fun openApp(id: String): Boolean {
            val pkg = appPackage(id) ?: return false
            return launch(launchIntent(pkg))
        }

        @JavascriptInterface
        fun openInput(id: String): Boolean {
            // TV ao vivo: pede o sintonizador (antena/cabo) explicitamente; só abrir o app voltaria à última fonte (ex.: HDMI 1).
            if (id == "live") {
                val tuner = getSystemService(TvInputManager::class.java)?.tvInputList
                    ?.filter { it.type == TvInputInfo.TYPE_TUNER && it.parentId == null && it.serviceInfo.packageName != "com.google.android.videos" }
                    ?.minByOrNull { it.id.substringAfterLast("HW").toIntOrNull() ?: Int.MAX_VALUE }
                    ?: return openApp("tv")
                val uri = TvContract.buildChannelUriForPassthroughInput(tuner.id)
                return launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")) || openApp("tv")
            }
            val n = id.removePrefix("hdmi").toIntOrNull() ?: return false
            val input = hdmiInputs().getOrNull(n - 1) ?: return false
            val uri = TvContract.buildChannelUriForPassthroughInput(input.id)
            return launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel"))
        }

        @JavascriptInterface
        fun openSettings(kind: String): Boolean {
            val action = when (kind) {
                "audio" -> Settings.ACTION_SOUND_SETTINGS
                "net" -> Settings.ACTION_WIFI_SETTINGS
                "a11y" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            return launch(Intent(action)) || launch(Intent(Settings.ACTION_SETTINGS))
        }
    }

    companion object {
        /** id usado no index.html → pacotes possíveis do app na TV (o primeiro instalado vence). */
        val APPS = mapOf(
            "tv" to listOf("com.mediatek.wwtv.tvcenter", "com.google.android.tv", "com.android.tv"),
            "yt" to listOf("com.google.android.youtube.tv", "com.google.android.youtube.googletv"),
            "prime" to listOf("com.amazon.amazonvideo.livingroom"),
            "netflix" to listOf("com.netflix.ninja"),
            "hbo" to listOf("com.wbd.stream", "com.hbo.hbonow"),
            "crunchy" to listOf("com.crunchyroll.crunchyroid"),
            "appletv" to listOf("com.apple.atve.androidtv.appletv"),
            "disney" to listOf("com.disney.disneyplus"),
            "globoplay" to listOf("com.globo.globotv"),
            "spotify" to listOf("com.spotify.tv.android"),
            "twitch" to listOf("tv.twitch.android.app"),
            "plex" to listOf("com.plexapp.android"),
            "kodi" to listOf("org.xbmc.kodi"),
            "browser" to listOf("com.android.chrome", "org.mozilla.tv.firefox", "com.opera.browser", "com.tcl.browser"),
            "play" to listOf("com.android.vending")
        )
    }
}
