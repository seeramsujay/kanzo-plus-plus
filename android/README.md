# Kanzo Mobile (Android Companion App)

Native Android client for [Kanzo](https://github.com/emanuele-em/kanzo) built with **Kotlin 2.0+** and **Jetpack Compose Material 3**.

Connects to your self-hosted Kanzo board from anywhere in the world over an end-to-end encrypted reverse tunnel (Pinggy / Cloudflare / WireGuard) or direct local network.

---

## ✨ Features

- 📱 **Native Material 3 / Material You**: Obsidian dark mode and dynamic wallpaper color extraction (Android 12+).
- 🔒 **Zero-Trust Encrypted Bunker Tunnel**: Connects via AES-256-GCM encrypted reverse tunnel over SSH port 443 with ephemeral ntfy rendezvous federation. Bypasses Fortinet, hostel, and corporate firewalls.
- ⚡ **Real-Time Kanban Sync**: Automatic instant board updates as coding agents move cards, via Server-Sent Events (`/api/changes`).
- 📋 **Full Board Management**:
  - Columns: Backlog, Todo, Doing, Review, Done.
  - Task cards with tags, waits-on dependency badges, and human-only flags.
  - Quick-move tasks across columns.
  - Create tasks, edit descriptions, and comment in real-time.
- 🔍 **Filter & Search**: Full query support (`tag:bug`, `status:doing`, `is:waiting`, `is:human`).
- 📂 **Multi-Project Switching**: Switch between projects or view aggregated tasks across all projects.

---

## 🚀 Quick Pairing Workflow

### 1. Start Tunnel on Laptop / Server
On the machine running Kanzo (`127.0.0.1:4242`):
```bash
tools/kanzo-tunnel.sh --bind
```
This displays the pairing QR code and prints the pairing ticket:
`KANZOBUNKER:<ticket>`

### 2. Connect in Android App
1. Open the Kanzo Mobile App.
2. Tap the connection status pill in the top bar.
3. In the **Bunker Tunnel** tab, paste the `KANZOBUNKER:...` ticket.
4. Tap **Pair & Connect**.

Your device binding is permanently saved in the app and on your host (`~/.config/kanzo-tunnel/session.json`). Subsequent connections connect automatically!

---

## 🛠️ Building

### Build Debug APK with Gradle Wrapper:
```bash
cd android
./gradlew assembleDebug
```
The compiled APK will be generated at:
`android/app/build/outputs/apk/debug/app-debug.apk`

### CI Build:
GitHub Actions builds and attaches the APK on every push to `main` and `feature/**` via `.github/workflows/build-android.yml`.
