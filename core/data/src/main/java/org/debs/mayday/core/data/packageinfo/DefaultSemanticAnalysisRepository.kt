package org.debs.mayday.core.data.packageinfo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.debs.mayday.core.model.AppSemanticAnalysisResult
import org.debs.mayday.core.model.AppSemanticRiskBucket
import org.debs.mayday.core.model.AppSemanticSignal
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSemanticAnalysisRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val analyzer: AppSemanticAnalyzer,
    private val cacheStore: AppSemanticCacheStore,
) : SemanticAnalysisRepository {

    private val cache = ConcurrentHashMap<String, AppSemanticAnalysisResult>()

    override suspend fun analyzeApp(
        packageName: String,
        force: Boolean,
    ): AppSemanticAnalysisResult? {
        val request = withContext(Dispatchers.IO) {
            prepareAnalysis(packageName)
        } ?: return null

        if (!force) {
            cache[request.cacheKey]?.let { cached -> return cached }
            cacheStore.read(request.fingerprint)?.let { cached ->
                cache[request.cacheKey] = cached
                return cached
            }
        }

        return withContext(Dispatchers.Default) {
            val coroutineContext = currentCoroutineContext()
            analyzer.analyze(
                packageName = packageName,
                versionCode = request.versionCode,
                apkPaths = request.apkPaths,
                cancellationCheck = { coroutineContext.ensureActive() },
            )
        }.also { result ->
            cache[request.cacheKey] = result
            withContext(Dispatchers.IO) {
                cacheStore.write(request.fingerprint, result)
            }
        }
    }

    override suspend fun cachedAnalysis(packageName: String): AppSemanticAnalysisResult? {
        val request = withContext(Dispatchers.IO) {
            prepareAnalysis(packageName)
        } ?: return null
        cache[request.cacheKey]?.let { return it }
        return withContext(Dispatchers.IO) {
            cacheStore.read(request.fingerprint)
        }?.also { cached ->
            cache[request.cacheKey] = cached
        }
    }

    override suspend fun exportReport(
        items: List<SemanticAnalysisExportItem>,
        onProgress: (SemanticAnalysisExportProgress) -> Unit,
    ): SemanticAnalysisExportResult = withContext(Dispatchers.IO) {
        val directory = context.getExternalFilesDir(EXPORT_DIRECTORY) ?: File(context.filesDir, EXPORT_DIRECTORY)
        directory.mkdirs()
        val timestamp = System.currentTimeMillis()
        val file = File(directory, "semantic-analysis-bundle-$timestamp.zip")
        val tempFile = File(directory, "${file.name}.tmp")
        var lastProgressAt = 0L
        fun emitProgress(
            progress: SemanticAnalysisExportProgress,
            force: Boolean = false,
        ) {
            val now = System.currentTimeMillis()
            if (force || now - lastProgressAt >= EXPORT_PROGRESS_THROTTLE_MS) {
                lastProgressAt = now
                onProgress(progress)
            }
        }

        val exportCoroutineContext = currentCoroutineContext()
        exportCoroutineContext.ensureActive()
        emitProgress(
            SemanticAnalysisExportProgress(
                stage = SemanticAnalysisExportStage.PREPARING,
                totalFiles = 1,
            ),
            force = true,
        )
        val payloads = items.map { item ->
            exportCoroutineContext.ensureActive()
            SemanticAnalysisExportPayload(
                item = item,
                artifacts = exportArtifactsFor(item.app.packageName),
            )
        }
        val artifacts = payloads.flatMap { it.artifacts }
        val totalFiles = 1 + artifacts.size
        val totalBytes = artifacts.sumOf { it.sizeBytes }
        val json = JSONObject()
            .put("schema_version", EXPORT_SCHEMA_VERSION)
            .put("analyzer_version", AppSemanticAnalyzer.ANALYZER_VERSION)
            .put("generated_at_epoch_millis", timestamp)
            .put("exporter_package_name", context.packageName)
            .put("exported_apps", items.size)
            .put("bundle_format", "zip")
            .put("report_entry", REPORT_ENTRY_NAME)
            .put("apps", payloads.toExportJsonArray())
        val jsonBytes = json.toString(JSON_INDENT).toByteArray(StandardCharsets.UTF_8)
        var completedFiles = 0
        var copiedBytes = 0L
        val exportTotalBytes = totalBytes + jsonBytes.size
        runCatching {
            ZipOutputStream(BufferedOutputStream(tempFile.outputStream())).use { zip ->
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(REPORT_ENTRY_NAME))
                emitProgress(
                    SemanticAnalysisExportProgress(
                        stage = SemanticAnalysisExportStage.WRITING_REPORT,
                        currentFileName = REPORT_ENTRY_NAME,
                        completedFiles = completedFiles,
                        totalFiles = totalFiles,
                        copiedBytes = copiedBytes,
                        totalBytes = exportTotalBytes,
                    ),
                    force = true,
                )
                zip.write(jsonBytes)
                copiedBytes += jsonBytes.size
                zip.closeEntry()
                completedFiles += 1
                emitProgress(
                    SemanticAnalysisExportProgress(
                        stage = SemanticAnalysisExportStage.WRITING_REPORT,
                        currentFileName = REPORT_ENTRY_NAME,
                        completedFiles = completedFiles,
                        totalFiles = totalFiles,
                        copiedBytes = copiedBytes,
                        totalBytes = exportTotalBytes,
                    ),
                    force = true,
                )

                zip.setLevel(Deflater.NO_COMPRESSION)
                artifacts.forEach { artifact ->
                    exportCoroutineContext.ensureActive()
                    zip.putNextEntry(ZipEntry(artifact.entryName))
                    emitProgress(
                        SemanticAnalysisExportProgress(
                            stage = SemanticAnalysisExportStage.COPYING_ARTIFACTS,
                            currentFileName = artifact.sourceName,
                            completedFiles = completedFiles,
                            totalFiles = totalFiles,
                            copiedBytes = copiedBytes,
                            totalBytes = exportTotalBytes,
                        ),
                        force = true,
                    )
                    artifact.file.inputStream().use { input ->
                        val buffer = ByteArray(EXPORT_BUFFER_SIZE)
                        while (true) {
                            exportCoroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            zip.write(buffer, 0, read)
                            copiedBytes += read
                            emitProgress(
                                SemanticAnalysisExportProgress(
                                    stage = SemanticAnalysisExportStage.COPYING_ARTIFACTS,
                                    currentFileName = artifact.sourceName,
                                    completedFiles = completedFiles,
                                    totalFiles = totalFiles,
                                    copiedBytes = copiedBytes,
                                    totalBytes = exportTotalBytes,
                                ),
                            )
                        }
                    }
                    zip.closeEntry()
                    completedFiles += 1
                    emitProgress(
                        SemanticAnalysisExportProgress(
                            stage = SemanticAnalysisExportStage.COPYING_ARTIFACTS,
                            currentFileName = artifact.sourceName,
                            completedFiles = completedFiles,
                            totalFiles = totalFiles,
                            copiedBytes = copiedBytes,
                            totalBytes = exportTotalBytes,
                        ),
                        force = true,
                    )
                }
            }
            emitProgress(
                SemanticAnalysisExportProgress(
                    stage = SemanticAnalysisExportStage.FINALIZING,
                    completedFiles = completedFiles,
                    totalFiles = totalFiles,
                    copiedBytes = copiedBytes,
                    totalBytes = exportTotalBytes,
                ),
                force = true,
            )
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure { error ->
            tempFile.delete()
            throw error
        }
        SemanticAnalysisExportResult(
            fileName = file.name,
            absolutePath = file.absolutePath,
            exportedApps = items.size,
        )
    }

    private fun prepareAnalysis(packageName: String): PreparedSemanticAnalysis? {
        val packageManager = context.packageManager
        val appInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
        }.getOrNull() ?: return null

        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull() ?: return null

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val apkPaths = appInfo.apkPaths()
        val fingerprint = cacheStore.fingerprint(
            packageName = packageName,
            versionCode = versionCode,
            apkPaths = apkPaths,
        )
        val cacheKey = buildCacheKey(fingerprint)
        return PreparedSemanticAnalysis(
            versionCode = versionCode,
            apkPaths = apkPaths,
            cacheKey = cacheKey,
            fingerprint = fingerprint,
        )
    }

    private fun buildCacheKey(fingerprint: AppSemanticCacheFingerprint): String {
        return buildString {
            append(fingerprint.analyzerVersion)
            append(':')
            append(fingerprint.packageName)
            append(':')
            append(fingerprint.versionCode)
            append(':')
            append(fingerprint.apkSignature)
        }
    }

    private fun ApplicationInfo.apkPaths(): List<String> {
        return listOfNotNull(publicSourceDir) + splitPublicSourceDirs.orEmpty()
    }

    private fun exportArtifactsFor(packageName: String): List<SemanticAnalysisExportArtifact> {
        val request = prepareAnalysis(packageName) ?: return emptyList()
        val packageSegment = packageName.safeZipSegment()
        return request.apkPaths.mapIndexedNotNull { index, path ->
            val source = File(path)
            if (!source.isFile || !source.canRead()) return@mapIndexedNotNull null
            val role = if (index == 0) "base" else "split-$index"
            val entryName = "artifacts/$packageSegment/${role}-${source.name.safeZipSegment()}"
            SemanticAnalysisExportArtifact(
                entryName = entryName,
                sourcePath = source.absolutePath,
                sourceName = source.name,
                sizeBytes = source.length(),
                file = source,
            )
        }
    }

    private fun List<SemanticAnalysisExportPayload>.toExportJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { payload -> array.put(payload.toJson()) }
        return array
    }

    private fun SemanticAnalysisExportPayload.toJson(): JSONObject {
        return JSONObject()
            .put("label", item.app.label)
            .put("package_name", item.app.packageName)
            .put("is_system", item.app.isSystem)
            .putNullable("version_name", item.app.versionName)
            .putNullable("version_code", item.app.versionCode)
            .put("analysis", item.analysis.toExportJson())
            .put("artifacts", artifacts.toArtifactsJsonArray())
    }

    private fun AppSemanticAnalysisResult.toExportJson(): JSONObject {
        return JSONObject()
            .put("score", score)
            .put("risk_level", riskLevel.name)
            .put("proof_confidence", proofConfidence)
            .put("proof_level", proofLevel.name)
            .put("clean_score", cleanScore)
            .put("clean_proof_confidence", cleanProofConfidence)
            .put("clean_proof_level", cleanProofLevel.name)
            .put("verdict_confidence", verdictConfidence)
            .put("verdict_level", verdictLevel.name)
            .put("verdict_status", verdictStatus.name)
            .put("methods_analyzed", methodsAnalyzed)
            .put("cfg_node_count", cfgNodeCount)
            .put("cfg_edge_count", cfgEdgeCount)
            .put("dfg_edge_count", dfgEdgeCount)
            .put("scanned_at_epoch_millis", scannedAtEpochMillis)
            .put("app_code_risk", appCodeRisk.toExportJson())
            .put("sdk_code_risk", sdkCodeRisk.toExportJson())
            .put("native_code_risk", nativeCodeRisk.toExportJson())
            .put("manifest_risk", manifestRisk.toExportJson())
            .put("cross_layer_risk", crossLayerRisk.toExportJson())
            .put("signals", signals.toSignalsJsonArray())
    }

    private fun AppSemanticRiskBucket.toExportJson(): JSONObject {
        return JSONObject()
            .put("score", score)
            .put("risk_level", riskLevel.name)
            .put("proof_confidence", proofConfidence)
            .put("proof_level", proofLevel.name)
            .put("signals", signals.toSignalsJsonArray())
    }

    private fun List<AppSemanticSignal>.toSignalsJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { signal -> array.put(signal.toExportJson()) }
        return array
    }

    private fun AppSemanticSignal.toExportJson(): JSONObject {
        return JSONObject()
            .put("type", type.name)
            .put("title", title)
            .put("description", description)
            .put("evidence", evidence)
            .put("confidence", confidence)
            .put("proof_confidence", proofConfidence)
            .put("proof_level", proofLevel.name)
            .put("proof_reason", proofReason)
            .put("scope", scope.name)
            .put("source", source.name)
            .put("evidence_chain", evidenceChain.toStringJsonArray())
    }

    private fun List<String>.toStringJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { value -> array.put(value) }
        return array
    }

    private fun List<SemanticAnalysisExportArtifact>.toArtifactsJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { artifact ->
            array.put(
                JSONObject()
                    .put("entry_name", artifact.entryName)
                    .put("source_name", artifact.sourceName)
                    .put("source_path", artifact.sourcePath)
                    .put("size_bytes", artifact.sizeBytes),
            )
        }
        return array
    }

    private fun JSONObject.putNullable(
        name: String,
        value: Any?,
    ): JSONObject {
        return put(name, value ?: JSONObject.NULL)
    }

    private fun String.safeZipSegment(): String {
        return replace(UNSAFE_ZIP_SEGMENT_CHARS, "_").ifBlank { "unknown" }
    }

    private data class SemanticAnalysisExportPayload(
        val item: SemanticAnalysisExportItem,
        val artifacts: List<SemanticAnalysisExportArtifact>,
    )

    private data class SemanticAnalysisExportArtifact(
        val entryName: String,
        val sourcePath: String,
        val sourceName: String,
        val sizeBytes: Long,
        val file: File,
    )

    private data class PreparedSemanticAnalysis(
        val versionCode: Long,
        val apkPaths: List<String>,
        val cacheKey: String,
        val fingerprint: AppSemanticCacheFingerprint,
    )

    private companion object {
        const val EXPORT_DIRECTORY = "semantic_exports"
        const val EXPORT_SCHEMA_VERSION = 1
        const val JSON_INDENT = 2
        const val REPORT_ENTRY_NAME = "report.json"
        const val EXPORT_BUFFER_SIZE = 128 * 1024
        const val EXPORT_PROGRESS_THROTTLE_MS = 250L
        val UNSAFE_ZIP_SEGMENT_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
