import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath: String? = System.getenv("SPOTIFYQUIET_KEYSTORE_FILE")
val releaseKeystorePassword: String? = System.getenv("SPOTIFYQUIET_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("SPOTIFYQUIET_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("SPOTIFYQUIET_KEY_PASSWORD")
val signingRequired: Boolean = System.getenv("SPOTIFYQUIET_REQUIRE_SIGNING") == "true"
val hasReleaseSigning: Boolean = releaseKeystorePath != null &&
    releaseKeystorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

if (signingRequired && !hasReleaseSigning) {
    throw GradleException(
        "SPOTIFYQUIET_REQUIRE_SIGNING=true but one or more release signing " +
            "environment variables are missing. Refusing to produce an unsigned release APK."
    )
}

android {
    namespace = "com.spotifyquiet.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spotifyquiet.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = false
        htmlReport = true
        xmlReport = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
