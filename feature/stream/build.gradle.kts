plugins {
    alias(libs.plugins.thor.android.feature)
}

android {
    namespace = "com.thor.feature.stream"
}

dependencies {
    implementation(projects.data)
    // The session puts the pad on the second panel when there is one.
    implementation(projects.core.display)
    implementation(libs.androidx.compose.material.icons.extended)
}
