package com.hermes.agent.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * Text for a settings field that saves on every keystroke.
 *
 * The saved value comes back through the settings flow a moment later, often for an older
 * keystroke, and some setters trim or screen it. Fields keyed on that value were rebuilt from it
 * mid-typing: fast typing lost characters ("Be brief." came out "Bbee"), backspacing stalled, and
 * a trailing space could not be typed where the setter trims. The field now keeps its own text
 * while it has focus, and shows the saved value (as stored) whenever it is not being edited.
 */
class SettingTextState internal constructor(initial: String) {
    var text by mutableStateOf(initial)
    internal var focused by mutableStateOf(false)
}

@Composable
fun rememberSettingText(saved: String): SettingTextState {
    val state = remember { SettingTextState(saved) }
    LaunchedEffect(saved, state.focused) {
        if (!state.focused) state.text = saved
    }
    return state
}

/** Lets [state] know when its field is being edited. */
fun Modifier.settingFocus(state: SettingTextState): Modifier =
    onFocusChanged { state.focused = it.isFocused }
