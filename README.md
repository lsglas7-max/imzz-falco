# ASISTEN GALAXY IMZZ — Galaxy Autopilot

Proyek Android native Kotlin untuk fitur AI Game & App Autopilot sesuai spesifikasi yang diberikan.

## 🐱 SKYLEN — maskot kucing putih (baru di v1.2.0)

Karakter AI berupa kucing putih yang tampil di layar utama, digambar penuh dengan Canvas (tanpa file gambar).

- **Bicara**: suara Android TextToSpeech lokal dengan pitch kucing, mulut bergerak sinkron dengan suara, plus balon ucapan yang mengetik huruf demi huruf. Semua ucapan asisten (buka aplikasi, status Autopilot, peringatan) sekarang diucapkan Skylen.
- **Ngobrol**: ketik `skylen, apa kabar?` atau kalimat obrolan biasa. Jawaban lewat API #2 lebih dulu, lalu API #1, dan kalau keduanya gagal Skylen tetap menjawab offline.
- **Gerak**: berkedip, bernapas, ekor goyang, kuping bergerak, mata mengikuti jari. 6 aksi: lompat, melambai, stretching, miring kepala, jalan, joget (spontan tiap 7–14 detik, atau tombol ✨ AKSI / perintah "skylen, joget").
- **Ekspresi**: normal, senang, jatuh cinta, mikir, siaga, kaget, ngantuk, sedih, marah. Otomatis mengikuti status Autopilot: PLANNING = mikir, RUNNING = siaga, PAUSED = ngantuk, WAITING = kaget, ERROR = sedih, EMERGENCY STOP = marah. Tidur sendiri kalau 2 menit tidak disentuh.
- **Gaya**: tombol 👕 GAYA ganti outfit (Polos, Pita, Topi Penyihir, Kacamata Cyber, Headphone, Syal, Mahkota, Helm Astronot). Pilihan tersimpan.
- **Interaksi**: usap kepala = mendengkur + hati melayang, ketuk badan = lompat + meow. Suara meow dan purr disintesis di memori (tanpa file audio dan tanpa internet).

File: `mascot/CatMascotView.kt` (gambar + animasi), `mascot/CatBrain.kt` (ngobrol + fallback offline), `mascot/MeowSynth.kt` (suara).

## 📦 Buka ZIP / file untuk Skylen (v1.4.0)

Tombol **BUKA ZIP / FILE** di kartu Skylen membuka ZIP (juga APK, JAR, DOCX) atau file teks apa pun lewat pemilih file Android. Isinya **tidak diekstrak ke penyimpanan**: dibaca dari memori per potongan, jadi aman dari zip-slip, dibatasi 150 MB, dan ZIP berpassword hanya dilaporkan (tidak ditembus).
- Skylen langsung menerima ringkasan (tipe file, struktur, cuplikan README / build file / manifest) dan menganalisisnya.
- Perintah lanjutan di kolom perintah: `baca README.md`, `baca MainActivity` (cukup potongan nama), `baca lanjut` (bagian berikutnya), `daftar file`.
- Per potongan ±2200 huruf karena endpoint `?q=` membawa semuanya lewat URL. Biner (gambar, program) tidak dibaca.

## 👁️ Vision kyio (v1.6.0, opt-in)

API vision `https://api.kyio.web.id/api/v2/ai/ai?q=…&sessionId=…&url=<link gambar>` menerima gambar lewat **link publik**, jadi app mengunggah screenshot (diperkecil 720px, JPEG) ke host file sementara (`autopilot/ImageHost.kt`: uguu.se, cadangan tmpfiles.org) lalu mengirim link-nya. Template-nya ada di `galaxyVisionApiUrl` (bawaan sudah diisi, memakai `{q}`, `{sid}`, `{url}`).

- **Default MATI.** Nyalakan lewat **API STATUS > Nyalakan Vision**; ada penjelasan privasi dan harus dikonfirmasi.
- Layar yang terdeteksi login/OTP/pembayaran/kata sandi **tidak pernah diunggah**. Layar lain (chat, foto, notifikasi) bisa ikut terunggah.
- Urutan Autopilot saat Vision aktif: vision -> API #1 -> API #2 (OCR). Kalau upload/vision gagal otomatis turun ke OCR.
- Mau pakai proxy JSON sendiri? Ganti `galaxyVisionApiUrl` dengan URL proxy (tanpa `{url}`).
- Belum diuji ke host dan API aslinya (tidak ada internet di tempat pembuatan). Kalau host upload berubah/diblokir, kabari.

## 👁️ Mata Skylen untuk layar tanpa teks (v1.5.0)

API kyio lu teks saja, jadi app sekarang menghitung sendiri "objek mencolok" dari screenshot di HP (`screen/SceneSummarizer.kt`, tanpa API dan tanpa internet): gumpalan warna cerah/jenuh atau jauh lebih terang dari latar, lengkap dengan posisi, ukuran, warna, dan bentuk kasar. Daftarnya dikirim ke AI bersama teks OCR sebagai kandidat titik tap di menu game atau tombol ikon. Ini perkiraan dari warna, bukan pengenalan gambar.

