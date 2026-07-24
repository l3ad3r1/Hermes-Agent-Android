package com.hermes.agent.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.hermes.agent.MainActivity

class JeevesVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        
        // When the assistant is invoked (e.g. long press power button), we want to launch MainActivity
        // with the START_VOICE_LISTEN action.
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.hermes.agent.action.START_VOICE_LISTEN"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startVoiceActivity(intent)
        finish() // Close the overlay session, let the app take over
    }
}
