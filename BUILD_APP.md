# PragonMobile - Android app for Pragon

The app links your phone to Pragon on the PC (scan a QR once). After that,
"open YouTube on my phone" opens it - no cable, no USB debugging.

## 1. Get the APK (pick ONE way)

### A) Android Studio (free, ~1 GB)
1. Install Android Studio (developer.android.com/studio).
2. File > Open > choose this `PragonMobile` folder. Let Gradle sync finish (first time downloads a lot).
3. Build > Build Bundle(s) / APK(s) > Build APK(s). Click "locate" in the popup.
   The file is `app/build/outputs/apk/debug/app-debug.apk`.

### B) No install: build it on GitHub (free account)
1. Create a new repository on github.com.
2. Upload the CONTENTS of this folder (including the `.github` folder) to it.
3. Repository > Actions > "Build PragonMobile APK" > Run workflow. Wait ~5 min.
4. Open the finished run > Artifacts > download `PragonMobile-apk` > unzip to get `app-debug.apk`.

## 2. Install on the phone
Copy `app-debug.apk` to the phone, open it, allow "Install unknown apps" when asked.

## 3. One-time permissions (inside the app)
1. **Enable accessibility service** > PragonMobile > On. (Needed for Home/Back, taps, swipes, typing.)
2. **Allow display over other apps** (lets it open apps in the background).
3. **Battery: don't restrict** (on OnePlus also: Settings > Battery > App battery management > PragonMobile > Allow background activity / auto-launch),
   otherwise OxygenOS kills the connection.

## 4. Pair
1. Start Pragon on the PC. Open Remote - PhoneView > Connect Phone (QR appears).
2. In PragonMobile tap "Scan QR from Pragon". Status turns to "Connected to Pragon on your PC".
3. Say: "open YouTube on my phone".

Phone and PC must be on the same Wi-Fi. The pairing is remembered; after a PC restart the app
reconnects by itself (open PragonMobile once if the phone was rebooted).

## What works
open/launch any app by name, YouTube/Google search, open links, Home/Back/Recents,
notifications, quick settings, lock, volume, play/pause/next/previous, scroll/swipe, tap,
type into the focused box, open dialer, phone status.
Screenshots and force-closing apps need ADB (Android doesn't let normal apps do those);
Pragon automatically uses ADB for those if it's set up.

## Two-way Remote screen (new)

Once paired, PragonMobile shows a **Remote** section (below the permission buttons):
- **Chat** with Pragon from the phone - same as typing into PhoneView's web chat box.
- **Model pills** (Pragon / Jarvis / Friday / Ghost) - switch engines from the phone; switching
  on the desktop updates the pills here too.
- **Mic button** - opens the Google "Talk to Pragon" speech dialog (same mic as Standalone mode); what you say is typed into the box and sent. No Microphone streaming any more.
- **PC-control buttons** - Wake, Lock PC, Play/Pause, Volume, Next, and "Open on PC" (type an app
  or site name and it opens on the computer). Media/volume/lock buttons are Windows-only for now.

This needs the updated `pragon_jarvis` zip (the one with `pragon_phoneview/server.py` and
`features/feature/pc_control.py` changes) - an older PC install will just ignore these messages.

## PhoneView-styled Remote screen + mouse control (new)

The Remote section now matches Pragon's PhoneView web UI: the same dark background, cyan
(`#00D4FF`) accents, pill-style model buttons, and chat bubbles.

New in this version:
- **Mouse trackpad** - a circular joystick-styled pad. Drag to move the PC's cursor (relative
  motion, like a laptop trackpad), tap to left-click. LEFT CLICK / RIGHT CLICK buttons underneath
  for precision. **Windows only** for now, same as the other PC-control buttons.
- Model pills, PC-control buttons, and chat now use the cyan/dark theme instead of default gray
  Android buttons.

Camera, Draw and Files from the web PhoneView aren't in the app yet (they need image upload,
which is a separate piece of work) - everything else PhoneView offers on the "control the PC"
side is here.

## Exact PhoneView UI (new)

The Remote section now has an **"OPEN PHONEVIEW (same UI as the PC)"** button. This doesn't
redraw the web UI in native Android - it opens the REAL PhoneView web app (`app.html`, the same
page you get scanning the QR in a browser) inside the app, already logged in. So chat, mic,
model pills, wake, camera, draw and files all look and behave exactly like the PC/browser
version, because it IS that version.

The native "Mouse" and "PC control" sections stay separate, underneath, since dragging a
trackpad and clicking a PC's mouse isn't something the web UI does at all - that part had to be
built new either way.

Needs the updated PC zip (adds `/mobile-login` and the `web_login` message to
`pragon_phoneview/server.py`) - an older PC install won't understand the new button's request.

## Screen sharing, PC screen and macros (new)

- **PC screen on the phone:** in the app's PhoneView screen, tap **PC Screen** (quick-action row).
  Shows your PC's screen live (about 5 frames/sec). Close it with the X.
- **Phone screen on the PC:** in Pragon on the PC, open Settings -> Remote -> **Phone Screen (live view)**.
  Your phone shows Android's "Start recording or casting?" prompt - tap **Start now**.
  A floating live-view window appears in Pragon. Tap **Stop** (or the phone notification) to end it.
  If the prompt doesn't pop up, pull down the notification "Pragon wants to show your screen".
