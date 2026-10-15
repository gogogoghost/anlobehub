# Local storage, providers, and backup

## Data boundaries

- Agents, agent configuration, provider metadata/configuration, model catalog entries, reasoning preferences, topics, messages, and attachment references are local application data.
- Room is the source of truth for structured data; UI language/theme remain in Preferences DataStore.
- Inference requests are sent directly from the device to the selected provider. LobeHub sign-in, server routing, sessions, tRPC, and Gateway are not part of the runtime.
- Chat, provider, and agent data stays on the device except for the prompt/attachments sent to the endpoint the user selected.

## Provider credentials

API keys and other credential fields are encrypted using Android Keystore AES-GCM. The per-installation Keystore key is non-exportable; encrypted credential data is stored under `noBackupFilesDir`, never in the Room database or logs. This prevents a restored ciphertext from being mistaken for a usable credential when its device-bound key is unavailable.

Because Android Keystore keys are not restored with app data, credential ciphertext is intentionally excluded from Android Auto Backup and device-to-device transfer. Restore provider names, endpoints, model settings, agents, conversations, and local files, then re-enter credentials.

## Android backup

`backup_rules.xml` and `data_extraction_rules.xml` include the Room database, the UI Preferences DataStore file (`datastore/ui_prefs.preferences_pb`), and `files/local_files/` for cloud backup and device transfer. Legacy server, cookie, and remote-profile DataStore files are not included. The provider secret vault is kept in `noBackupFilesDir` and is not included. Android/Google Play backup remains subject to the user's device backup settings and platform limits.

## Provider metadata

`core/data/src/main/assets/provider_catalog.json` bundles 85 providers and 1,886 model entries from the [LobeHub Model Bank](https://github.com/lobehub/lobehub/tree/6c7a6fb033e443bc27bf34cbd5509ab3db7f22f3/packages/model-bank), pinned to commit `6c7a6fb033e443bc27bf34cbd5509ab3db7f22f3`. It excludes the `lobehub` hosted-service provider. The snapshot includes names, descriptions, provider documentation/key links, endpoint hints, model types, abilities, context/output limits, and other upstream model metadata. It is static app data; there are no runtime LobeHub requests.

The local provider form combines catalog API-key/endpoint/auth hints with the maintained `ProviderConfigSpec` and provider-specific credential fields. API-key values and secret fields remain in Android Keystore-backed storage.

All catalog providers are visible in provider settings. Local adapters cover 67 bundled providers: 60 OpenAI Chat Completions-compatible providers, 3 OpenAI-compatible routers, 2 Anthropic Messages providers, Gemini, and Ollama. Runtime chat eligibility is derived from the local adapter and model `type`; OAuth-only providers, unsupported protocols, and non-chat models remain unavailable rather than routed through a fallback.

Fresh installs seed every provider and catalog model as disabled. The first catalog migration also disables previously enabled providers/models once; subsequent refreshes preserve the user's choices. Chat model pickers expose only chat-capable models that are enabled and whose provider is enabled.
