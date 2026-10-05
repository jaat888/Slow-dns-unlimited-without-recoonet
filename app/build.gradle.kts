plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
  namespace = "app.sd"
  compileSdk = 34
  defaultConfig { applicationId = "app.sd"; minSdk = 26; targetSdk = 33; versionCode = 1; versionName = "1.0"; ndk { abiFilters += "arm64-v8a" } }
  packaging { jniLibs { useLegacyPackaging = true } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("com.github.mwiede:jsch:0.2.18") }
