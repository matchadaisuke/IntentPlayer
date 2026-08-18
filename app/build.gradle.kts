plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") // ← 追加したプラグイン
}

android {
    namespace = "com.intentplayer"
    // Phase 9: compileSdk を 35 に更新（Android 15 実機検証対応）
    compileSdk = 35

    val overrideVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull()
    val overrideVersionName = project.findProperty("versionName")?.toString()

    defaultConfig {
        applicationId = "com.intentplayer"
        minSdk = 26
        // Phase 9: targetSdk を 35 に更新
        targetSdk = 35
        versionCode = overrideVersionCode ?: 13
        versionName = overrideVersionName ?: "Phase11.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    // ※ここにあった composeOptions は削除しました

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.8.1")
    implementation("androidx.media3:media3-common:1.8.1")
    implementation("androidx.media3:media3-session:1.8.1")

    // Phase 9: MediaButtonReceiver (androidx.media.session) のために必要
    implementation("androidx.media:media:1.7.0")

    // DocumentFile (SAF)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}