# Nova Live

A standalone Android live-TV shell with a premium dark neon-purple/cyan interface and a native AndroidX Media3 player.

## Project identity
- App: Nova Live
- Package: `com.novalive.app`
- Min Android: 7.0 (API 24)
- Player: AndroidX Media3 ExoPlayer

## Build
Open this project in Android Studio (JDK 17), sync Gradle, then run `:app:assembleDebug`.
GitHub Actions also builds a debug APK on pushes to `main` or by manual dispatch.

## Channel data
Edit `app/src/main/assets/channels.json`. Each channel supports:
- `id`, `name`, `category`, `logo`, `url`, `mimeType`

For HLS, use an authorized `.m3u8` URL. The player auto-detects HLS when the URL ends in `.m3u8`.
`config.json` reserves the API endpoint setting; remote API fetching and cache refresh are the next implementation step.

## Important
The bundled channels are placeholders with empty stream URLs. No third-party channel feeds or DRM keys are bundled. Add only streams you are authorized to distribute or access. DRM integration must be configured against the relevant provider's legitimate license system.
