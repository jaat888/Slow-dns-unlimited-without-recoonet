plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
  namespace = "app.sd"
  compileSdk = 34
  defaultConfig { applicationId = "app.sd"; minSdk = 26; targetSdk = 33; versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1); versionName = "1.0"; ndk { abiFilters += "arm64-v8a" } }
  packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
  // Fixed signing key: every build is signed the same way, so a new APK installs over the old one (no uninstall, settings and accounts stay)
  signingConfigs { create("fixed") { storeFile = file("mollad.jks"); storePassword = "molladdns"; keyAlias = "mollad"; keyPassword = "molladdns" } }
  buildTypes { getByName("debug") { isDebuggable = false; signingConfig = signingConfigs.getByName("fixed") } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("com.github.mwiede:jsch:0.2.18") }
