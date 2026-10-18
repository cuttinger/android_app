plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xingang.recentapps"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xingang.recentapps"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // Keystore details come from ~/.gradle/gradle.properties, which is outside
    // the repo. Without them the release build is simply left unsigned rather
    // than failing, so anyone can still build and inspect it.
    val storeFilePath: String? = findProperty("RECENTAPPS_STORE_FILE") as String?
    signingConfigs {
        if (storeFilePath != null && file(storeFilePath).exists()) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = findProperty("RECENTAPPS_STORE_PASSWORD") as String?
                keyAlias = findProperty("RECENTAPPS_KEY_ALIAS") as String?
                keyPassword = findProperty("RECENTAPPS_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
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
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Chinese -> pinyin conversion for search (tiny, dictionary bundled)
    implementation("io.github.biezhi:TinyPinyin:2.0.3.RELEASE")
}
