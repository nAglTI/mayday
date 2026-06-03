package org.debs.mayday.core.data.packageinfo

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.debs.mayday.core.model.AppRiskScanResult
import org.debs.mayday.core.model.InstalledApp
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultInstalledAppsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val riskScanner: InstalledAppRiskScanner,
    private val riskCacheStore: AppRiskCacheStore,
) : InstalledAppsRepository {

    @SuppressLint("QueryPermissionsNeeded")
    override suspend fun getInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val appPackageName = context.packageName
        val applications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        applications
            .asSequence()
            .filter { it.packageName != appPackageName }
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .mapNotNull { appInfo ->
                val packageInfo = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getPackageInfo(
                            appInfo.packageName,
                            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getPackageInfo(
                            appInfo.packageName,
                            PackageManager.GET_PERMISSIONS,
                        )
                    }
                }.getOrNull() ?: return@mapNotNull null

                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
                val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                val apkPaths = appInfo.apkPaths()
                val cachedRisk = if (isSystem) {
                    AppRiskScanResult()
                } else {
                    riskCacheStore.read(
                        riskCacheStore.fingerprint(
                            packageName = appInfo.packageName,
                            versionCode = versionCode,
                            apkPaths = apkPaths,
                        ),
                    ) ?: AppRiskScanResult()
                }
                InstalledApp(
                    packageName = appInfo.packageName,
                    label = packageManager.getApplicationLabel(appInfo).toString(),
                    isSystem = isSystem,
                    versionName = packageInfo.versionName,
                    versionCode = versionCode,
                    risk = cachedRisk,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    override suspend fun scanAppRisk(
        packageName: String,
        force: Boolean,
    ): AppRiskScanResult? {
        val preparedScan = withContext(Dispatchers.IO) {
            prepareRiskScan(
                packageName = packageName,
                force = force,
            )
        } ?: return null

        return when (preparedScan) {
            is PreparedRiskScan.Cached -> preparedScan.risk
            is PreparedRiskScan.Pending -> {
                val risk = try {
                    withContext(Dispatchers.Default) {
                        riskScanner.scan(
                            packageName = packageName,
                            versionCode = preparedScan.versionCode,
                            requestedPermissions = preparedScan.requestedPermissions,
                            apkPaths = preparedScan.apkPaths,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return null
                }
                withContext(Dispatchers.IO) {
                    riskCacheStore.write(preparedScan.fingerprint, risk)
                }
                risk
            }
        }
    }

    private fun prepareRiskScan(
        packageName: String,
        force: Boolean,
    ): PreparedRiskScan? {
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
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_PERMISSIONS,
                )
            }
        }.getOrNull() ?: return null

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val apkPaths = appInfo.apkPaths()
        val fingerprint = riskCacheStore.fingerprint(
            packageName = packageName,
            versionCode = versionCode,
            apkPaths = apkPaths,
        )
        if (!force) {
            riskCacheStore.read(fingerprint)?.let { cachedRisk ->
                return PreparedRiskScan.Cached(cachedRisk)
            }
        }

        return PreparedRiskScan.Pending(
            versionCode = versionCode,
            requestedPermissions = packageInfo.requestedPermissions
                ?.filterNotNull()
                .orEmpty(),
            apkPaths = apkPaths,
            fingerprint = fingerprint,
        )
    }

    private fun ApplicationInfo.apkPaths(): List<String> {
        return listOfNotNull(publicSourceDir) + splitPublicSourceDirs.orEmpty()
    }

    private sealed interface PreparedRiskScan {
        data class Cached(val risk: AppRiskScanResult) : PreparedRiskScan

        data class Pending(
            val versionCode: Long,
            val requestedPermissions: List<String>,
            val apkPaths: List<String>,
            val fingerprint: AppRiskCacheFingerprint,
        ) : PreparedRiskScan
    }
}
