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

// After every debug build: copy the APK to the project root and upload it to the home server,
// where Caddy serves it at https://9292games.duckdns.org/apps/CityWalk.apk (see server/Caddyfile).
// Needs SSH key access to the server (home network); otherwise it only warns. Skip with -PnoPublish.
val publishApk by tasks.registering {
    description = "Copies the debug APK to CityWalk.apk and uploads it to the web server"
    val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val localCopy = rootProject.layout.projectDirectory.file("CityWalk.apk").asFile
    val skip = providers.gradleProperty("noPublish").isPresent
    doLast {
        val apk = apkFile.get().asFile
        apk.copyTo(localCopy, overwrite = true)
        if (skip) return@doLast

        val server = "deploy@192.168.2.41"
        val ssh = arrayOf("-o", "BatchMode=yes", "-o", "ConnectTimeout=5")
        fun run(vararg cmd: String): Boolean = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start().let { p ->
                val out = p.inputStream.bufferedReader().readText()
                (p.waitFor() == 0).also { ok -> if (!ok) logger.warn(out.trim()) }
            }
        } catch (e: Exception) {
            logger.warn(e.message)
            false
        }

        // Upload under a temp name and rename, so a half-uploaded APK is never served
        val ok = run("scp", *ssh, apk.absolutePath, "$server:/srv/apps/CityWalk.apk.tmp") &&
            run("ssh", *ssh, server, "mv /srv/apps/CityWalk.apk.tmp /srv/apps/CityWalk.apk")
        if (ok) {
            logger.lifecycle("Published: https://9292games.duckdns.org/apps/CityWalk.apk")
        } else {
            logger.warn("APK not published (server unreachable?). Local copy: ${localCopy.path}")
        }
    }
}

tasks.configureEach {
    if (name == "assembleDebug") finalizedBy(publishApk)
}
