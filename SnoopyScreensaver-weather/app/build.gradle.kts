plugins {
    id("com.android.application")
}

android {
    namespace = "com.snoopysaver.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.snoopysaver.weather"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "1.1-weather"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*")
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("io.github.awxkee:avif-coder:3.0.0")
}
