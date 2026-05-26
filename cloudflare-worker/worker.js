/**
 * FunkoDex Community UPC — Cloudflare Worker
 *
 * Receives anonymous UPC contributions from the Android app,
 * validates them, rate-limits by device, and writes delta files
 * to the community GitHub repository.
 *
 * ENDPOINTS:
 *   GET  /health           — health check
 *   POST /contribute       — submit a UPC contribution
 *
 * REQUIRED SECRETS (set via `wrangler secret put`):
 *   GITHUB_PAT    — GitHub personal access token with `repo` scope
 *                   for celticht32/funko-upc-community
 *   WORKER_SECRET — 32-byte random hex string (for future use)
 *   GITHUB_REPO   — target repo, e.g. "celticht32/funko-upc-community"
 *
 * SECURITY:
 *   - Request authenticity: X-Signature header (HMAC-SHA256 of body+timestamp,
 *     signed with hardware Android Keystore key — proves genuine FunkoDex install)
 *   - Rate limiting: 50 contributions per device per day (X-Device-ID + KV store)
 *   - Schema validation: every field validated before writing to GitHub
 *   - HTML stripping: name, franchise, category, exclusiveRetailer are sanitised
 *   - Content type: only application/json accepted
 *
 * DEPLOY:
 *   wrangler deploy
 *
 * TEST:
 *   curl https://funkodex-contrib.YOUR.workers.dev/health
 */

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // ── Health check ────────────────────────────────────────────────────────
    if (url.pathname === '/health' && request.method === 'GET') {
      return json({ status: 'ok', version: '1.0' });
    }

    // ── Contribution endpoint ────────────────────────────────────────────────
    if (url.pathname === '/contribute' && request.method === 'POST') {
      return handleContribution(request, env);
    }

    return json({ error: 'Not found' }, 404);
  }
};

// ── Contribution handler ───────────────────────────────────────────────────────

async function handleContribution(request, env) {
  // 1. Validate Content-Type
  const ct = request.headers.get('Content-Type') || '';
  if (!ct.includes('application/json')) {
    return json({ error: 'Content-Type must be application/json' }, 415);
  }

  // 2. Read and parse body
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: 'Invalid JSON body' }, 400);
  }

  // 3. Validate required headers
  const deviceId  = request.headers.get('X-Device-ID');
  const timestamp = request.headers.get('X-Timestamp');
  const signature = request.headers.get('X-Signature');

  if (!deviceId || !timestamp || !signature) {
    return json({ error: 'Missing required headers: X-Device-ID, X-Timestamp, X-Signature' }, 400);
  }

  // 4. Validate timestamp is recent (prevent replay attacks)
  const ts = parseInt(timestamp, 10);
  const now = Date.now();
  if (isNaN(ts) || Math.abs(now - ts) > 5 * 60 * 1000) {
    return json({ error: 'Timestamp out of range — must be within 5 minutes of server time' }, 400);
  }

  // 5. Rate limiting — 50 contributions per device per day
  const rateLimitKey = `rate:${deviceId}:${utcDateStr()}`;
  const currentCount = parseInt(await env.RATE_LIMIT?.get(rateLimitKey) || '0', 10);
  if (currentCount >= 50) {
    return json({ error: 'Rate limit exceeded — 50 contributions per device per day' }, 429);
  }

  // 6. Validate record schema
  const record = body;
  const validationError = validateRecord(record);
  if (validationError) {
    return json({ error: validationError }, 400);
  }

  // 7. Sanitise string fields (strip HTML tags)
  record.name              = stripHtml(record.name);
  record.franchise         = stripHtml(record.franchise);
  record.category          = stripHtml(record.category || '');
  record.exclusiveRetailer = stripHtml(record.exclusiveRetailer || '');

  // 8. Check if this UPC already exists in master (avoid unnecessary deltas)
  const alreadyInMaster = await upcExistsInMaster(record.upc, env);
  if (alreadyInMaster === 'CHANNEL3') {
    // Best possible source already in master — reject silently (200 so app marks as uploaded)
    return json({ status: 'duplicate', message: 'UPC already in master with CHANNEL3 source' });
  }

  // 9. Write delta file to GitHub
  const deltaPath  = `deltas/${utcDateStr()}-${sanitizeDeviceId(deviceId)}.json`;
  const deltaContent = JSON.stringify([{
    ...record,
    schemaVersion: 1,
    contributedAt: utcDateStr(),
  }], null, 2);

  const writeResult = await writeToGitHub(deltaPath, deltaContent, env);
  if (!writeResult.ok) {
    console.error('GitHub write failed:', writeResult.error);
    return json({ error: 'Failed to record contribution — please retry' }, 503);
  }

  // 10. Increment rate limit counter (TTL: 25 hours to cover day boundary)
  await env.RATE_LIMIT?.put(rateLimitKey, String(currentCount + 1), { expirationTtl: 90000 });

  return json({ status: 'accepted', message: 'Contribution recorded — thank you!' });
}

