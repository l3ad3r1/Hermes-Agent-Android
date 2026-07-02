package com.hermes.agent.data.security

import com.hermes.agent.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Output redaction — ported from hermes-agent's in-process security
 * heuristics (SECURITY.md "In-Process Heuristics": approval gate,
 * redaction, Skills Guard).
 *
 * Scrubs secrets from tool outputs before they are fed back into the LLM
 * conversation (and therefore before they can be echoed to the UI, stored
 * in history, or leaked to a connected messaging platform via `notify`).
 *
 * Two layers:
 *  1. **Known secrets** — the user's configured credentials (cloud/aux API
 *     keys, GitHub PAT) are replaced wherever they appear verbatim.
 *  2. **Pattern heuristics** — common credential shapes (OpenAI `sk-`,
 *     GitHub `ghp_`/`github_pat_`, Slack `xox*-`, Google `AIza`, NVIDIA
 *     `nvapi-`, AWS `AKIA`, bearer tokens) are masked even when they are
 *     not the user's own configured keys — e.g. a key accidentally read
 *     from a file or web page by a tool.
 */
@Singleton
class OutputRedactor @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {

    suspend fun redact(text: String): String {
        if (text.isBlank()) return text
        var out = text

        // Layer 1: literal configured secrets.
        val settings = runCatching { settingsRepository.current() }.getOrNull()
        if (settings != null) {
            listOf(
                settings.cloudApiKey to "cloud-api-key",
                settings.auxApiKey to "aux-api-key",
                settings.githubPat to "github-pat",
            ).forEach { (secret, label) ->
                if (secret.length >= MIN_SECRET_LENGTH && out.contains(secret)) {
                    out = out.replace(secret, "[redacted:$label]")
                }
            }
        }

        // Layer 2: credential-shaped patterns.
        for ((pattern, label) in PATTERNS) {
            out = pattern.replace(out) { "[redacted:$label]" }
        }
        return out
    }

    companion object {
        /** Secrets shorter than this are too likely to collide with normal
         *  text (e.g. a 4-char key would redact random substrings). */
        private const val MIN_SECRET_LENGTH = 8

        private val PATTERNS: List<Pair<Regex, String>> = listOf(
            Regex("""sk-[A-Za-z0-9_-]{16,}""") to "api-key",
            Regex("""ghp_[A-Za-z0-9]{20,}""") to "github-token",
            Regex("""github_pat_[A-Za-z0-9_]{20,}""") to "github-token",
            Regex("""xox[bapr]-[A-Za-z0-9-]{10,}""") to "slack-token",
            Regex("""AIza[A-Za-z0-9_-]{30,}""") to "google-api-key",
            Regex("""nvapi-[A-Za-z0-9_-]{20,}""") to "nvidia-api-key",
            Regex("""AKIA[A-Z0-9]{16}""") to "aws-access-key",
            Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]{20,}""") to "bearer-token",
        )
    }
}
