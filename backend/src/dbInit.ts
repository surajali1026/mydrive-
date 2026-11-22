import { Pool } from "pg";

/**
 * Creates the MyDrive metadata schema if it does not exist.
 * OAuth access/refresh tokens are stored encrypted by the API.
 */
export async function initializeDatabase(pool: Pool): Promise<void> {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS users (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      email TEXT NOT NULL UNIQUE,
      display_name TEXT NOT NULL,
      password_hash TEXT,
      auth_provider TEXT NOT NULL DEFAULT 'email',
      is_verified BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS connected_accounts (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      provider_type TEXT NOT NULL,
      account_email TEXT NOT NULL,
      encrypted_tokens TEXT NOT NULL,
      quota_total BIGINT NOT NULL DEFAULT -1,
      quota_used BIGINT NOT NULL DEFAULT -1,
      priority_order INTEGER NOT NULL DEFAULT 0,
      status TEXT NOT NULL DEFAULT 'CONNECTED',
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE INDEX IF NOT EXISTS connected_accounts_user_provider_idx
      ON connected_accounts(user_id, provider_type);

    CREATE TABLE IF NOT EXISTS cloud_files (
      id UUID PRIMARY KEY,
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      account_id UUID NOT NULL REFERENCES connected_accounts(id) ON DELETE CASCADE,
      provider_type TEXT NOT NULL,
      remote_file_id TEXT NOT NULL,
      file_name TEXT NOT NULL,
      mime_type TEXT NOT NULL,
      size_bytes BIGINT NOT NULL DEFAULT 0,
      sha256_hash CHAR(64) NOT NULL,
      category TEXT NOT NULL,
      folder_path TEXT NOT NULL DEFAULT '/',
      is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
      is_trashed BOOLEAN NOT NULL DEFAULT FALSE,
      uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE INDEX IF NOT EXISTS cloud_files_user_uploaded_idx
      ON cloud_files(user_id, uploaded_at DESC);
    CREATE INDEX IF NOT EXISTS cloud_files_account_idx
      ON cloud_files(account_id);
  `);
}
