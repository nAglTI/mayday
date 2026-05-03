package org.debs.mayday.core.data.packageinfo

import net.dongliu.apk.parser.ApkFile
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

class AppSemanticAnalyzerPerformanceTest {

    @Test
    fun apkCorpusPerformanceBaseline() {
        assumeTrue(
            "Enable with -Dsemantic.performanceScan=true or SEMANTIC_PERFORMANCE_SCAN=true",
            property("semantic.performanceScan") == "true",
        )

        val inputRoot = File(
            property("semantic.apkCorpusRoot")
                ?: defaultTargetedCorpusRoot().path,
        ).canonicalFile
        assertTrue("APK corpus root is missing: $inputRoot", inputRoot.isDirectory)

        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val outputRoot = File(
            property("semantic.performanceOutput")
                ?: File(defaultProjectRoot(), "docs/internalDocs/semantic_scan_performance/$timestamp").path,
        ).canonicalFile
        outputRoot.mkdirs()

        val workerCount = property("semantic.performanceWorkers")
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: Runtime.getRuntime().availableProcessors().coerceAtMost(4).coerceAtLeast(1)
        val maxPackages = property("semantic.maxPackages")?.toIntOrNull()?.takeIf { it > 0 }
        val packageFilter = property("semantic.packages")
            ?.split(',', ';', ' ', '\n', '\r', '\t')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()

        val inputs = corpusScanInputs(inputRoot)
            .let { packages ->
                if (packageFilter.isEmpty()) {
                    packages
                } else {
                    packages.filter { input -> input.packageName in packageFilter }
                }
            }
            .let { packages -> maxPackages?.let(packages::take) ?: packages }
        assertTrue("No APK groups found under $inputRoot for filter=$packageFilter", inputs.isNotEmpty())

        println("Semantic performance input: packages=${inputs.size}, workers=$workerCount, output=$outputRoot")

        val completed = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(workerCount)
        val futures: List<Future<PerformanceScanResult>> = inputs.map { input ->
            executor.submit(
                Callable {
                    val startedAt = System.nanoTime()
                    val analysis = AppSemanticAnalyzer().analyze(
                        packageName = input.packageName,
                        versionCode = null,
                        apkPaths = input.apkFiles.map(File::getAbsolutePath),
                    )
                    val durationMs = elapsedMs(startedAt)
                    val index = completed.incrementAndGet()
                    println(
                        "[$index/${inputs.size}] ${input.bucket}/${input.packageName} " +
                            "duration=${durationMs}ms methods=${analysis.methodsAnalyzed}",
                    )
                    PerformanceScanResult(
                        bucket = input.bucket,
                        packageName = input.packageName,
                        apkCount = input.apkFiles.size,
                        totalApkBytes = input.apkFiles.sumOf(File::length),
                        durationMs = durationMs,
                        methodsAnalyzed = analysis.methodsAnalyzed,
                        cfgNodeCount = analysis.cfgNodeCount,
                        cfgEdgeCount = analysis.cfgEdgeCount,
                        dfgEdgeCount = analysis.dfgEdgeCount,
                        signalCount = analysis.signals.size,
                        riskLevel = analysis.riskLevel.name,
                        score = analysis.score,
                    )
                },
            )
        }

        executor.shutdown()
        val results = futures.map(Future<PerformanceScanResult>::get)
        executor.awaitTermination(1, TimeUnit.MINUTES)

        File(outputRoot, "performance_summary.csv").writeText(performanceCsv(results))
        File(outputRoot, "performance_summary.md").writeText(
            performanceMarkdown(
                inputRoot = inputRoot,
                outputRoot = outputRoot,
                workerCount = workerCount,
                results = results,
            ),
        )
        println("Semantic performance CSV: ${File(outputRoot, "performance_summary.csv").canonicalPath}")
        println("Semantic performance report: ${File(outputRoot, "performance_summary.md").canonicalPath}")
    }

    private fun corpusScanInputs(inputRoot: File): List<PerformanceScanInput> {
        val inputs = mutableListOf<PerformanceScanInput>()

        fun addCategoryDirectory(
            bucket: String,
            dir: File,
        ) {
            if (!dir.isDirectory) return
            val apks = dir.listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
                .orEmpty()
                .sortedBy(File::getName)
            apks.forEach { apk ->
                inputs += PerformanceScanInput(
                    bucket = bucket,
                    packageName = apk.packageNameFromManifest() ?: apk.nameWithoutExtension,
                    apkFiles = listOf(apk),
                )
            }

            dir.listFiles(File::isDirectory)
                .orEmpty()
                .sortedBy(File::getName)
                .forEach { packageDir ->
                    val apkFiles = packageDir.walkTopDown()
                        .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                        .sortedBy { it.name }
                        .toList()
                    if (apkFiles.isNotEmpty()) {
                        inputs += PerformanceScanInput(
                            bucket = bucket,
                            packageName = packageDir.name,
                            apkFiles = apkFiles,
                        )
                    }
                }
        }

        addCategoryDirectory("critical", File(inputRoot, "critical"))
        addCategoryDirectory("research", File(inputRoot, "research"))
        addCategoryDirectory("false-positive", File(inputRoot, "false-positive"))
        addCategoryDirectory("false_positive", File(inputRoot, "false_positive"))
        addCategoryDirectory("false-negative", File(inputRoot, "false-negative"))
        addCategoryDirectory("false_negative", File(inputRoot, "false_negative"))
        addCategoryDirectory("black", File(inputRoot, "black"))
        addCategoryDirectory("grey", File(inputRoot, "grey"))
        addCategoryDirectory("gray", File(inputRoot, "gray"))
        addCategoryDirectory("white", File(inputRoot, "white"))
        addCategoryDirectory("unknown", File(inputRoot, "unknown"))

        return inputs.sortedWith(
            compareBy<PerformanceScanInput> { it.bucket }
                .thenBy { it.packageName },
        )
    }

