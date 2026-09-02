plugins { id("com.android.application") }

android {
    namespace = "com.ketelcustoms.radarwallpaper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ketelcustoms.radarwallpaper"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
