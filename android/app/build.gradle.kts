import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

val keystorePath: String? = System.getenv("KEYSTORE_PATH")

// Room writes one JSON per schema version here, and they are COMMITTED. They are the record of
// what each shipped version looked like; the migration test validates against them, so without
// them there is nothing to test a migration against.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.tote"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tote"
        minSdk = 26
        targetSdk = 35
        // CI passes VERSION_CODE (epoch minutes) so each signed release installs cleanly over the
        // previous one; defaults to a low value for local/debug builds. Suite invariant 2: a
        // local debug build therefore CANNOT install over a CI release without uninstalling.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // AppAuth's redirect receiver activity binds to this scheme — the custom-scheme half of
        // the com.tote:/oauth2redirect URI registered in dragonfly-id (Phase 1).
        manifestPlaceholders["appAuthRedirectScheme"] = "com.tote"
        buildConfigField(
            "String", "SERVER_URL",
            "\"${localProperties.getProperty("server.url", "https://dragonfly.tail2ce561.ts.net:8448/")}\""
        )
    }

    signingConfigs {
        // A stable, committed key so every build — debug, local release, CI release — shares one
        // signing identity. New APKs install over the top of existing ones without Android
        // complaining about INSTALL_FAILED_UPDATE_INCOMPATIBLE. Password is not secret.
        // This is NOT the suite release key (that one never enters a public repo — see the
        // suite invariants in C:\Code\CLAUDE.md).
        create("stable") {
            storeFile = file("tote-debug.keystore")
            storePassword = "tote01"
            keyAlias = "tote"
            keyPassword = "tote01"
        }
        // CI's real release key (the suite key), only when KEYSTORE_PATH is supplied in the env.
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            // Prefer CI's release key; fall back to the stable committed key for local releases.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("stable")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    // The exported schemas, as assets — MigrationTestHelper loads them from there and nowhere
    // else (its `file` parameter is the database file, not the schema directory).
    //
    // Scoped to DEBUG rather than main: Robolectric runs against the debug variant, so the
    // migration test can read them, while the release APK carries none of it. A schema JSON is a
    // build-time record and has no business shipping to a phone.
    sourceSets.getByName("debug") {
        assets.srcDir("$projectDir/schemas")
    }

    // Same, for the on-device migration test (ToteDatabaseMigrationAndroidTest), which is the
    // only place Room's MigrationTestHelper can do column-level validation.
    sourceSets.getByName("androidTest") {
        assets.srcDir("$projectDir/schemas")
    }


}

tasks.withType<Test>().configureEach {
    // Where ToteDatabaseMigrationTest lists the exported versions from. It reads the source
    // tree directly for the listing; MigrationTestHelper itself loads them from assets (below).
    systemProperty("tote.schemaDir", "$projectDir/schemas")

    listOf(
        "roborazzi.test.record",
        "roborazzi.test.verify",
        "roborazzi.test.compare",
    ).forEach { key ->
        (project.findProperty(key) as String?)?.let { systemProperty(key, it) }
    }
    // The Robolectric NATIVE-graphics screenshot tests download a large android-all runtime at
    // test time, which can stall CI. Pass -PexcludeScreenshots to skip them (the gating
    // "Android — Unit Tests" job does this); they still run in the dedicated screenshots job.
    if (project.hasProperty("excludeScreenshots")) {
        filter { excludeTestsMatching("com.tote.screenshot.*") }
    }
}

dependencies {
    // Hilt's generated components reference errorprone annotations at compile time; not pulled
    // transitively under AGP 9 / KSP2, so declare it explicitly (compile-only is enough).
    compileOnly("com.google.errorprone:error_prone_annotations:2.50.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // PULSE design system (theme tokens + component kit), from the sibling Pulse repo via the
    // composite build declared in settings.gradle.kts. Tote leads PulseAccent.Slate.
    implementation(libs.pulse.ui)

    // Suite SSO: OpenID Connect authorization-code + PKCE via AppAuth.
    implementation(libs.appauth)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.datastore.preferences)

    // Room: a read cache of the catalog, so the app works in the attic and the garage where
    // the Wi-Fi is worst. The server stays the source of truth.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    // The capture queue drains in the background: a bin's worth of photos shot in a garage with
    // no signal has to upload later without anyone reopening the app.
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Thumbnails of queued captures and of the server's photos. Rides the app's OkHttp client
    // (see ToteApp) so authenticated photo URLs load.
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    testImplementation(libs.room.testing)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)

    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
}
