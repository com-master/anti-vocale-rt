package com.antivocale.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.antivocale.app.service.live.LiveSessionController
import com.antivocale.app.service.live.LiveTranscriptionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Live mode screen state. The session itself lives in the process-lifetime
 * [LiveSessionController] behind [LiveTranscriptionService], so it keeps
 * running with the screen off; this ViewModel only relays.
 */
@HiltViewModel
class LiveTranscriptionViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val controller: LiveSessionController,
) : ViewModel() {

    val state: StateFlow<LiveSessionController.LiveUiState> = controller.state

    fun hasMicPermission(): Boolean = controller.hasMicPermission()

    fun setRolesEnabled(enabled: Boolean) = controller.setRolesEnabled(enabled)

    fun setMicGain(gain: Float) = controller.setMicGain(gain)

    fun setSensitivity(sensitivity: Float) = controller.setSensitivity(sensitivity)

    fun setAutoLevel(enabled: Boolean) = controller.setAutoLevel(enabled)

    /** Starts through the foreground service (must be called while the screen is visible). */
    fun start() = LiveTranscriptionService.start(appContext)

    fun stop() = controller.stop()
}
