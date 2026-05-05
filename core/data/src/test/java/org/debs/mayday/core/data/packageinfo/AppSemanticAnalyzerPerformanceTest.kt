package org.debs.mayday.core.data.packageinfo

import net.dongliu.apk.parser.ApkFile
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
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
                    val gcBefore = GcStats.capture()
                    val memoryBefore = MemoryStats.capture()
                    val performanceTrace = AppSemanticAnalyzerPerformanceTrace()
                    val analysis = AppSemanticAnalyzer().analyze(
                        packageName = input.packageName,
                        versionCode = null,
                        apkPaths = input.apkFiles.map(File::getAbsolutePath),
                        performanceTrace = performanceTrace,
                    )
                    val memoryAfter = MemoryStats.capture()
                    val gcAfter = GcStats.capture()
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
                        gcCountDelta = gcAfter.collectionCount - gcBefore.collectionCount,
                        gcTimeMsDelta = gcAfter.collectionTimeMs - gcBefore.collectionTimeMs,
                        heapUsedBeforeBytes = memoryBefore.usedBytes,
                        heapUsedAfterBytes = memoryAfter.usedBytes,
                        heapTotalAfterBytes = memoryAfter.totalBytes,
                        heapMaxBytes = memoryAfter.maxBytes,
                        methodsAnalyzed = analysis.methodsAnalyzed,
                        cfgNodeCount = analysis.cfgNodeCount,
                        cfgEdgeCount = analysis.cfgEdgeCount,
                        dfgEdgeCount = analysis.dfgEdgeCount,
                        signalCount = analysis.signals.size,
                        riskLevel = analysis.riskLevel.name,
                        score = analysis.score,
                        stageMillis = performanceTrace.totalsByStageMillis(),
                        counters = performanceTrace.countersByName(),
                        dexEntries = performanceTrace.dexEntries,
                    )
                },
            )
        }

        executor.shutdown()
        val results = futures.map(Future<PerformanceScanResult>::get)
        executor.awaitTermination(1, TimeUnit.MINUTES)

        File(outputRoot, "performance_summary.csv").writeText(performanceCsv(results))
        File(outputRoot, "performance_stages.csv").writeText(performanceStagesCsv(results))
        File(outputRoot, "performance_counters.csv").writeText(performanceCountersCsv(results))
        File(outputRoot, "performance_dex_entries.csv").writeText(performanceDexEntriesCsv(results))
        File(outputRoot, "performance_summary.md").writeText(
            performanceMarkdown(
                inputRoot = inputRoot,
                outputRoot = outputRoot,
                workerCount = workerCount,
                results = results,
            ),
        )
        println("Semantic performance CSV: ${File(outputRoot, "performance_summary.csv").canonicalPath}")
        println("Semantic performance stages CSV: ${File(outputRoot, "performance_stages.csv").canonicalPath}")
        println("Semantic performance counters CSV: ${File(outputRoot, "performance_counters.csv").canonicalPath}")
        println("Semantic performance DEX entries CSV: ${File(outputRoot, "performance_dex_entries.csv").canonicalPath}")
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
                "gc_count_delta",
                "gc_time_ms_delta",
                "heap_used_before_bytes",
                "heap_used_after_bytes",
                "heap_used_delta_bytes",
                "heap_total_after_bytes",
                "heap_max_bytes",
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
                "dex_entry_count",
                "dex_class_count",
                "dex_method_count_total",
                "dex_method_count_with_implementation",
                "summary_methods_visited",
                "summary_instructions_visited",
                "summary_iterations_executed",
                "summary_iterations_changed",
                "summary_iterations_no_change",
                "final_methods_analyzed_counter",
                "final_methods_skipped_sdk_infra",
                "instructions_visited_final",
                "invoke_instructions_final",
                "handle_invoke_calls",
                "handle_invoke_skipped_semantics_final",
                "handle_invoke_nanos_final",
                "instruction_evidence_builds_final",
                "register_list_calls_final",
                "register_reads_summary",
                "register_reads_summary_constructor",
                "register_list_objects_built_final",
                "opcode_key_calls_summary",
                "opcode_key_calls_summary_constructor",
                "opcode_key_calls_final",
                "argument_tags_built_final",
                "argument_strings_built_final",
                "argument_ints_built_final",
                "argument_tags_by_index_built_final",
                "method_signature_with_arguments_calls_final",
                "method_signature_with_arguments_nanos_final",
                "method_call_candidates_final",
                "method_call_objects_retained_final",
                "method_calls_discarded_final",
                "method_calls_discarded_platform_final",
                "method_calls_discarded_blank_target_final",
                "methods_with_cfg",
                "facts_count",
                "method_calls_count",
                "native_bytes_scanned",
                "native_strings_seen",
                "native_evidence_strings_built",
                "native_symbol_texts_retained",
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
                        result.gcCountDelta.toString(),
                        result.gcTimeMsDelta.toString(),
                        result.heapUsedBeforeBytes.toString(),
                        result.heapUsedAfterBytes.toString(),
                        (result.heapUsedAfterBytes - result.heapUsedBeforeBytes).toString(),
                        result.heapTotalAfterBytes.toString(),
                        result.heapMaxBytes.toString(),
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
                        result.counterValue("dex_entry_count").toString(),
                        result.counterValue("dex_class_count").toString(),
                        result.counterValue("dex_method_count_total").toString(),
                        result.counterValue("dex_method_count_with_implementation").toString(),
                        result.counterValue("summary_methods_visited").toString(),
                        result.counterValue("summary_instructions_visited").toString(),
                        result.counterValue("summary_iterations_executed").toString(),
                        result.counterValue("summary_iterations_changed").toString(),
                        result.counterValue("summary_iterations_no_change").toString(),
                        result.counterValue("final_methods_analyzed").toString(),
                        result.counterValue("final_methods_skipped_sdk_infra").toString(),
                        result.counterValue("instructions_visited_final").toString(),
                        result.counterValue("invoke_instructions_final").toString(),
                        result.counterValue("handle_invoke_calls").toString(),
                        result.counterValue("handle_invoke_skipped_semantics_final").toString(),
                        result.counterValue("handle_invoke_nanos_final").toString(),
                        result.counterValue("instruction_evidence_builds_final").toString(),
                        result.counterValue("register_list_calls_final").toString(),
                        result.counterValue("register_reads_summary").toString(),
                        result.counterValue("register_reads_summary_constructor").toString(),
                        result.counterValue("register_list_objects_built_final").toString(),
                        result.counterValue("opcode_key_calls_summary").toString(),
                        result.counterValue("opcode_key_calls_summary_constructor").toString(),
                        result.counterValue("opcode_key_calls_final").toString(),
                        result.counterValue("argument_tags_built_final").toString(),
                        result.counterValue("argument_strings_built_final").toString(),
                        result.counterValue("argument_ints_built_final").toString(),
                        result.counterValue("argument_tags_by_index_built_final").toString(),
                        result.counterValue("method_signature_with_arguments_calls_final").toString(),
                        result.counterValue("method_signature_with_arguments_nanos_final").toString(),
                        result.counterValue("method_call_candidates_final").toString(),
                        result.counterValue("method_call_objects_retained_final").toString(),
                        result.counterValue("method_calls_discarded_final").toString(),
                        result.counterValue("method_calls_discarded_platform_final").toString(),
                        result.counterValue("method_calls_discarded_blank_target_final").toString(),
                        result.counterValue("methods_with_cfg").toString(),
                        result.counterValue("facts_count").toString(),
                        result.counterValue("method_calls_count").toString(),
                        result.counterValue("native_bytes_scanned").toString(),
                        result.counterValue("native_strings_seen").toString(),
                        result.counterValue("native_evidence_strings_built").toString(),
                        result.counterValue("native_symbol_texts_retained").toString(),
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
        val totalGcCount = results.sumOf(PerformanceScanResult::gcCountDelta)
        val totalGcTimeMs = results.sumOf(PerformanceScanResult::gcTimeMsDelta)
        val maxHeapUsedAfterBytes = results.maxOfOrNull(PerformanceScanResult::heapUsedAfterBytes) ?: 0L
        fun counterTotal(name: String): Long = results.sumOf { result -> result.counterValue(name) }
        val stageTotals = results
            .flatMap { result -> result.stageMillis.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, values) -> values.sum() }
            .toList()
            .sortedByDescending { (_, millis) -> millis }
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
            appendLine("- GC collections during package scans: $totalGcCount")
            appendLine("- GC time during package scans: ${"%.2f".formatUs(totalGcTimeMs / 1000.0)} s")
            appendLine("- max heap used after package scan: ${"%.1f".formatUs(maxHeapUsedAfterBytes.toDouble() / BYTES_IN_MIB)} MiB")
            appendLine("- final instructions visited: ${counterTotal("instructions_visited_final")}")
            appendLine("- summary instructions visited: ${counterTotal("summary_instructions_visited")}")
            appendLine("- dex entries: ${counterTotal("dex_entry_count")}")
            appendLine("- summary iterations executed: ${counterTotal("summary_iterations_executed")}")
            appendLine("- summary no-change iterations: ${counterTotal("summary_iterations_no_change")}")
            appendLine("- methods with CFG: ${counterTotal("methods_with_cfg")}")
            appendLine()
            appendLine("## Stage Totals")
            appendLine()
            appendLine("| Stage | Total, s |")
            appendLine("| --- | ---: |")
            stageTotals.take(20).forEach { (stage, millis) ->
                appendLine("| `$stage` | ${"%.2f".formatUs(millis / 1000.0)} |")
            }
            appendLine()
            appendLine("## Counter Totals")
            appendLine()
            appendLine("| Counter | Total |")
            appendLine("| --- | ---: |")
            COUNTERS_IN_SUMMARY.forEach { counter ->
                appendLine("| `$counter` | ${counterTotal(counter)} |")
            }
            appendLine()
            appendLine("## Top DEX Entries")
            appendLine()
            appendLine("| Package | Entry | Duration, s | Summary, s | Final, s | Classes | Methods impl | Summary iters | Summary instr | Final instr |")
            appendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
            results
                .flatMap { result -> result.dexEntries.map { entry -> result to entry } }
                .sortedByDescending { (_, entry) -> entry.durationMillis }
                .take(12)
                .forEach { (result, entry) ->
                    appendLine(
                        "| `${result.packageName}` | `${entry.entryName.substringAfter("!/")}` | " +
                            "${"%.1f".formatUs(entry.durationMillis / 1000.0)} | " +
                            "${"%.1f".formatUs(entry.summaryMillis / 1000.0)} | " +
                            "${"%.1f".formatUs(entry.finalAnalysisMillis / 1000.0)} | " +
                            "${entry.classCount} | " +
                            "${entry.methodCountWithImplementation} | " +
                            "${entry.summaryIterationsExecuted} | " +
                            "${entry.summaryInstructionsVisited} | " +
                            "${entry.instructionsVisitedFinal} |",
                    )
                }
            appendLine()
            appendLine("## Packages")
            appendLine()
            appendLine("| Package | Bucket | Risk | Duration, s | GC count | GC time, s | Heap after, MiB | APK MiB | Methods | Methods/s | us/method | Final instr | Summary instr | CFG methods | Facts | Calls |")
            appendLine("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
            results
                .sortedByDescending(PerformanceScanResult::durationMs)
                .forEach { result ->
                    appendLine(
                        "| `${result.packageName}` | ${result.bucket} | ${result.riskLevel} | " +
                            "${"%.1f".formatUs(result.durationMs / 1000.0)} | " +
                            "${result.gcCountDelta} | " +
                            "${"%.1f".formatUs(result.gcTimeMsDelta / 1000.0)} | " +
                            "${"%.1f".formatUs(result.heapUsedAfterBytes.toDouble() / BYTES_IN_MIB)} | " +
                            "${"%.1f".formatUs(result.totalApkBytes.toDouble() / BYTES_IN_MIB)} | " +
                            "${result.methodsAnalyzed} | " +
                            "${"%.0f".formatUs(result.methodsPerSecond)} | " +
                            "${"%.2f".formatUs(result.microsecondsPerMethod)} | " +
                            "${result.counterValue("instructions_visited_final")} | " +
                            "${result.counterValue("summary_instructions_visited")} | " +
                            "${result.counterValue("methods_with_cfg")} | " +
                            "${result.counterValue("facts_count")} | " +
                            "${result.counterValue("method_calls_count")} |",
                    )
                }
        }
    }

    private fun performanceStagesCsv(results: List<PerformanceScanResult>): String = buildString {
        appendLine("bucket,package,stage,duration_ms")
        results
            .sortedWith(
                compareBy<PerformanceScanResult> { it.bucket }
                    .thenBy { it.packageName },
            )
            .forEach { result ->
                result.stageMillis
                    .toList()
                    .sortedByDescending { (_, millis) -> millis }
                    .forEach { (stage, millis) ->
                        appendLine(
                            listOf(
                                result.bucket,
                                result.packageName,
                                stage,
                                millis.toString(),
                            ).joinToString(",") { it.csv() },
                        )
                    }
            }
    }

    private fun performanceCountersCsv(results: List<PerformanceScanResult>): String = buildString {
        appendLine("bucket,package,counter,value")
        results
            .sortedWith(
                compareBy<PerformanceScanResult> { it.bucket }
                    .thenBy { it.packageName },
            )
            .forEach { result ->
                result.counters
                    .toList()
                    .sortedBy { (counter, _) -> counter }
                    .forEach { (counter, value) ->
                        appendLine(
                            listOf(
                                result.bucket,
                                result.packageName,
                                counter,
                                value.toString(),
                            ).joinToString(",") { it.csv() },
                        )
                    }
            }
    }

    private fun performanceDexEntriesCsv(results: List<PerformanceScanResult>): String = buildString {
        appendLine(
            listOf(
                "bucket",
                "package",
                "entry",
                "duration_ms",
                "dex_read_ms",
                "summary_ms",
                "final_analysis_ms",
                "class_count",
                "method_count_total",
                "method_count_with_implementation",
                "summary_methods_visited",
                "summary_instructions_visited",
                "summary_iterations_executed",
                "summary_iterations_changed",
                "summary_iterations_no_change",
                "final_classes_visited",
                "final_classes_skipped_sdk_infra",
                "final_methods_analyzed",
                "final_methods_skipped_sdk_infra",
                "instructions_visited_final",
                "invoke_instructions_final",
                "handle_invoke_calls",
                "handle_invoke_nanos_final",
                "instruction_evidence_builds_final",
                "register_list_calls_final",
                "method_signature_with_arguments_calls_final",
                "method_signature_with_arguments_nanos_final",
                "method_call_candidates_final",
                "method_calls_discarded_final",
                "method_calls_discarded_platform_final",
                "method_calls_discarded_blank_target_final",
                "methods_with_cfg",
                "facts_emitted_final",
                "method_calls_retained_final",
            ).joinToString(","),
        )
        results
            .sortedWith(
                compareBy<PerformanceScanResult> { it.bucket }
                    .thenBy { it.packageName },
            )
            .forEach { result ->
                result.dexEntries
                    .sortedByDescending(AppSemanticAnalyzerDexEntryMetrics::durationMillis)
                    .forEach { entry ->
                        appendLine(
                            listOf(
                                result.bucket,
                                result.packageName,
                                entry.entryName,
                                entry.durationMillis.toString(),
                                entry.dexReadMillis.toString(),
                                entry.summaryMillis.toString(),
                                entry.finalAnalysisMillis.toString(),
                                entry.classCount.toString(),
                                entry.methodCountTotal.toString(),
                                entry.methodCountWithImplementation.toString(),
                                entry.summaryMethodsVisited.toString(),
                                entry.summaryInstructionsVisited.toString(),
                                entry.summaryIterationsExecuted.toString(),
                                entry.summaryIterationsChanged.toString(),
                                entry.summaryIterationsNoChange.toString(),
                                entry.finalClassesVisited.toString(),
                                entry.finalClassesSkippedSdkInfra.toString(),
                                entry.finalMethodsAnalyzed.toString(),
                                entry.finalMethodsSkippedSdkInfra.toString(),
                                entry.instructionsVisitedFinal.toString(),
                                entry.invokeInstructionsFinal.toString(),
                                entry.handleInvokeCalls.toString(),
                                entry.handleInvokeNanosFinal.toString(),
                                entry.instructionEvidenceBuildsFinal.toString(),
                                entry.registerListCallsFinal.toString(),
                                entry.methodSignatureWithArgumentsCallsFinal.toString(),
                                entry.methodSignatureWithArgumentsNanosFinal.toString(),
                                entry.methodCallCandidatesFinal.toString(),
                                entry.methodCallsDiscardedFinal.toString(),
                                entry.methodCallsDiscardedPlatformFinal.toString(),
                                entry.methodCallsDiscardedBlankTargetFinal.toString(),
                                entry.methodsWithCfg.toString(),
                                entry.factsEmittedFinal.toString(),
                                entry.methodCallsRetainedFinal.toString(),
                            ).joinToString(",") { it.csv() },
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
        val gcCountDelta: Long,
        val gcTimeMsDelta: Long,
        val heapUsedBeforeBytes: Long,
        val heapUsedAfterBytes: Long,
        val heapTotalAfterBytes: Long,
        val heapMaxBytes: Long,
        val methodsAnalyzed: Int,
        val cfgNodeCount: Int,
        val cfgEdgeCount: Int,
        val dfgEdgeCount: Int,
        val signalCount: Int,
        val riskLevel: String,
        val score: Int,
        val stageMillis: Map<String, Long>,
        val counters: Map<String, Long>,
        val dexEntries: List<AppSemanticAnalyzerDexEntryMetrics>,
    ) {
        val methodsPerSecond: Double
            get() = if (durationMs > 0L) methodsAnalyzed / (durationMs / 1000.0) else 0.0

        val microsecondsPerMethod: Double
            get() = if (methodsAnalyzed > 0) (durationMs * 1000.0) / methodsAnalyzed else 0.0

        fun counterValue(name: String): Long = counters[name] ?: 0L
    }

    private data class GcStats(
        val collectionCount: Long,
        val collectionTimeMs: Long,
    ) {
        companion object {
            fun capture(): GcStats {
                val beans = ManagementFactory.getGarbageCollectorMXBeans()
                return GcStats(
                    collectionCount = beans.sumOf { bean -> bean.collectionCount.takeIf { it >= 0L } ?: 0L },
                    collectionTimeMs = beans.sumOf { bean -> bean.collectionTime.takeIf { it >= 0L } ?: 0L },
                )
            }
        }
    }

    private data class MemoryStats(
        val usedBytes: Long,
        val totalBytes: Long,
        val maxBytes: Long,
    ) {
        companion object {
            fun capture(): MemoryStats {
                val runtime = Runtime.getRuntime()
                return MemoryStats(
                    usedBytes = runtime.totalMemory() - runtime.freeMemory(),
                    totalBytes = runtime.totalMemory(),
                    maxBytes = runtime.maxMemory(),
                )
            }
        }
    }

    private companion object {
        const val BYTES_IN_MIB = 1024.0 * 1024.0
        val COUNTERS_IN_SUMMARY = listOf(
            "dex_entry_attempt_count",
            "dex_entry_count",
            "dex_class_count",
            "dex_method_count_total",
            "dex_method_count_with_implementation",
            "summary_methods_visited",
            "summary_instructions_visited",
            "summary_iterations_executed",
            "summary_iterations_changed",
            "summary_iterations_no_change",
            "final_methods_analyzed",
            "final_methods_skipped_sdk_infra",
            "instructions_visited_final",
            "invoke_instructions_final",
            "handle_invoke_calls",
            "handle_invoke_skipped_semantics_final",
            "handle_invoke_nanos_final",
            "instruction_evidence_builds_final",
            "register_list_calls_final",
            "register_reads_summary",
            "register_reads_summary_constructor",
            "register_list_objects_built_final",
            "opcode_key_calls_summary",
            "opcode_key_calls_summary_constructor",
            "opcode_key_calls_final",
            "argument_tags_built_final",
            "argument_strings_built_final",
            "argument_ints_built_final",
            "argument_tags_by_index_built_final",
            "method_signature_with_arguments_calls_final",
            "method_signature_with_arguments_nanos_final",
            "method_call_candidates_final",
            "method_call_objects_retained_final",
            "method_calls_discarded_final",
            "method_calls_discarded_platform_final",
            "method_calls_discarded_blank_target_final",
            "methods_with_cfg",
            "facts_count",
            "method_calls_count",
            "native_bytes_scanned",
            "native_strings_seen",
            "native_evidence_strings_built",
            "native_symbol_texts_retained",
        )
    }
}
