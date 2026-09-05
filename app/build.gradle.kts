plugins { id("com.android.application") }

android {
    namespace = "com.ketelcustoms.radarwallpaper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ketelcustoms.radarwallpaper"
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "0.31"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies { }

val cacheTaipeiRadar by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "download_taipei_preview.py")
}

tasks.named("preBuild") {
    dependsOn(cacheTaipeiRadar)
}
