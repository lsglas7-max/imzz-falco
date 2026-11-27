'use strict';
// Server lokal untuk Termux: `node server.js` lalu isi galaxyVisionApiUrl = http://127.0.0.1:8787/api/vision?token=...
const http = require('http');
const fs = require('fs');
const path = require('path');
const { handle } = require('./lib/core');

// .env sederhana (tanpa dependensi)
try {
  fs.readFileSync(path.join(__dirname, '.env'), 'utf8').split('\n').forEach((line) => {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (m && m[2] && !(m[1] in process.env)) process.env[m[1]] = m[2];
  });
} catch { /* tidak ada .env: pakai environment yang sudah ada */ }

const PORT = Number(process.env.PORT) || 8787;
const HOST = process.env.HOST || '127.0.0.1'; // hanya HP ini; ganti ke 0.0.0.0 kalau mau dari perangkat lain
const MAX_BODY = 8 * 1024 * 1024;

function send(res, status, obj) {
  const data = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(data);
}

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname !== '/api/vision') return send(res, 404, { error: 'Pakai /api/vision' });
  if (req.method === 'GET') return send(res, 200, { ok: true, name: 'skylen-vision-proxy' });
  if (req.method !== 'POST') return send(res, 405, { error: 'Gunakan POST.' });
  if (process.env.PROXY_TOKEN && url.searchParams.get('token') !== process.env.PROXY_TOKEN) {
    return send(res, 401, { error: 'Token salah.' });
  }

  const chunks = [];
  let size = 0;
  req.on('data', (c) => {
    size += c.length;
    if (size > MAX_BODY) { send(res, 413, { error: 'Body terlalu besar.' }); req.destroy(); return; }
    chunks.push(c);
  });
  req.on('end', async () => {
    if (res.writableEnded) return;
    let body;
    try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return send(res, 400, { error: 'JSON tidak valid.' }); }
    const out = await handle(body, process.env);
    send(res, out.status, out.json);
  });
}).listen(PORT, HOST, () => console.log(`Skylen vision proxy aktif di http://${HOST}:${PORT}/api/vision`));
