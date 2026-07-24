package com.hermes.agent.ui.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.agent.ui.theme.HermesTheme

class ShareTargetActivity : ComponentActivity() {

    private var sharedText by mutableStateOf("")

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        }

        setContent {
            HermesTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                
                ModalBottomSheet(
                    onDismissRequest = { finish() },
                    sheetState = sheetState
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(text = "Share to Jeeves", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                        Text(text = sharedText, maxLines = 3, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                        
                        Button(onClick = { handleAction("summarize") }, modifier = Modifier.fillMaxWidth()) {
                            Text("Summarize")
                        }
                        Button(onClick = { handleAction("save_note") }, modifier = Modifier.fillMaxWidth()) {
                            Text("Save as Note")
                        }
                        Button(onClick = { handleAction("remind_me") }, modifier = Modifier.fillMaxWidth()) {
                            Text("Remind Me")
                        }
                    }
                }
            }
        }
    }

    private fun handleAction(action: String) {
        // We can send this intent to MainActivity or create a background service call.
        val intent = Intent(this, com.hermes.agent.MainActivity::class.java).apply {
            this.action = "com.hermes.agent.action.SHARE_TO_JEEVES"
            putExtra("EXTRA_SHARE_ACTION", action)
            putExtra("EXTRA_SHARE_TEXT", sharedText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }
}
