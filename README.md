# Qwen Coder Native v1.3

Android 8+ Qwen Coder wrapper.

## Native mode (default)
- Full-screen content area (no browser address bar), forced landscape.
- Desktop User-Agent, wide CSS viewport, pinch zoom.
- Native drag-to-position microphone with Android SpeechRecognizer (Russian).
- In-app files, browser history, progress, persistent WebView cookies.
- Prefer **Email + verification code** in `https://coder.qwen.ai/auth` if Qwen permits sign-in.

## Google login
- Native WebView and Chrome use isolated cookie jars. Google OAuth initiated in a WebView may fail (`Invalid origin`).
- Select **Open Chrome** from the in-app menu to use Google's already-working Chrome session.
- The Chrome window's orientation, desktop mode, and toolbar are controlled by Chrome, not by the native Activity.
- The Android app cannot carry Qwen login cookies from Chrome into WebView, and cannot remove the Chrome toolbar without website ownership and verified TWA.

This is intentionally a dual-mode app: Native desktop UI with email authentication, or secure Chrome login. It does not claim Google OAuth works in the embedded WebView.
