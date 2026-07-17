plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.driver.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.driver.app"
        minSdk = 26          // PiP RemoteAction доступен с API 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
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
    // ─── MapLibre Native SDK ─────────────────────────────────────────────────
    implementation("org.maplibre.gl:android-sdk:11.5.2")
    // Примечание: НЕ используем org.maplibre.gl:android-plugin-annotation-v9 —
    // у него известная несовместимость с android-sdk 11.2.0+ (падает с
    // NoClassDefFoundError на com.mapbox.android.gestures.AndroidGesturesManager,
    // см. github.com/maplibre/maplibre-native/issues/2835). Маркер пассажира
    // реализован через штатные GeoJsonSource + SymbolLayer (без плагина).

    // ─── Socket.IO клиент — связь с сервером (координаты + чат) ─────────────
    // exclude org.json — эта библиотека тащит свою копию, которая конфликтует
    // с org.json, встроенным в Android (ошибка "duplicate class" при сборке).
    implementation("io.socket:socket.io-client:2.1.1") {
        exclude(group = "org.json", module = "json")
    }

    // ─── AndroidX ────────────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // ─── Coroutines ─────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // ─── Lifecycle (ViewModel + LiveData/StateFlow) ─────────────────────────
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")

    // ─── Activity KTX (viewModels() delegate) ───────────────────────────────
    implementation("androidx.activity:activity-ktx:1.9.3")
}
