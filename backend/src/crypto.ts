import crypto from "crypto";

const ALGORITHM = "aes-256-gcm";
const IV_LENGTH = 12;

function getEncryptionKey(): Buffer {
  const hexKey = process.env.TOKEN_ENCRYPTION_KEY;
  if (hexKey && /^[0-9a-fA-F]{64}$/.test(hexKey)) {
    return Buffer.from(hexKey, "hex");
  }
  // Ephemeral fallback for local dev only; production MUST configure TOKEN_ENCRYPTION_KEY
  if (!fallbackKey) {
    fallbackKey = crypto.randomBytes(32);
  }
  return fallbackKey;
}

let fallbackKey: Buffer | null = null;

/**
 * Encrypts sensitive OAuth access/refresh tokens using AES-256-GCM before storing in PostgreSQL.
 */
export function encryptTokens(plaintext: string): string {
  const iv = crypto.randomBytes(IV_LENGTH);
  const cipher = crypto.createCipheriv(ALGORITHM, getEncryptionKey(), iv);
  const encrypted = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  const authTag = cipher.getAuthTag();
  return `${iv.toString("base64")}:${authTag.toString("base64")}:${encrypted.toString("base64")}`;
}

export function decryptTokens(encoded: string): string {
  const [ivB64, tagB64, dataB64] = encoded.split(":");
  if (!ivB64 || !tagB64 || !dataB64) {
    throw new Error("Invalid encrypted token payload");
  }
  const decipher = crypto.createDecipheriv(
    ALGORITHM,
    getEncryptionKey(),
    Buffer.from(ivB64, "base64")
  );
  decipher.setAuthTag(Buffer.from(tagB64, "base64"));
  const decrypted = Buffer.concat([
    decipher.update(Buffer.from(dataB64, "base64")),
    decipher.final(),
  ]);
  return decrypted.toString("utf8");
}

/**
 * Salted scrypt password hashing for independent MyDrive user authentication.
 */
export function hashPassword(password: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const salt = crypto.randomBytes(16).toString("hex");
    crypto.scrypt(password, salt, 64, (err, derivedKey) => {
      if (err) reject(err);
      else resolve(`${salt}:${derivedKey.toString("hex")}`);
    });
  });
}

export function verifyPassword(password: string, storedHash: string): Promise<boolean> {
  return new Promise((resolve, reject) => {
    const [salt, keyHex] = storedHash.split(":");
    if (!salt || !keyHex) return resolve(false);
    crypto.scrypt(password, salt, 64, (err, derivedKey) => {
      if (err) reject(err);
      else {
        const expected = Buffer.from(keyHex, "hex");
        resolve(
          expected.length === derivedKey.length &&
            crypto.timingSafeEqual(expected, derivedKey)
        );
      }
    });
  });
}
