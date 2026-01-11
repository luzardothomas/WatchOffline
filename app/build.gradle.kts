plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}



android {
    namespace = "com.example.watchoffline"
    compileSdk = 35
    viewBinding {
        enable = true
    }

    defaultConfig {
        applicationId = "com.example.watchoffline"
        minSdk = 21
        targetSdk = 35
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

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // SMB client
    implementation("com.hierynomus:smbj:0.11.5")

    implementation("androidx.leanback:leanback:1.1.0")
    implementation(libs.leanback)

    implementation("org.videolan.android:libvlc-all:3.6.0")
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.material)
}