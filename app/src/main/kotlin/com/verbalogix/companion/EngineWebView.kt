/*
 * Copyright 2026 Eyal Nof (Verbalogix)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.verbalogix.companion

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

/**
 * Compose wrapper around Android's WebView, pointed at the engine SPA.
 *
 * Why here rather than a separate Gradle module: WebView ships with
 * Android (zero APK size cost) and we only need it in one place. Keeping
 * the integration a single file keeps the Compose UI navigable.
 *
 * Surfaced controls:
 *   - reload / go home / exit-back behaviour
 *   - a lightweight "engine offline" fallback when 127.0.0.1:8000 is down
 *
 * NOT surfaced (deliberately, for v1):
 *   - cookie jar / cache sizing — defaults are fine for loopback
 *   - file upload — the SPA doesn't take uploads in WebView paths
 *   - custom user-agent — the engine doesn't care who's talking
 */
@Composable
fun EngineWebView(
    engineUrl: String = "http://127.0.0.1:8000",
    modifier: Modifier = Modifier,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var hasError by remember { mutableStateOf(false) }
    var lastErrorCode by remember { mutableStateOf<Int?>(null) }

    Box(modifier = modifier.fillMaxSize()) {

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // Alpine.js + the engine SPA live entirely in JS + localStorage.
                    // Turn on the features they need; leave everything else at
                    // defaults (which are restrictive by design).
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true        // Alpine stores auth token here
                        allowContentAccess = false    // tighten — we don't need content:// URIs
                        allowFileAccess = false       // tighten — not a file browser
                        mediaPlaybackRequiresUserGesture = false  // voice loop plays TTS
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        // Force fresh fetches from the local engine. The
                        // engine SPA is served from 127.0.0.1 — bandwidth
                        // is free, latency is sub-millisecond — but cached
                        // index.html across companion-app upgrades has
                        // bitten us once already. LOAD_NO_CACHE flushes
                        // every load so engine-side HTML/JS changes
                        // propagate the moment the user reloads the tab.
                        cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    }

                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )

                    // Keep navigation inside the WebView. Without this, every
                    // link opens an external browser because the default
                    // policy treats the WebView as a "preview."
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            // Only flag errors for the main document — loopback
                            // often has transient favicon or asset 404s that
                            // don't mean the page itself is broken.
                            if (request?.isForMainFrame == true) {
                                hasError = true
                                lastErrorCode = error?.errorCode
                            }
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            hasError = false
                            lastErrorCode = null
                        }
                    }

                    setBackgroundColor(android.graphics.Color.BLACK)
                    loadUrl(engineUrl)
                    webViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (hasError) {
            // Overlay — keeps the WebView mounted so reload works in place.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF04040A).copy(alpha = 0.96f))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(64.dp))
                Text(
                    text = "Engine not reachable at $engineUrl",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFFF4444),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Is the engine running in Termux?  Try `bash start.sh`." +
                            (lastErrorCode?.let { "  (code $it)" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { webViewRef?.reload() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reload")
                }
            }
        }
    }
}
