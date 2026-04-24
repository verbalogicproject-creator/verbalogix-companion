/*
 * Copyright 2026 Eyal Nof (Verbalogix)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.verbalogix.companion.http

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom

/**
 * Persistent Bearer token store for the HTTP server's auth gate.
 *
 * One token per install. Generated on first read if absent. Regenerable
 * on demand from the UI (invalidates the previous engine configuration,
 * so the user has to copy the new one).
 *
 * This is not SecureSharedPreferences / EncryptedSharedPreferences —
 * the threat model assumes the device itself is trusted. A root attacker
 * can read app-private prefs regardless of encryption.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the current token, generating one on first access. */
    fun getOrCreate(): String {
        val existing = prefs.getString(KEY_TOKEN, null)
        if (existing != null) return existing
        val fresh = generate()
        prefs.edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    /** Replace the token. Invalidates the previous one immediately. */
    fun regenerate(): String {
        val fresh = generate()
        prefs.edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    /** True iff the provided token matches the stored one, in constant time. */
    fun validate(candidate: String): Boolean {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return false
        return constantTimeEquals(stored, candidate)
    }

    private fun generate(): String {
        // 32 bytes = 256 bits of entropy. Base64url-encoded to keep it
        // HTTP-header-safe (no `=` padding, no `+` or `/`).
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    companion object {
        private const val PREFS_NAME = "verbalogix_companion_auth"
        private const val KEY_TOKEN = "bearer_token"
    }
}
