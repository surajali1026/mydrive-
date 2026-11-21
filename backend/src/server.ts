import "dotenv/config";
import cors from "cors";
import express from "express";
import rateLimit from "express-rate-limit";
import helmet from "helmet";
import { Pool } from "pg";
import { z } from "zod";
import { AuthenticatedRequest, requireAuth, signUserJwt } from "./authMiddleware";
import { decryptTokens, encryptTokens, hashPassword, verifyPassword } from "./crypto";
import { getProviderSpec } from "./oauthProviders";

const app = express();
const pool = new Pool({ connectionString: process.env.DATABASE_URL });

const allowedOrigins = (process.env.CORS_ORIGINS || "")
  .split(",")
  .map((s) => s.trim())
  .filter(Boolean);

app.use(helmet());
app.use(
  cors({
    origin: (origin, callback) => {
      if (!origin || allowedOrigins.includes(origin)) callback(null, true);
      else callback(new Error("Origin not allowed by CORS policy"));
    },
  })
);
app.use(express.json({ limit: "1mb" }));
app.use(
  rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 200,
    standardHeaders: true,
    legacyHeaders: false,
  })
);

app.get("/health", (_req, res) => {
  res.json({ status: "ok", service: "mydrive-oauth-backend" });
});

// ==================== INDEPENDENT MYDRIVE AUTH ====================

const registerSchema = z.object({
  email: z.string().email(),
  password: z.string().min(8).max(128),
  displayName: z.string().max(80).optional(),
});

app.post("/api/auth/register", async (req, res) => {
  const parsed = registerSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "Invalid registration fields" });
    return;
  }
  const email = parsed.data.email.trim().toLowerCase();
  const displayName = (parsed.data.displayName || email.split("@")[0]).trim();
  const passwordHash = await hashPassword(parsed.data.password);

  try {
    const result = await pool.query(
      `INSERT INTO users (email, display_name, password_hash, auth_provider, is_verified)
       VALUES ($1, $2, $3, 'email', FALSE)
       RETURNING id, email, display_name, auth_provider, is_verified`,
      [email, displayName, passwordHash]
    );
    const user = result.rows[0];
    const token = signUserJwt(user.id, user.email);
    res.status(201).json({
      token,
      user: {
        id: user.id,
        email: user.email,
        displayName: user.display_name,
        authProvider: user.auth_provider,
        isVerified: user.is_verified,
      },
    });
  } catch (err: unknown) {
    const pgErr = err as { code?: string };
    if (pgErr.code === "23505") {
      res.status(409).json({ error: "Email is already registered" });
      return;
    }
    res.status(500).json({ error: "Failed to create account" });
  }
});

const loginSchema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
});

app.post("/api/auth/login", async (req, res) => {
  const parsed = loginSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "Invalid login credentials" });
    return;
  }
  const email = parsed.data.email.trim().toLowerCase();
  const result = await pool.query(
    `SELECT id, email, display_name, password_hash, auth_provider, is_verified
     FROM users WHERE LOWER(email) = $1 LIMIT 1`,
    [email]
  );
  const user = result.rows[0];
  if (!user || !user.password_hash) {
    res.status(401).json({ error: "Invalid email or password" });
    return;
  }
  const valid = await verifyPassword(parsed.data.password, user.password_hash);
  if (!valid) {
    res.status(401).json({ error: "Invalid email or password" });
    return;
  }
  const token = signUserJwt(user.id, user.email);
  res.json({
    token,
    user: {
      id: user.id,
      email: user.email,
      displayName: user.display_name,
      authProvider: user.auth_provider,
      isVerified: user.is_verified,
    },
  });
});

app.post("/api/auth/forgot-password", async (req, res) => {
  const email = typeof req.body?.email === "string" ? req.body.email.trim().toLowerCase() : "";
  if (!email) {
    res.status(400).json({ error: "Email is required" });
    return;
  }
  // Always return generic 200 to avoid account enumeration
  res.json({ message: "If an account exists for this email, reset instructions have been queued." });
});

