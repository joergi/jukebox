import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

// ---------------------------------------------------------------------------
// Read Discogs credentials from local.properties (never committed to git)
// ---------------------------------------------------------------------------
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}
val discogsConsumerKey: String = localProps.getProperty("discogs.consumerKey", "")
val discogsConsumerSecret: String = localProps.getProperty("discogs.consumerSecret", "")

kotlin {
    // Use the same JDK version as .sdkmanrc (Java 25 / Liberica)
    jvmToolchain(25)

    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
                }
            }
        }
    }

    jvm("desktop")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.materialIconsExtended)

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.logging)

                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor)

                implementation(libs.navigation.compose)
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.androidx.lifecycle.viewmodel.compose)

                implementation(libs.datastore.preferences.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.kotest.assertions.core)
                implementation(libs.ktor.client.mock)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.security.crypto)
                implementation(libs.androidx.work.runtime)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
            }
        }

        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        val iosX64Main by getting { dependsOn(iosMain) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }

        val iosTest by creating { dependsOn(commonTest) }
        val iosX64Test by getting { dependsOn(iosTest) }
        val iosArm64Test by getting { dependsOn(iosTest) }
        val iosSimulatorArm64Test by getting { dependsOn(iosTest) }

        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit)
                implementation(libs.mockk)
                implementation(libs.junit)                   // TemporaryFolder rule
                implementation(libs.kotest.assertions.core)  // shouldBe etc. on JVM
                implementation(libs.turbine)                 // StateFlow testing
                implementation(libs.ktor.client.mock)        // MockEngine
                implementation(libs.kotlinx.coroutines.test) // runTest
            }
        }

        val androidTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(libs.kotlin.test.junit)
                implementation(libs.junit)
                implementation("androidx.test:core:1.7.0")
                implementation("androidx.test.ext:junit:1.1.5")
                implementation("androidx.test:runner:1.5.2")
                implementation("androidx.work:work-testing:2.11.2")
                implementation(libs.androidx.work.runtime)
            }
        }
    }
}

android {
    namespace = "com.joergi.jukebox"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.joergi.jukebox"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // Inject credentials into BuildConfig so they are never in source
        buildConfigField("String", "DISCOGS_CONSUMER_KEY", "\"$discogsConsumerKey\"")
        buildConfigField("String", "DISCOGS_CONSUMER_SECRET", "\"$discogsConsumerSecret\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        // Android bytecode must target JVM 11 (AGP constraint); the Gradle
        // toolchain above selects JDK 25 for compilation.
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

compose.desktop {
    application {
        mainClass = "com.joergi.jukebox.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            packageName = "Jukebox"
            packageVersion = "1.0.0"
        }
    }
}

// Set working dir for :run to project root so local.properties is found.
afterEvaluate {
    tasks.withType<JavaExec>().configureEach {
        if (name == "run") workingDir = rootProject.projectDir
    }
    
    // Configure desktop tests to run sequentially due to Kotlin 2.3.21 test flakiness
    tasks.named<Test>("desktopTest") {
        maxParallelForks = 1
    }
}
