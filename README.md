# Cloudflare Workers AI STT (Speech-to-Text) Popup for Android

Aplicación minimalista y ultrarrápida para Android diseñada con **Jetpack Compose** que funciona como ventana flotante / pop-up (y Quick Settings Tile). Graba audio directamente en PCM/WAV (16 kHz mono) y realiza Speech-to-Text mediante la API de **Cloudflare Workers AI** (usando `@cf/openai/whisper` por defecto).

## Características

- **Diseño flotante / Pop-up**: Aparece superpuesta en la parte inferior de la pantalla sin interrumpir tu flujo de trabajo.
- **Acceso rápido**: Incluye servicio de Quick Settings Tile (`STTTileService`) y soporte para comandos de voz / asistente.
- **Sin intermediarios**: Graba audio en memoria en formato WAV compatible con Whisper y lo envía directamente a Cloudflare Workers AI.
- **Auto-copiado al portapapeles**: El texto transcrito se copia automáticamente para que puedas pegarlo inmediatamente donde quieras, con botón para compartir.
- **Configurable**: El Account ID y API Token de Cloudflare se introducen de manera segura en la UI de la app (guardados localmente en `SharedPreferences`). **El repositorio no contiene ningún token ni credencial.**

## Configuración requerida en Cloudflare

1. **Cloudflare Account ID**
2. **API Token** con permisos:
   - `Workers AI: Read`
   - `Workers AI: Write`