    private fun performanceCsv(results: List<PerformanceScanResult>): String = buildString {
        appendLine(
            listOf(
                "bucket",
                "package",
                "risk_level",
                "score",
                "duration_ms",
                "apk_count",
                "total_apk_bytes",
                "apk_mb",
                "methods_analyzed",
                "methods_per_second",
                "microseconds_per_method",
                "cfg_node_count",
                "cfg_edge_count",
                "dfg_edge_count",
                "signal_count",
            ).joinToString(","),
        )
        results
            .sortedByDescending(PerformanceScanResult::durationMs)
            .forEach { result ->
                appendLine(
                    listOf(
                        result.bucket,
                        result.packageName,
                        result.riskLevel,
                        result.score.toString(),
                        result.durationMs.toString(),
                        result.apkCount.toString(),
                        result.totalApkBytes.toString(),
                        "%.2f".formatUs(result.totalApkBytes.toDouble() / BYTES_IN_MIB),
                        result.methodsAnalyzed.toString(),
                        "%.2f".formatUs(result.methodsPerSecond),
                        "%.2f".formatUs(result.microsecondsPerMethod),
                        result.cfgNodeCount.toString(),
                        result.cfgEdgeCount.toString(),
                        result.dfgEdgeCount.toString(),
                        result.signalCount.toString(),
                    ).joinToString(",") { it.csv() },
                )
            }
    }

    private fun performanceMarkdown(
        inputRoot: File,
        outputRoot: File,
        workerCount: Int,
        results: List<PerformanceScanResult>,
    ): String {
        val totalDurationMs = results.sumOf(PerformanceScanResult::durationMs)
        val totalMethods = results.sumOf(PerformanceScanResult::methodsAnalyzed)
        val totalBytes = results.sumOf(PerformanceScanResult::totalApkBytes)
        return buildString {
            appendLine("# Semantic Analyzer Performance Baseline")
            appendLine()
            appendLine("Generated: ${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}")
            appendLine()
            appendLine("- input: `${inputRoot.canonicalPath}`")
            appendLine("- output: `${outputRoot.canonicalPath}`")
            appendLine("- package groups: ${results.size}")
            appendLine("- workers: $workerCount")
            appendLine("- summed package duration: ${"%.2f".formatUs(totalDurationMs / 60000.0)} min")
            appendLine("- summed methods: $totalMethods")
            appendLine("- summed APK size: ${"%.1f".formatUs(totalBytes.toDouble() / BYTES_IN_MIB)} MiB")
            appendLine("- weighted methods/sec: ${"%.2f".formatUs(totalMethods / (totalDurationMs / 1000.0))}")
            appendLine()
            appendLine("| Package | Bucket | Risk | Duration, s | APK MiB | Methods | Methods/s | us/method |")
            appendLine("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |")
            results
                .sortedByDescending(PerformanceScanResult::durationMs)
                .forEach { result ->
                    appendLine(
                        "| `${result.packageName}` | ${result.bucket} | ${result.riskLevel} | " +
                            "${"%.1f".formatUs(result.durationMs / 1000.0)} | " +
                            "${"%.1f".formatUs(result.totalApkBytes.toDouble() / BYTES_IN_MIB)} | " +
                            "${result.methodsAnalyzed} | " +
                            "${"%.0f".formatUs(result.methodsPerSecond)} | " +
                            "${"%.2f".formatUs(result.microsecondsPerMethod)} |",
                    )
                }
        }
    }

    private fun File.packageNameFromManifest(): String? {
        return runCatching {
            ApkFile(this).use { apk -> apk.apkMeta.packageName }
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun defaultTargetedCorpusRoot(): File {
        return File(defaultProjectRoot(), "docs/internalDocs/testInstructions/targeted-corpus/20260503-174555")
    }

    private fun defaultProjectRoot(): File {
        return File(System.getProperty("user.dir") ?: ".").canonicalFile
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

    private fun String.csv(): String {
        val escaped = replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun String.formatUs(value: Double): String {
        return format(java.util.Locale.US, value)
    }

    private data class PerformanceScanInput(
        val bucket: String,
        val packageName: String,
        val apkFiles: List<File>,
    )

    private data class PerformanceScanResult(
        val bucket: String,
        val packageName: String,
        val apkCount: Int,
        val totalApkBytes: Long,
        val durationMs: Long,
        val methodsAnalyzed: Int,
        val cfgNodeCount: Int,
        val cfgEdgeCount: Int,
        val dfgEdgeCount: Int,
        val signalCount: Int,
        val riskLevel: String,
        val score: Int,
    ) {
        val methodsPerSecond: Double
            get() = if (durationMs > 0L) methodsAnalyzed / (durationMs / 1000.0) else 0.0

        val microsecondsPerMethod: Double
            get() = if (methodsAnalyzed > 0) (durationMs * 1000.0) / methodsAnalyzed else 0.0
    }

    private companion object {
        const val BYTES_IN_MIB = 1024.0 * 1024.0
    }
}
