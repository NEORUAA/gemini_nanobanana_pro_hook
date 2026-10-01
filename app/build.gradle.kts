plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.neoruaa.geminihook"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.neoruaa.geminihook"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

    }

    signingConfigs {
        val keystore = providers.environmentVariable("GEMINI_HOOK_KEYSTORE").orNull
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("GEMINI_HOOK_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("GEMINI_HOOK_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("GEMINI_HOOK_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        resources.merges += "META-INF/xposed/*"
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.smali.dexlib2)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    providers.environmentVariable("GEMINI_HOST_APK").orNull?.let {
        systemProperty("gemini.host.apk", it)
    }
}