## 🔌 Proxy (folder `proxy/`, opsional)

Proxy Node kecil (Vercel gratis atau Termux) yang **tanpa API key** memakai 2 API kyio dengan failover; kalau suatu saat ada key model vision (Gemini/OpenAI-compatible), screenshot otomatis diteruskan ke sana. Panduan: `proxy/README.md`. App tidak membutuhkannya; isi `galaxyVisionApiUrl` / `GALAXY_VISION_API_URL` hanya kalau mau memakainya.

## Dua API AI (sudah terisi bawaan)

Bawaan build: API #1 = `https://api.kyio.web.id/api/v2/ai/gpt-5?q=` dan API #2 = `https://api.kyio.web.id/api/v2/ai/gpt-6-luna?q=`. Keduanya endpoint **teks** (GET `?q=`): tidak menerima gambar, jadi Autopilot mengirim teks layar hasil OCR beserta koordinat tiap teks, lalu AI membalas satu aksi JSON (tap/swipe/dll). Game atau video tanpa teks di layar tidak bisa dibaca dengan cara ini; untuk itu perlu API yang menerima gambar.


- **API #1** (`galaxyAiApiUrl` / `GALAXY_AI_API_URL`): utama untuk Autopilot (vision).
- **API #2** (`galaxyAiApiUrl2` / `GALAXY_AI_API_URL_2`): cadangan Autopilot (pindah otomatis kalau API #1 gagal) dan dipakai **lebih dulu** untuk ngobrol dengan Skylen, supaya jatah API #1 tidak habis oleh obrolan. Boleh dikosongkan.
- GitHub Actions: buat dua repository variable `GALAXY_AI_API_URL` dan `GALAXY_AI_API_URL_2`.
- Format request/response ada di `docs/AI_API_CONTRACT.md` (termasuk mode `chat` baru).

## Fitur yang sudah diimplementasikan

- **Smart App & Game Scanner**: membaca aplikasi yang punya launcher activity dari PackageManager, mengambil label/package/activity, menandai game dengan metadata/kata petunjuk, menampilkan ikon, serta menyimpan katalog ke SQLite lokal.
- **Package resolver**: pencarian nama aplikasi, alias DDS/UBG, pencocokan label dan package, dan dialog pemilihan jika ada beberapa hasil.
- **App launcher**: membuka aplikasi dengan launcher Intent aktual dari PackageManager. Status sukses hanya berarti Android menerima permintaan pembukaan; aplikasi foreground tidak diklaim sudah terverifikasi.
- **Voice & chat command**: pengenalan suara bahasa Indonesia via tombol push-to-talk (RecognizerIntent) dan respons suara menggunakan Android TextToSpeech lokal tanpa layanan TTS cloud. Always-on hotword belum diaktifkan.
- **Screen capture**: MediaProjection dengan persetujuan sistem Android, dijalankan melalui foreground service, dan memiliki notifikasi STOP.
- **Screen understanding**: OCR lokal ML Kit Text Recognition, screenshot terbaru disimpan sementara di cache aplikasi, lalu dikirim ke endpoint AI API milik pengguna hanya setelah pengguna menekan mulai dan menyetujui dialog privasi.
- **Gesture control**: AccessibilityService dengan tap, swipe, dan input teks pada kolom editable yang sedang fokus (bukan password). Izin Accessibility harus diaktifkan manual oleh pengguna.
- **Autopilot loop**: OBSERVE → UNDERSTAND → PLAN → ACT → VERIFY → REPLAN, status yang ditampilkan, batas 10 menit dan 120 aksi, pause/resume, AI Assist, Manual, dan Emergency Stop.
- **Safety fallback**: berhenti dan meminta pengguna mengambil alih saat OCR mendeteksi sinyal autentikasi atau tindakan sensitif; tidak ada bypass anti-cheat, pembelian, pembayaran, penghapusan, pengiriman pesan, atau autentikasi otomatis. Emergency Stop membatalkan rencana/aksi berikutnya; gesture yang sudah diserahkan ke Android mungkin menyelesaikan durasi gesture pendek yang sedang berjalan.
- **Game memory**: profil durasi tap/swipe dan sensitivitas disimpan lokal per package aplikasi yang aktif.
- **Package updates**: receiver untuk package install/remove/change memicu scan ulang dan memperbarui database.

## Cara membuka dan build

1. Ekstrak folder `GalaxyAutopilot`.
2. Buka folder tersebut sebagai proyek di Android Studio versi yang mendukung Android Gradle Plugin 8.7.3.
3. Gunakan JDK 17 dan Android SDK Platform 35.
4. Jalankan **Sync Project with Gradle Files**.
5. Pilih perangkat Android (minimum Android 8.0 / API 26), lalu Build > Build APK(s).

