plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.jvaneyck.citywalk"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.jvaneyck.citywalk"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"

        // Web page that turns a shared link into "open City Walk" (hosted with the lingo site)
        // DuckDNS name for the home server (WhatsApp only links a bare IP partially)
        val linkHost = "9292games.duckdns.org"
        val linkPath = "/citywalk"
        manifestPlaceholders["linkHost"] = linkHost
        manifestPlaceholders["linkPath"] = linkPath
        buildConfigField("String", "LINK_BASE", "\"https://$linkHost$linkPath\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
