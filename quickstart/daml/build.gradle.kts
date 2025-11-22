// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(Deps.transcode.plugin)
        classpath("org.apache.commons:commons-compress:1.27.1")
    }
}

import com.digitalasset.transcode.codegen.java.gradle.JavaCodegenTask

plugins {
    id("base")
    id("de.undercouch.download") version "5.6.0"
}

// Use manual drain+credit bindings by default (skip Transcode codegen unless explicitly enabled)
val enableClearportxCodegen = providers.environmentVariable("CLEARPORTX_ENABLE_CODEGEN")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

tasks.register<Exec>("compileDaml") {
    dependsOn("verifyDamlSdkVersion")
    val sdkVars = computeSdkVariables()
    val requiredVersion = sdkVars["damlSdkVersion"] as String
    commandLine("daml", "damlc", "build", "--all")
      .setEnvironment(mapOf("DAML_SDK_VERSION" to requiredVersion))
}

tasks.register<Exec>("testDaml") {
    dependsOn("verifyDamlSdkVersion")
    val sdkVars = computeSdkVariables()
    val requiredVersion = sdkVars["damlSdkVersion"] as String
    commandLine("daml", "test", "--project-root", "licensing-tests")
      .setEnvironment(mapOf("DAML_SDK_VERSION" to requiredVersion))
}

tasks.register<com.digitalasset.transcode.codegen.java.gradle.JavaCodegenTask>("codeGen") {
    dar.from("$projectDir/licensing/.daml/dist/quickstart-licensing-0.0.1.dar")
    destination = file("$rootDir/backend/build/generated-daml-bindings")
    dependsOn("compileDaml")
}

tasks.register<com.digitalasset.transcode.codegen.java.gradle.JavaCodegenTask>("codeGenClearportX") {
    // Always prefer the latest GV DAR from daml-prod, skip if none present
    val prodDist = file("$rootDir/clearportx/daml-prod/.daml/dist")
    val latestGvDar: File? = if (prodDist.exists()) {
        prodDist.listFiles()?.filter { it.isFile && it.name.matches(Regex("clearportx-amm-production-gv-.*\\.dar")) }
            ?.maxByOrNull { it.lastModified() }
    } else null
    val fallbackGv = file("$rootDir/clearportx/artifacts/devnet/clearportx-amm-gv.dar")
    val chosenGv: File? = latestGvDar ?: (if (fallbackGv.exists()) fallbackGv else null)
    onlyIf {
        val enabled = enableClearportxCodegen.get()
        if (!enabled) {
            println("⚠️  Skipping ClearportX codegen (manual drain+credit bindings in use). Set CLEARPORTX_ENABLE_CODEGEN=true to re-enable Transcode.")
        }
        enabled && chosenGv != null
    }
    if (chosenGv != null) {
        println("Using GV DAR for codegen: ${chosenGv.absolutePath}")
        dar.from(chosenGv.absolutePath)
    }
    destination = file("$rootDir/backend/build/generated-daml-bindings")
}

tasks.register<com.digitalasset.transcode.codegen.java.gradle.JavaCodegenTask>("codeGenClearportXProd") {
    // Generate bindings for non-GV production package to satisfy legacy imports if present
    val prodDist = file("$rootDir/clearportx/daml-prod/.daml/dist")
    val latestProdDar: File? = if (prodDist.exists()) {
        prodDist.listFiles()?.filter { it.isFile && it.name.matches(Regex("clearportx-amm-production-[0-9].*\\.dar")) && !it.name.contains("-gv-") }
            ?.maxByOrNull { it.lastModified() }
    } else null
    // Remove frozen DAR fallback - only use latest prod DAR
    val chosen: File? = latestProdDar
    onlyIf { chosen != null }
    if (chosen != null) {
        println("Using PROD DAR for codegen: ${chosen.absolutePath}")
        dar.from(chosen.absolutePath)
    }
    destination = file("$rootDir/backend/build/generated-daml-bindings")
}

tasks.named("build") {
    // Keep licensing codegen, do not force ClearportX codegen during normal build
    dependsOn("codeGen")
    if (enableClearportxCodegen.get()) {
        // Also generate ClearportX (gv-compat) bindings and run it AFTER licensing so its Daml.java wins
        dependsOn("codeGenClearportX")
        tasks.named("codeGenClearportX") {
            mustRunAfter("codeGen")
        }
    } else {
        doFirst {
            println("✅ ClearportX manual bindings overlay active (transcode codegen disabled).")
        }
    }
}

