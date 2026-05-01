package org.debs.mayday.core.data.packageinfo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.debs.mayday.core.model.AppRiskFinding
import org.debs.mayday.core.model.AppRiskFindingType
import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppRiskMatchedSignal
import org.debs.mayday.core.model.AppRiskScanResult
import org.debs.mayday.core.model.AppRiskSignalStrength
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRiskCacheStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    internal fun read(fingerprint: AppRiskCacheFingerprint): AppRiskScanResult? {
        val file = cacheFile(fingerprint.packageName)
        if (!file.isFile) return null

        return runCatching {
            val json = JSONObject(file.readText(StandardCharsets.UTF_8))
            if (!json.matches(fingerprint)) return null
            json.getJSONObject("result").toRiskResult()
        }.getOrNull()
    }

    internal fun write(
        fingerprint: AppRiskCacheFingerprint,
        result: AppRiskScanResult,
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
    ): AppRiskCacheFingerprint {
        return AppRiskCacheFingerprint(
            packageName = packageName,
            versionCode = versionCode,
            apkSignature = apkSignature(apkPaths),
            rulesVersion = AppRiskRules.RULES_VERSION,
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

    private fun JSONObject.matches(fingerprint: AppRiskCacheFingerprint): Boolean {
        return optInt("schema_version") == SCHEMA_VERSION &&
            optInt("rules_version") == fingerprint.rulesVersion &&
            optString("package_name") == fingerprint.packageName &&
            optNullableLong("version_code") == fingerprint.versionCode &&
            optString("apk_signature") == fingerprint.apkSignature
    }

    private fun AppRiskCacheFingerprint.toJson(result: AppRiskScanResult): JSONObject {
        return JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("rules_version", rulesVersion)
            .put("package_name", packageName)
            .putNullable("version_code", versionCode)
            .put("apk_signature", apkSignature)
            .put("result", result.toJson())
    }

    private fun AppRiskScanResult.toJson(): JSONObject {
        return JSONObject()
            .put("risk_score", riskScore)
            .put("risk_level", riskLevel.name)
            .put("findings", findings.toFindingsJsonArray())
            .putNullable("known_group", knownGroup)
            .putNullable("known_app_name", knownAppName)
            .putNullable("known_status", knownStatus)
            .put("scanned_at_epoch_millis", scannedAtEpochMillis)
    }

    private fun AppRiskFinding.toJson(): JSONObject {
        return JSONObject()
            .put("type", type.name)
            .put("indicator", indicator)
            .put("strength", strength.name)
            .put("evidence", evidence)
            .put("score", score)
            .put("matched_signals", matchedSignals.toMatchedSignalsJsonArray())
            .put("related_indicators", relatedIndicators.toStringJsonArray())
            .put("description", description)
    }

    private fun List<AppRiskFinding>.toFindingsJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { finding -> array.put(finding.toJson()) }
        return array
    }

    private fun List<AppRiskMatchedSignal>.toMatchedSignalsJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { signal ->
            array.put(
                JSONObject()
                    .put("indicator", signal.indicator)
                    .put("evidence", signal.evidence),
            )
        }
        return array
    }

    private fun List<String>.toStringJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { value -> array.put(value) }
        return array
    }

    private fun JSONObject.toRiskResult(): AppRiskScanResult {
        return AppRiskScanResult(
            riskScore = optInt("risk_score", 0),
            riskLevel = optEnum("risk_level", AppRiskLevel.CLEAN),
            findings = optJSONArray("findings").toFindings(),
            knownGroup = optNullableString("known_group"),
            knownAppName = optNullableString("known_app_name"),
            knownStatus = optNullableString("known_status"),
            scannedAtEpochMillis = optLong("scanned_at_epoch_millis", 0L),
        )
    }

    private fun JSONArray?.toFindings(): List<AppRiskFinding> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                val evidence = json.optString("evidence")
                val indicator = json.optString("indicator")
                add(
                    AppRiskFinding(
                        type = json.optEnum("type", AppRiskFindingType.COMBINED),
                        indicator = indicator,
                        strength = json.optEnum("strength", AppRiskSignalStrength.LOW),
                        evidence = evidence,
                        score = json.optInt("score", 0),
                        matchedSignals = json.optJSONArray("matched_signals")
                            .toMatchedSignals()
                            .ifEmpty {
                                if (evidence.isBlank()) {
                                    emptyList()
                                } else {
                                    listOf(AppRiskMatchedSignal(indicator = indicator, evidence = evidence))
                                }
                            },
                        relatedIndicators = json.optJSONArray("related_indicators").toStringList(),
                        description = json.optString("description"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toMatchedSignals(): List<AppRiskMatchedSignal> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                val indicator = json.optString("indicator")
                val evidence = json.optString("evidence")
                if (indicator.isNotBlank() || evidence.isNotBlank()) {
                    add(AppRiskMatchedSignal(indicator = indicator, evidence = evidence))
                }
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

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf(String::isNotBlank)
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
        const val CACHE_DIRECTORY = "app_risk_cache"
        const val SCHEMA_VERSION = 2
        val UNSAFE_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}

internal data class AppRiskCacheFingerprint(
    val packageName: String,
    val versionCode: Long?,
    val apkSignature: String,
    val rulesVersion: Int,
)
