package org.debs.mayday.core.data.packageinfo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppSemanticAnalysisResult
import org.debs.mayday.core.model.AppSemanticEvidenceSource
import org.debs.mayday.core.model.AppSemanticRiskBucket
import org.debs.mayday.core.model.AppSemanticRiskScope
import org.debs.mayday.core.model.AppSemanticSignal
import org.debs.mayday.core.model.AppSemanticSignalType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSemanticCacheStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    internal fun read(fingerprint: AppSemanticCacheFingerprint): AppSemanticAnalysisResult? {
        val file = cacheFile(fingerprint.packageName)
        if (!file.isFile) return null

        return runCatching {
            val json = JSONObject(file.readText(StandardCharsets.UTF_8))
            if (!json.matches(fingerprint)) return null
            json.getJSONObject("result").toSemanticResult()
        }.getOrNull()
    }

    internal fun write(
        fingerprint: AppSemanticCacheFingerprint,
        result: AppSemanticAnalysisResult,
    ) {
        runCatching {
            val directory = cacheDirectory()
            directory.mkdirs()
            val target = cacheFile(fingerprint.packageName)
            val temp = File.createTempFile(target.name, ".tmp", directory)
            temp.writeText(fingerprint.toJson(result).toString(), StandardCharsets.UTF_8)
            moveReplacing(temp, target)
        }
    }

    internal fun fingerprint(
        packageName: String,
        versionCode: Long?,
        apkPaths: List<String>,
    ): AppSemanticCacheFingerprint {
        return AppSemanticCacheFingerprint(
            packageName = packageName,
            versionCode = versionCode,
            apkSignature = apkSignature(apkPaths),
            analyzerVersion = AppSemanticAnalyzer.ANALYZER_VERSION,
        )
    }

    private fun cacheDirectory(): File {
        return File(context.filesDir, CACHE_DIRECTORY)
    }

    private fun cacheFile(packageName: String): File {
        return File(cacheDirectory(), "${packageName.safeFileName()}.json")
    }

    private fun apkSignature(apkPaths: List<String>): String {
        return apkPaths
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(separator = "|") { path ->
                val file = File(path)
                "${file.path}:${file.length()}:${file.lastModified()}"
            }
    }

    private fun moveReplacing(temp: File, target: File) {
        runCatching {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun String.safeFileName(): String {
        return replace(UNSAFE_FILE_NAME_CHARS, "_").ifBlank { "unknown" }
    }

    private fun JSONObject.matches(fingerprint: AppSemanticCacheFingerprint): Boolean {
        return optInt("schema_version") == SCHEMA_VERSION &&
            optInt("analyzer_version") == fingerprint.analyzerVersion &&
            optString("package_name") == fingerprint.packageName &&
            optNullableLong("version_code") == fingerprint.versionCode &&
            optString("apk_signature") == fingerprint.apkSignature
    }

    private fun AppSemanticCacheFingerprint.toJson(result: AppSemanticAnalysisResult): JSONObject {
        return JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("analyzer_version", analyzerVersion)
            .put("package_name", packageName)
            .putNullable("version_code", versionCode)
            .put("apk_signature", apkSignature)
            .put("result", result.toJson())
    }

    private fun AppSemanticAnalysisResult.toJson(): JSONObject {
        return JSONObject()
            .put("score", score)
            .put("risk_level", riskLevel.name)
            .put("signals", signals.toSignalsJsonArray())
            .put("app_code_risk", appCodeRisk.toJson())
            .put("sdk_code_risk", sdkCodeRisk.toJson())
            .put("native_code_risk", nativeCodeRisk.toJson())
            .put("manifest_risk", manifestRisk.toJson())
            .put("cross_layer_risk", crossLayerRisk.toJson())
            .put("methods_analyzed", methodsAnalyzed)
            .put("cfg_node_count", cfgNodeCount)
            .put("cfg_edge_count", cfgEdgeCount)
            .put("dfg_edge_count", dfgEdgeCount)
            .put("scanned_at_epoch_millis", scannedAtEpochMillis)
    }

    private fun AppSemanticRiskBucket.toJson(): JSONObject {
        return JSONObject()
            .put("score", score)
            .put("risk_level", riskLevel.name)
            .put("signals", signals.toSignalsJsonArray())
    }

    private fun AppSemanticSignal.toJson(): JSONObject {
        return JSONObject()
            .put("type", type.name)
            .put("title", title)
            .put("description", description)
            .put("evidence", evidence)
            .put("confidence", confidence)
            .put("scope", scope.name)
            .put("source", source.name)
            .put("evidence_chain", evidenceChain.toStringJsonArray())
    }

    private fun List<AppSemanticSignal>.toSignalsJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { signal -> array.put(signal.toJson()) }
        return array
    }

    private fun List<String>.toStringJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { value -> array.put(value) }
        return array
    }

    private fun JSONObject.toSemanticResult(): AppSemanticAnalysisResult {
        return AppSemanticAnalysisResult(
            score = optInt("score", 0),
            riskLevel = optEnum("risk_level", AppRiskLevel.CLEAN),
            signals = optJSONArray("signals").toSignals(),
            appCodeRisk = optJSONObject("app_code_risk").toBucket(),
            sdkCodeRisk = optJSONObject("sdk_code_risk").toBucket(),
            nativeCodeRisk = optJSONObject("native_code_risk").toBucket(),
            manifestRisk = optJSONObject("manifest_risk").toBucket(),
            crossLayerRisk = optJSONObject("cross_layer_risk").toBucket(),
            methodsAnalyzed = optInt("methods_analyzed", 0),
            cfgNodeCount = optInt("cfg_node_count", 0),
            cfgEdgeCount = optInt("cfg_edge_count", 0),
            dfgEdgeCount = optInt("dfg_edge_count", 0),
            scannedAtEpochMillis = optLong("scanned_at_epoch_millis", 0L),
        )
    }

    private fun JSONObject?.toBucket(): AppSemanticRiskBucket {
        if (this == null) return AppSemanticRiskBucket()
        return AppSemanticRiskBucket(
            score = optInt("score", 0),
            riskLevel = optEnum("risk_level", AppRiskLevel.CLEAN),
            signals = optJSONArray("signals").toSignals(),
        )
    }

    private fun JSONArray?.toSignals(): List<AppSemanticSignal> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                add(
                    AppSemanticSignal(
                        type = json.optEnum("type", AppSemanticSignalType.COMBINATION),
                        title = json.optString("title"),
                        description = json.optString("description"),
                        evidence = json.optString("evidence"),
                        confidence = json.optInt("confidence", 0),
                        scope = json.optEnum("scope", AppSemanticRiskScope.APP_CODE),
                        source = json.optEnum("source", AppSemanticEvidenceSource.DIRECT_APP_CODE),
                        evidenceChain = json.optJSONArray("evidence_chain").toStringList()
                            .ifEmpty {
                                json.optString("evidence").takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
                            },
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private inline fun <reified T : Enum<T>> JSONObject.optEnum(
        name: String,
        default: T,
    ): T {
        val value = optString(name)
        return runCatching { enumValueOf<T>(value) }.getOrDefault(default)
    }

    private fun JSONObject.optNullableLong(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return optLong(name)
    }

    private fun JSONObject.putNullable(
        name: String,
        value: Any?,
    ): JSONObject {
        return put(name, value ?: JSONObject.NULL)
    }

    private companion object {
        const val CACHE_DIRECTORY = "app_semantic_cache"
        const val SCHEMA_VERSION = 1
        val UNSAFE_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}

internal data class AppSemanticCacheFingerprint(
    val packageName: String,
    val versionCode: Long?,
    val apkSignature: String,
    val analyzerVersion: Int,
)