// ==================== ZERO-SECRET OAUTH 2.0 PKCE BROKER ====================

app.get("/api/oauth/:provider/authorize", requireAuth, (req: AuthenticatedRequest, res) => {
  const providerId = String(req.params.provider);
  if (providerId === "mega") {
    res.status(501).json({ error: "MEGA is Integration Pending (official SDK commercial registration required)." });
    return;
  }
  const spec = getProviderSpec(providerId);
  if (!spec) {
    res.status(400).json({ error: "Unsupported cloud provider" });
    return;
  }
  const clientId = process.env[spec.clientIdEnv];
  if (!clientId) {
    res.status(503).json({ error: `${spec.clientIdEnv} is not configured on the MyDrive backend.` });
    return;
  }

  const codeChallenge = String(req.query.code_challenge || "");
  const state = String(req.query.state || "");
  const redirectUri = String(req.query.redirect_uri || "mydrive://oauth/callback");
  const rawLoginHint = typeof req.query.login_hint === "string" ? req.query.login_hint.trim() : "";
  if (!state) {
    res.status(400).json({ error: "Missing OAuth state" });
    return;
  }

  const params = new URLSearchParams({
    client_id: clientId,
    redirect_uri: redirectUri,
    response_type: "code",
    state,
  });
  if (spec.scope) params.set("scope", spec.scope);
  if (spec.supportsPkce && codeChallenge) {
    params.set("code_challenge", codeChallenge);
    params.set("code_challenge_method", "S256");
  }
  if (spec.extraAuthParams) {
    for (const [k, v] of Object.entries(spec.extraAuthParams)) {
      params.set(k, v);
    }
  }
  if (rawLoginHint && rawLoginHint.length <= 254 && rawLoginHint.includes("@")) {
    params.set("login_hint", rawLoginHint);
  }

  res.json({
    authorizeUrl: `${spec.authorizeEndpoint}?${params.toString()}`,
    clientId,
  });
});

const exchangeSchema = z.object({
  code: z.string().min(1),
  codeVerifier: z.string().min(1),
  redirectUri: z.string().min(1),
});

