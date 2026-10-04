package com.hermes.agent.data.hotfix

/**
 * Crash-loop protection for a loaded patch, on top of Tinker's own safe mode (which only counts
 * starts that die before the application delegate attaches). A crash within [quickCrashWindowMs]
 * of process start while a patch is loaded counts; [maxQuickCrashes] in a row remove the patch.
 * Surviving the window resets the count.
 */
class CrashLoopPolicy(
    val quickCrashWindowMs: Long = 10_000,
    val maxQuickCrashes: Int = 3,
) {
    data class Decision(val newCount: Int, val rollBack: Boolean)

    fun onCrash(millisSinceStart: Long, previousCount: Int, patchLoaded: Boolean): Decision {
        if (!patchLoaded) return Decision(0, false)
        if (millisSinceStart > quickCrashWindowMs) return Decision(previousCount, false)
        val count = previousCount + 1
        return if (count >= maxQuickCrashes) Decision(0, true) else Decision(count, false)
    }
}
