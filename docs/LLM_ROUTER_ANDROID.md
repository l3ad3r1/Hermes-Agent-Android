# LLM routing on Android

Hermes uses an Android-native adaptation of the quality-gated Hybrid LLM
strategy from [U-Lab's LLMRouter](https://github.com/ulab-uiuc/LLMRouter).
The upstream package is Python/PyTorch-based and is not embedded in the APK.

## Runtime flow

1. `HybridLlmRouter` checks which configured providers are actually available.
2. Each available cloud model becomes a normalized candidate with quality,
   cost, latency, and tool-reliability data. Local inference is not included in
   this competition.
3. `LlmRoutingPolicy` ranks the cloud candidates for the current request.
4. `QualityAwareLlmRoutingPolicy` chooses the least expensive cloud candidate
   that clears the request's quality and tool-reliability gates.
5. Hermes creates a cloud failover chain in ranked order. Authentication,
   quota, server, and network failures advance to the next cloud provider.
6. The on-device model is appended only as the final fallback after every
   configured cloud provider is unavailable or fails.

Agentic Productivity, Research, Device Control, and delegated tasks require
reliable structured tool calls. This keeps small local models available for
offline fallback without selecting them ahead of a configured cloud model for
calendar or screen-control work.

## Extension point

`LlmRoutingPolicy` is independent of provider execution. A trained router
exported to ONNX or TensorFlow Lite can replace the current deterministic
policy without changing the orchestrator, local inference engine, or cloud API
client.

## Upstream relationship

The design follows LLMRouter's separation between routing and model execution.
Hermes applies its quality gate within the configured cloud pool, while the
product's explicit cloud-first policy keeps local inference as an offline
safety net. No Python or PyTorch source is bundled in Hermes.
