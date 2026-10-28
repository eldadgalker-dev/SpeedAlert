plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val cleanRes = tasks.register<Sync>("cleanRes") {
    from("src/main/res") { exclude("**/desktop.ini") }
    into(layout.buildDirectory.dir("res-clean"))
}
tasks.named("preBuild") { dependsOn(cleanRes) }

// Version values are injected by CI (-PversionCode / -PversionName); local builds use the defaults.
val appVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName: String = (project.findProperty("versionName") as String?) ?: "0.2.0-local"
val githubRepo = "eldadgalker-dev/SpeedAlert"

// Release signing comes from environment variables (CI secrets); without them release stays unsigned.
val signingEnv = listOf("SPEED_KEYSTORE_FILE", "SPEED_KEYSTORE_PASSWORD", "SPEED_KEY_ALIAS", "SPEED_KEY_PASSWORD")
    .associateWith { System.getenv(it) }
val hasReleaseSigning = signingEnv.values.all { !it.isNullOrBlank() }

android {
    namespace = "il.speedalert"
    compileSdk = 35

    defaultConfig {
        applicationId = "il.speedalert"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "DATA_URL", "\"https://raw.githubusercontent.com/$githubRepo/main/data\"")
        buildConfigField("String", "RELEASE_URL", "\"https://github.com/$githubRepo/releases/latest/download\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingEnv.getValue("SPEED_KEYSTORE_FILE")!!)
                storePassword = signingEnv["SPEED_KEYSTORE_PASSWORD"]
                keyAlias = signingEnv["SPEED_KEY_ALIAS"]
                keyPassword = signingEnv["SPEED_KEY_PASSWORD"]
            }
        }
    }

    // Camera database and penalty table live in /data at the repo root.
    sourceSets["main"].assets.srcDirs("../data")
    // Google Drive drops desktop.ini into every folder: skip it in assets,
    // and compile resources from a copy without it.
    androidResources { ignoreAssetsPattern = "!desktop.ini:!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~" }
    sourceSets["main"].res.setSrcDirs(listOf(cleanRes.map { it.destinationDir }))

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true }
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
    // Real org.json for unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
