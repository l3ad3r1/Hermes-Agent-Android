package com.hermes.agent.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.hermes.agent.data.evolution.EvolutionSettings
import com.hermes.agent.data.plugin.evolution.EvolutionDispatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Runs the builder/reviewer loop for one approved proposal. A worker rather than
 * a ViewModel coroutine because a round can take many minutes on a local LLM and
 * must survive the user leaving the screen.
 *
 * Ordinary WorkManager work is stopped after about ten minutes, far less than
 * a few bot rounds take, so the run asks to be foreground (data-sync) work with
 * an ongoing notification, as local-model downloads do. If that is refused, or
 * the process dies mid-run, the proposal is left BUILDING/IN_REVIEW and the next
 * dispatch resumes it; the dispatcher counts the interrupted round, so repeated
 * interruptions still end after its round limit.
 *
 * Always [Result.success]: the dispatcher records every outcome on the proposal.
 * Cancelling the unique work (the user rejecting the proposal) stops the bot run.
 */
@HiltWorker
class EvolutionDispatchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dispatcher: EvolutionDispatcher,
    private val settings: EvolutionSettings,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_PROPOSAL_ID) ?: return Result.success()
        promoteToForeground()
        val s = settings.current()
        try {
            val outcome = dispatcher.dispatch(id, s.builderProfile, s.reviewerProfile)
            Timber.tag(TAG).i("proposal %s: %s", id, outcome::class.simpleName)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "dispatch failed for %s", id)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    /** Best effort, like the model download: a refused foreground start must not abort the run. */
    private suspend fun promoteToForeground() {
        try {
            setForeground(foregroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "foreground refused; the bot run continues as background work")
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Feature evolution builds", NotificationManager.IMPORTANCE_LOW),
        )
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Your bots are building an improvement")
            .setContentText("Builder and reviewer are working. Review it under Settings → Feature evolution.")
            .setOngoing(true)
            .apply {
                launch?.let {
                    setContentIntent(
                        PendingIntent.getActivity(
                            applicationContext, 0, it,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_PROPOSAL_ID = "proposal_id"
        private const val TAG = "EvolutionDispatch"
        private const val CHANNEL_ID = "hermes_evolution_builds"
        private const val NOTIFICATION_ID = 9006
        fun uniqueName(proposalId: String) = "hermes.feature_evolution.dispatch.$proposalId"
    }
}
