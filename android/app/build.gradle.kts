plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.tuempresa.creatorhub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tuempresa.creatorhub"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // El client_id de tipo 3 ("web") dentro de google-services.json.
        // Credential Manager lo necesita para que Firebase pueda validar el
        // token; con el ID de Android puesto aqui, el login falla en silencio.
        buildConfigField(
            "String",
            "WEB_CLIENT_ID",
            "\"000000000000-xxxxxxxxxxxxxxxxxxxx.apps.googleusercontent.com\""
        )
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
        debug {
            applicationIdSuffix = ".debug"
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
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    buildToolsVersion = "34.0.0"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.functions)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.play.services)
}
kotlin {
    jvmToolchain(17)
}

tasks.register("prepareKotlinBuildScriptModel") {
    // Dummy task to satisfy Android Studio
}
