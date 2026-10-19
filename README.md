# TV Launcher

Launcher personalizado para Android TV / Google TV (Philips Ambilight), no estilo Apple TV: sem anúncios, leve e com tema claro/escuro automático.

## Estado atual

- Protótipo visual em HTML (`tv-launcher/index.html`). No navegador do computador, apps, destaques e notícias são simulados.
- App Android (`android/`) que mostra esse mesmo HTML em tela cheia na TV, com dados reais:

| Parte | Na TV vem de |
|---|---|
| Barra de apps | Apps instalados, com o banner oficial de cada um |
| Entradas | HDMI 1–4 e TV ao vivo do sistema |
| Clima e nascer/pôr do sol | [Open-Meteo](https://open-meteo.com) (gratuito, sem chave) |
| Notícias | RSS do g1 e do ge, por categoria |
| Destaques | [TMDB](https://www.themoviedb.org): em alta na semana, só dos serviços instalados e disponíveis no Brasil (precisa de chave) |
| Ambilight | Menus oficiais da Philips (estilo, brilho, saturação, cor da parede) |

O Personalizar e o relógio flutuante continuam sendo deste projeto. A lógica dos destaques foi adaptada do [Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT).

TV testada: Philips 50PUG7907/78 (Android TV 11, launcher original `com.google.android.tvlauncher`).

## Rodar o protótipo

Requer Node.js.

```bash
node tv-launcher/serve.js
```

Abra http://localhost:5178 e navegue com o teclado como se fosse o controle remoto:

| Tecla | Ação |
|---|---|
| ← ↑ → ↓ | navegar |
| Enter | selecionar |
| Esc | voltar |
| T | alternar tema (claro / escuro / automático) |
| F | mostrar/ocultar moldura da TV e Ambilight |
| H | relógio flutuante (hora e clima por cima de qualquer tela) |

## Instalar na TV

Pré-requisitos no Mac: JDK 17, Android SDK (platform 34 e build-tools 34) e `adb`.
Na TV: Opções do desenvolvedor e Depuração USB ligadas.

```bash
# Compilar (gera android/app/build/outputs/apk/debug/app-debug.apk)
cd android && ./gradlew assembleDebug

# Conectar e instalar
adb connect IP_DA_TV:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Chave do TMDB (destaques)

Crie uma conta grátis em [themoviedb.org](https://www.themoviedb.org/signup), abra [Configurações → API](https://www.themoviedb.org/settings/api) e copie a *Chave da API* ou o *Token de leitura da API*. Coloque em `android/local.properties` (fora do Git):

```properties
tmdb.key=SUA_CHAVE
```

Depois compile e instale de novo. Sem a chave, o bloco de destaques fica escondido na TV.

O HTML é copiado para dentro do APK a cada compilação: altere `tv-launcher/index.html`, compile e instale de novo.

### Usar como tela inicial

O Android TV 11 não deixa escolher outro launcher pelas configurações. Desative o original:

```bash
adb shell pm disable-user --user 0 com.google.android.tvlauncher
```

Para voltar ao launcher original:

```bash
adb shell pm enable com.google.android.tvlauncher
```

## Documentação

- [Relógio flutuante na TV](docs/relogio-flutuante.md): como o botão do controle mostra a hora por cima de qualquer app, e a configuração via ADB.

## Próximas etapas

1. Ajustar o visual no protótipo.
2. Portar para app Android nativo (Kotlin + Compose for TV) e testar no emulador de Android TV.
3. Instalar na TV via ADB por Wi-Fi e definir como launcher padrão.

## Segurança

Nunca faça commit de chaves de API (TMDB, clima etc.), do IP da TV ou das chaves do ADB (`~/.android/adbkey`).
Coloque essas informações em `local.properties` ou `.env`, que já estão no `.gitignore`.