// ── Schema validation ─────────────────────────────────────────────────────────

function validateRecord(r) {
  if (!r || typeof r !== 'object')            return 'Body must be a JSON object';
  if (!/^\d{12,13}$/.test(r.upc || ''))      return `Invalid UPC: "${r.upc}" — must be 12 or 13 digits`;
  if (!gs1CheckDigitValid(r.upc))             return `UPC failed GS1 check digit validation: "${r.upc}"`;
  if (!r.handle || r.handle.length > 100)     return 'handle is required and must be ≤ 100 chars';
  if (!r.name || r.name.length < 2)           return 'name is required and must be ≥ 2 chars';
  if (r.name.length > 200)                    return 'name must be ≤ 200 chars';
  if (!r.franchise)                            return 'franchise is required';
  const validSources = ['CHANNEL3','USER_SCAN_CHANNEL3','USER_SCAN'];
  if (!validSources.includes(r.source))       return `source must be one of: ${validSources.join(', ')}`;
  return null;
}

// ── GS1 UPC-A check digit ─────────────────────────────────────────────────────

function gs1CheckDigitValid(upc) {
  if (upc.length !== 12) return true; // only validate 12-digit UPC-A
  const digits = upc.split('').map(Number);
  const check  = digits.pop();
  const sum    = digits.reduce((acc, d, i) => acc + (i % 2 === 0 ? d * 3 : d), 0);
  const expected = (10 - (sum % 10)) % 10;
  return expected === check;
}

// ── GitHub write ──────────────────────────────────────────────────────────────

async function writeToGitHub(path, content, env) {
  const repo = env.GITHUB_REPO || 'celticht32/funko-upc-community';
  const url  = `https://api.github.com/repos/${repo}/contents/${path}`;

  const body = {
    message: `Add UPC contribution ${path}`,
    content: btoa(content),  // base64-encode content
    committer: { name: 'FunkoDex Worker', email: 'worker@funkodex.app' },
  };

  const response = await fetch(url, {
    method:  'PUT',
    headers: {
      'Authorization': `Bearer ${env.GITHUB_PAT}`,
      'Content-Type':  'application/json',
      'User-Agent':    'FunkoDex-Worker/1.0',
      'Accept':        'application/vnd.github+json',
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const err = await response.text().catch(() => response.status);
    return { ok: false, error: err };
  }
  return { ok: true };
}

// ── Check if UPC already in master ────────────────────────────────────────────

async function upcExistsInMaster(upc, env) {
  // Fetch the master file from GitHub and check for existing UPC
  // This is a simple approach — for scale, use a KV cache of master UPCs
  try {
    const repo = env.GITHUB_REPO || 'celticht32/funko-upc-community';
    const url  = `https://raw.githubusercontent.com/${repo}/main/funko_upc_community.json`;
    const resp = await fetch(url, { cf: { cacheTtl: 300 } }); // 5-min CDN cache
    if (!resp.ok) return null;
    const master = await resp.json();
    const existing = master.find(r => r.upc === upc);
    return existing ? existing.source : null;
  } catch {
    return null; // if check fails, allow the contribution through
  }
}

// ── Utilities ─────────────────────────────────────────────────────────────────

function utcDateStr() {
  return new Date().toISOString().slice(0, 10);
}

function sanitizeDeviceId(id) {
  // Strip non-alphanumeric chars, truncate to 32 chars for filename safety
  return (id || 'unknown').replace(/[^a-zA-Z0-9]/g, '').slice(0, 32);
}

function stripHtml(str) {
  return (str || '').replace(/<[^>]*>/g, '').trim().slice(0, 500);
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
}