> Versi 1.2.0 belum dikompilasi di lingkungan pembuatnya (tidak ada Android SDK/Gradle). Kalau build GitHub Actions error, kirim log-nya, nanti diperbaiki.

Konfigurasi menggunakan Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Gradle 8.9, compile/target SDK 35, dan min SDK 26.

> APK belum disertakan. Build belum dapat diverifikasi di lingkungan ini karena Gradle Wrapper gagal mengunduh distribusi Gradle dari `services.gradle.org` (koneksi jaringan tidak tersedia). Buka project di Android Studio/JDK 17 untuk menjalankan build lokal.

## Konfigurasi endpoint AI

Project ini tidak menyertakan URL backend pihak ketiga. Masukkan URL API yang memang sudah lu miliki saat build:

- Lokal: `./gradlew assembleDebug -PgalaxyAiApiUrl="https://domain-api-lu/path"`
- Environment: set `GALAXY_AI_API_URL` sebelum menjalankan Gradle.
- GitHub Actions: buat repository variable bernama `GALAXY_AI_API_URL`; workflow akan meneruskannya saat build.

Endpoint harus menerima request JSON sesuai kontrak di bagian Privasi dan keamanan API dan mengembalikan satu planned action JSON. Endpoint diakses tanpa header provider key dari aplikasi Android.

## Setup pertama kali di Android

1. Instal APK hasil build.
2. Buka **ASISTEN GALAXY IMZZ** lalu tekan **SCAN APPS**.
3. Tekan **ACCESSIBILITY** dan aktifkan **Galaxy Autopilot Control** pada pengaturan Accessibility Android.
4. Tekan **SCREEN SHARE**, lalu setujui dialog rekam/cast layar Android. Notifikasi foreground service akan muncul selama capture aktif.
5. Saat build, masukkan URL API yang sudah lu punya melalui Gradle property `galaxyAiApiUrl` atau environment variable `GALAXY_AI_API_URL`. Aplikasi tidak meminta API key.
6. Berikan perintah seperti `Buka Roblox`. Setelah Roblox dibuka, ucapkan nama game seperti `Drag Drive Simulator` atau `Untitled Boxing Game`; Autopilot akan memakai OCR/vision untuk mencoba navigasi UI yang terlihat.
7. Gunakan **STOP**, **MANUAL**, atau notifikasi **STOP** untuk menghentikan aksi. Jangan tinggalkan sesi Autopilot tanpa pengawasan.

## Privasi dan keamanan API

- Screenshot dan OCR dikirim ke URL AI API yang dikonfigurasi. Endpoint harus menerima JSON berisi `goal`, `ocrText`, `previousActions`, `screenshotMimeType`, `screenshotBase64`, dan `safety`.
- API mengembalikan JSON aksi langsung (`type`, `x`, `y`, `x2`, `y2`, `text`, `waitMs`, `reason`, `speech`) atau objek `{ "action": { ... } }`. Proxy yang mengembalikan format Gemini `candidates[].content.parts[].text` juga didukung.
- Tidak ada API key provider yang ditanam di APK atau diminta lewat UI. Letakkan autentikasi/provider key di server API milik lu, bukan di aplikasi Android.
- Voice feedback menggunakan Android TextToSpeech lokal.
- App scanner hanya meminta visibilitas launcher melalui `<queries>`; proyek tidak meminta `QUERY_ALL_PACKAGES`.
- Screen capture memerlukan persetujuan sistem. Accessibility harus diaktifkan pengguna secara manual.
- Model AI pada endpoint dapat bersifat probabilistik. Koordinat UI bisa keliru, OCR dapat salah baca, dan tiap game memiliki layout/kontrol berbeda. Karena itu fitur tetap dibatasi, memerlukan persetujuan sesi, dan menyediakan Emergency Stop.

## Struktur modul

- `apps/`: scanner, package resolver, app launcher, deteksi game, navigasi game.
- `screen/`: MediaProjection manager/service dan OCR processor.
- `accessibility/`: service dan gesture controller.
- `autopilot/`: AI API client, planner, state machine, screen safety, action executor.
- `storage/`: SQLite app catalog dan memory control profile.
- `voice/`: Android TextToSpeech lokal.

## Batasan yang perlu diketahui

Autopilot belum bisa menjamin semua game/aplikasi dapat dikendalikan. Aplikasi yang tidak menyediakan UI accessibility, game dengan UI yang berubah-ubah, dialog izin, CAPTCHA, proteksi anti-cheat, atau layout yang tidak terbaca dapat memerlukan tindakan manual. Fitur pencarian Roblox dilakukan melalui UI yang terlihat, bukan API internal Roblox dan bukan hardcoded daftar game. Verifikasi keberhasilan masuk ke game bergantung pada perubahan layar/OCR setelah aksi.
