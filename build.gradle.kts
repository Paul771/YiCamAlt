// Top-level build file. Plugins declared here, applied in module build files via `plugins { id(...) }`.
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
    id("com.google.dagger.hilt.android") version "2.51" apply false
    id("de.mannodermaus.android-junit5") version "1.10.0.0" apply false
}