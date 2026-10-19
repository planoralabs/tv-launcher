# TV Launcher

Launcher personalizado para Android TV / Google TV (Philips Ambilight), no estilo Apple TV: sem anúncios, leve e com tema claro/escuro automático.

## Estado atual

Protótipo visual em HTML (`tv-launcher/index.html`). Conteúdo, clima e notícias ainda são simulados.

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

## Documentação

- [Relógio flutuante na TV](docs/relogio-flutuante.md): como o botão do controle mostra a hora por cima de qualquer app, e a configuração via ADB.

## Próximas etapas

1. Ajustar o visual no protótipo.
2. Portar para app Android nativo (Kotlin + Compose for TV) e testar no emulador de Android TV.
3. Instalar na TV via ADB por Wi-Fi e definir como launcher padrão.

## Segurança

Nunca faça commit de chaves de API (TMDB, clima etc.), do IP da TV ou das chaves do ADB (`~/.android/adbkey`).
Coloque essas informações em `local.properties` ou `.env`, que já estão no `.gitignore`.
