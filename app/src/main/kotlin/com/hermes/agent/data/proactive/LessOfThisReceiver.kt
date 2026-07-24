package com.hermes.agent.data.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.hermes.agent.domain.proactive.ProactiveSource
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * One-tap "Less of this" from a proactive notification: durably halves the
 * source's daily allowance (2 → 1 → muted). Re-enable from the proactive
 * settings when they land; until then the ledger records every suppression.
 */
@AndroidEntryPoint
class LessOfThisReceiver : BroadcastReceiver() {

    @Inject lateinit var store: BudgetStateStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val source = intent.getStringExtra(EXTRA_SOURCE)
            ?.let { name -> ProactiveSource.entries.firstOrNull { it.name == name } }
            ?: return
        store.recordLessOfThis(source)
        Timber.tag("Proactive").i("less-of-this recorded for %s", source.name)
        Toast.makeText(
            context,
            "Got it — fewer ${source.displayName.lowercase()} pings.",
            Toast.LENGTH_SHORT,
        ).show()
    }

    companion object {
        const val ACTION = "com.jeeves.app.action.LESS_OF_THIS"
        const val EXTRA_SOURCE = "source"
    }
}
