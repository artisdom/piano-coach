plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The MIDI library is copied into generated assets (assets/midi/…) rather
// than checked in: 2449 songs, 44 MB.
val midiLibrary = rootProject.file(providers.gradleProperty("midiLibrary").get())
val midiAssets = layout.buildDirectory.dir("generated/midiAssets")
val syncMidiAssets by tasks.registering(Sync::class) {
    from(midiLibrary) {
        include { it.isDirectory || it.name.lowercase().substringAfterLast('.') in setOf("mid", "midi", "kar", "rmi") }
    }
    into(midiAssets.map { it.dir("midi") })
}

android {
    namespace = "org.pianocoach"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.pianocoach"
        // android.media.midi needs API 23.
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets["main"].assets.srcDir(midiAssets)

    buildTypes {
        release {
            // R8 makes Compose several times faster on slow tablets.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // MIDI files are tiny and read whole; no point compressing twice.
        noCompress += listOf("mid", "midi", "kar")
    }
}

tasks.named("preBuild") { dependsOn(syncMidiAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
