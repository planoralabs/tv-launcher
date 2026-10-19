# Relógio flutuante na TV

Barra semitransparente com hora, data e temperatura que aparece **por cima de qualquer app** (YouTube, Netflix, tela inicial…) ao apertar um botão do controle e some sozinha depois de alguns segundos.

No protótipo HTML ela é simulada com a tecla **H**. As opções ficam em **Personalizar → Relógio flutuante**.

> Status: implementado em `android/app/src/main/java/com/planoralabs/tvlauncher/overlay/ClockOverlayService.kt` (o mesmo serviço também aciona o Ambilight). O código abaixo foi o esboço inicial; o arquivo real é a referência.

---

## 1. Por que o launcher sozinho não basta

O launcher só está ativo enquanto a tela inicial está aberta. Quando a Netflix abre, ele vai para segundo plano e deixa de receber as teclas do controle.

Por isso o relógio é um **serviço separado dentro do mesmo app** (mesmo APK), que fica sempre ligado:

| Peça | Função |
|---|---|
| `ClockOverlayService` (Serviço de Acessibilidade) | Recebe as teclas do controle em qualquer app e "consome" o botão escolhido |
| Janela sobreposta (`TYPE_ACCESSIBILITY_OVERLAY`) | Desenha a barra por cima do app atual, sem precisar da permissão "Exibir sobre outros apps" |
| Configurações compartilhadas | O serviço lê as mesmas opções do Personalizar (posição, tempo, cidade, botão) |
| Open-Meteo | Temperatura real da cidade (API gratuita, sem chave), com cache de 20 minutos |

O serviço **só lê eventos de tecla**. Ele não lê o conteúdo da tela nem o texto dos apps.

---

## 2. Arquivos do app Android

Pacote previsto: `com.planoralabs.tvlauncher`.

### `AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.INTERNET" />

<application ...>
    <service
        android:name=".overlay.ClockOverlayService"
        android:exported="false"
        android:label="Relógio flutuante"
        android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
        <intent-filter>
            <action android:name="android.accessibilityservice.AccessibilityService" />
        </intent-filter>
        <meta-data
            android:name="android.accessibilityservice"
            android:resource="@xml/clock_overlay_service" />
    </service>
</application>
```

### `res/xml/clock_overlay_service.xml`

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRequestFilterKeyEvents"
    android:canRequestFilterKeyEvents="true"
    android:canRetrieveWindowContent="false"
    android:description="@string/clock_overlay_description"
    android:notificationTimeout="100" />
```

`canRetrieveWindowContent="false"` garante que o serviço não tem acesso ao conteúdo da tela.

### `overlay/ClockOverlayService.kt` (esboço)

```kotlin
class ClockOverlayService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var bar: View? = null
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }

    // Botão definido em Personalizar → Relógio flutuante → Definir botão.
    private val triggerKey get() = prefs.getInt("osd_key", KeyEvent.KEYCODE_PROG_YELLOW)
    private val durationMs get() = prefs.getInt("osd_dur", 5) * 1000L

    // Quando a tela "Definir botão" está aberta, a próxima tecla é gravada em vez de executada.
    private val capturing get() = prefs.getBoolean("osd_capturing", false)

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (capturing && event.action == KeyEvent.ACTION_DOWN) {
            prefs.edit().putInt("osd_key", event.keyCode).putBoolean("osd_capturing", false).apply()
            return true
        }
        if (event.keyCode != triggerKey) return false          // outras teclas seguem para o app
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {   // repetições do botão segurado são ignoradas
                if (bar != null && !held) hideBar()                 // aperto com a barra visível: esconde
                else { held = true; showBar() }                     // segurando: fica fixa, sem contagem
            }
            KeyEvent.ACTION_UP -> if (held) {                       // soltou: começa a contagem para sumir
                held = false
                handler.postDelayed(::hideBar, durationMs)
            }
        }
        return true                                             // consome o botão (app não reage)
    }

    private var held = false

    private fun showBar() {
        handler.removeCallbacksAndMessages(null)
        if (bar != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.clock_bar, null)
        bindContent(view)                                       // hora, data, temperatura, cidade
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (prefs.getString("osd_pos", "inferior") == "superior")
                Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 56
        }
        getSystemService(WindowManager::class.java).addView(view, params)
        bar = view
    }

    private fun hideBar() {
        handler.removeCallbacksAndMessages(null)
        held = false
        bar?.let { getSystemService(WindowManager::class.java).removeView(it) }
        bar = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = hideBar()
}
```

