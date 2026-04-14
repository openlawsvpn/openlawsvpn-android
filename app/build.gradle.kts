import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("androidx.navigation.safeargs.kotlin")
}

// Release-signing credentials — resolved in priority order:
//   1. Environment variable  (GHA secrets / `pass`-injected env)
//   2. local.properties key  (developer workstation, gitignored)
// Keys: KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
val localProps = Properties().also { p ->
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(p::load)
}
fun secret(key: String): String? = System.getenv(key) ?: localProps[key] as String?

android {
    namespace = "com.openlawsvpn.android"
    compileSdk = 35
    ndkVersion = "30.0.14904198" //28.0.12433566"  // update to latest LTS from SDK Manager

    defaultConfig {
        applicationId = "com.openlawsvpn.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-26"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            val ks = secret("KEYSTORE_PATH")
            if (!ks.isNullOrBlank()) {
                storeFile     = file(ks)
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias      = secret("KEY_ALIAS")
                keyPassword   = secret("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
}
