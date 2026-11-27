'use strict';
// Uji tanpa internet: proxy -> "Gemini palsu" lokal. Jalankan: node test/smoke.js
const http = require('http');
const assert = require('assert');
const { spawn } = require('child_process');
const path = require('path');

const replies = [];
const mock = http.createServer((req, res) => {
  let b = '';
  req.on('data', (c) => (b += c));
  req.on('end', () => {
    const parsed = JSON.parse(b);
    mock.lastBody = parsed;
    mock.lastKey = req.headers['x-goog-api-key'];
    const text = replies.shift();
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ candidates: [{ content: { parts: [{ text }] } }] }));
  });
});

function post(port, p, body) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const r = http.request({ host: '127.0.0.1', port, path: p, method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) } }, (res) => {
      let t = '';
      res.on('data', (c) => (t += c));
      res.on('end', () => resolve({ status: res.statusCode, json: JSON.parse(t) }));
    });
    r.on('error', reject);
    r.end(data);
  });
}

// "kyio palsu": /gagal -> HTTP 500 (uji failover), /ok -> {"result": "..."} tanpa API key
const kyioReplies = [];
const kyioMock = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  kyioMock.lastQ = u.searchParams.get('q');
  if (u.pathname === '/gagal') { res.statusCode = 500; return res.end('boom'); }
  res.setHeader('Content-Type', 'application/json');
  res.end(JSON.stringify({ status: true, result: kyioReplies.shift() }));
});

(async () => {
  await new Promise((r) => mock.listen(9101, '127.0.0.1', r));
  await new Promise((r) => kyioMock.listen(9102, '127.0.0.1', r));
  const child = spawn(process.execPath, [path.join(__dirname, '..', 'server.js')], {
    env: { ...process.env, PORT: '8799', GEMINI_API_KEY: 'k-test', GEMINI_BASE_URL: 'http://127.0.0.1:9101', PROXY_TOKEN: 'rahasia' },
    stdio: 'ignore'
  });
  await new Promise((r) => setTimeout(r, 700));
  try {
    // token salah
    let r = await post(8799, '/api/vision?token=salah', {});
    assert.strictEqual(r.status, 401);

    // aksi tap valid + koordinat di-clamp
    replies.push('```json\n{"type":"tap","x":1.7,"y":0.4,"reason":"buka tab","speech":"Siap bre"}\n```');
    r = await post(8799, '/api/vision?token=rahasia', { goal: 'buka TikTok', ocrLayout: '0.5,0.9 Beranda', screenshotBase64: 'AAAA', screenshotMimeType: 'image/jpeg' });
    assert.strictEqual(r.status, 200);
    assert.strictEqual(r.json.type, 'tap');
    assert.strictEqual(r.json.x, 1);
    assert.strictEqual(r.json.y, 0.4);
    assert.strictEqual(mock.lastKey, 'k-test');
    assert.ok(mock.lastBody.contents[0].parts.some((p) => p.inline_data && p.inline_data.data === 'AAAA'), 'screenshot harus ikut terkirim');

    // jenis aksi terlarang -> ask_user
    replies.push('{"type":"purchase","x":0.5}');
    r = await post(8799, '/api/vision?token=rahasia', { goal: 'x' });
    assert.strictEqual(r.json.type, 'ask_user');

    // balasan sampah -> ask_user
    replies.push('maaf saya tidak bisa');
    r = await post(8799, '/api/vision?token=rahasia', { goal: 'x' });
    assert.strictEqual(r.json.type, 'ask_user');

    // mode chat
    replies.push('{"reply":"Meow! Halo bre.","mood":"HAPPY","animation":"WAVE"}');
    r = await post(8799, '/api/vision?token=rahasia', { mode: 'chat', message: 'halo' });
    assert.deepStrictEqual(r.json, { reply: 'Meow! Halo bre.', mood: 'HAPPY', animation: 'WAVE' });

    // swipe + waitMs di-clamp
    replies.push('{"type":"swipe","x":0.5,"y":0.75,"x2":0.5,"y2":0.25,"waitMs":999999}');
    r = await post(8799, '/api/vision?token=rahasia', { goal: 'scroll' });
    assert.strictEqual(r.json.type, 'swipe');
    assert.strictEqual(r.json.waitMs, 8000);

    // ---- provider kyio: tanpa key, failover, teks OCR + scene masuk prompt, screenshot diabaikan
    const child2 = spawn(process.execPath, [path.join(__dirname, '..', 'server.js')], {
      env: { ...process.env, PORT: '8798', GEMINI_API_KEY: '', OPENAI_API_KEY: '', PROXY_TOKEN: '',
             KYIO_URLS: 'http://127.0.0.1:9102/gagal?q=,http://127.0.0.1:9102/ok?q=' },
      stdio: 'ignore'
    });
    await new Promise((r) => setTimeout(r, 700));
    try {
      kyioReplies.push('Ini jawabannya: {"type":"tap","x":0.5,"y":0.9,"reason":"tombol bawah"}');
      r = await post(8798, '/api/vision', { goal: 'buka menu', ocrLayout: '0.50,0.90 Mulai', scene: '0.50,0.90 0.30×0.06 merah memanjang', screenshotBase64: 'AAAA' });
      assert.strictEqual(r.status, 200);
      assert.strictEqual(r.json.type, 'tap');
      assert.strictEqual(r.json.y, 0.9);
      assert.ok(kyioMock.lastQ.includes('0.50,0.90 Mulai'), 'OCR harus masuk prompt');
      assert.ok(kyioMock.lastQ.includes('OBJEK MENCOLOK'), 'scene harus masuk prompt');
      assert.ok(!kyioMock.lastQ.includes('AAAA'), 'screenshot tidak boleh ikut ke API teks');
      assert.ok(kyioMock.lastQ.length < 4200, 'prompt harus muat di URL');

      kyioReplies.push('{"reply":"Halo bre!","mood":"HAPPY"}');
      r = await post(8798, '/api/vision', { mode: 'chat', message: 'halo' });
      assert.strictEqual(r.json.reply, 'Halo bre!');
    } finally { child2.kill(); }

    console.log('SEMUA UJI LULUS');
  } catch (e) {
    console.error('GAGAL:', e.message);
    process.exitCode = 1;
  } finally {
    child.kill();
    mock.close();
    kyioMock.close();
  }
})();
