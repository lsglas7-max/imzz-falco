'use strict';
/**
 * Inti proxy. Dipakai oleh api/vision.js (Vercel) dan server.js (Termux/lokal).
 *
 * Masuk : JSON dari app (kontrak di docs/AI_API_CONTRACT.md)
 *         {goal, ocrText, ocrLayout, previousActions, screenshotBase64, screenshotMimeType, ...}
 *         atau {mode:"chat", message, persona, history}
 * Keluar: mode biasa -> {type, x, y, x2, y2, text, waitMs, reason, speech}
 *         mode chat  -> {reply, mood, animation}
 * API key HANYA ada di environment variable proxy, tidak pernah ada di APK.
 */

const ALLOWED = ['tap', 'swipe', 'type_text', 'wait', 'ask_user', 'stop'];
const MOODS = ['IDLE', 'HAPPY', 'LOVE', 'THINKING', 'ALERT', 'SURPRISED', 'SLEEPY', 'SAD', 'ANGRY'];
const ANIMS = ['HOP', 'WAVE', 'STRETCH', 'HEAD_TILT', 'WALK', 'DANCE'];
const MAX_IMAGE_B64 = 6 * 1024 * 1024; // ~4.5 MB gambar

// Provider "kyio": 2 API teks bawaan (GET ?q=), TANPA API key. Dicoba berurutan (failover).
// Model ini tidak menerima gambar, jadi screenshot diabaikan dan yang dipakai adalah teks OCR + "OBJEK MENCOLOK" dari app.
const KYIO_DEFAULTS = [
  'https://api.kyio.web.id/api/v2/ai/gpt-5?q=',
  'https://api.kyio.web.id/api/v2/ai/gpt-6-luna?q='
];
const KYIO_BUDGET = 3800; // batas panjang prompt di URL (karakter mentah)

const PLAN_RULES = [
  'Kamu adalah Skylen, AI yang mengendalikan layar HP Android lewat tap dan swipe.',
  'Kamu diberi screenshot layar, teks OCR, dan daftar "x,y teks" (titik tengah ternormalisasi 0.0-1.0, 0,0 = kiri atas).',
  'Pilih SATU aksi berikutnya yang paling masuk akal untuk mencapai TUJUAN.',
  'Balas HANYA satu objek JSON: {"type":"tap|swipe|type_text|wait|ask_user|stop","x":0.5,"y":0.5,"x2":0.5,"y2":0.5,"text":"","waitMs":1500,"reason":"alasan singkat","speech":"kalimat pendek santai bahasa Indonesia"}.',
  'x,y = titik tap atau awal swipe; x2,y2 = akhir swipe. Scroll video/feed ke bawah = swipe dari y 0.75 ke y 0.25.',
  'Pakai "wait" kalau layar masih loading, "stop" kalau tujuan sudah tercapai, "ask_user" kalau ragu atau layar tidak jelas.',
  'DILARANG otomatis: login, OTP, captcha, memasukkan password, pembayaran atau pembelian, hapus akun, mengirim pesan, posting, atau mengakali anti-cheat. Untuk itu balas ask_user.'
].join('\n');

const CHAT_RULES = [
  'Kamu adalah Skylen (dipanggil Sky atau Len), kucing putih virtual yang sangat pintar, manis, dan jahil, maskot aplikasi ASISTEN GALAXY IMZZ.',
  'Jawab bahasa Indonesia santai, panggil pengguna "bre". Obrolan biasa maksimal 2 kalimat; pertanyaan teknis (pemrograman, desain) jawab lengkap dengan kalimat pertama berupa ringkasan satu kalimat.',
  'Balas HANYA JSON: {"reply":"...","mood":"IDLE|HAPPY|LOVE|THINKING|ALERT|SURPRISED|SLEEPY|SAD|ANGRY","animation":"HOP|WAVE|STRETCH|HEAD_TILT|WALK|DANCE atau kosong"}.'
].join('\n');

function clamp(n, lo, hi, d) {
  const v = Number(n);
  if (!Number.isFinite(v)) return d;
  return Math.min(hi, Math.max(lo, v));
}
const str = (v, max) => (typeof v === 'string' ? v : v == null ? '' : String(v)).slice(0, max);

function extractJson(text) {
  if (typeof text !== 'string') return null;
  const a = text.indexOf('{');
  const b = text.lastIndexOf('}');
  if (a < 0 || b <= a) return null;
  try { return JSON.parse(text.slice(a, b + 1)); } catch { return null; }
}

