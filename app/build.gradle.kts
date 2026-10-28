plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val cleanRes = tasks.register<Sync>("cleanRes") {
    from("src/main/res") { exclude("**/desktop.ini") }
    into(layout.buildDirectory.dir("res-clean"))
}
tasks.named("preBuild") { dependsOn(cleanRes) }

android {
    namespace = "il.speedalert"
    compileSdk = 35

    defaultConfig {
        applicationId = "il.speedalert"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Camera database and penalty table live in /data at the repo root.
    sourceSets["main"].assets.srcDirs("../data")
    // Google Drive drops desktop.ini into every folder: skip it in assets,
    // and compile resources from a copy without it.
    androidResources { ignoreAssetsPattern = "!desktop.ini:!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~" }
    sourceSets["main"].res.setSrcDirs(listOf(cleanRes.map { it.destinationDir }))

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    testImplementation("junit:junit:4.13.2")
}
