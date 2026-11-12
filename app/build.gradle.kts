import com.google.gms.googleservices.GoogleServicesPlugin.GoogleServicesPluginConfig
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp.plugin)
    alias(libs.plugins.google.services) apply false
}

val firebaseDevConfigured = file("src/firebaseDev/google-services.json").isFile
if (firebaseDevConfigured) {
    apply(plugin = "com.google.gms.google-services")
    configure<GoogleServicesPluginConfig> {
        missingGoogleServicesStrategy = MissingGoogleServicesStrategy.IGNORE
    }
}

val releaseStore = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD").orNull
val releaseAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD").orNull
val releaseSigningReady = listOf(releaseStore, releaseStorePassword, releaseAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }
val releaseVersionCode = providers.environmentVariable("ANDROID_VERSION_CODE").orElse("1").get()
    .toIntOrNull()?.takeIf { it in 1..2100000000 }
    ?: throw GradleException("ANDROID_VERSION_CODE must be a positive supported integer")

if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) } && !releaseSigningReady) {
    throw GradleException("Release tasks require the app-specific upload signer configuration")
}

android {
    namespace = "com.haebi.petworld"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.haebi.petworld"
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = providers.environmentVariable("ANDROID_VERSION_NAME").orElse("0.1.0").get()
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseStore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        if (firebaseDevConfigured) {
            create("firebaseDev") {
                initWith(getByName("debug"))
                applicationIdSuffix = ".dev"
                matchingFallbacks += "debug"
            }
        }
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    if (firebaseDevConfigured) {
        add("firebaseDevImplementation", platform(libs.firebase.bom))
        add("firebaseDevImplementation", libs.firebase.auth)
    }

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)

    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
