# AppAgent device test

`AppAgentSmokeTest` verifies screen analysis, tapping, and text entry on a real
Android device through an injected `UiAutomation` backend. It does not require
the Hermes accessibility service to be enabled, so instrumentation startup
cannot race the service's process rebind.

## Run the automated contract test

Connect and unlock the device, then run:

```powershell
.\gradlew.bat connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.hermes.agent.AppAgentSmokeTest
```

The test opens a debug-only fixture screen and verifies that:

- `ScreenAnalyzer` exposes its tap and text targets;
- `AppAnalyzeScreenTool` returns the visible controls;
- `AppTapTool` injects a real tap that changes the fixture state;
- `AppTypeTool` enters text without echoing that text into tool output.

## Check the production accessibility binding separately

The installed debug application ID is `com.hermes.agent.debug`, not
`com.hermes.agent`. On Android 14, if Restricted Settings blocks manual
enablement for the sideloaded debug APK, the development-only app-op command is:

```powershell
adb shell appops set com.hermes.agent.debug ACCESS_RESTRICTED_SETTINGS allow
```

Enable Hermes under **Settings > Accessibility > Installed apps**, then verify
that Android lists this component as enabled:

```text
com.hermes.agent.debug/com.hermes.agent.service.AppAgentAccessibilityService
```

This lifecycle check intentionally remains separate from instrumentation. A
remote `:agent` process would still be stopped with the package and would also
require Binder IPC instead of the current in-process service adapter.
