package com.moonlight.ds

import android.app.Application
import com.thor.core.common.dispatchers.ApplicationScope
import com.thor.core.common.log.CrashReporter
import com.thor.core.common.log.ThorLog
import com.thor.core.datastore.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Much shorter than Loki's, and the difference is the point of this app rather
 * than an omission. There is no image loader because nothing here loads artwork
 * over the network — box art belongs to a library, and this program has none.
 * There is no play-time tracker and no screen-off receiver, because neither
 * measures anything when the only thing that can be started is a stream that
 * reports its own state.
 *
 * What is kept is what the shared modules expect to find: a Hilt graph, the
 * crash reporter, and the verbose-logging switch wired to the same setting.
 */
@HiltAndroidApp
class MoonlightApplication : Application() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        // Installed first, before anything else can throw, so a crash during
        // startup is still captured.
        CrashReporter.install(filesDir)

        // Follows the same developer setting Loki's does, so a shipped build is
        // quiet until the user explicitly asks for diagnostics.
        settingsRepository.developer
            .onEach { ThorLog.enabled = it.verboseLogging }
            .launchIn(applicationScope)

        ThorLog.i("App", "Moonlight DS started")
    }
}
