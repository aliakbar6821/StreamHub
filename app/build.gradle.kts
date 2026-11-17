plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.lynx.streamhub"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.lynx.streamhub"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":api"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}

val ks = rootProject.file("keystore/debug.keystore")
if (ks.exists()) {
    android {
        signingConfigs {
            getByName("debug") {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
}

android {
    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.version",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

configurations.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    }
}

dependencies {
    implementation("com.github.recloudstream.cloudstream:library:v4.8.0")
    compileOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    compileOnly("com.squareup.okhttp3:okhttp:5.3.2")
    compileOnly("com.github.Blatzar:NiceHttp:0.4.17")
}