function normalizeAction(obj) {
  if (!obj || typeof obj !== 'object') {
    return { type: 'ask_user', reason: 'Model tidak membalas aksi yang valid.', speech: 'Gw bingung bre, bantuin dong.' };
  }
  const a = obj.action && typeof obj.action === 'object' ? obj.action : obj;
  const type = str(a.type, 20).toLowerCase();
  if (!ALLOWED.includes(type)) {
    return { type: 'ask_user', reason: 'Model memberi jenis aksi yang tidak didukung.', speech: 'Gw nggak yakin langkahnya bre.' };
  }
  return {
    type,
    x: clamp(a.x, 0, 1, 0.5),
    y: clamp(a.y, 0, 1, 0.5),
    x2: clamp(a.x2, 0, 1, 0.5),
    y2: clamp(a.y2, 0, 1, 0.5),
    text: str(a.text, 100),
    waitMs: clamp(a.waitMs, 500, 8000, 1500),
    reason: str(a.reason, 300),
    speech: str(a.speech, 240)
  };
}

function normalizeChat(obj, rawText) {
  const reply = str(obj && (obj.reply || obj.speech || obj.text), 4000) || str(rawText, 4000);
  const mood = str(obj && obj.mood, 12).toUpperCase();
  const animation = str(obj && (obj.animation || obj.gesture), 12).toUpperCase();
  const out = { reply };
  if (MOODS.includes(mood)) out.mood = mood;
  if (ANIMS.includes(animation)) out.animation = animation;
  return out;
}

async function fetchJson(url, init, timeoutMs) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { ...init, signal: ctl.signal });
    const text = await res.text();
    if (!res.ok) throw new Error(`Provider HTTP ${res.status}: ${text.slice(0, 300)}`);
    try { return JSON.parse(text); } catch { throw new Error('Provider membalas non-JSON.'); }
  } finally {
    clearTimeout(timer);
  }
}

function pickProvider(env) {
  const want = (env.VISION_PROVIDER || '').toLowerCase();
  if (want === 'kyio') return 'kyio';
  if (want === 'gemini' && env.GEMINI_API_KEY) return 'gemini';
  if (want === 'openai' && env.OPENAI_API_KEY) return 'openai';
  // Kalau ada key penyedia yang bisa melihat gambar, pakai itu; kalau tidak, kyio (tanpa key).
  if (env.GEMINI_API_KEY) return 'gemini';
  if (env.OPENAI_API_KEY) return 'openai';
  return 'kyio';
}

function extractText(raw) {
  const t = String(raw).trim();
  if (!(t.startsWith('{') || t.startsWith('['))) return t;
  let root;
  try { root = JSON.parse(t); } catch { return t; }
  const keys = ['result', 'response', 'answer', 'reply', 'speech', 'text', 'content', 'message', 'output', 'data', 'choices', 'candidates', 'parts'];
  const find = (v, d) => {
    if (d > 6 || v == null) return null;
    if (typeof v === 'string') return v.trim() ? v : null;
    if (Array.isArray(v)) { for (const x of v) { const r = find(x, d + 1); if (r) return r; } return null; }
    if (typeof v === 'object') { for (const k of keys) { if (k in v) { const r = find(v[k], d + 1); if (r) return r; } } }
    return null;
  };
  return find(root, 0) || t;
}

async function askKyio(env, system, userText) {
  const urls = (env.KYIO_URLS ? String(env.KYIO_URLS).split(',').map((u) => u.trim()).filter(Boolean) : KYIO_DEFAULTS);
  const userBudget = Math.max(400, KYIO_BUDGET - system.length - 20);
  const prompt = `${system}\n\n${userText.slice(0, userBudget)}`;
  const timeout = Number(env.PROVIDER_TIMEOUT_MS) || 40000;
  let lastErr = null;
  for (const base of urls) {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), timeout);
    try {
      const res = await fetch(base + encodeURIComponent(prompt), { signal: ctl.signal, headers: { Accept: 'application/json, text/plain, */*' } });
      const raw = await res.text();
      if (!res.ok) throw new Error(`kyio HTTP ${res.status}: ${raw.slice(0, 200)}`);
      const text = extractText(raw);
      if (!text) throw new Error('kyio membalas kosong.');
      return text;
    } catch (e) {
      lastErr = e;
    } finally {
      clearTimeout(timer);
    }
  }
  throw lastErr || new Error('Semua endpoint kyio gagal.');
}

