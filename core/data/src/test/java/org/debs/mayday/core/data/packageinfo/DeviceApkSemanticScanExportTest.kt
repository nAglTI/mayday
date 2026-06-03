package org.debs.mayday.core.data.packageinfo

import net.dongliu.apk.parser.ApkFile
import org.debs.mayday.core.model.AppSemanticAnalysisResult
import org.debs.mayday.core.model.AppSemanticRiskBucket
import org.debs.mayday.core.model.AppSemanticSignal
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeviceApkSemanticScanExportTest {

    @Test
    fun scanExportedDeviceApks() {
        assumeTrue(
            "Enable with -Dsemantic.scanDeviceApks=true or -Dsemantic.scanApkCorpus=true",
            property("semantic.scanDeviceApks") == "true" || property("semantic.scanApkCorpus") == "true",
        )

        val inputRoot = File(
            property("semantic.apkCorpusRoot")
                ?: property("semantic.deviceApksRoot")
                ?: defaultCorpusRoot().path,
        ).canonicalFile
        assertTrue("Device APK root is missing: $inputRoot", inputRoot.isDirectory)

        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val outputRoot = File(
            property("semantic.scanOutput")
                ?: defaultOutputRoot(inputRoot, timestamp).path,
        ).canonicalFile
        val packageOutputRoot = File(outputRoot, "packages")
        packageOutputRoot.mkdirs()

        val workerCount = property("semantic.scanWorkers")
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: Runtime.getRuntime().availableProcessors().coerceAtMost(4).coerceAtLeast(1)
        val rescan = property("semantic.rescan") == "true"
        val maxPackages = property("semantic.maxPackages")?.toIntOrNull()?.takeIf { it > 0 }
        val packageFilter = property("semantic.packages")
            ?.split(',', ';', ' ', '\n', '\r', '\t')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()

        val allPackages = corpusScanInputs(inputRoot)
            .let { packages ->
                if (packageFilter.isEmpty()) {
                    packages
                } else {
                    packages.filter { input -> input.packageName in packageFilter }
                }
            }
            .let { packages -> maxPackages?.let(packages::take) ?: packages }
        val devices = allPackages
            .map(PackageScanInput::device)
            .distinctBy(DeviceScanInput::id)
            .sortedBy(DeviceScanInput::id)

        assertTrue("No APK files found under $inputRoot for filter=$packageFilter", allPackages.isNotEmpty())
        assertTrue("No APK groups found under $inputRoot", devices.isNotEmpty())
        println("Semantic scan input: groups=${devices.size}, packages=${allPackages.size}, workers=$workerCount, output=$outputRoot")

        val completed = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(workerCount)
        val futures: List<Future<String>> = allPackages.map { input ->
            executor.submit(
                Callable {
                    val resultPath = packageResultFile(packageOutputRoot, input)
                    if (resultPath.isFile && !rescan) {
                        val index = completed.incrementAndGet()
                        println("[$index/${allPackages.size}] cached ${input.device.label}/${input.packageName}")
                        return@Callable resultPath.readText()
                    }

                    val startedAt = System.nanoTime()
                    val json = runCatching {
                        val analysis = AppSemanticAnalyzer().analyze(
                            packageName = input.packageName,
                            versionCode = null,
                            apkPaths = input.apkFiles.map(File::getAbsolutePath),
                        )
                        packageResultJson(
                            input = input,
                            status = "scanned",
                            durationMs = elapsedMs(startedAt),
                            analysis = analysis,
                            error = null,
                        )
                    }.getOrElse { error ->
                        packageResultJson(
                            input = input,
                            status = "error",
                            durationMs = elapsedMs(startedAt),
                            analysis = null,
                            error = error,
                        )
                    }

                    resultPath.parentFile?.mkdirs()
                    resultPath.writeText(json)
                    val index = completed.incrementAndGet()
                    println("[$index/${allPackages.size}] ${input.device.label}/${input.packageName} -> ${compactStatus(json)}")
                    json
                },
            )
        }

        executor.shutdown()
        val packageJsons = futures.map(Future<String>::get)
        executor.awaitTermination(1, TimeUnit.MINUTES)

        val summaryJson = aggregateJson(
            inputRoot = inputRoot,
            outputRoot = outputRoot,
            workerCount = workerCount,
            devices = devices,
            packages = packageJsons,
        )
        outputRoot.mkdirs()
        File(outputRoot, "semantic_scan_results.json").writeText(summaryJson)
        File(outputRoot, "semantic_scan_summary.csv").writeText(summaryCsv(packageJsons))
        File(outputRoot, "semantic_scan_apks.csv").writeText(apkCsv(allPackages, packageJsons))

        println("Semantic scan complete: ${File(outputRoot, "semantic_scan_results.json").canonicalPath}")
        println("Semantic CSV summary: ${File(outputRoot, "semantic_scan_summary.csv").canonicalPath}")
        println("Semantic APK CSV: ${File(outputRoot, "semantic_scan_apks.csv").canonicalPath}")
    }

    private fun corpusScanInputs(inputRoot: File): List<PackageScanInput> {
        val inputs = mutableListOf<PackageScanInput>()
        fun device(
            id: String,
            label: String,
            dir: File,
            packageDirs: List<File>,
        ) = DeviceScanInput(
            id = id,
            dir = dir,
            serial = "",
            brand = label,
            model = "",
            includeSystem = false,
            packageDirs = packageDirs,
        )

        fun addCategoryDirectory(
            id: String,
            label: String,
            dir: File,
        ) {
            if (!dir.isDirectory) return
            val apks = dir.listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
                .orEmpty()
                .sortedBy(File::getName)
            val packageDirs = dir.listFiles(File::isDirectory)
                .orEmpty()
                .sortedBy(File::getName)
            val scanDevice = device(id, label, dir, apks + packageDirs)
            apks.forEach { apk ->
                inputs += PackageScanInput(
                    device = scanDevice,
                    packageName = apk.packageNameFromManifest() ?: apk.nameWithoutExtension,
                    packageDir = dir,
                    apkFiles = listOf(apk),
                )
            }
            packageDirs.forEach { packageDir ->
                val apkFiles = packageDir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                    .sortedBy { it.name }
                    .toList()
                if (apkFiles.isNotEmpty()) {
                    inputs += PackageScanInput(
                        device = scanDevice,
                        packageName = packageDir.name,
                        packageDir = packageDir,
                        apkFiles = apkFiles,
                    )
                }
            }
        }

        fun addSplitPackageRoot(
            id: String,
            label: String,
            root: File,
        ) {
            if (!root.isDirectory) return
            val packageDirs = root.listFiles(File::isDirectory)
                .orEmpty()
                .sortedBy(File::getName)
            val scanDevice = device(id, label, root, packageDirs)
            packageDirs.forEach { packageDir ->
                val apkFiles = packageDir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                    .sortedBy { it.name }
                    .toList()
                if (apkFiles.isNotEmpty()) {
                    inputs += PackageScanInput(
                        device = scanDevice,
                        packageName = packageDir.name,
                        packageDir = packageDir,
                        apkFiles = apkFiles,
                    )
                }
            }
        }

        addCategoryDirectory("black", "black", File(inputRoot, "black"))
        addCategoryDirectory("grey", "grey", File(inputRoot, "grey"))
        addCategoryDirectory("white", "white", File(inputRoot, "white"))
        addCategoryDirectory("unknown", "unknown", File(inputRoot, "unknown"))
        addCategoryDirectory("gray", "gray", File(inputRoot, "gray"))
        addCategoryDirectory("gray_high", "gray/high", File(inputRoot, "gray/high"))
        addCategoryDirectory("gray_low", "gray/low", File(inputRoot, "gray/low"))
        addCategoryDirectory("critical", "critical", File(inputRoot, "critical"))
        addCategoryDirectory("research", "research", File(inputRoot, "research"))
        addCategoryDirectory("false_positive", "false-positive", File(inputRoot, "false-positive"))
        addCategoryDirectory("false_positive", "false_positive", File(inputRoot, "false_positive"))
        addCategoryDirectory("false_negative", "false-negative", File(inputRoot, "false-negative"))
        addCategoryDirectory("false_negative", "false_negative", File(inputRoot, "false_negative"))

        val splitRoot = File(inputRoot, "apks/apks")
        if (splitRoot.isDirectory) {
            addSplitPackageRoot("apks", "apks", splitRoot)
        }

        val categoryRoots = setOf(
            "black",
            "grey",
            "white",
            "unknown",
            "gray",
            "critical",
            "research",
            "false-positive",
            "false_positive",
            "false-negative",
            "false_negative",
        )
        inputRoot.listFiles(File::isDirectory)
            .orEmpty()
            .filterNot { dir -> dir.name in categoryRoots }
            .forEach { deviceDir ->
                addSplitPackageRoot(
                    id = deviceDir.name,
                    label = deviceDir.name,
                    root = File(deviceDir, "apks"),
                )
            }

        val rootPackageDirs = inputRoot.listFiles(File::isDirectory)
            .orEmpty()
            .filter { dir ->
                dir.name !in categoryRoots &&
                    !File(dir, "apks").isDirectory &&
                    dir.walkTopDown().any { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            }
        if (rootPackageDirs.isNotEmpty()) {
            addSplitPackageRoot("root", "root", inputRoot)
        }

        return inputs.sortedWith(
            compareBy<PackageScanInput> { it.device.id }
                .thenBy { it.packageName }
                .thenBy { it.packageDir.name },
        )
    }

    private fun defaultCorpusRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".").canonicalFile
        return listOf(
            File(userDir, "analize/apk"),
            File(userDir, "../../analize/apk"),
            File(userDir, "../analize/apk"),
            File(userDir, "../../../analize/apk"),
        ).firstOrNull(File::isDirectory)
            ?: File(userDir, "../../analize/apk")
    }

    private fun defaultOutputRoot(
        inputRoot: File,
        timestamp: String,
    ): File {
        val projectRoot = inputRoot.parentFile?.parentFile
        return File(projectRoot ?: File(System.getProperty("user.dir") ?: "."), "build/semantic_scan_results/$timestamp")
    }

    private fun File.packageNameFromManifest(): String? {
        return runCatching {
            ApkFile(this).use { apk -> apk.apkMeta.packageName }
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun packageResultFile(
        outputRoot: File,
        input: PackageScanInput,
    ): File = File(File(outputRoot, input.device.id.safeFileSegment()), "${input.packageName.safeFileSegment()}.json")

    private fun packageResultJson(
        input: PackageScanInput,
        status: String,
        durationMs: Long,
        analysis: AppSemanticAnalysisResult?,
        error: Throwable?,
    ): String {
        val apkBytes = input.apkFiles.sumOf(File::length)
        return buildString {
            append("{\n")
            append("  \"status\": ${status.json()},\n")
            append("  \"device_id\": ${input.device.id.json()},\n")
            append("  \"device_label\": ${input.device.label.json()},\n")
            append("  \"serial\": ${input.device.serial.json()},\n")
            append("  \"brand\": ${input.device.brand.json()},\n")
            append("  \"model\": ${input.device.model.json()},\n")
            append("  \"include_system\": ${input.device.includeSystem},\n")
            append("  \"package_name\": ${input.packageName.json()},\n")
            append("  \"apk_count\": ${input.apkFiles.size},\n")
            append("  \"total_apk_bytes\": $apkBytes,\n")
            append("  \"duration_ms\": $durationMs,\n")
            append("  \"apk_paths\": [\n")
            input.apkFiles.forEachIndexed { index, apk ->
                append("    ${apk.canonicalPath.json()}${if (index == input.apkFiles.lastIndex) "\n" else ",\n"}")
            }
            append("  ]")
            if (analysis != null) {
                append(",\n")
                append("  \"score\": ${analysis.score},\n")
                append("  \"risk_level\": ${analysis.riskLevel.name.json()},\n")
                append("  \"proof_confidence\": ${analysis.proofConfidence},\n")
                append("  \"proof_level\": ${analysis.proofLevel.name.json()},\n")
                append("  \"clean_score\": ${analysis.cleanScore},\n")
                append("  \"clean_proof_confidence\": ${analysis.cleanProofConfidence},\n")
                append("  \"clean_proof_level\": ${analysis.cleanProofLevel.name.json()},\n")
                append("  \"verdict_confidence\": ${analysis.verdictConfidence},\n")
                append("  \"verdict_level\": ${analysis.verdictLevel.name.json()},\n")
                append("  \"verdict_status\": ${analysis.verdictStatus.name.json()},\n")
                append("  \"methods_analyzed\": ${analysis.methodsAnalyzed},\n")
                append("  \"cfg_node_count\": ${analysis.cfgNodeCount},\n")
                append("  \"cfg_edge_count\": ${analysis.cfgEdgeCount},\n")
                append("  \"dfg_edge_count\": ${analysis.dfgEdgeCount},\n")
                append("  \"buckets\": {\n")
                append("    \"app_code\": ${analysis.appCodeRisk.bucketJson()},\n")
                append("    \"sdk_code\": ${analysis.sdkCodeRisk.bucketJson()},\n")
                append("    \"native_code\": ${analysis.nativeCodeRisk.bucketJson()},\n")
                append("    \"manifest\": ${analysis.manifestRisk.bucketJson()},\n")
                append("    \"cross_layer\": ${analysis.crossLayerRisk.bucketJson()}\n")
                append("  },\n")
                append("  \"signals\": [\n")
                analysis.signals.forEachIndexed { index, signal ->
                    append(signal.signalJson(indent = "    "))
                    append(if (index == analysis.signals.lastIndex) "\n" else ",\n")
                }
                append("  ]\n")
            } else if (error != null) {
                append(",\n")
                append("  \"score\": 0,\n")
                append("  \"risk_level\": \"ERROR\",\n")
                append("  \"error\": {\n")
                append("    \"type\": ${error::class.java.name.json()},\n")
                append("    \"message\": ${(error.message ?: "").json()}\n")
                append("  }\n")
            } else {
                append("\n")
            }
            append("}\n")
        }
    }

    private fun aggregateJson(
        inputRoot: File,
        outputRoot: File,
        workerCount: Int,
        devices: List<DeviceScanInput>,
        packages: List<String>,
    ): String {
        val generatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val levelCounts = packages.groupingBy { it.stringValue("risk_level") ?: "UNKNOWN" }.eachCount()
        return buildString {
            append("{\n")
            append("  \"generated_at\": ${generatedAt.json()},\n")
            append("  \"input_root\": ${inputRoot.canonicalPath.json()},\n")
            append("  \"output_root\": ${outputRoot.canonicalPath.json()},\n")
            append("  \"worker_count\": $workerCount,\n")
            append("  \"device_count\": ${devices.size},\n")
            append("  \"package_count\": ${packages.size},\n")
            append("  \"risk_level_counts\": ${levelCounts.mapJson()},\n")
            append("  \"devices\": [\n")
            devices.forEachIndexed { index, device ->
                append("    {\n")
                append("      \"id\": ${device.id.json()},\n")
                append("      \"label\": ${device.label.json()},\n")
                append("      \"serial\": ${device.serial.json()},\n")
                append("      \"brand\": ${device.brand.json()},\n")
                append("      \"model\": ${device.model.json()},\n")
                append("      \"include_system\": ${device.includeSystem},\n")
                append("      \"package_count\": ${device.packageDirs.size}\n")
                append("    }${if (index == devices.lastIndex) "\n" else ",\n"}")
            }
            append("  ],\n")
            append("  \"packages\": [\n")
            packages.forEachIndexed { index, json ->
                append(json.trim().prependIndent("    "))
                append(if (index == packages.lastIndex) "\n" else ",\n")
            }
            append("  ]\n")
            append("}\n")
        }
    }

    private fun summaryCsv(packageJsons: List<String>): String = buildString {
        appendLine(
            listOf(
                "device",
                "package",
                "status",
                "risk_level",
                "score",
                "proof_level",
                "proof_confidence",
                "clean_score",
                "clean_proof_level",
                "clean_proof_confidence",
                "verdict_status",
                "verdict_level",
                "verdict_confidence",
                "duration_ms",
                "apk_count",
                "total_apk_bytes",
                "methods_analyzed",
                "cfg_node_count",
                "cfg_edge_count",
                "dfg_edge_count",
                "signal_count",
                "top_signals",
            ).joinToString(","),
        )
        packageJsons.forEach { json ->
            val topSignals = Regex(""""title"\s*:\s*"((?:\\.|[^"])*)"""")
                .findAll(json)
                .take(6)
                .joinToString(" | ") { it.groupValues[1].jsonUnescape() }
            appendLine(
                listOf(
                    json.stringValue("device_label").orEmpty(),
                    json.stringValue("package_name").orEmpty(),
                    json.stringValue("status").orEmpty(),
                    json.stringValue("risk_level").orEmpty(),
                    json.numberValue("score"),
                    json.stringValue("proof_level").orEmpty(),
                    json.numberValue("proof_confidence"),
                    json.numberValue("clean_score"),
                    json.stringValue("clean_proof_level").orEmpty(),
                    json.numberValue("clean_proof_confidence"),
                    json.stringValue("verdict_status").orEmpty(),
                    json.stringValue("verdict_level").orEmpty(),
                    json.numberValue("verdict_confidence"),
                    json.numberValue("duration_ms"),
                    json.numberValue("apk_count"),
                    json.numberValue("total_apk_bytes"),
                    json.numberValue("methods_analyzed"),
                    json.numberValue("cfg_node_count"),
                    json.numberValue("cfg_edge_count"),
                    json.numberValue("dfg_edge_count"),
                    Regex(""""title"\s*:""").findAll(json).count().toString(),
                    topSignals,
                ).joinToString(",") { it.csv() },
            )
        }
    }

    private fun apkCsv(
        packages: List<PackageScanInput>,
        packageJsons: List<String>,
    ): String {
        val byKey = packageJsons.associateBy {
            "${it.stringValue("device_id").orEmpty()}:${it.stringValue("package_name").orEmpty()}"
        }
        return buildString {
            appendLine("device,package,risk_level,score,apk_path,apk_bytes")
            packages.forEach { input ->
                val json = byKey["${input.device.id}:${input.packageName}"]
                input.apkFiles.forEach { apk ->
                    appendLine(
                        listOf(
                            input.device.label,
                            input.packageName,
                            json?.stringValue("risk_level").orEmpty(),
                            json?.numberValue("score").orEmpty(),
                            apk.canonicalPath,
                            apk.length().toString(),
                        ).joinToString(",") { it.csv() },
                    )
                }
            }
        }
    }

    private fun AppSemanticRiskBucket.bucketJson(): String {
        return buildString {
            append("{")
            append("\"score\": $score, ")
            append("\"risk_level\": ${riskLevel.name.json()}, ")
            append("\"proof_confidence\": $proofConfidence, ")
            append("\"proof_level\": ${proofLevel.name.json()}, ")
            append("\"signal_count\": ${signals.size}")
            append("}")
        }
    }

    private fun AppSemanticSignal.signalJson(indent: String): String {
        return buildString {
            append("${indent}{\n")
            append("$indent  \"type\": ${type.name.json()},\n")
            append("$indent  \"title\": ${title.json()},\n")
            append("$indent  \"description\": ${description.json()},\n")
            append("$indent  \"confidence\": $confidence,\n")
            append("$indent  \"proof_confidence\": $proofConfidence,\n")
            append("$indent  \"proof_level\": ${proofLevel.name.json()},\n")
            append("$indent  \"proof_reason\": ${proofReason.json()},\n")
            append("$indent  \"scope\": ${scope.name.json()},\n")
            append("$indent  \"source\": ${source.name.json()},\n")
            append("$indent  \"evidence\": ${evidence.json()},\n")
            append("$indent  \"evidence_chain\": [\n")
            evidenceChain.forEachIndexed { index, evidence ->
                append("$indent    ${evidence.json()}${if (index == evidenceChain.lastIndex) "\n" else ",\n"}")
            }
            append("$indent  ]\n")
            append("$indent}")
        }
    }

    private fun compactStatus(json: String): String {
        val level = json.stringValue("risk_level") ?: "UNKNOWN"
        val score = json.numberValue("score")
        val duration = json.numberValue("duration_ms")
        val signalCount = Regex(""""title"\s*:""").findAll(json).count()
        return "$level score=$score signals=$signalCount duration=${duration}ms"
    }

    private fun elapsedMs(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun property(name: String): String? {
        return System.getProperty(name)?.takeIf(String::isNotBlank)
            ?: System.getenv(name.envKey())?.takeIf(String::isNotBlank)
    }

    private fun String.envKey(): String {
        return replace(Regex("""([a-z])([A-Z])"""), "$1_$2")
            .replace(Regex("""[^A-Za-z0-9]"""), "_")
            .uppercase()
    }

    private fun String.stringValue(key: String): String? = jsonString(key)

    private fun String.numberValue(key: String): String {
        return Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""").find(this)?.groupValues?.get(1).orEmpty()
    }

    private fun String.jsonString(key: String): String? {
        return Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"])*)"""")
            .find(this)
            ?.groupValues
            ?.get(1)
            ?.jsonUnescape()
    }

    private fun String.jsonBoolean(key: String): Boolean {
        return Regex(""""${Regex.escape(key)}"\s*:\s*(true|false)""")
            .find(this)
            ?.groupValues
            ?.get(1)
            ?.toBooleanStrictOrNull()
            ?: false
    }

    private fun String.json(): String = buildString {
        append('"')
        this@json.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }

    private fun String.jsonUnescape(): String {
        return replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    private fun String.csv(): String {
        val escaped = replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun String.safeFileSegment(): String = replace(Regex("""[^A-Za-z0-9._-]"""), "_").trim('_')

    private fun Map<String, Int>.mapJson(): String {
        return entries.sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, value) -> "${key.json()}: $value" }
    }

    private data class DeviceScanInput(
        val id: String,
        val dir: File,
        val serial: String,
        val brand: String,
        val model: String,
        val includeSystem: Boolean,
        val packageDirs: List<File>,
    ) {
        val label: String = listOf(brand, model).filter(String::isNotBlank).joinToString(" ").ifBlank { id }
    }

    private data class PackageScanInput(
        val device: DeviceScanInput,
        val packageName: String,
        val packageDir: File,
        val apkFiles: List<File>,
    )
}
