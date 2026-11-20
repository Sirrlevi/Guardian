plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
val firebaseApiKey = providers.gradleProperty("FIREBASE_API_KEY").orElse(providers.environmentVariable("FIREBASE_API_KEY")).getOrElse("")
val firebaseDatabaseUrl = providers.gradleProperty("FIREBASE_DATABASE_URL").orElse(providers.environmentVariable("FIREBASE_DATABASE_URL")).getOrElse("")
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val signingValues = listOf("ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
val hasSigning = file("$rootDir/guardian-release.jks").exists() &&
    signingValues.all { !System.getenv(it).isNullOrBlank() }

android {
    namespace = "com.sirrlevi.guardian.child"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sirrlevi.guardian.child"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "FIREBASE_API_KEY", quoted(firebaseApiKey))
        buildConfigField("String", "FIREBASE_DATABASE_URL", quoted(firebaseDatabaseUrl))
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    if (hasSigning) {
        signingConfigs {
            create("release") {
                storeFile = file("$rootDir/guardian-release.jks")
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.material:material:1.12.0")
}
