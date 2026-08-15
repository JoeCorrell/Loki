package com.thor.feature.home

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Allows the start-up intro to be claimed once during this app process.
 *
 * A [LauncherViewModel] can be recreated without the process being killed. Android
 * does exactly that when it reclaims the launcher's activity while another app is
 * in front, then creates it again for a Home press. Keeping this bit on the view
 * model therefore turns an ordinary return Home into an apparent cold start.
 *
 * Hilt retains this singleton until the process dies. That is the intended lifetime:
 * a genuinely new process gets one intro, while replacement activities and view
 * models in the same process do not replay it. The atomic claim also prevents two
 * concurrently created hosts from both winning.
 */
@Singleton
class LauncherIntroGate @Inject constructor() {

    private val claimed = AtomicBoolean(false)

    /** Returns true only to the first launcher view model in this process. */
    fun claim(): Boolean = claimed.compareAndSet(false, true)
}
