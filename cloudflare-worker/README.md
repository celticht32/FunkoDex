# FunkoDex Community UPC — Cloudflare Worker

The proxy between the Android app and the community GitHub repository.
Validates, rate-limits, and signs UPC contributions before writing them
as delta files to `celtic-heart-steamworks/funko-upc-community`.

## Setup (one-time, ~10 minutes)

### 1. Install Wrangler
```bash
npm install -g wrangler
wrangler login
```

### 2. Create KV namespace for rate limiting
```bash
wrangler kv:namespace create RATE_LIMIT
# Copy the `id` from the output and paste it into wrangler.toml
```

### 3. Add secrets
```bash
# GitHub PAT — create at github.com/settings/tokens
# Scope: repo (for the community repo only — not your app repo)
echo "YOUR_GITHUB_PAT" | wrangler secret put GITHUB_PAT

# Random 32-byte signing secret
openssl rand -hex 32 | wrangler secret put WORKER_SECRET

# Target repo
echo "celtic-heart-steamworks/funko-upc-community" | wrangler secret put GITHUB_REPO
```

### 4. Deploy
```bash
wrangler deploy
# Note your Worker URL: https://funkodex-contrib.YOUR_ACCOUNT.workers.dev
```

### 5. Add URL to Android project
Edit `local.properties` in your FunkoDex project:
```
workerUrl=https://funkodex-contrib.YOUR_ACCOUNT.workers.dev
```

### 6. Test
```bash
curl https://funkodex-contrib.YOUR_ACCOUNT.workers.dev/health
# Expected: {"status":"ok","version":"1.0"}
```

## Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Health check — returns `{"status":"ok"}` |
| `POST` | `/contribute` | Submit a UPC contribution |

## Request format (`POST /contribute`)

```
Content-Type: application/json
X-Device-ID: {anonymous install UUID}
X-Timestamp: {Unix timestamp ms}
X-Signature: {HMAC-SHA256(bodyJson + timestamp, AndroidKeyStore key)}
```

Body:
```json
{
  "upc": "889698123456",
  "handle": "batman-1989",
  "name": "Batman (1989)",
  "franchise": "DC Comics",
  "category": "Pop! Movies",
  "seriesNumber": "#01",
  "retailPrice": 11.99,
  "isVaulted": false,
  "isChase": false,
  "isExclusive": true,
  "exclusiveRetailer": "Target",
  "imageUrl": "https://..._large.jpg",
  "source": "USER_SCAN"
}
```

## Rate limiting

50 contributions per device per day. Enforced via Cloudflare KV.
HTTP 429 is returned when exceeded — the Android app retries the next day.

## Security model

- HMAC-SHA256 signature proves the request came from a genuine FunkoDex install
- Device ID is anonymous — no way to link to a user identity
- GitHub PAT is stored in Cloudflare Secrets — never in the APK
- HTML stripping prevents XSS in contributed field values
- GS1 check digit validation rejects invalid UPCs before they reach GitHub
