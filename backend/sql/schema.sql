-- MyDrive PostgreSQL Schema
-- Matches the Android Room local schema for seamless sync & deduplication.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL DEFAULT '',
    password_hash TEXT,
    auth_provider TEXT NOT NULL CHECK (auth_provider IN ('email', 'google')),
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS connected_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_type TEXT NOT NULL CHECK (
        provider_type IN ('google_drive', 'onedrive', 'dropbox', 'box', 'pcloud', 'koofr')
    ),
    account_email TEXT NOT NULL,
    encrypted_tokens TEXT NOT NULL, -- AES-256-GCM encrypted JSON (iv:tag:ciphertext)
    quota_total BIGINT NOT NULL DEFAULT -1,
    quota_used BIGINT NOT NULL DEFAULT -1,
    priority_order INT NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'CONNECTED' CHECK (
        status IN ('CONNECTED', 'EXPIRED_RECONNECT', 'INTEGRATION_PENDING', 'DISCONNECTED')
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_connected_accounts_user_priority
    ON connected_accounts(user_id, priority_order ASC);

CREATE TABLE IF NOT EXISTS cloud_files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES connected_accounts(id) ON DELETE CASCADE,
    provider_type TEXT NOT NULL,
    remote_file_id TEXT NOT NULL,
    file_name TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256_hash CHAR(64) NOT NULL,
    category TEXT NOT NULL,
    folder_path TEXT NOT NULL DEFAULT '/MyDrive',
    is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
    is_trashed BOOLEAN NOT NULL DEFAULT FALSE,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cloud_files_user_uploaded
    ON cloud_files(user_id, uploaded_at DESC);

CREATE INDEX IF NOT EXISTS idx_cloud_files_dedup_sha256
    ON cloud_files(user_id, sha256_hash) WHERE is_trashed = FALSE;
