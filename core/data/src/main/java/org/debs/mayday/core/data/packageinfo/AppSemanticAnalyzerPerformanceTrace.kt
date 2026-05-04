package org.debs.mayday.core.data.packageinfo

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class AppSemanticAnalyzerPerformanceTrace {

    private val mutableStages = Collections.synchronizedList(mutableListOf<AppSemanticAnalyzerStageTiming>())
    private val mutableDexEntries = Collections.synchronizedList(mutableListOf<AppSemanticAnalyzerDexEntryMetrics>())
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    val stages: List<AppSemanticAnalyzerStageTiming>
        get() = synchronized(mutableStages) { mutableStages.toList() }

    val dexEntries: List<AppSemanticAnalyzerDexEntryMetrics>
        get() = synchronized(mutableDexEntries) { mutableDexEntries.toList() }

    fun <T> measure(
        stage: String,
        block: () -> T,
    ): T {
        val startedAt = System.nanoTime()
        try {
            return block()
        } finally {
            record(stage = stage, durationNanos = System.nanoTime() - startedAt)
        }
    }

    fun record(
        stage: String,
        durationNanos: Long,
    ) {
        mutableStages += AppSemanticAnalyzerStageTiming(
            stage = stage,
            durationNanos = durationNanos,
        )
    }

    fun count(
        name: String,
        amount: Long = 1L,
    ) {
        if (amount == 0L) return
        counters.getOrPut(name) { AtomicLong() }.addAndGet(amount)
    }

    fun counter(name: String): Long {
        return counters[name]?.get() ?: 0L
    }

    fun countersByName(): Map<String, Long> {
        return counters
            .mapValues { (_, value) -> value.get() }
            .toSortedMap()
    }

    fun recordDexEntry(metrics: AppSemanticAnalyzerDexEntryMetrics) {
        mutableDexEntries += metrics
    }

    fun totalMillis(stage: String): Long {
        return stages
            .asSequence()
            .filter { timing -> timing.stage == stage }
            .sumOf { timing -> timing.durationMillis }
    }

    fun totalsByStageMillis(): Map<String, Long> {
        return stages
            .groupingBy(AppSemanticAnalyzerStageTiming::stage)
            .fold(0L) { total, timing -> total + timing.durationMillis }
    }
}

data class AppSemanticAnalyzerStageTiming(
    val stage: String,
    val durationNanos: Long,
) {
    val durationMillis: Long
        get() = TimeUnit.NANOSECONDS.toMillis(durationNanos)
}

data class AppSemanticAnalyzerDexEntryMetrics(
    val entryName: String,
    val durationMillis: Long,
    val dexReadMillis: Long,
    val summaryMillis: Long,
    val finalAnalysisMillis: Long,
    val classCount: Long,
    val methodCountTotal: Long,
    val methodCountWithImplementation: Long,
    val summaryMethodsVisited: Long,
    val summaryInstructionsVisited: Long,
    val summaryIterationsExecuted: Long,
    val summaryIterationsChanged: Long,
    val summaryIterationsNoChange: Long,
    val finalClassesVisited: Long,
    val finalClassesSkippedSdkInfra: Long,
    val finalMethodsAnalyzed: Long,
    val finalMethodsSkippedSdkInfra: Long,
    val instructionsVisitedFinal: Long,
    val invokeInstructionsFinal: Long,
    val handleInvokeCalls: Long,
    val handleInvokeNanosFinal: Long,
    val instructionEvidenceBuildsFinal: Long,
    val registerListCallsFinal: Long,
    val methodSignatureWithArgumentsCallsFinal: Long,
    val methodSignatureWithArgumentsNanosFinal: Long,
    val methodCallCandidatesFinal: Long,
    val methodCallsDiscardedFinal: Long,
    val methodCallsDiscardedPlatformFinal: Long,
    val methodCallsDiscardedBlankTargetFinal: Long,
    val methodsWithCfg: Long,
    val factsEmittedFinal: Long,
    val methodCallsRetainedFinal: Long,
)
