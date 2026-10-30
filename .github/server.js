const express = require('express');
const multer = require('multer');
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');

const PORT = Number(process.env.PORT || 8787);
const API_KEY = process.env.WAVESPEED_API_KEY;
const MODEL_URL = 'https://api.wavespeed.ai/api/v3/wavespeed-ai/wan-2.2/animate-2';
const API_BASE = 'https://api.wavespeed.ai/api/v3';
const upload = multer({
  dest: path.join(__dirname, 'tmp'),
  limits: { fileSize: 500 * 1024 * 1024, files: 2 }
});

const app = express();
app.use(express.json({ limit: '1mb' }));
app.use(express.static(path.join(__dirname, 'public')));

function requireKey(res) {
  if (!API_KEY) {
    res.status(503).json({ error: 'Server is not configured. Set WAVESPEED_API_KEY before starting Clip Forge.' });
    return false;
  }
  return true;
}

async function uploadToWaveSpeed(file) {
  const stat = await fsp.stat(file.path);
  const ticketRes = await fetch(`${API_BASE}/media/uploads`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${API_KEY}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      filename: file.originalname,
      size: stat.size,
      content_type: file.mimetype || undefined
    })
  });
  const ticket = await ticketRes.json().catch(() => ({}));
  if (!ticketRes.ok) throw new Error(ticket.message || `WaveSpeed upload ticket failed (${ticketRes.status})`);

  const uploadUrl = ticket?.data?.upload?.url;
  const headers = ticket?.data?.upload?.headers || {};
  const downloadUrl = ticket?.data?.download_url;
  if (!uploadUrl || !downloadUrl) throw new Error('WaveSpeed returned an incomplete upload ticket.');

  const bytes = await fsp.readFile(file.path);
  const putHeaders = new Headers(headers);
  if (!putHeaders.has('Content-Length')) putHeaders.set('Content-Length', String(bytes.length));
  const putRes = await fetch(uploadUrl, { method: 'PUT', headers: putHeaders, body: bytes });
  if (!putRes.ok) throw new Error(`WaveSpeed media upload failed (${putRes.status})`);
  return downloadUrl;
}

app.get('/api/health', (_req, res) => {
  res.json({ ok: true, configured: Boolean(API_KEY), model: 'wavespeed-ai/wan-2.2/animate-2' });
});

app.post('/api/generate', upload.fields([
  { name: 'image', maxCount: 1 },
  { name: 'video', maxCount: 1 }
]), async (req, res) => {
  if (!requireKey(res)) return;
  const image = req.files?.image?.[0];
  const video = req.files?.video?.[0];
  if (!image || !video) return res.status(400).json({ error: 'Both a reference image and a driving video are required.' });

  try {
    const [imageUrl, videoUrl] = await Promise.all([uploadToWaveSpeed(image), uploadToWaveSpeed(video)]);
    const payload = {
      image: imageUrl,
      video: videoUrl,
      resolution: ['480p', '720p'].includes(req.body.resolution) ? req.body.resolution : '480p'
    };
    if (req.body.prompt?.trim()) payload.prompt = req.body.prompt.trim();
    if (req.body.motion_prompt?.trim()) payload.motion_prompt = req.body.motion_prompt.trim();

    const submit = await fetch(MODEL_URL, {
      method: 'POST',
      headers: { Authorization: `Bearer ${API_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const body = await submit.json().catch(() => ({}));
    if (!submit.ok || body.code && body.code !== 200) {
      throw new Error(body.message || `WaveSpeed generation submission failed (${submit.status})`);
    }
    const task = body.data || body;
    if (!task.id) throw new Error('WaveSpeed did not return a prediction ID.');
    res.json({ id: task.id, status: task.status || 'created' });
  } catch (err) {
    res.status(502).json({ error: err.message || 'Generation request failed.' });
  } finally {
    for (const f of [image, video]) if (f) fs.rm(f.path, { force: true }, () => {});
  }
});

app.get('/api/result/:id', async (req, res) => {
  if (!requireKey(res)) return;
  const id = encodeURIComponent(req.params.id);
  try {
    const r = await fetch(`${API_BASE}/predictions/${id}/result`, {
      headers: { Authorization: `Bearer ${API_KEY}` }
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok) return res.status(r.status).json({ error: body.message || `Result request failed (${r.status})` });
    res.json(body.data || body);
  } catch (err) {
    res.status(502).json({ error: err.message || 'Result request failed.' });
  }
});

app.listen(PORT, () => console.log(`Hollowhaven Clip Forge listening on http://localhost:${PORT}`));
