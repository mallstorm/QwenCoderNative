plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.mallstorm.qwencoder"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mallstorm.qwencoder"
        minSdk = 26
        targetSdk = 32
        versionCode = 19
        versionName = "4.9"
    }
    signingConfigs {
        // Постоянный ключ: все сборки подписываются одним сертификатом, поэтому APK ставится поверх предыдущего.
        create("release") {
            storeFile = file("qc-release.jks")
            storePassword = "9c081c93ae01113b435f92c3"
            keyAlias = "qwencoder"
            keyPassword = "9c081c93ae01113b435f92c3"
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
}
