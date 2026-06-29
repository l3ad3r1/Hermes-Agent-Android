package com.hermes.agent.data.agent

/**
 * Shared instruction appended to a system prompt whenever tools are offered.
 *
 * Models differ wildly in how they emit tool calls when not using OpenAI's
 * structured `tool_calls` field: Nous/Nemotron use `<TOOLCALL>[…]</TOOLCALL>`,
 * while Gemma 3 (function calling is pure prompt-engineering, no tool tokens)
 * defaults to ```tool_code``` Python-call syntax that the app can't parse.
 *
 * Rather than parse every dialect, we pin a single text format —
 * `<tool_call>{json}</tool_call>` — which instruction-tuned models reliably
 * follow and which [com.hermes.agent.data.llm.CloudLlmProvider]'s fallback
 * parser already recovers. Structured `tool_calls` (when the provider supports
 * it) still takes precedence.
 */
object ToolCallPrompt {

    val INSTRUCTION: String =
        "\n\n# Tool calling\n" +
            "You have access to the tools declared for this request. When you want to use one, " +
            "reply with ONLY one or more tags in EXACTLY this format:\n" +
            "<tool_call>{\"name\": \"TOOL_NAME\", \"arguments\": {\"ARG\": \"VALUE\"}}</tool_call>\n" +
            "Rules:\n" +
            "- Use the exact tool name and a valid JSON object for arguments.\n" +
            "- Emit one <tool_call> tag per call; you may emit several in a row.\n" +
            "- Do NOT use Python, do NOT use ```tool_code``` or any markdown code fences for tool " +
            "calls — only the <tool_call> tag.\n" +
            "- After the tool result comes back, either call another tool the same way or write " +
            "your final answer to the user in plain text."
}
