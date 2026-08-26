package com.hermes.agent.ui.oauth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.hermes.agent.data.oauth.OAuthCallbackReceiver
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class OAuthCallbackActivity : ComponentActivity() {

    @Inject
    lateinit var callbackReceiver: OAuthCallbackReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        callbackReceiver.handleCallback(data)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val data = intent.data
        callbackReceiver.handleCallback(data)
        finish()
    }
}
