plugins {
    id("com.android.application")
}

val ciKeystoreFile = System.getenv("SIGNING_KEYSTORE_FILE")
val ciStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
val ciKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
val ciKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
val ciSigning = ciKeystoreFile != null && ciStorePassword != null && ciKeyAlias != null && ciKeyPassword != null

android {
    namespace = "dev.uninvg"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.uninvg"
        minSdk = 24
        targetSdk = 35
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 60000
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.6.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (ciSigning) {
            create("release") {
                storeFile = file(ciKeystoreFile!!)
                storePassword = ciStorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
    }
    buildTypes {
        debug {
            if (ciSigning) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint {
        abortOnError = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    compileOnly("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18")
}
