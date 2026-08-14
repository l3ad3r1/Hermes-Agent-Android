# Known bugs and limitations

This list describes the current Android port. It is intentionally separate
from the feature roadmap so regressions can be tracked without presenting
planned work as a defect.

## Current limitations

- Release builds are unsigned unless a local signing configuration is supplied.
- The local model remains a final fallback; it is not selected ahead of an
  available cloud provider for structured tool tasks.
- Cloud provider health is evaluated at request time. There is no persistent
  cross-session health score yet.
- Google Meet links and attendee invitations require the target calendar app's
  supported Android intent flow; Hermes does not impersonate an email attendee.
- Screen automation and app launching require the accessibility service and
  remain interactive even when trusted background mode is enabled.
- Shell and Termux commands always require biometric or device-PIN approval.
- The AppAgent instrumentation suite requires an unlocked, connected Android
  device and is not an emulator-only test.

## Verified fixes in this release

- Biometric approval now clears its pending prompt after timeout or denial.
- Trusted background approval is restricted to an explicit safe-tool allowlist.
- Shell and Termux requests cannot inherit background approval.
- Cloud routing failures fall through the configured provider chain before local
  inference is attempted.

## Reporting a new issue

Please include the app version, Android version/device, whether the action was
interactive or background, the selected provider/model, and a redacted log
excerpt. Never attach API keys, tokens, calendar contents, or personal data.
