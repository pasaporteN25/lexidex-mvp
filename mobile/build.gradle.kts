plugins {
    alias(libs.plugins.android.application) apply false
    // No org.jetbrains.kotlin.android plugin: AGP 9's built-in Kotlin support supersedes it.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
