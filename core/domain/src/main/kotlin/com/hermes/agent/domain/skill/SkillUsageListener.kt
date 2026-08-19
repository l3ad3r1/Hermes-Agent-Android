package com.hermes.agent.domain.skill

interface SkillUsageListener {
    suspend fun onSkillUsed(skillName: String)
}