app.post("/api/oauth/:provider/exchange", requireAuth, async (req: AuthenticatedRequest, res) => {
  const providerId = String(req.params.provider);
  const spec = getProviderSpec(providerId);
  if (!spec) {
    res.status(400).json({ error: "Unsupported cloud provider" });
    return;
  }
  const parsed = exchangeSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "Missing code, codeVerifier, or redirectUri" });
    return;
  }

  const clientId = process.env[spec.clientIdEnv] || "";
  const clientSecret = process.env[spec.clientSecretEnv] || "";
  if (!clientId || !clientSecret) {
    res.status(503).json({ error: `OAuth credentials missing on backend for ${providerId}` });
    return;
  }

  const form = new URLSearchParams({
    grant_type: "authorization_code",
    code: parsed.data.code,
    client_id: clientId,
    client_secret: clientSecret,
    redirect_uri: parsed.data.redirectUri,
  });
  if (spec.supportsPkce) {
    form.set("code_verifier", parsed.data.codeVerifier);
  }

  const tokenResp = await fetch(spec.tokenEndpoint, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: form.toString(),
  });
  const tokenData = (await tokenResp.json()) as {
    access_token?: string;
    refresh_token?: string;
    expires_in?: number;
    error_description?: string;
  };
  if (!tokenResp.ok || !tokenData.access_token) {
    res.status(502).json({ error: tokenData.error_description || "Provider token exchange failed" });
    return;
  }

  // Resolve the actual cloud provider account email & live quota so multiple accounts per provider never collide
  let resolvedEmail = req.userEmail || "Connected Account";
  let quotaTotal = -1;
  let quotaUsed = -1;

  if (providerId === "google_drive") {
    try {
      const aboutResp = await fetch(
        "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress),storageQuota",
        {
          headers: { Authorization: `Bearer ${tokenData.access_token}` },
        }
      );
      if (aboutResp.ok) {
        const aboutJson = (await aboutResp.json()) as {
          user?: { emailAddress?: string };
          storageQuota?: { limit?: string; usage?: string };
        };
        if (aboutJson.user?.emailAddress) {
          resolvedEmail = aboutJson.user.emailAddress.trim().toLowerCase();
        }
        const parsedLimit = Number(aboutJson.storageQuota?.limit);
        const parsedUsage = Number(aboutJson.storageQuota?.usage);
        if (Number.isFinite(parsedLimit) && parsedLimit > 0) quotaTotal = parsedLimit;
        if (Number.isFinite(parsedUsage) && parsedUsage >= 0) quotaUsed = parsedUsage;
      }
    } catch {
      // Fallback to MyDrive email if Google Drive profile fetch transiently fails; Android will refresh quota immediately
    }
  }

  const expiresAtEpochMs = tokenData.expires_in
    ? Date.now() + tokenData.expires_in * 1000
    : 0;
  const encrypted = encryptTokens(
    JSON.stringify({
      accessToken: tokenData.access_token,
      refreshToken: tokenData.refresh_token ?? null,
      expiresAtEpochMs,
    })
  );

  // Deduplicate only when reconnecting the exact same (user, provider, email); distinct accounts always insert a new row
  const existing = await pool.query(
    `SELECT id FROM connected_accounts
     WHERE user_id = $1 AND provider_type = $2 AND LOWER(account_email) = LOWER($3)
     LIMIT 1`,
    [req.userId, providerId, resolvedEmail]
  );

  let accountId: string;
  if (existing.rowCount && existing.rowCount > 0) {
    accountId = existing.rows[0].id;
    await pool.query(
      `UPDATE connected_accounts
       SET encrypted_tokens = $1,
           quota_total = CASE WHEN $2 > 0 THEN $2 ELSE quota_total END,
           quota_used = CASE WHEN $3 >= 0 THEN $3 ELSE quota_used END,
           status = 'CONNECTED',
           updated_at = NOW()
       WHERE id = $4 AND user_id = $5`,
      [encrypted, quotaTotal, quotaUsed, accountId, req.userId]
    );
  } else {
    const inserted = await pool.query(
      `INSERT INTO connected_accounts (
         user_id, provider_type, account_email, encrypted_tokens, quota_total, quota_used, priority_order, status
       )
       VALUES (
         $1, $2, $3, $4, $5, $6,
         COALESCE((SELECT MAX(priority_order) + 1 FROM connected_accounts WHERE user_id = $1), 0),
         'CONNECTED'
       )
       RETURNING id`,
      [req.userId, providerId, resolvedEmail, encrypted, quotaTotal, quotaUsed]
    );
    accountId = inserted.rows[0].id;
  }

  res.json({
    accountId,
    providerType: providerId,
    accountEmail: resolvedEmail,
    accessToken: tokenData.access_token,
    refreshToken: tokenData.refresh_token ?? null,
    expiresAtEpochMs,
  });
});

