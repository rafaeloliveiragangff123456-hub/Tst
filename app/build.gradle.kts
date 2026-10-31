plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Configuração vem de variáveis de ambiente (GitHub Actions) ou gradle.properties local — nunca fica no código.
fun cfg(nome: String, padrao: String = ""): String =
    System.getenv(nome)?.takeIf { it.isNotBlank() } ?: (project.findProperty(nome) as String?)?.takeIf { it.isNotBlank() } ?: padrao

android {
    namespace = "com.botsync.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.botsync.app"
        minSdk = 26
        targetSdk = 34
        versionCode = cfg("BS_VERSION_CODE", "1").toInt()
        versionName = cfg("BS_VERSION_NAME", "1.0.0")

        buildConfigField("String", "SERVER_URL", "\"${cfg("BOTSYNC_URL", "https://CONFIGURE-BOTSYNC_URL.squareweb.app").trimEnd('/')}\"")
        buildConfigField("String", "TLS_PINS", "\"${cfg("BOTSYNC_PINS")}\"")
        buildConfigField("String", "CERT_SHA256", "\"${cfg("BOTSYNC_CERT_SHA256")}\"")
        buildConfigField("boolean", "BLOQUEAR_ROOT", cfg("BOTSYNC_BLOQUEAR_ROOT", "true"))
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        create("release") {
            val ks = cfg("BS_KEYSTORE")
            if (ks.isNotEmpty()) {
                storeFile = file(ks)
                storePassword = cfg("BS_KEYSTORE_PASS")
                keyAlias = cfg("BS_KEY_ALIAS", "botsync")
                keyPassword = cfg("BS_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // R8: encurta e ofusca o código
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("com.google.android.gms:play-services-auth:21.2.0")   // conta Google do aparelho → token do Gmail (somente leitura)
}
