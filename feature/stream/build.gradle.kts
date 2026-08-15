plugins {
    alias(libs.plugins.thor.android.feature)
}

android {
    namespace = "com.thor.feature.stream"
}

dependencies {
    /*
     * The protocol layer alone, rather than the whole of `:data`.
     *
     * This module is the streaming interface for both Loki and Moonlight DS, so
     * it may only depend on what streaming actually needs. Reaching the session
     * through `:data` would make the standalone app carry the library database
     * and the scrapers to show a list of PCs.
     */
    // `api` because it is in this module's own public signatures — `StreamUiState`
    // carries a `LaunchStage`, and `StreamPadHost` takes a `StreamPad`. A consumer
    // cannot call what it cannot name.
    api(projects.core.streaming)
    // The session puts the pad on the second panel when there is one.
    implementation(projects.core.display)
    implementation(libs.androidx.compose.material.icons.extended)
}
