plugins {
    alias(libs.plugins.thor.android.application)
    alias(libs.plugins.thor.android.application.compose)
    alias(libs.plugins.thor.android.hilt)
}

android {
    namespace = "com.moonlight.ds"

    defaultConfig {
        applicationId = "com.moonlight.ds"
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    // The same collision `:app` hits, for the same reason: pairing pulls in
    // BouncyCastle, which ships four jars carrying identical OSGi metadata that
    // the merger refuses rather than picks between. None of it means anything to
    // an APK. Scoped to the colliding paths so the service-loader entries the
    // security provider registers itself through survive.
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

/*
 * Moonlight DS: Loki's streaming section as an application of its own.
 *
 * The dependency list is the point of this module. It names the shared modules
 * and stops — there is no `:data`, no `:core:database`, and none of the feature
 * modules that make Loki a front-end. What streams here is the same code that
 * streams there, and what is missing is only the library around it.
 */
dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    // The on-screen keyboard, the feedback engine and the pointer hover model.
    implementation(projects.core.ui)
    // The presentation window, the display monitor and the focus rule.
    implementation(projects.core.display)
    // The controller router and the shipped button profiles.
    implementation(projects.core.input)
    // Settings, which is the whole ThorSettings document — this app writes only
    // the parts it owns, but the theme is generated from the same values.
    implementation(projects.core.datastore)
    // Discovery, pairing, launching, the session and the decoder.
    implementation(projects.core.streaming)
    // The streaming interface itself, shared verbatim with Loki.
    implementation(projects.feature.stream)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
