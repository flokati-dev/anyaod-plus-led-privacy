plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.codecandy.blinkify"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.codecandy.blinkify"
        minSdk = 26
        targetSdk = 36
        versionCode = 56
        versionName = "1.5.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Extracts native debug symbols from transitive libraries (Compose, AndroidX etc.)
        // and bundles them into the AAB for Play Console crash/ANR analysis.
        // Does NOT increase the APK size delivered to end users.
        ndk {
            debugSymbolLevel = "FULL"
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
    }

    flavorDimensions += "store"
    productFlavors {
        create("googlePlay") {
            dimension = "store"
            // keeps the existing package name
        }
        create("samsung") {
            dimension = "store"
            applicationId = "com.codecandy.anyaod"
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")

    // Google Play Billing Library — only in googlePlay flavor
    "googlePlayImplementation"("com.android.billingclient:billing-ktx:7.1.1")

    // Samsung IAP SDK — only in samsung flavor
    "samsungImplementation"(fileTree(mapOf("dir" to "libs/samsung", "include" to listOf("*.aar"))))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
