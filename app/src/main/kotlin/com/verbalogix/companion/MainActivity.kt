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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verbalogix.companion.accessibility.EngineAccessibilityService
import com.verbalogix.companion.apm.AdvancedProtectionDetector
import com.verbalogix.companion.http.ApmState
import com.verbalogix.companion.http.EngineHttpService
import com.verbalogix.companion.http.TokenStore
import kotlinx.coroutines.delay

/**
 * Minimal Compose UI.
 *
 * Scope (v1):
 *  - Show AccessibilityService state + deep-link to settings
 *  - Show server URL + bearer token with copy buttons
 *  - Show APM state banner if active (and suppress settings deep-link — it
 *    would just fail)
 *  - Regenerate token button
 *
 * Out of scope (v1):
 *  - Log viewer
 *  - Per-endpoint usage counters
 *  - Settings screen for port override
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CompanionApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionApp() {
    val ctx = LocalContext.current
    val tokenStore = remember { TokenStore(ctx) }
    val apm = remember { AdvancedProtectionDetector(ctx) }

    var token by remember { mutableStateOf(tokenStore.getOrCreate()) }
    var serviceEnabled by remember { mutableStateOf(false) }
    var apmState by remember { mutableStateOf(apm.current()) }

    LaunchedEffect(Unit) {
        // Cheap polling — the proper fix lives in AdvancedProtectionDetector
        // once compileSdk is bumped to 37 and we can register a callback.
        while (true) {
            serviceEnabled = EngineAccessibilityService.current() != null
            apmState = apm.current()
            delay(1_000)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00FF88))) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = { TopAppBar(title = { Text("Verbalogix Companion") }) },
            ) { inner ->
                Column(
                    modifier = Modifier
                        .padding(inner)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ApmBanner(state = apmState)
                    Spacer(Modifier.height(12.dp))

                    StatusCard(
                        serviceEnabled = serviceEnabled,
                        serverPort = EngineHttpService.DEFAULT_PORT,
                        apmBlocked = apmState == ApmState.ON,
                        onOpenSettings = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK,
                                ),
                            )
                        },
                    )

                    Spacer(Modifier.height(12.dp))

                    TokenCard(
                        token = token,
                        onCopy = { copyToClipboard(ctx, "bearer-token", token) },
                        onRegenerate = {
                            token = tokenStore.regenerate()
                            Toast.makeText(ctx, "New token generated — update the engine config", Toast.LENGTH_SHORT).show()
                        },
                    )

                    Spacer(Modifier.height(12.dp))

                    UrlCard(
                        port = EngineHttpService.DEFAULT_PORT,
                        onCopy = { url -> copyToClipboard(ctx, "server-url", url) },
                    )

                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = "Both values go into the engine as env vars: " +
                                "COMPANION_URL and COMPANION_BEARER. See " +
                                "ANDROID-COMPANION-APP-BRIEF.md in the engine repo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ApmBanner(state: ApmState) {
    if (state != ApmState.ON) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Advanced Protection Mode is active. The companion " +
                    "service cannot run while APM is on. The engine will fall " +
                    "back to its ADB Screen Agent automatically.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StatusCard(
    serviceEnabled: Boolean,
    serverPort: Int,
    apmBlocked: Boolean,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (serviceEnabled) "Accessibility service: enabled"
                       else "Accessibility service: NOT enabled",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (serviceEnabled) "Local server: 127.0.0.1:$serverPort"
                       else "Local server: stopped (needs service)",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!serviceEnabled && !apmBlocked) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Accessibility Settings")
                }
            }
        }
    }
}

@Composable
private fun TokenCard(token: String, onCopy: () -> Unit, onRegenerate: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Bearer token",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = token,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                Text("Copy token")
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onRegenerate, modifier = Modifier.fillMaxWidth()) {
                Text("Regenerate token")
            }
        }
    }
}

@Composable
private fun UrlCard(port: Int, onCopy: (String) -> Unit) {
    val url = "http://127.0.0.1:$port"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "Server URL", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onCopy(url) }, modifier = Modifier.fillMaxWidth()) {
                Text("Copy URL")
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
