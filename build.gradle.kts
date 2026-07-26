plugins {
    // 8.10.x is the newest AGP that still runs on Gradle 8.11.1 (8.11 needs
    // Gradle 8.13) and the first line that knows about compileSdk 36.
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
