# Hermes Agent Android v0.9.3

## Highlights

- Android AppAgent bridge with screen observation, taps, swipes, and text entry
  through the accessibility service.
- Deterministic local phone-command routing for calendar, alarms,
  communication, media, device controls, and navigation.
- Cloud-first quality-aware LLM routing with ranked provider failover and
  local-model fallback only after cloud providers are unavailable.
- User-controlled auto-approval for safe phone actions and authenticated
  approval for background and high-risk operations.
- Shell and Termux commands require biometric or device-PIN approval for every
  execution.
- Local backup/restore support for settings and provider profiles.

## Verification

- `:app:testDebugUnitTest` passed.
- `:app:assembleRelease` passed.
- APK SHA-256:
  `C8E6E4F11020F13AD874C07BFBDECE335762262FC2BB1E948F727A13C406FB5A`
- Release APK is unsigned unless a local signing configuration is provided.

## Credits

Project direction and Android integration: **l3ad3r1**. Routing concepts were
inspired by [U-Lab's LLMRouter](https://github.com/ulab-uiuc/LLMRouter). The
agent design is conceptually aligned with
[NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent).
Implementation and test assistance: **OpenAI Codex**.
