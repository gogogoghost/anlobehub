# Local Agent for Android

A standalone, local-first Android agent app built with Jetpack Compose. It is not a LobeHub client and does not require a hosted server, account, or sign-in.

Agents, conversations, messages, provider settings, model lists, and UI preferences are stored on-device. Chat requests go directly from the device to the provider endpoint selected by the user. Local/LAN inference servers can be configured; cloud providers still require their own API credentials and network access. No chat data is sent to LobeHub.

## Features

- Create, edit, duplicate, and delete local agents with system prompts, model binding, opening messages/questions, and local avatars.
- Keep per-agent conversation history locally; resume, start, and delete conversations.
- Configure provider endpoints and models locally; use built-in provider settings or add an OpenAI-compatible endpoint.
- Providers and models start disabled; enable the providers and chat models you choose to use.
- Upgrading an existing installation disables old provider/model activation once, then preserves later manual choices.
- Stream model output directly from supported provider APIs; model availability and protocol support depend on the configured provider.
- Supported direct adapters: 60 OpenAI Chat Completions-compatible providers, 3 OpenAI-compatible routers, 2 Anthropic Messages providers, Gemini, and Ollama. OAuth-only providers and protocols without a local adapter remain unavailable; unsupported requests are never redirected to another provider or service.
- Choose app language, theme, and dynamic color.
- Use Android Auto Backup / device transfer for the local database, app preferences, and local attachments when system backup is enabled.

## Privacy and backup

Provider credentials are encrypted with Android Keystore AES-GCM and stored under Android's no-backup directory. The encryption key and credential ciphertext are intentionally excluded from cloud backup and device transfer. After restoring an app backup, re-enter provider API keys; provider names, endpoints, agents, conversations, messages, UI settings, and local files are backed up.

The app remains usable offline for agent management and history. Model generation requires the configured provider endpoint to be reachable. For local inference, configure an on-device or LAN-compatible endpoint.

The bundled provider/model metadata snapshot uses the public LobeHub Model Bank (commit `6c7a6fb033e443bc27bf34cbd5509ab3db7f22f3`) as a static catalog source only: 85 provider entries and 1,886 model entries. The app makes no runtime requests to LobeHub. Local chat support is derived from the provider protocol/adapter and each model's chat type, enabling 67 bundled providers; non-chat models and unsupported protocols remain browsable but unavailable for chat.

## Build

```bash
# Requires Android SDK platform 37 and JDK 17+
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew test
```
