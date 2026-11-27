# Galaxy Autopilot — AI API contract

The Android app calls the configured HTTPS endpoint with `POST` and `Content-Type: application/json`. It does not send a provider API key or any authorization header.

## Request

```json
{
  "goal": "Help navigate the current app",
  "ocrText": "visible OCR text",
  "previousActions": "recent action history",
  "screenshotMimeType": "image/jpeg",
  "screenshotBase64": "BASE64_ENCODED_JPEG",
  "responseFormat": "planned_action_json",
  "safety": {
    "allowedActions": ["tap", "swipe", "type_text", "wait", "ask_user", "stop"],
    "neverAutomateSensitiveActions": true,
    "neverBypassAuthenticationOrAntiCheat": true
  }
}
```

## Response

Return either the action object directly or wrap it in an `action` property:

```json
{
  "type": "tap",
  "x": 0.5,
  "y": 0.5,
  "x2": 0.5,
  "y2": 0.5,
  "text": "",
  "waitMs": 1500,
  "reason": "The visible Continue control is clear",
  "speech": "Melanjutkan bre"
}
```

`type` must be one of `tap`, `swipe`, `type_text`, `wait`, `ask_user`, or `stop`. Coordinates are normalized from `0.0` to `1.0`. The client clamps coordinates and wait duration and rejects unknown action types. For sensitive or ambiguous screens, return `ask_user` or `stop`.

The Android client also accepts a Gemini-compatible response wrapper where `candidates[0].content.parts[0].text` contains the action JSON. The request itself remains the JSON contract above; the existing API backend must translate it to whichever model/provider it uses.

## Configure the endpoint

Build with:

```bash
./gradlew assembleDebug -PgalaxyAiApiUrl="https://YOUR-EXISTING-API-ENDPOINT"
```

or set `GALAXY_AI_API_URL` in the build environment. On GitHub Actions, define the repository variable `GALAXY_AI_API_URL`; the included workflow passes it to Gradle. Keep provider credentials on the backend, not in the APK.

## Chat mode (Skylen)

Skylen ngobrol lewat endpoint yang sama. Bedanya `mode` bernilai `chat`, tidak ada screenshot, dan `responseFormat` = `chat_reply_json`. Aplikasi mencoba **API #2 dulu**, lalu API #1.

```json
{
  "mode": "chat",
  "goal": "pesan pengguna",
  "message": "pesan pengguna",
  "persona": "Kamu adalah Skylen, kucing putih virtual ...",
  "history": [ { "role": "user", "text": "..." }, { "role": "cat", "text": "..." } ],
  "ocrText": "",
  "previousActions": "",
  "responseFormat": "chat_reply_json"
}
```

Balas dengan salah satu bentuk ini (field pertama yang terisi dipakai):

```json
{ "reply": "Meow! Halo bre, ada apa nih?", "mood": "HAPPY", "animation": "WAVE" }
```

- `reply` (atau `speech`, `text`, `message`, `response`, `answer`, `content`) = kalimat yang diucapkan Skylen, maksimal 240 karakter.
- `mood` (opsional): `IDLE, HAPPY, LOVE, THINKING, ALERT, SURPRISED, SLEEPY, SAD, ANGRY`.
- `animation` (opsional): `HOP, WAVE, STRETCH, HEAD_TILT, WALK, DANCE`.
- Format Gemini (`candidates[0].content.parts[0].text`) dan OpenAI (`choices[0].message.content`) juga dibaca.
- Kalau backend membalas aksi layar (`tap`, `swipe`, `type_text`) untuk request chat, balasan itu diabaikan dan Skylen menjawab offline.
- Tidak ada API key di aplikasi: backend yang memegang key provider.

## Endpoint teks (GET ?q=)

URL yang mengandung `q=` (mis. `https://api.kyio.web.id/api/v2/ai/gpt-5?q=`) diperlakukan sebagai endpoint teks: prompt di-URL-encode lalu ditempel setelah `q=` dengan GET. Balasan boleh teks biasa atau JSON apa saja; app mengambil nilai teks pertama dari kunci `result, response, answer, reply, text, content, message, output, data, choices, candidates, parts`. Untuk Autopilot, prompt memuat tujuan, riwayat aksi, dan daftar "x,y teks" dari OCR; AI harus membalas satu objek JSON aksi.

## Proxy (opsional)

Request juga membawa `scene` (daftar objek mencolok dari app). Endpoint JSON (bukan `?q=`) yang diisi lewat `galaxyVisionApiUrl`. Memakai kontrak POST di atas (screenshot JPEG base64 + `ocrLayout`) dan mode `chat`. Implementasi siap pakai ada di folder `proxy/` (tanpa API key memakai 2 API kyio; dengan key vision meneruskan screenshot). Dicoba pertama untuk Autopilot; kalau gagal app pindah ke API #1 lalu #2.

## Template vision (GET + link gambar)

`galaxyVisionApiUrl` yang mengandung `{url}` diperlakukan sebagai API vision gaya kyio: `{q}` = prompt (URL-encoded), `{sid}` = id sesi acak per langkah, `{url}` = link publik screenshot hasil unggah ke host sementara. Balasan dibaca seperti endpoint teks (JSON aksi di dalam teks). Hanya dipakai kalau pengguna menyalakan Vision.