app.post("/api/oauth/:provider/refresh", requireAuth, async (req: AuthenticatedRequest, res) => {
  const providerId = String(req.params.provider);
  const accountId = String(req.body?.accountId || "");
  const spec = getProviderSpec(providerId);
  if (!spec || !accountId) {
    res.status(400).json({ error: "Invalid provider or accountId" });
    return;
  }

  // Resource ownership check
  const row = await pool.query(
    `SELECT encrypted_tokens FROM connected_accounts WHERE id = $1 AND user_id = $2 LIMIT 1`,
    [accountId, req.userId]
  );
  if (row.rowCount === 0) {
    res.status(404).json({ error: "Connected account not found" });
    return;
  }

  const stored = JSON.parse(decryptTokens(row.rows[0].encrypted_tokens)) as {
    refreshToken?: string;
  };
  if (!stored.refreshToken) {
    res.status(401).json({ error: "No refresh token available — reconnect required" });
    return;
  }

  const form = new URLSearchParams({
    grant_type: "refresh_token",
    refresh_token: stored.refreshToken,
    client_id: process.env[spec.clientIdEnv] || "",
    client_secret: process.env[spec.clientSecretEnv] || "",
  });

  const resp = await fetch(spec.tokenEndpoint, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: form.toString(),
  });
  const data = (await resp.json()) as {
    access_token?: string;
    refresh_token?: string;
    expires_in?: number;
  };
  if (!resp.ok || !data.access_token) {
    await pool.query(
      `UPDATE connected_accounts SET status = 'EXPIRED_RECONNECT', updated_at = NOW() WHERE id = $1 AND user_id = $2`,
      [accountId, req.userId]
    );
    res.status(401).json({ error: "Provider refresh rejected — reconnect account" });
    return;
  }

  const expiresAtEpochMs = data.expires_in ? Date.now() + data.expires_in * 1000 : 0;
  const nextRefresh = data.refresh_token || stored.refreshToken;
  const reEncrypted = encryptTokens(
    JSON.stringify({ accessToken: data.access_token, refreshToken: nextRefresh, expiresAtEpochMs })
  );
  await pool.query(
    `UPDATE connected_accounts SET encrypted_tokens = $1, status = 'CONNECTED', updated_at = NOW()
     WHERE id = $2 AND user_id = $3`,
    [reEncrypted, accountId, req.userId]
  );

  res.json({
    accountId,
    providerType: providerId,
    accessToken: data.access_token,
    refreshToken: nextRefresh,
    expiresAtEpochMs,
  });
});

// ==================== METADATA SYNC (POSTGRESQL) ====================

const fileSyncSchema = z.object({
  id: z.string().uuid(),
  accountId: z.string().min(1),
  providerType: z.string().min(1),
  remoteFileId: z.string().min(1),
  fileName: z.string().min(1),
  mimeType: z.string().min(1),
  sizeBytes: z.number().nonnegative(),
  sha256Hash: z.string().length(64),
  category: z.string().min(1),
  folderPath: z.string().min(1),
  isFavorite: z.boolean(),
  isTrashed: z.boolean(),
  uploadedAt: z.number(),
});

app.post("/api/files", requireAuth, async (req: AuthenticatedRequest, res) => {
  const parsed = fileSyncSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "Invalid file metadata payload" });
    return;
  }
  const f = parsed.data;
  await pool.query(
    `INSERT INTO cloud_files
      (id, user_id, account_id, provider_type, remote_file_id, file_name, mime_type, size_bytes, sha256_hash, category, folder_path, is_favorite, is_trashed, uploaded_at)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13, to_timestamp($14 / 1000.0))
     ON CONFLICT (id) DO UPDATE SET
      file_name = EXCLUDED.file_name,
      folder_path = EXCLUDED.folder_path,
      is_favorite = EXCLUDED.is_favorite,
      is_trashed = EXCLUDED.is_trashed`,
    [
      f.id,
      req.userId,
      f.accountId,
      f.providerType,
      f.remoteFileId,
      f.fileName,
      f.mimeType,
      f.sizeBytes,
      f.sha256Hash,
      f.category,
      f.folderPath,
      f.isFavorite,
      f.isTrashed,
      f.uploadedAt,
    ]
  );
  res.status(201).json({ synced: true, id: f.id });
});

app.get("/api/files", requireAuth, async (req: AuthenticatedRequest, res) => {
  const rows = await pool.query(
    `SELECT * FROM cloud_files WHERE user_id = $1 ORDER BY uploaded_at DESC LIMIT 500`,
    [req.userId]
  );
  res.json({ files: rows.rows });
});

const port = Number(process.env.PORT || 4000);
app.listen(port, () => {
  console.log(`MyDrive OAuth Broker & Metadata API listening on port ${port}`);
});
