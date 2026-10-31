import java.io.FileInputStream
import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(FileInputStream(file))
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.karyatunggal.gesermap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.karyatunggal.gesermap"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    flavorDimensions += "edition"
    productFlavors {
        create("ads") {
            dimension = "edition"
            applicationId = "com.karyatunggal.gesermap"
            resValue("string", "app_name", "GeserMap")
            buildConfigField("boolean", "SHOW_ADS", "true")
        }
        create("adfree") {
            dimension = "edition"
            applicationId = "com.karyatunggal.gesermap.pro"
            resValue("string", "app_name", "GeserMap Pro")
            buildConfigField("boolean", "SHOW_ADS", "false")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(localProperties.getProperty("STORE_FILE") ?: "my-release-key.jks")
            storePassword = localProperties.getProperty("STORE_PASSWORD")
            keyAlias = localProperties.getProperty("KEY_ALIAS")
            keyPassword = localProperties.getProperty("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(libs.play.services.ads)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
