import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val maximumAndroidVersionCode = 2_100_000_000
val camxApplicationBaselineApi = 23
val rawCiRunNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull
val ciRunNumber = rawCiRunNumber?.let { raw ->
    require(raw.all(Char::isDigit)) { "GITHUB_RUN_NUMBER must be a non-negative integer" }
    val parsed = raw.toIntOrNull()
        ?: error("GITHUB_RUN_NUMBER is outside the supported integer range")
    require(parsed <= maximumAndroidVersionCode - 10_000) {
        "GITHUB_RUN_NUMBER cannot produce a valid Android versionCode"
    }
    parsed
} ?: 0
val sourceSha = providers.environmentVariable("CAMX_GIT_SHA")
    .orElse(providers.environmentVariable("GITHUB_SHA"))
    .getOrElse("local")
val buildTimestampUtc = providers.environmentVariable("CAMX_BUILD_TIMESTAMP_UTC")
    .getOrElse("unknown")
val rawDevOtaVersionCode = providers.gradleProperty("devOtaVersionCode").orNull
val devOtaVersionCode = rawDevOtaVersionCode?.let { raw ->
    val parsed = raw.toIntOrNull()
        ?: error("devOtaVersionCode must be an integer when supplied")
    require(parsed in 1..maximumAndroidVersionCode) {
        "devOtaVersionCode must be in Android's 1..$maximumAndroidVersionCode range"
    }
    parsed
}
val rawDevOtaVersionName = providers.gradleProperty("devOtaVersionName").orNull
val devOtaVersionName = rawDevOtaVersionName?.let { raw ->
    val normalized = raw.trim()
    require(normalized.isNotEmpty() && normalized.length <= 128 &&
        normalized.none { it.isISOControl() }
    ) { "devOtaVersionName must be a nonblank, bounded single-line value" }
    normalized
}

val encodedDevSigner = rootProject.file("tools/dev-signing/camx-dev.jks.b64")
val decodedDevSigner = layout.buildDirectory.file("dev-signing/camx-dev.jks").get().asFile
check(encodedDevSigner.isFile) {
    "Permanent CamX dev signer is missing: tools/dev-signing/camx-dev.jks.b64"
}
val signerBytes = Base64.getMimeDecoder().decode(encodedDevSigner.readText())
decodedDevSigner.parentFile.mkdirs()
if (!decodedDevSigner.isFile || !decodedDevSigner.readBytes().contentEquals(signerBytes)) {
    decodedDevSigner.writeBytes(signerBytes)
}

fun String.asBuildConfigLiteral(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.sahidcode404.camx"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.sahidcode404.camx"
        minSdk = camxApplicationBaselineApi
        targetSdk = 37
        versionCode = devOtaVersionCode ?: (10_000 + ciRunNumber)
        versionName = devOtaVersionName
            ?: if (ciRunNumber == 0) "0.1.0-architecture" else "0.1.0-dev.$ciRunNumber"

        buildConfigField("String", "GIT_SHA", sourceSha.asBuildConfigLiteral())
        buildConfigField("String", "BUILD_TIMESTAMP_UTC", buildTimestampUtc.asBuildConfigLiteral())
        buildConfigField("String", "OTA_CHANNEL", "none".asBuildConfigLiteral())

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++20",
                    "-Wall",
                    "-Wextra",
                    "-Werror",
                    "-fvisibility=hidden",
                )
            }
        }
    }

    signingConfigs {
        create("devOta") {
            storeFile = decodedDevSigner
            storePassword = "camx-dev-only-2026"
            keyAlias = "camx-dev"
            keyPassword = "camx-dev-only-2026"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("debug")
        create("devOta") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("devOta")
            isDebuggable = true
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "OTA_CHANNEL", "development".asBuildConfigLiteral())
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("../native/core/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        check(variant.minSdk == 23) {
            "CamX application support must remain at Android API 23; " +
                "${variant.name} resolves minSdk ${variant.minSdk}"
        }
    }
}

tasks.register("verifyApi23Baseline") {
    group = "verification"
    description = "Verifies the Tier-A Android API-23 application baseline from the Android model."
    doLast {
        check(android.defaultConfig.minSdk == 23) {
            "CamX application baseline changed from Android API 23"
        }
        println("CamX Android model baseline verified: minSdk=23")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
