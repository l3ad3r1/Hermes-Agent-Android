package com.hermes.agent.domain.tool

/** Narrow settings contract used by execution authorization policy. */
interface ToolAuthorizationSettings {
    suspend fun autoApprovePhoneActions(): Boolean
    suspend fun trustedBackgroundPhoneActions(): Boolean
}