async function askModel(provider, env, system, userText, imageB64, mime, wantJson) {
  if (provider === 'kyio') return askKyio(env, system, userText);
  const timeout = Number(env.PROVIDER_TIMEOUT_MS) || 25000;
  if (provider === 'gemini') {
    const model = env.GEMINI_MODEL || 'gemini-2.5-flash';
    const base = (env.GEMINI_BASE_URL || 'https://generativelanguage.googleapis.com').replace(/\/$/, '');
    const parts = [{ text: userText }];
    if (imageB64) parts.push({ inline_data: { mime_type: mime, data: imageB64 } });
    const body = {
      systemInstruction: { parts: [{ text: system }] },
      contents: [{ role: 'user', parts }],
      generationConfig: { temperature: 0.2, ...(wantJson ? { responseMimeType: 'application/json' } : {}) }
    };
    const json = await fetchJson(`${base}/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY },
      body: JSON.stringify(body)
    }, timeout);
    const cand = json.candidates && json.candidates[0];
    const text = cand && cand.content && cand.content.parts && cand.content.parts.map((p) => p.text || '').join('');
    if (!text) throw new Error('Gemini tidak memberi teks (mungkin diblokir filter).');
    return text;
  }

  const model = env.VISION_MODEL || 'gpt-4o-mini';
  const base = (env.OPENAI_BASE_URL || 'https://api.openai.com/v1').replace(/\/$/, '');
  const content = [{ type: 'text', text: userText }];
  if (imageB64) content.push({ type: 'image_url', image_url: { url: `data:${mime};base64,${imageB64}` } });
  const body = {
    model,
    temperature: 0.2,
    messages: [{ role: 'system', content: system }, { role: 'user', content }],
    ...(wantJson ? { response_format: { type: 'json_object' } } : {})
  };
  const json = await fetchJson(`${base}/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${env.OPENAI_API_KEY}` },
    body: JSON.stringify(body)
  }, timeout);
  const text = json.choices && json.choices[0] && json.choices[0].message && json.choices[0].message.content;
  if (!text) throw new Error('Provider tidak memberi teks.');
  return text;
}

function planPrompt(b) {
  const layout = str(b.ocrLayout, 3000) || str(b.ocrText, 3000);
  const scene = str(b.scene, 1200);
  return [
    `TUJUAN: ${str(b.goal, 1500)}`,
    `AKSI SEBELUMNYA:\n${str(b.previousActions, 1500) || '(belum ada)'}`,
    `TEKS LAYAR (x,y teks):\n${layout || '(tidak ada teks terbaca)'}`,
    scene ? `OBJEK MENCOLOK (perkiraan kasar dari warna, bukan pengenalan gambar):\n${scene}` : '',
    'Tentukan satu aksi berikutnya. Balas JSON saja.'
  ].filter(Boolean).join('\n\n');
}

function chatPrompt(b) {
  const lines = [];
  if (Array.isArray(b.history)) {
    b.history.slice(-6).forEach((h) => {
      if (h && typeof h.text === 'string') lines.push(`${h.role === 'user' ? 'Pengguna' : 'Skylen'}: ${h.text.slice(0, 400)}`);
    });
  }
  lines.push(`Pengguna: ${str(b.message || b.goal, 4000)}`);
  return lines.join('\n');
}

/** @returns {Promise<{status:number, json:object}>} */
async function handle(body, env = process.env) {
  if (!body || typeof body !== 'object') return { status: 400, json: { error: 'Body harus JSON object.' } };

  const provider = pickProvider(env);
  if (!provider) {
    return { status: 500, json: { error: 'Provider tidak ditemukan.' } };
  }

  const chat = body.mode === 'chat';
  try {
    if (chat) {
      const raw = await askModel(provider, env, `${CHAT_RULES}\n${str(body.persona, 1200)}`, chatPrompt(body), null, null, true);
      return { status: 200, json: normalizeChat(extractJson(raw), raw) };
    }

    let image = null;
    let mime = 'image/jpeg';
    if (typeof body.screenshotBase64 === 'string' && body.screenshotBase64.length > 0) {
      if (body.screenshotBase64.length > MAX_IMAGE_B64) return { status: 413, json: { error: 'Screenshot terlalu besar.' } };
      image = body.screenshotBase64;
      mime = ['image/jpeg', 'image/png', 'image/webp'].includes(body.screenshotMimeType) ? body.screenshotMimeType : 'image/jpeg';
    }
    const raw = await askModel(provider, env, PLAN_RULES, planPrompt(body), image, mime, true);
    return { status: 200, json: normalizeAction(extractJson(raw)) };
  } catch (e) {
    // Jangan bocorkan detail internal berlebihan; app akan failover ke API lain.
    return { status: 502, json: { error: String(e && e.message ? e.message : e).slice(0, 300) } };
  }
}

module.exports = { handle, normalizeAction, normalizeChat, extractJson, ALLOWED };
