plugins {
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

allprojects {
    group = "com.github.OneTracePro.onetrace-android"
    version = providers.gradleProperty("VERSION_NAME").get()
}
