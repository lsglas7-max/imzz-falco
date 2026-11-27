'use strict';
// Vercel serverless function: POST https://<proyek>.vercel.app/api/vision?token=<PROXY_TOKEN>
const { handle } = require('../lib/core');

module.exports = async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  if (req.method === 'GET') return res.status(200).json({ ok: true, name: 'skylen-vision-proxy' });
  if (req.method !== 'POST') return res.status(405).json({ error: 'Gunakan POST.' });

  const token = process.env.PROXY_TOKEN;
  if (token && (req.query && req.query.token) !== token) return res.status(401).json({ error: 'Token salah.' });

  let body = req.body;
  if (typeof body === 'string') {
    try { body = JSON.parse(body); } catch { return res.status(400).json({ error: 'JSON tidak valid.' }); }
  }
  const out = await handle(body, process.env);
  return res.status(out.status).json(out.json);
};
