// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
}

// The project lives on Google Drive; keep local build output off the synced folder.
// CI (GitHub Actions sets CI=true) builds in the default location.
if (System.getenv("CI") == null) {
    allprojects {
        layout.buildDirectory.set(file("${System.getProperty("user.home")}/.speedalert-build/${project.name}"))
    }
}
