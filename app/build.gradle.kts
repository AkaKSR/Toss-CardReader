import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from keystore-cardapp.properties (not committed): the app's own key since 1.0.0, so
// updates install over existing data. The reader service and samples use keystore.properties instead.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore-cardapp.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.toss.cardapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.toss.cardapp"
        minSdk = 26
        targetSdk = 33
        versionCode = 4
        versionName = "1.3.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    implementation(project(":reader"))
    testImplementation("junit:junit:4.13.2")
}