// Ensure gv-compat codegen runs after licensing codegen so aggregated Daml.java contains AMM when enabled
tasks.named("codeGenClearportX") {
    mustRunAfter("codeGen")
}
// Helper function to compute SDK variables
fun computeSdkVariables(): Map<String, Any> {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    val isWindows = osName.contains("win")
    val isMac = osName.contains("mac")
    val isUnix = osName.contains("nix") || osName.contains("nux") || osName.contains("aix")

    val sdkOs = when {
        isWindows -> "windows-x86_64"
        isMac -> "macos-x86_64"
        isUnix -> when {
            osArch.contains("arm") || osArch.contains("aarch64") -> "linux-aarch64"
            else -> "linux-x86_64"
        }
        else -> throw Exception("Unsupported OS: $osName")
    }

    val damlSdkRuntimeVersion = VersionFiles.dotenv["DAML_RUNTIME_VERSION"] as String
    val damlSdkVersion = VersionFiles.damlYamlSdk
    val sdkArchive = "daml-sdk-$damlSdkRuntimeVersion-$sdkOs-ee.tar.gz"
    val sdkUrl = "https://digitalasset.jfrog.io/artifactory/sdk-ee/$damlSdkRuntimeVersion/$sdkArchive"
    val sdkDir = file("$projectDir/.sdk")
    val sdkArchiveFile = file("${sdkDir}/${sdkArchive}")
    val extractedDir = file("${sdkDir}/extracted")

    return mapOf(
        "damlSdkRuntimeVersion" to damlSdkRuntimeVersion,
        "damlSdkVersion" to damlSdkVersion,
        "sdkOs" to sdkOs,
        "sdkArchive" to sdkArchive,
        "sdkUrl" to sdkUrl,
        "sdkDir" to sdkDir,
        "sdkArchiveFile" to sdkArchiveFile,
        "extractedDir" to extractedDir
    )
}

// Task to download the SDK archive
tasks.register<de.undercouch.gradle.tasks.download.Download>("fetchDamlSdk") {
    val sdkVars = computeSdkVariables()
    val sdkUrl = sdkVars["sdkUrl"] as String
    val sdkArchiveFile = sdkVars["sdkArchiveFile"] as File
    val sdkDir = sdkVars["sdkDir"] as File

    src(sdkUrl)
    dest(sdkArchiveFile)
    overwrite(false)
    onlyIfModified(true)

    doFirst {
        sdkDir.mkdirs()
        val (username, password) = Credentials.execFromNetRc("digitalasset.jfrog.io")
        username(username)
        password(password)
    }

    doLast {
        println("Downloaded $sdkUrl to ${sdkArchiveFile.absolutePath}")
    }
}

// Task to unpack the SDK archive
tasks.register<UnpackTarGzTask>("unpackDamlSdk") {
    dependsOn("fetchDamlSdk")
    val sdkVars = computeSdkVariables()
    archiveFile = sdkVars["sdkArchiveFile"] as File
    destinationDir = sdkVars["extractedDir"] as File
}

// Task to run the install script
tasks.register<Exec>("installDamlSdk") {
    dependsOn("unpackDamlSdk")
    val sdkVars = computeSdkVariables()
    val sdkDir = sdkVars["sdkDir"] as File
    val extractedDir = sdkVars["extractedDir"] as File
    val damlSdkRuntimeVersion = sdkVars["damlSdkRuntimeVersion"] as String
    val damlSdkVersion = sdkVars["damlSdkVersion"] as String
    val sdkOs = sdkVars["sdkOs"] as String

    doFirst {
        val topLevelDirs = extractedDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (topLevelDirs.isEmpty()) {
            throw Exception("No directories found in $extractedDir")
        }
        workingDir = topLevelDirs.first()
    }
    commandLine(
        if (sdkOs == "windows-x86_64") "./install.bat" else "./install.sh",
        "--install-with-custom-version",
        damlSdkVersion
    )
    doLast {
        println("Installed Daml SDK runtime $damlSdkRuntimeVersion as $damlSdkVersion")
        println("Cleaning up downloaded files")
        sdkDir.deleteRecursively()
    }
}

// Task to ensure the right Daml SDK version is installed
tasks.register("verifyDamlSdkVersion") {
    val sdkVars = computeSdkVariables()
    val requiredVersion = sdkVars["damlSdkVersion"] as String

    doLast {
        val output = java.io.ByteArrayOutputStream()
        exec {
            commandLine = listOf("daml", "version")
            standardOutput = output
            isIgnoreExitValue = true
        }

        val versionLine = output.toString()
            .lineSequence()
            .firstOrNull { it.contains(requiredVersion) }
            ?.trim()

        if (versionLine == null) {
            throw GradleException(
                """
                ❌ Could not find required DAML SDK version:
                   Required:  $requiredVersion

                💡 Please try running: make install-daml-sdk
                """.trimIndent()
            )
        } else {
            println("✅ DAML SDK version $requiredVersion is installed.")
        }

    }
}
