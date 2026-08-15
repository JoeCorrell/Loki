plugins {
    alias(libs.plugins.thor.android.library)
    alias(libs.plugins.thor.android.hilt)
}

android {
    namespace = "com.thor.core.streaming"
}

/*
 * The streaming protocol layer, and nothing above it.
 *
 * This was inside `:data` while the launcher was the only thing that streamed.
 * It is its own module because Moonlight DS streams as well, and depending on
 * `:data` to reach it would drag Room, WorkManager, the scrapers, the SMB client
 * and the archive readers into an app that does none of those things.
 *
 * The split is along a real seam rather than a convenient one: nothing in here
 * knows about a library, an emulator or a profile. It discovers hosts, pairs with
 * them, asks them to launch something, and decodes what comes back.
 */
dependencies {
    // The JNI bridge into Moonlight's own streaming core. `api` because the
    // decoder and audio renderer implement its interfaces in public signatures.
    api(projects.core.moonlight)
    api(projects.core.model)
    // Quality, host list and client identity all live in the settings document.
    api(projects.core.datastore)
    implementation(projects.core.common)

    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    // Pairing is a certificate exchange; the platform's own X.509 builder is not
    // public API, so the same provider Moonlight uses does the work.
    implementation(libs.bouncycastle.pkix)
}
