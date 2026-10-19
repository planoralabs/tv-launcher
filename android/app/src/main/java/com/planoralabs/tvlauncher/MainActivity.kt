package com.planoralabs.tvlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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

        /** O mesmo, com "Authorization: Bearer" (token de leitura do TMDB). */
        @JavascriptInterface
        fun fetchAuth(id: Int, url: String, token: String) = get(id, url, token)

        private fun get(id: Int, url: String, token: String?) {
            thread {
                val (ok, body) = try {
                    require(url.startsWith("https://")) { "só https" }
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 12000
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) TVLauncher")
                    if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
                    val code = c.responseCode
                    val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                    (code < 400) to (if (code < 400) text else "HTTP $code")
                } catch (e: Exception) {
                    false to (e.message ?: "erro")
                }
                runOnUiThread { js("tvFetchDone($id, $ok, ${JSONObject.quote(body)})") }
            }
        }

        @JavascriptInterface
        fun tmdbKey(): String = BuildConfig.TMDB_KEY

        /** Telas oficiais de Ambilight da Philips: "style" (estilo e ativar) ou "osd" (ajustes rápidos). */
        @JavascriptInterface
        fun openAmbilight(kind: String): Boolean = launch(Intent(
            if (kind == "osd") "org.droidtv.ambilight.AMBILIGHTOSD" else "org.droidtv.ambilight.AMBILIGHTSTYLE"
        ))

        @JavascriptInterface
        fun openApp(id: String): Boolean {
            val pkg = appPackage(id) ?: return false
            return launch(launchIntent(pkg))
        }

        @JavascriptInterface
        fun openInput(id: String): Boolean {
            if (id == "live") return openApp("tv")
            val n = id.removePrefix("hdmi").toIntOrNull() ?: return false
            val input = hdmiInputs().getOrNull(n - 1) ?: return false
            val uri = TvContract.buildChannelUriForPassthroughInput(input.id)
            return launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel"))
        }

        @JavascriptInterface
        fun openSettings(kind: String): Boolean {
            val action = when (kind) {
                "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "audio" -> Settings.ACTION_SOUND_SETTINGS
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
