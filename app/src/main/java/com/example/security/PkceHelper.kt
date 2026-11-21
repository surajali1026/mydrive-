package com.example.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

data class PkceParams(
    val codeVerifier: String,
    val codeChallenge: String,
    val state: String
)

/**
 * Generates RFC 7636 S256 PKCE parameters and anti-CSRF OAuth state tokens.
 */
object PkceHelper {
    const val REDIRECT_URI = "mydrive://oauth/callback"

    fun generate(): PkceParams {
        val random = SecureRandom()
        val verifierBytes = ByteArray(64).also { random.nextBytes(it) }
        val verifier = Base64.encodeToString(
            verifierBytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val challengeDigest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(
            challengeDigest,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val stateBytes = ByteArray(24).also { random.nextBytes(it) }
        val state = Base64.encodeToString(
            stateBytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return PkceParams(
            codeVerifier = verifier,
            codeChallenge = challenge,
            state = state
        )
    }
}