- **Macros:** tap **Macros** in the PhoneView screen to list the macros recorded on the PC.
  Tap a macro twice (second tap confirms) to run it on the PC; **Stop running macro** cancels it.
- **Mic:** only one microphone feeds Pragon at a time (phone app, then browser mic, then PC mic),
  so PC and phone mics no longer talk over each other.

Needs the updated `pragon_jarvis` (PC) and a rebuilt APK (GitHub Actions).

## Standalone mode (works without a PC)

PragonMobile now opens straight to its own chat screen - no pairing needed:
- **Chat** by typing, or tap the **mic** to talk (Google speech recognition). Spoken questions get
  spoken answers; the speaker icon mutes them.
- **Phone control by chat/voice:** "open YouTube", "search YouTube for lofi", "call 555...", "volume up",
  "go home" and so on (Accessibility must be on for taps/keys).
- **AI key:** put your own Gemini key in Settings -> AI FOR STANDALONE MODE. If you leave it empty, the
  app uses the Gemini key from your PC, which it copies once when you pair (so pair at least once).
- **When paired and the PC is reachable,** the app switches to the full PhoneView screen from the PC.
  If the PC isn't reachable, you stay in standalone mode.

Standalone chat needs internet (it talks to Gemini directly).

## Standalone AI engines (online + offline)

Standalone mode can use three engines. Pick one in Settings > AI ENGINE, or tap the engine chip at the top of the chat screen to cycle through them:

- **GEMINI (online)** - needs a Gemini API key (yours, or the one from a paired PC).
- **OLLAMA (offline / your network)** - talks to an Ollama server: the paired PC (set `OLLAMA_HOST=0.0.0.0` on the PC, address `PC-IP:11434`) or Termux on the phone (`127.0.0.1:11434`).
  Settings can list the installed models and **pull** (download) new ones such as `hermes3`, `llama3.2`, `qwen2.5`, `gemma3` or `hf.co/user/repo`. The download happens on the Ollama server, not on the phone.
- **CUSTOM (any OpenAI-compatible server)** - LM Studio, llama.cpp server, vLLM, OpenRouter, an OpenClaw gateway and similar. Enter the base URL (up to `/v1`), an optional API key and the model name.

All three can control the phone (phone_control) if the model supports tool calling; models that don't are used for chat only. Pictures can be attached with Ollama and Custom engines; PDFs, audio and video only work with Gemini.

## Float mode - hovering voice bubble (new)

A round bubble that floats over every app and keeps listening by voice, so you can control the phone hands-free.

**Start it:** tap the round **float button** next to the mic on the chat screen (first time it asks for the microphone and
"Display over other apps"). Pragon steps into the background and the bubble appears. It also **starts by itself** whenever
an app is opened through Pragon standalone (say or type "open YouTube" in the chat): the app opens, the bubble appears and is already listening.

| Say | What happens |
|---|---|
| "I'm home" / "Daddy's home" (with or without "Pragon") | starts listening (bubble turns cyan) |
| "open Instagram", "launch WhatsApp" | opens the app |
| "close YouTube", "close this app" | force-stops the app |
| "search YouTube for lofi", "search pizza near me" | YouTube / Google search |
| "go home", "go back", "recents", "volume up/down", "pause", "next", "scroll down" | phone buttons and media |
| "Hey Pragon, what's the weather" | anything else addressed to Pragon goes to your AI engine; the answer is spoken |
| "Mute" | microphone fully off, bubble turns grey |
| "Goodbye" | closes float mode |

Bubble colours: amber = waiting for "I'm home", cyan (pulsing) = listening, grey = muted.
Tap the bubble = listening on/off. Drag = move it. Long-press = close. The notification also has a Close button.

Notes:
- **Muted means the microphone is really off**, so it can't hear "I'm home" again. Tap the bubble to resume.
- "Close app" works by opening the app's App info page and pressing **Force stop > OK** through the accessibility
  service (Android has no other way for a normal app). It looks for the English button names plus the standard Settings
  button ids, so phones set to another language or with a heavily customised Settings app may not work. If the app isn't running, Pragon says so.
- "Mute", "goodbye" and "I'm home" work alone, but only as short sentences (so a long sentence in a video can't trigger them). Add "Pragon" in front if you want to be extra safe.
- Only commands you say after the wake phrase are acted on. Free-form questions need "Pragon" in the sentence, so a video that says "open ..." can't ask the AI anything.
- Speech recognition is Google's, so it needs internet (unless you downloaded offline speech for your language), and on some phones it makes a small beep each time it re-arms.
- Voice is picked up through the speaker too, so loud videos can confuse it. Keep the volume moderate or use headphones.

## Float bubble fix (OnePlus / ColorOS)
The bubble is now drawn through the Pragon accessibility service (TYPE_ACCESSIBILITY_OVERLAY) whenever that service is ON,
because OnePlus/ColorOS silently hides normal "Display over other apps" bubbles. If the accessibility service is OFF it falls
back to the normal overlay (needs "Display over other apps"). Keep Accessibility ON after every reinstall.
