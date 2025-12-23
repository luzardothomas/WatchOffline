plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}



android {
    namespace = "com.example.watchoffline"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.watchoffline"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

    }

    packagingOptions {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.glide)
    implementation(libs.androidx.media3.exoplayer)
    implementation("androidx.appcompat:appcompat:1.4.0")
    implementation("com.google.code.gson:gson:2.10.1")
    // ExoPlayer
    implementation("com.google.android.exoplayer:exoplayer-core:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // SMB client
    implementation("com.hierynomus:smbj:0.11.5")

    implementation("androidx.leanback:leanback:1.1.0")
    implementation(libs.leanback)
    implementation(libs.androidx.media3.ui.leanback)

    implementation("org.videolan.android:libvlc-all:3.5.1")
    implementation("com.google.android.material:material:1.12.0")
}