plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
}

// The project lives on Google Drive; keep build output off the synced folder.
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/.speedalert-build/${project.name}"))
}