`FLAG_NOT_FOCUSABLE` faz a barra nunca "roubar" o controle: o vídeo continua respondendo normalmente enquanto ela está na tela.

### Temperatura (Open-Meteo)

```
https://api.open-meteo.com/v1/forecast?latitude=-23.55&longitude=-46.63&current=temperature_2m,weather_code&timezone=auto
```

Guardar a última resposta e só buscar de novo depois de 20 minutos. Se a TV estiver sem internet, mostrar só a hora.

---

## 3. Configuração na TV (via ADB pelo Wi-Fi)

Pré-requisito: **Opções do desenvolvedor** e **Depuração por rede (ADB)** ligadas na TV.

```bash
# 1. Conectar (aceite o aviso que aparece na TV)
adb connect IP_DA_TV:5555

# 2. Instalar ou atualizar o app
adb install -r tv-launcher.apk

# 3. Ver se já existe outro serviço de acessibilidade ligado
adb shell settings get secure enabled_accessibility_services
```

O comando seguinte **substitui** a lista. Se o passo 3 retornou algo diferente de `null`, junte os valores com `:`.

```bash
# 4. Ligar o relógio flutuante
adb shell settings put secure enabled_accessibility_services com.planoralabs.tvlauncher/.overlay.ClockOverlayService
adb shell settings put secure accessibility_enabled 1

# 5. Conferir
adb shell dumpsys accessibility | grep -i ClockOverlay
```

### Escolher o botão do controle

1. Na TV, abra **Personalizar → Relógio flutuante → Definir botão** e aperte o botão desejado.
2. Se nada acontecer, aquele botão é tratado pelo firmware da Philips antes de chegar ao Android. Teste outro.

Para ver quais botões chegam ao Android:

```bash
adb shell getevent -l
```

Aperte os botões do controle e veja o que aparece. Linha nova = o botão chega ao Android.

| Candidatos comuns | Código Android |
|---|---|
| Vermelho / Verde / Amarelo / Azul | `KEYCODE_PROG_RED` (183) / `PROG_GREEN` (184) / `PROG_YELLOW` (185) / `PROG_BLUE` (186) |
| Info | `KEYCODE_INFO` (165) |
| Options / 123 | varia por modelo; confirmar com `getevent` |

Botões que costumam **não** funcionar: Home, Ambilight e os de atalho de apps (Netflix, YouTube).

**Recomendação:** usar um botão colorido que você não usa, consumido por inteiro. Um "aperto longo" para preservar a função original é possível, mas o aperto curto chega ao app antes de sabermos se vai virar longo, então alguns apps podem reagir aos dois.

---

## 4. Checklist de teste na TV

- [ ] O botão mostra a barra na tela inicial do launcher
- [ ] Mostra por cima do YouTube, Netflix, Prime Video e TV ao vivo
- [ ] Some sozinha no tempo configurado; apertar de novo esconde na hora
- [ ] Segurando o botão, a barra fica fixa sem piscar; ao soltar, começa a contagem
- [ ] O vídeo continua respondendo ao controle (play/pause, voltar) com a barra na tela
- [ ] Temperatura bate com a cidade configurada
- [ ] Continua funcionando depois de desligar e ligar a TV
- [ ] Continua funcionando depois de uma atualização de firmware

Se o serviço parar depois de reiniciar a TV, rode de novo o passo 4 e confira:

```bash
adb shell dumpsys deviceidle whitelist +com.planoralabs.tvlauncher
```

---

## 5. Como desfazer

```bash
adb shell settings put secure enabled_accessibility_services null
adb uninstall com.planoralabs.tvlauncher
```
