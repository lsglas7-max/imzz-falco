# Proxy Skylen

Proxy kecil di depan AI. **Tanpa API key**: bawaannya memakai 2 API kyio lu (gpt-5, lalu gpt-6-luna kalau gagal).
Kalau suatu saat lu punya key model yang bisa melihat gambar (Gemini / kompatibel OpenAI), isi key-nya di `.env` dan proxy otomatis meneruskan screenshot ke sana.

Penting: API kyio itu **teks saja**, jadi tanpa key vision proxy ini tidak benar-benar "melihat" screenshot. Yang dipakai: teks OCR + koordinatnya + daftar "objek mencolok" (gumpalan warna/tombol) yang dihitung app di HP. Cukup untuk menu dan tombol, lemah untuk gameplay yang butuh memahami gambar.

**Proxy ini opsional.** (Sejak v1.6.0 slot `galaxyVisionApiUrl` bawaannya API vision kyio; kalau mau pakai proxy ini, ganti isinya dengan URL proxy.) App v1.5.0 sudah memanggil 2 API kyio langsung tanpa proxy. Pakai proxy kalau mau satu pintu (mis. dipakai dari perangkat lain) atau nanti nambah key vision.

Alur: app -> proxy (POST JSON) -> model -> proxy merapikan jadi satu aksi aman -> app.
Aksi hanya `tap, swipe, type_text, wait, ask_user, stop`; koordinat dan durasi dijepit, jenis aksi lain diubah jadi `ask_user`.

## Opsi A — Vercel (gratis, dari mana saja)
1. Push repo ini ke GitHub, lalu di Vercel: **Add New > Project**, pilih repo, set **Root Directory = `proxy`**.
2. Di **Settings > Environment Variables** isi `PROXY_TOKEN` (kata acak panjang). Tidak perlu API key.
3. Deploy. URL proxy: `https://NAMA-PROYEK.vercel.app/api/vision?token=ISI_PROXY_TOKEN`
4. Di GitHub repo: **Settings > Secrets and variables > Actions > Variables**, buat `GALAXY_VISION_API_URL` berisi URL di atas, lalu build ulang APK.

## Opsi B — Termux (jalan di HP sendiri)
```bash
pkg install nodejs git -y
cd ~/NAMA-REPO/proxy
cp .env.example .env && nano .env     # cukup isi PROXY_TOKEN (opsional), tanpa API key
node server.js                        # biarkan jalan; pakai tab Termux lain untuk hal lain
```
URL untuk app: `http://127.0.0.1:8787/api/vision?token=ISI_PROXY_TOKEN` (app mengizinkan http hanya untuk 127.0.0.1/localhost/10.0.2.2).
Bangun APK dengan `-PgalaxyVisionApiUrl="..."` atau variable `GALAXY_VISION_API_URL`.

## Uji
```bash
node test/smoke.js    # tanpa internet, memakai API palsu
curl "https://NAMA-PROYEK.vercel.app/api/vision"   # harus membalas {"ok":true,...}
```

## Catatan
- `.env` sudah masuk `.gitignore`; jangan commit API key.
- Ganti model lewat `GEMINI_MODEL` (bawaan `gemini-2.5-flash`) atau `VISION_MODEL` untuk OpenAI. Nama model bisa berubah, cek dokumentasi penyedia.
- Urutan Autopilot di app: proxy vision -> API #1 -> API #2 (dua API teks). Kalau proxy mati, otomatis jatuh ke OCR.
- Batas kasar: screenshot ~4.5 MB; Vercel Hobby membatasi body 4.5 MB.
