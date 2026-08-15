package com.moonlight.ds.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thor.core.common.coroutines.launchSafely
import com.thor.core.datastore.SettingsRepository
import com.thor.core.model.AudioSettings
import com.thor.core.model.ControlSettings
import com.thor.core.model.DisplaySettings
import com.thor.core.model.StreamSettings
import com.thor.core.model.ThorSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Moonlight DS's settings.
 *
 * Named `SettingsViewModel`, matching Loki's, because the pages under [page] are
 * a copy of Loki's stream pages and take one of these — keeping the name means
 * the copied files differ from the originals only in their package line, so a
 * change made there can be brought across by hand without reading around it.
 *
 * Far smaller than the launcher's, which carries the library, the scrapers, the
 * icon packs and the profiles as well. What is here is the settings this app can
 * actually act on: the stream, the two panels, and the controller.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<ThorSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = ThorSettings.DEFAULT,
    )

    /**
     * Writes are fire-and-forget, as they are in Loki.
     *
     * The screen re-renders from the store rather than from the call, so a write
     * that has not landed yet simply shows the previous value for a frame. That
     * is the correct behaviour for a settings row: what is drawn is what is
     * saved, never what was asked for.
     */
    fun updateStream(transform: (StreamSettings) -> StreamSettings) {
        viewModelScope.launchSafely(TAG) { repository.updateStream(transform) }
    }

    fun updateDisplay(transform: (DisplaySettings) -> DisplaySettings) {
        viewModelScope.launchSafely(TAG) { repository.updateDisplay(transform) }
    }

    fun updateControls(transform: (ControlSettings) -> ControlSettings) {
        viewModelScope.launchSafely(TAG) { repository.updateControls(transform) }
    }

    fun updateAudio(transform: (AudioSettings) -> AudioSettings) {
        viewModelScope.launchSafely(TAG) { repository.updateAudio(transform) }
    }

    private companion object {
        const val TAG = "Settings"

        /** Long enough to survive a configuration change without re-reading. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
