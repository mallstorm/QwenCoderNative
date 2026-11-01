plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.mallstorm.qwencoder"
 compileSdk = 35
 defaultConfig { applicationId = "com.mallstorm.qwencoder"; minSdk = 26; targetSdk = 32; versionCode = 4; versionName = "1.3" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}

dependencies { implementation("androidx.browser:browser:1.8.0") }
