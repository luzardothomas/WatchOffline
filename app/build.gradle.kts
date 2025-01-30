plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}



android {
    namespace = "com.example.watchoffline"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.watchoffline"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

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


}