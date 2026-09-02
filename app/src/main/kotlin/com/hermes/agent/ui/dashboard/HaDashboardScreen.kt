package com.hermes.agent.ui.dashboard

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.ui.settings.SettingsViewModel
import org.json.JSONObject

/**
 * Home Assistant Lovelace dashboard embedded in a WebView.
 *
 * The frontend keeps its auth in `localStorage.hassTokens`. We pre-seed that
 * with the long-lived access token from settings so the dashboard opens without
 * a login round-trip. If the token is missing or rejected, HA's own login page
 * shows in the WebView and the user can sign in there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HaDashboardScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val baseUrl = settings.homeAssistantUrl.trim().trimEnd('/')
    val token = settings.homeAssistantToken.trim()
    val target = if (settings.homeAssistantDashboardPath.isBlank()) {
        "$baseUrl/"
    } else {
        "$baseUrl/${settings.homeAssistantDashboardPath.trim('/')}"
    }

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView: WebView? by remember { mutableStateOf(null) }

    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Home Assistant") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (baseUrl.isBlank()) {
                Text(
                    "Set the Home Assistant URL in Settings → Connections first.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Box
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webView = this
                        with(this.settings) {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mediaPlaybackRequiresUserGesture = false
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                if (token.isNotBlank() && url != null && url.startsWith(baseUrl)) {
                                    view.evaluateJavascript(seedTokenJs(baseUrl, token), null)
                                }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                err: WebResourceError,
                            ) {
                                if (request.isForMainFrame) {
                                    error = "Couldn't reach $baseUrl — check the URL and that the phone is on the same network."
                                    loading = false
                                }
                            }
                        }
                        loadUrl(target)
                    }
                },
            )

            if (loading && error == null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                )
            }
            error?.let {
                Text(
                    it,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Seeds `localStorage.hassTokens` with the long-lived token, then reloads once
 * (the equality guard stops it looping). Long-lived tokens last ~10 years, so
 * the expiry is set well into the future.
 */
private fun seedTokenJs(hassUrl: String, token: String): String {
    val tokens = JSONObject().apply {
        put("access_token", token)
        put("token_type", "Bearer")
        put("expires_in", 315_360_000)
        put("hassUrl", hassUrl)
        put("clientId", JSONObject.NULL)
        put("expires", System.currentTimeMillis() + 315_360_000_000L)
    }.toString()
    return """
        (function () {
          try {
            var want = $tokens;
            var have = window.localStorage.getItem('hassTokens');
            if (have !== JSON.stringify(want)) {
              window.localStorage.setItem('hassTokens', JSON.stringify(want));
              window.location.reload();
            }
          } catch (e) {}
        })();
    """.trimIndent()
}
