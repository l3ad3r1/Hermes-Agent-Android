package com.hermes.agent.ui.voice

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TalkViewModel @Inject constructor(
    val controller: TalkSessionController,
) : ViewModel()
