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
        // Credential Manager lo necesita para que el servidor pueda validar el
        // token; con el ID de Android puesto aqui, el login falla en silencio.
        //
        // OJO: si dev y pruebas viven en OTRO proyecto de Firebase, este valor
        // cambia por sabor. En ese caso borra esta linea y pon un
        // buildConfigField("String", "WEB_CLIENT_ID", ...) dentro de cada uno.
        buildConfigField(
            "String",
            "WEB_CLIENT_ID",
            "\"389825726990-b6ubrv9f9fv2n2rn2r9dcbho9dnmdv8c.apps.googleusercontent.com\""
        )

        // API_BASE NO se define aqui a proposito. Vive solo en los sabores, de
        // modo que sea imposible compilar una variante sin decidir contra que
        // servidor habla. Un valor por defecto aqui seria justo el que se
        // cuela en produccion sin que nadie lo note.
    }

    // -------------------------------------------------------------------------
    // Ambientes
    // -------------------------------------------------------------------------
    // Cada sabor instala una app distinta en el telefono: applicationId
    // distinto, nombre distinto, icono propio si quieres. Puedes tener los tres
    // a la vez y comparar comportamientos sin desinstalar nada.
    //
    // No se puede llamar "test" a un sabor: el AGP reserva ese prefijo para los
    // conjuntos de fuentes de pruebas unitarias y la sincronizacion falla.
    flavorDimensions += "ambiente"

    productFlavors {
        create("dev") {
            dimension = "ambiente"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"

            // Dentro del emulador, localhost es el propio emulador; el equipo
            // anfitrion se alcanza por 10.0.2.2. Con un telefono fisico por
            // USB, cambia esto por la IP de tu computadora en la red local.
            buildConfigField("String", "API_BASE", "\"http://10.0.2.2:8080\"")
        }

        create("pruebas") {
            dimension = "ambiente"
            applicationIdSuffix = ".pruebas"
            versionNameSuffix = "-pruebas"

            // Confirma este nombre antes de compilar: el Caddyfile del
            // repositorio sirve testhub.d2600.com, pero .env.test.example dice
            // test.ythub.d2600.com. Tiene que coincidir con RELAY_URL_PUBLICA
            // del ambiente de pruebas en el VPS.
            buildConfigField("String", "API_BASE", "\"https://testhub.d2600.com\"")
        }

        create("prod") {
            dimension = "ambiente"
            // Sin sufijo: este es el applicationId de verdad, el que va a Play.
            buildConfigField("String", "API_BASE", "\"https://ythub.d2600.com\"")
        }
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
            // Ya NO lleva applicationIdSuffix = ".debug".
            // El sufijo lo pone el sabor. Si ambos pusieran el suyo saldrian
            // seis paquetes distintos (...dev.debug, ...pruebas.debug, etc.) y
            // habria que registrar los seis en Firebase.
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

    // Solo mensajeria. El directorio, las sesiones y los favoritos viven
    // ahora en el servidor propio.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.play.services)
}
kotlin {
    jvmToolchain(17)
}

tasks.register("prepareKotlinBuildScriptModel") {
    // Dummy task to satisfy Android Studio
}