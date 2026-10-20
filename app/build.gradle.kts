import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("de.mannodermaus.android-junit5")
}

android {
    namespace = "com.yicamalt"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yicamalt"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // Vendor P2P libraries exist only for these two ABIs (GSD Phase 2, M-P2P-NATIVE).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "YI_API_BASE_URL", "\"https://gw-us.xiaoyi.com/\"")
            buildConfigField("boolean", "LOCAL_RTSP_ENABLED", "true")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "YI_API_BASE_URL", "\"https://gw-us.xiaoyi.com/\"")
            buildConfigField("boolean", "LOCAL_RTSP_ENABLED", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Ship the vendor libraries extracted and byte-identical (no NDK strip, no in-APK mmap).
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libPPPP_API.so", "**/libIOTCAPIs.so", "**/libAVAPIs.so", "**/libsCHL.so")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

// Vendor native libraries (GSD Phase 2, M-P2P-NATIVE, V-M-P2P-NATIVE).
// The repository holds no vendor binary. extractVendorNatives copies lib/<abi>/lib<NAME>.so out of the
// user's own Yi Home APK (under _apk/ or -PvendorApk) into app/build only and records a status file;
// verifyVendorNatives turns that status into a build failure for APK-producing tasks only, so a fresh
// clone without an APK still configures and runs every JVM test.
// Properties: -PvendorApk=<file>, -PvendorApkDir=<dir> (default _apk), -PvendorOutDir=<dir>
// (default app/build/generated/vendorJniLibs, status file is <dir>-status.txt), -PvendorNativesAccept=true.

// START_BLOCK_EXTRACT_VENDOR_NATIVES
val vendorAbis = listOf("arm64-v8a", "armeabi-v7a")
val vendorLibs = listOf("libPPPP_API.so", "libIOTCAPIs.so", "libAVAPIs.so", "libsCHL.so")
val vendorManifest: File = file("vendor-natives.sha256")
val vendorApkProp: String? = providers.gradleProperty("vendorApk").orNull?.takeIf { it.isNotBlank() }
val vendorApkDir: File = providers.gradleProperty("vendorApkDir").orNull?.takeIf { it.isNotBlank() }
    ?.let { file(it) } ?: rootProject.file("_apk")
val vendorOut: File = providers.gradleProperty("vendorOutDir").orNull?.takeIf { it.isNotBlank() }
    ?.let { file(it) } ?: layout.buildDirectory.dir("generated/vendorJniLibs").get().asFile
// Status file lives NEXT TO the jniLibs dir, never inside it, so it is never packaged.
val vendorStatus: File = File(vendorOut.parentFile, vendorOut.name + "-status.txt")
val vendorNativesAccept: Boolean = providers.gradleProperty("vendorNativesAccept").orNull == "true"

// Reads all vendorLibs for one ABI from a zip stream; returns null unless all are present.
fun readVendorLibsFromStream(zin: ZipInputStream, abi: String): Map<String, ByteArray>? {
    val found = mutableMapOf<String, ByteArray>()
    while (true) {
        val entry = zin.nextEntry ?: break
        val lib = vendorLibs.firstOrNull { entry.name == "lib/$abi/$it" }
        if (lib != null) found[lib] = zin.readBytes()
    }
    return found.takeIf { it.size == vendorLibs.size }
}

// One candidate file: a universal/ABI-split APK (lib/<abi>/... directly) or a bundle
// (.xapk/.apkm/.apks) holding split APKs as inner zip entries. Throws if the file is not a zip.
fun readVendorLibs(candidate: File, abi: String): Map<String, ByteArray>? {
    ZipFile(candidate).use { zf ->
        val direct = vendorLibs.associateWith { zf.getEntry("lib/$abi/$it") }
        if (direct.values.all { it != null }) {
            return direct.mapValues { (_, e) -> zf.getInputStream(e!!).use { s -> s.readBytes() } }
        }
        val split = "split_config." + abi.replace('-', '_') + ".apk"
        val inner = zf.entries().asSequence().filter { it.name.endsWith(".apk") }.toList()
            .sortedBy { if (it.name.substringAfterLast('/') == split) 0 else 1 }
        for (e in inner) {
            val libs = ZipInputStream(zf.getInputStream(e)).use { readVendorLibsFromStream(it, abi) }
            if (libs != null) return libs
        }
    }
    return null
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

val extractVendorNatives by tasks.registering {
    group = "vendor"
    description = "Extracts the vendor P2P .so files from the user's local Yi Home APK into app/build."
    val candidateTree = fileTree(vendorApkDir) {
        include("*.apk", "*.apkm", "*.xapk", "*.apks", "extracted/split_config.*.apk")
    }
    inputs.file(vendorManifest)
    inputs.property("vendorApk", vendorApkProp ?: "")
    inputs.property("vendorApkDir", vendorApkDir.absolutePath)
    inputs.property("vendorNativesAccept", vendorNativesAccept)
    inputs.files(candidateTree).withPropertyName("vendorCandidates")
    if (vendorApkProp != null) inputs.files(file(vendorApkProp)).withPropertyName("vendorApkFile")
    outputs.dir(vendorOut)
    outputs.file(vendorStatus)
    doLast {
        vendorOut.deleteRecursively()
        vendorOut.mkdirs()
        val expected = vendorManifest.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val parts = line.split(Regex("\\s+"))
                parts[1] to (parts[0].uppercase() to parts.getOrNull(2)?.toLongOrNull())
            }
        val explicit = vendorApkProp?.let { file(it) }
        val treeFiles = candidateTree.files.sortedBy { it.name }
        val statusLines = mutableListOf<String>()
        val sourceLines = mutableListOf<String>()
        val observed = mutableListOf<String>()
        var missing = false
        var mismatch = false
        for (abi in vendorAbis) {
            val preferred = File(vendorApkDir, "extracted/split_config." + abi.replace('-', '_') + ".apk")
            val ordered = (listOfNotNull(explicit, preferred) + treeFiles)
                .distinctBy { it.absoluteFile.normalize() }
                .filter { it.isFile }
            var libs: Map<String, ByteArray>? = null
            var source: File? = null
            for (candidate in ordered) {
                libs = try {
                    readVendorLibs(candidate, abi)
                } catch (e: Exception) {
                    logger.info("vendor natives: skip ${candidate.name} (${e.javaClass.simpleName})")
                    null
                }
                if (libs != null) { source = candidate; break }
            }
            if (libs == null || source == null) {
                missing = true
                vendorLibs.forEach { statusLines += "$abi/$it sha256=- bytes=0 expected=missing" }
                continue
            }
            sourceLines += "SOURCE=${source.name} abi=$abi"
            val abiDir = File(vendorOut, abi).apply { mkdirs() }
            for (lib in vendorLibs) {
                val bytes = libs.getValue(lib)
                File(abiDir, lib).writeBytes(bytes)
                val hex = sha256Hex(bytes)
                val key = "$abi/$lib"
                val pin = expected[key]
                val match = pin != null && pin.first == hex && (pin.second == null || pin.second == bytes.size.toLong())
                if (!match) mismatch = true
                statusLines += "$key sha256=$hex bytes=${bytes.size} expected=${if (match) "match" else "DIFF"}"
                observed += "$hex  $key  ${bytes.size}"
            }
        }
        val status = when {
            missing -> "MISSING"
            mismatch && vendorNativesAccept -> {
                val header = vendorManifest.readLines().filter { it.startsWith("#") }
                vendorManifest.writeText((header + observed).joinToString("\n") + "\n")
                logger.lifecycle("vendor natives: manifest re-pinned with observed hashes (-PvendorNativesAccept=true); review the diff")
                "OK"
            }
            mismatch -> "MISMATCH"
            else -> "OK"
        }
        vendorStatus.parentFile.mkdirs()
        vendorStatus.writeText((listOf("STATUS=$status") + statusLines + sourceLines).joinToString("\n") + "\n")
        if (status != "OK") {
            logger.warn("vendor natives: STATUS=$status (APK builds will fail in verifyVendorNatives; JVM tests are unaffected)")
        }
    }
}
// END_BLOCK_EXTRACT_VENDOR_NATIVES

// START_BLOCK_VERIFY_VENDOR_NATIVES
val verifyVendorNatives by tasks.registering {
    group = "vendor"
    description = "Fails APK builds when the vendor .so files are missing or differ from app/vendor-natives.sha256."
    dependsOn(extractVendorNatives)
    doLast {
        val lines = if (vendorStatus.isFile) vendorStatus.readLines() else emptyList()
        when (lines.firstOrNull()) {
            "STATUS=OK" -> logger.lifecycle("vendor natives: OK (8 libs, manifest match)")
            "STATUS=MISMATCH" -> {
                val diffs = lines.filter { it.endsWith("expected=DIFF") }
                    .joinToString("\n") { "  DIFF " + it.substringBefore(" expected=") }
                throw GradleException(
                    "Vendor native libs differ from app/vendor-natives.sha256 (another Yi Home build?):\n" + diffs +
                        "\nre-run with -PvendorNativesAccept=true to pin these hashes after review.",
                )
            }
            else -> throw GradleException(
                "Vendor native libs missing: put your own Yi Home APK (or its split_config.arm64_v8a.apk) under _apk/ " +
                    "or pass -PvendorApk=<path>; see HANDOFF.md section 3. Vendor binaries are never committed.",
            )
        }
    }
}

android.sourceSets["main"].jniLibs.srcDir(vendorOut)
tasks.named("preBuild") { dependsOn(extractVendorNatives) }
// EXACT task names only: a prefix match would also catch assembleDebugUnitTest and break JVM tests on a fresh clone.
val verifyHook = Regex("(assemble|install|bundle)(Debug|Release)|merge(Debug|Release)JniLibFolders")
tasks.matching { verifyHook.matches(it.name) }.configureEach { dependsOn(verifyVendorNatives) }
// END_BLOCK_VERIFY_VENDOR_NATIVES

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51")
    ksp("com.google.dagger:hilt-android-compiler:2.51")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Security (encrypted prefs)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // ---- Unit tests (JUnit5 + Mockk + Robolectric) ----
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("io.mockk:mockk-android:1.13.9")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // ---- Instrumented tests (Espresso + Compose) ----
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("io.mockk:mockk-android:1.13.9")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Kaspersky MITM CA must be trusted by the test JVM so Robolectric can fetch
    // the android-all-instrumented jar over HTTPS from Maven Central.
    jvmArgs(
        "-Djavax.net.ssl.trustStore=C:/Users/pvanyushkin/Android/Sdk/cacerts.jks",
        "-Djavax.net.ssl.trustStorePassword=changeit",
        "-Djavax.net.ssl.trustStoreType=JKS",
    )
    // SEC-01: opt-in sweep of a pulled device log (RealLogScanTest), -PredactionLogFile=<abs path>.
    val redactionLogFile = (project.findProperty("redactionLogFile") as String?).orEmpty()
    systemProperty("redaction.logFile", redactionLogFile)
    if (redactionLogFile.isNotBlank()) outputs.upToDateWhen { false }
}