package com.hermes.agent.data.diagnostics

/**
 * Strips personal data from a problem report before it leaves the phone.
 *
 * Reports go to a private GitHub repo, but a stack trace or log line can quote whatever the
 * app was handling: an address, a phone number, a key. Anything shaped like one of those is
 * replaced with a tag; the user still sees the redacted text before it is sent.
 */
object ReportRedactor {

    private val rules = listOf(
        Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""") to "[email]",
        Regex("""(?i)\b(bearer|token|key|secret|password|pat)\b(["'\s:=]+)[^\s"',;]+""") to "$1$2[secret]",
        Regex("""\b(sk|pk|ghp|gho|github_pat|xox[abp]|AIza)[\w-]{10,}""") to "[secret]",
        Regex("""\b[A-Za-z0-9+/_-]{32,}={0,2}""") to "[secret]",
        Regex("""\b[\w-]+\.[\w-]+\.ts\.net\b""") to "[tailnet-host]",
        Regex("""\b100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}\b""") to "[tailnet-ip]",
        Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""") to "[ip]",
        Regex("""(https?://[^\s?#]+)\?[^\s#]*""") to "$1?[query]",
        // Phone numbers, without eating dates and times ("2026-09-28 22:58:17").
        Regex("""\+\d[\d ()-]{8,}\d""") to "[number]",
        Regex("""\b\d{5}[ -]?\d{5}\b""") to "[number]",
    )

    fun redact(text: String): String = rules.fold(text) { acc, (pattern, replacement) ->
        pattern.replace(acc, replacement)
    }
}
