plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xingang.ttsreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xingang.ttsreader"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // MediaStyle notification
    implementation("androidx.media:media:1.7.0")
    // Android Auto templates
    implementation("androidx.car.app:app:1.4.0")
    // Android Automotive OS: hosts the templates inside CarAppActivity
    implementation("androidx.car.app:app-automotive:1.4.0")

    testImplementation("junit:junit:4.13.2")
}
