package org.debs.mayday.core.data.packageinfo

import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppSemanticProofLevel
import org.debs.mayday.core.model.AppSemanticRiskScope
import org.debs.mayday.core.model.AppSemanticVerdictStatus
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31i
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction3rc
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppSemanticAnalyzerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val analyzer = AppSemanticAnalyzer()

    @Test
    fun analyzeFindsCfgDfgUnderlyingNetworkBypassPath() {
        val apk = apkWithDexMethodBody(
            name = "semantic-bypass.apk",
            sourceClass = "Lcom/example/security/SemanticProbe;",
            sourceMethod = "checkBypass",
            strings = listOf(
                "https://ifconfig.me/ip",
                "NetworkCapabilities.TRANSPORT_VPN",
            ),
            invokedMethods = listOf(
                DexMethodRef(
                    definingClass = "Landroid/net/ConnectivityManager;",
                    name = "getAllNetworks",
                    parameterTypes = emptyList(),
                    returnType = "[Landroid/net/Network;",
                    registerCount = 0,
                ),
                DexMethodRef(
                    definingClass = "Landroid/net/ConnectivityManager;",
                    name = "bindProcessToNetwork",
                    parameterTypes = listOf("Landroid/net/Network;"),
                    returnType = "Z",
                    registerCount = 0,
                ),
                DexMethodRef(
                    definingClass = "Ljava/net/URL;",
                    name = "openConnection",
                    parameterTypes = listOf("Ljava/lang/String;"),
                    returnType = "Ljava/net/URLConnection;",
                    registerCount = 1,
                ),
            ),
        )

        val result = analyzer.analyze(
            packageName = "com.example.semantic",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CRITICAL, result.riskLevel)
        assertTrue(result.methodsAnalyzed > 0)
        assertTrue(result.cfgNodeCount > 0)
        assertTrue(result.dfgEdgeCount > 0)
        assertEquals(AppSemanticProofLevel.HIGH, result.proofLevel)
        assertEquals(AppSemanticProofLevel.HIGH, result.verdictLevel)
        assertEquals(AppSemanticVerdictStatus.PROVEN_THREAT, result.verdictStatus)
        assertTrue(result.verdictConfidence >= 80)
        assertTrue(result.signals.any { it.title == "underlying-network bypass semantic path" })
        assertTrue(result.signals.any { it.title == "public-IP endpoint flows into network call" })
    }

    @Test
    fun analyzeTreatsVpnInventoryTelemetryAndPublicIpAsCritical() {
        val instructions = listOf(
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                0,
                ImmutableStringReference("android.net.VpnService"),
            ),
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                1,
                0,
                0,
                0,
                0,
                0,
                ImmutableMethodReference(
                    "Landroid/content/pm/PackageManager;",
                    "queryIntentServices",
                    listOf("Landroid/content/Intent;", "I"),
                    "Ljava/util/List;",
                ),
            ),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                1,
                1,
                0,
                0,
                0,
                0,
                ImmutableMethodReference(
                    "Ljava/util/List;",
                    "size",
                    emptyList(),
                    "I",
                ),
            ),
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                2,
                ImmutableStringReference("vpn_status"),
            ),
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                2,
                1,
                2,
                0,
                0,
                0,
                ImmutableMethodReference(
                    "Lorg/json/JSONObject;",
                    "put",
                    listOf("Ljava/lang/String;", "Ljava/lang/Object;"),
                    "Lorg/json/JSONObject;",
                ),
            ),
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                3,
                ImmutableStringReference("SOCKS5"),
            ),
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                4,
                ImmutableStringReference("https://api.ipify.org"),
            ),
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                1,
                4,
                0,
                0,
                0,
                0,
                ImmutableMethodReference(
                    "Ljava/net/URL;",
                    "openConnection",
                    listOf("Ljava/lang/String;"),
                    "Ljava/net/URLConnection;",
                ),
            ),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val apk = apkWithInstructions(
            name = "semantic-vpn-inventory.apk",
            sourceClass = "Lcom/example/security/VpnInventory;",
            sourceMethod = "collectAndReport",
            registerCount = 5,
            instructions = instructions,
        )

        val result = analyzer.analyze(
            packageName = "com.example.security",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CRITICAL, result.riskLevel)
        assertEquals(AppRiskLevel.CRITICAL, result.appCodeRisk.riskLevel)
        assertTrue(result.signals.any { it.title == "critical VPN inventory with proxy and public-IP probing" })
        assertTrue(result.signals.any { it.evidenceChain.size > 1 })
    }

    @Test
    fun analyzeDocsApkSamplesThroughSemanticBehavior() {
        val samples = listOf(
            "com.notcvnt.rknhardering" to File("../../docs/internalDocs/RKNHardering-v2.6.7.apk").canonicalFile,
            "com.yourvpndead" to File("../../docs/internalDocs/YourVPNDead-v22.apk").canonicalFile,
        )
        assumeTrue(samples.all { it.second.isFile })

        samples.forEach { (packageName, apk) ->
            val result = analyzer.analyze(
                packageName = packageName,
                versionCode = 1,
                apkPaths = listOf(apk.path),
            )

            assertTrue(result.methodsAnalyzed > 0)
            assertTrue(result.signals.isNotEmpty())
            assertTrue(result.manifestRisk.signals.any { it.scope == AppSemanticRiskScope.MANIFEST })
            assertEquals(
                "$packageName golden malicious sample must stay CRITICAL:\n${result.describeForFailure()}",
                AppRiskLevel.CRITICAL,
                result.riskLevel,
            )
            assertTrue("$packageName should produce DFG edges", result.dfgEdgeCount > 0)
            assertTrue(
                result.signals.any { signal ->
                    signal.title.contains("VPN", ignoreCase = true) ||
                        signal.title.contains("proxy", ignoreCase = true) ||
                        signal.title.contains("bypass", ignoreCase = true) ||
                        signal.title.contains("public-IP", ignoreCase = true)
                },
            )
        }
    }

    @Test
    fun analyzeCleanChatClientDoesNotEscalateNetworkStack() {
        val apk = File("../../docs/internalDocs/clean_chat_client.apk").canonicalFile
        assumeTrue(apk.isFile)

        val result = analyzer.analyze(
            packageName = "org.debs.kalog",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "Clean chat client should stay clean:\n${result.describeForFailure()}",
            AppRiskLevel.CLEAN,
            result.riskLevel,
        )
    }

    @Test
    fun analyzeRequiresNumericTunnelInterfaceSuffix() {
        val apkWithoutSuffix = apkWithDexMethodBody(
            name = "semantic-tunnel-name-only.apk",
            sourceClass = "Lcom/example/security/TunnelNames;",
            sourceMethod = "checkNames",
            strings = listOf("wg", "tun", "tap", "ppp", "pptp", "ipsec", "IPSecConfiguration"),
            invokedMethods = emptyList(),
        )
        val cleanResult = analyzer.analyze(
            packageName = "com.example.security",
            versionCode = 1,
            apkPaths = listOf(apkWithoutSuffix.path),
        )

        assertTrue(cleanResult.signals.none { it.title == "tunnel interface inspection" })

        val apkWithSuffix = apkWithDexMethodBody(
            name = "semantic-tunnel-numbered.apk",
            sourceClass = "Lcom/example/security/TunnelNames;",
            sourceMethod = "checkNames",
            strings = listOf("wg0", "tun1", "ipsec0"),
            invokedMethods = emptyList(),
        )
        val flaggedResult = analyzer.analyze(
            packageName = "com.example.security",
            versionCode = 1,
            apkPaths = listOf(apkWithSuffix.path),
        )

        assertTrue(flaggedResult.signals.any { it.title == "tunnel interface inspection" })
    }

    @Test
    fun analyzeRequiresTransportVpnArgumentForNetworkCapabilitiesSignal() {
        val nonVpnApk = apkWithInstructions(
            name = "semantic-non-vpn-transport.apk",
            sourceClass = "Lcom/example/network/NetworkState;",
            sourceMethod = "checkWifi",
            registerCount = 2,
            instructions = listOf(
                ImmutableInstruction31i(Opcode.CONST, 0, 1),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/net/NetworkCapabilities;",
                        "hasTransport",
                        listOf("I"),
                        "Z",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )

        val nonVpnResult = analyzer.analyze(
            packageName = "com.example.network",
            versionCode = 1,
            apkPaths = listOf(nonVpnApk.path),
        )

        assertTrue(nonVpnResult.signals.none { it.title == "VPN transport state check" })

        val vpnApk = apkWithInstructions(
            name = "semantic-vpn-transport.apk",
            sourceClass = "Lcom/example/network/NetworkState;",
            sourceMethod = "checkVpn",
            registerCount = 2,
            instructions = listOf(
                ImmutableInstruction31i(Opcode.CONST, 0, 4),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/net/NetworkCapabilities;",
                        "hasTransport",
                        listOf("I"),
                        "Z",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )

        val vpnResult = analyzer.analyze(
            packageName = "com.example.network",
            versionCode = 1,
            apkPaths = listOf(vpnApk.path),
        )

        assertTrue(vpnResult.signals.any { it.title == "VPN transport state check" })
    }

    @Test
    fun analyzeDoesNotEscalateGenericAnalyticsOrPackageNames() {
        val apk = apkWithDexMethodBody(
            name = "semantic-generic-analytics.apk",
            sourceClass = "Lcom/example/network/GenericAnalytics;",
            sourceMethod = "report",
            strings = listOf("analytics", "telemetry", "report", "packageName", "serviceInfo", "applicationInfo"),
            invokedMethods = listOf(
                DexMethodRef(
                    definingClass = "Ljava/net/URL;",
                    name = "openConnection",
                    parameterTypes = listOf("Ljava/lang/String;"),
                    returnType = "Ljava/net/URLConnection;",
                    registerCount = 0,
                ),
            ),
        )

        val result = analyzer.analyze(
            packageName = "com.example.network",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "Generic analytics/package field names must not be interpreted as VPN telemetry:\n${result.describeForFailure()}",
            AppRiskLevel.CLEAN,
            result.riskLevel,
        )
    }

    @Test
    fun analyzeDoesNotJoinUnrelatedAppWidePackageInfoAndVpnTelemetry() {
        val packageInfoMethod = immutableMethod(
            sourceClass = "Lcom/xiaomi/router/common/util/PackageUtil;",
            sourceMethod = "collectInstalledPluginInfo",
            registerCount = 3,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("com.xiaomi.router.autoupdate"),
                ),
                ImmutableInstruction31i(Opcode.CONST, 1, 0),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    2,
                    0,
                    1,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/content/pm/PackageManager;",
                        "getPackageInfo",
                        listOf("Ljava/lang/String;", "I"),
                        "Landroid/content/pm/PackageInfo;",
                    ),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val routerVpnSettingsMethod = immutableMethod(
            sourceClass = "Lcom/xiaomi/router/settings/RouterVpnSettings;",
            sourceMethod = "renderVpnStatus",
            registerCount = 2,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("vpn_status"),
                ),
                ImmutableInstruction31i(Opcode.CONST, 1, 0),
                ImmutableInstruction21t(Opcode.IF_EQZ, 1, 2),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val knownVpnTextMethod = immutableMethod(
            sourceClass = "Lcom/xiaomi/router/support/DocumentationLinks;",
            sourceMethod = "knownVpnClientName",
            registerCount = 1,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("com.v2ray.ang"),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val apk = apkWithEntry(
            name = "semantic-mi-wifi-package-info.apk",
            entryName = "classes.dex",
            content = dexWithMethods(
                listOf(
                    packageInfoMethod,
                    routerVpnSettingsMethod,
                    knownVpnTextMethod,
                ),
            ),
        )

        val result = analyzer.analyze(
            packageName = "com.xiaomi.router",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "Unrelated app-wide PackageInfo, VPN UI text, and branch facts must not combine into HIGH:\n${result.describeForFailure()}",
            AppRiskLevel.CLEAN,
            result.riskLevel,
        )
        assertEquals(AppSemanticProofLevel.HIGH, result.verdictLevel)
        assertEquals(100, result.verdictConfidence)
    }

    @Test
    fun analyzeKeepsSameClassCoordinatorSplitVpnDiscoveryAndTelemetryHigh() {
        val coordinatorMethod = immutableMethod(
            sourceClass = "Lcom/example/security/VpnDetector;",
            sourceMethod = "scanAndReport",
            registerCount = 1,
            instructions = listOf(
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Lcom/example/security/VpnDetector;",
                        "queryVpnServices",
                        emptyList(),
                        "V",
                    ),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Lcom/example/security/VpnDetector;",
                        "buildTelemetryDecision",
                        emptyList(),
                        "V",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val vpnDiscoveryMethod = immutableMethod(
            sourceClass = "Lcom/example/security/VpnDetector;",
            sourceMethod = "queryVpnServices",
            registerCount = 2,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("android.net.VpnService"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/content/pm/PackageManager;",
                        "queryIntentServices",
                        listOf("Landroid/content/Intent;", "I"),
                        "Ljava/util/List;",
                    ),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val telemetryDecisionMethod = immutableMethod(
            sourceClass = "Lcom/example/security/VpnDetector;",
            sourceMethod = "buildTelemetryDecision",
            registerCount = 3,
            instructions = vpnStateBranchInstructions(telemetryLabel = "vpn_status"),
        )
        val apk = apkWithEntry(
            name = "semantic-same-class-split-vpn-flow.apk",
            entryName = "classes.dex",
            content = dexWithMethods(listOf(coordinatorMethod, vpnDiscoveryMethod, telemetryDecisionMethod)),
        )

        val result = analyzer.analyze(
            packageName = "com.example.security",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "Same-class split VPN discovery and telemetry decision should remain high-risk:\n${result.describeForFailure()}",
            AppRiskLevel.HIGH,
            result.riskLevel,
        )
        assertTrue(result.signals.any { it.title == "branching VPN-state telemetry decision" })
    }

    @Test
    fun analyzeKeepsDirectCrossClassVpnFlowHigh() {
        val vpnDiscoveryMethod = immutableMethod(
            sourceClass = "Lcom/example/security/VpnPackageCollector;",
            sourceMethod = "queryAndReportVpnServices",
            registerCount = 2,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("android.net.VpnService"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/content/pm/PackageManager;",
                        "queryIntentServices",
                        listOf("Landroid/content/Intent;", "I"),
                        "Ljava/util/List;",
                    ),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Lcom/example/security/VpnTelemetryReporter;",
                        "reportVpnState",
                        emptyList(),
                        "V",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val telemetryMethod = immutableMethod(
            sourceClass = "Lcom/example/security/VpnTelemetryReporter;",
            sourceMethod = "reportVpnState",
            registerCount = 3,
            instructions = vpnStateBranchInstructions(telemetryLabel = "vpn_status"),
        )
        val apk = apkWithEntry(
            name = "semantic-direct-cross-class-vpn-flow.apk",
            entryName = "classes.dex",
            content = dexWithMethods(listOf(vpnDiscoveryMethod, telemetryMethod)),
        )

        val result = analyzer.analyze(
            packageName = "com.example.security",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "Direct cross-class VPN discovery and telemetry should remain high-risk:\n${result.describeForFailure()}",
            AppRiskLevel.HIGH,
            result.riskLevel,
        )
        assertTrue(result.signals.any { it.title == "branching VPN-state telemetry decision" })
    }

    @Test
    fun analyzeKeepsCoordinatorLinkedCrossClassVpnFlowHigh() {
        val coordinatorMethod = immutableMethod(
            sourceClass = "Lcom/example/security/VpnScanCoordinator;",
            sourceMethod = "scanAndReport",
            registerCount = 1,
            instructions = listOf(
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Lcom/example/security/VpnPackageCollector;",
                        "queryVpnServices",
                        emptyList(),
                        "V",
                    ),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Lcom/example/security/VpnTelemetryReporter;",
                        "reportVpnState",
                        emptyList(),
                        "V",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val vpnDiscoveryMethod = immutableMethod(
            sourceClass = "Lcom/example/security/VpnPackageCollector;",
            sourceMethod = "queryVpnServices",
            registerCount = 2,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("android.net.VpnService"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/content/pm/PackageManager;",
                        "queryIntentServices",
                        listOf("Landroid/content/Intent;", "I"),
                        "Ljava/util/List;",
                    ),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val telemetryMethod = immutableMethod(
            sourceClass = "Lcom/example/security/VpnTelemetryReporter;",
            sourceMethod = "reportVpnState",
            registerCount = 3,
            instructions = vpnStateBranchInstructions(telemetryLabel = "vpn_status"),
        )
        val apk = apkWithEntry(
            name = "semantic-cross-class-coordinator-vpn-flow.apk",
            entryName = "classes.dex",
            content = dexWithMethods(listOf(coordinatorMethod, vpnDiscoveryMethod, telemetryMethod)),
        )

        val result = analyzer.analyze(
            packageName = "com.example.security",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "Coordinator-linked cross-class VPN discovery and telemetry should remain high-risk:\n${result.describeForFailure()}",
            AppRiskLevel.HIGH,
            result.riskLevel,
        )
        assertTrue(result.signals.any { it.title == "branching VPN-state telemetry decision" })
    }

    @Test
    fun analyzeKeepsTransitiveCrossClassVpnFlowHigh() {
        val entryMethod = immutableMethod(
            sourceClass = "Lcom/example/security/VpnPackageCollector;",
            sourceMethod = "queryThenDelegate",
            registerCount = 2,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("android.net.VpnService"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/content/pm/PackageManager;",
                        "queryIntentServices",
                        listOf("Landroid/content/Intent;", "I"),
                        "Ljava/util/List;",
                    ),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Lcom/example/security/VpnFlowMiddle;",
                        "forwardToReporter",
                        emptyList(),
                        "V",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val middleMethod = immutableMethod(
            sourceClass = "Lcom/example/security/VpnFlowMiddle;",
            sourceMethod = "forwardToReporter",
            registerCount = 1,
            instructions = listOf(
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Lcom/example/security/VpnTelemetryReporter;",
                        "reportVpnState",
                        emptyList(),
                        "V",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val telemetryMethod = immutableMethod(
            sourceClass = "Lcom/example/security/VpnTelemetryReporter;",
            sourceMethod = "reportVpnState",
            registerCount = 3,
            instructions = vpnStateBranchInstructions(telemetryLabel = "vpn_status"),
        )
        val apk = apkWithEntry(
            name = "semantic-transitive-cross-class-vpn-flow.apk",
            entryName = "classes.dex",
            content = dexWithMethods(listOf(entryMethod, middleMethod, telemetryMethod)),
        )

        val result = analyzer.analyze(
            packageName = "com.example.security",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "Transitive cross-class VPN discovery and telemetry should remain high-risk:\n${result.describeForFailure()}",
            AppRiskLevel.HIGH,
            result.riskLevel,
        )
        assertTrue(result.signals.any { it.title == "branching VPN-state telemetry decision" })
    }

    @Test
    fun analyzeKeepsSplitTunnelPackagePickerLowRisk() {
        val splitTunnelMethod = immutableMethod(
            sourceClass = "Lcom/example/vpn/SplitTunnelSettings;",
            sourceMethod = "loadSplitTunnelApps",
            registerCount = 3,
            instructions = listOf(
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/content/pm/PackageManager;",
                        "getInstalledApplications",
                        listOf("I"),
                        "Ljava/util/List;",
                    ),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/net/VpnService;",
                        "prepare",
                        listOf("Landroid/content/Context;"),
                        "Landroid/content/Intent;",
                    ),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    1,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/net/VpnService\$Builder;",
                        "addAllowedApplication",
                        listOf("Ljava/lang/String;"),
                        "Landroid/net/VpnService\$Builder;",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val apk = apkWithEntry(
            name = "semantic-split-tunnel-picker.apk",
            entryName = "classes.dex",
            content = dexWithMethods(listOf(splitTunnelMethod)),
        )

        val result = analyzer.analyze(
            packageName = "com.example.vpn",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "Split-tunnel app selection should stay low-risk without telemetry/probing:\n${result.describeForFailure()}",
            AppRiskLevel.LOW,
            result.riskLevel,
        )
        assertTrue(result.signals.any { it.title == "VPN client or split-tunnel app selection context" })
    }

    @Test
    fun analyzeKeepsSelfProxyWithPublicIpDiagnosticOnly() {
        val selfProxyMethod = immutableMethod(
            sourceClass = "Lcom/example/chat/ProxySettings;",
            sourceMethod = "configureOwnProxy",
            registerCount = 4,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("socksProxyHost"),
                ),
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    1,
                    ImmutableStringReference("127.0.0.1"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    2,
                    0,
                    1,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Ljava/lang/System;",
                        "setProperty",
                        listOf("Ljava/lang/String;", "Ljava/lang/String;"),
                        "Ljava/lang/String;",
                    ),
                ),
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    2,
                    ImmutableStringReference("https://api.ipify.org"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    2,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Ljava/net/URL;",
                        "openConnection",
                        listOf("Ljava/lang/String;"),
                        "Ljava/net/URLConnection;",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val apk = apkWithEntry(
            name = "semantic-self-proxy.apk",
            entryName = "classes.dex",
            content = dexWithMethods(listOf(selfProxyMethod)),
        )

        val result = analyzer.analyze(
            packageName = "com.example.chat",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "Own proxy configuration plus public-IP utility call should stay diagnostic, not high-risk:\n${result.describeForFailure()}",
            AppRiskLevel.MEDIUM,
            result.riskLevel,
        )
        assertTrue(result.signals.none { it.title == "localhost proxy probe with exit-IP comparison" })
    }

    @Test
    fun analyzeKeepsSystemProxyInspectionLowWithoutTelemetry() {
        val proxyInspectionMethod = immutableMethod(
            sourceClass = "Lcom/example/network/SystemProxyReader;",
            sourceMethod = "readSystemProxy",
            registerCount = 3,
            instructions = listOf(
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/net/ConnectivityManager;",
                        "getDefaultProxy",
                        emptyList(),
                        "Landroid/net/ProxyInfo;",
                    ),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/net/ProxyInfo;",
                        "getPacFileUrl",
                        emptyList(),
                        "Landroid/net/Uri;",
                    ),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/net/ProxyInfo;",
                        "isValid",
                        emptyList(),
                        "Z",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val apk = apkWithEntry(
            name = "semantic-system-proxy-low.apk",
            entryName = "classes.dex",
            content = dexWithMethods(listOf(proxyInspectionMethod)),
        )

        val result = analyzer.analyze(
            packageName = "com.example.network",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "System proxy inspection without telemetry/probing must stay low-risk:\n${result.describeForFailure()}",
            AppRiskLevel.LOW,
            result.riskLevel,
        )
        assertTrue(result.signals.any { it.title == "system proxy inspection" })
        assertTrue(result.signals.none { it.title == "localhost proxy probe with exit-IP comparison" })
    }

    @Test
    fun analyzeKeepsSystemProxyTelemetryBelowHighWithoutVpnOrExitIp() {
        val proxyTelemetryMethod = immutableMethod(
            sourceClass = "Lcom/example/network/SystemProxyReporter;",
            sourceMethod = "reportSystemProxy",
            registerCount = 3,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("http.proxyHost"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Ljava/lang/System;",
                        "getProperty",
                        listOf("Ljava/lang/String;"),
                        "Ljava/lang/String;",
                    ),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    1,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Lcom/example/analytics/Reporter;",
                        "report",
                        listOf("Ljava/lang/String;"),
                        "V",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val apk = apkWithEntry(
            name = "semantic-system-proxy-telemetry.apk",
            entryName = "classes.dex",
            content = dexWithMethods(listOf(proxyTelemetryMethod)),
        )

        val result = analyzer.analyze(
            packageName = "com.example.network",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "System proxy telemetry alone is suspicious, but must not become high-risk without VPN/exit-IP/proxy-scan context:\n${result.describeForFailure()}",
            AppRiskLevel.MEDIUM,
            result.riskLevel,
        )
        assertTrue(result.signals.any { it.title == "system proxy telemetry path" })
        assertTrue(result.signals.none { it.title == "localhost proxy probe with exit-IP comparison" })
    }

    @Test
    fun analyzeTreatsProcRouteAsRouteInspection() {
        val apk = apkWithDexMethodBody(
            name = "semantic-proc-route.apk",
            sourceClass = "Lcom/example/network/RouteReader;",
            sourceMethod = "readRoutes",
            strings = listOf("/proc/net/route"),
            invokedMethods = emptyList(),
        )

        val result = analyzer.analyze(
            packageName = "com.example.network",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.LOW, result.riskLevel)
        assertTrue(result.signals.any { it.title == "route table inspection" })
        assertTrue(result.signals.none { it.title == "proc socket table inspection" })
    }

    @Test
    fun analyzeKeepsMtProtoProxyProbeDiagnosticWithoutExitIp() {
        val apk = apkWithDexMethodBody(
            name = "semantic-mtproto-prober.apk",
            sourceClass = "Lcom/example/security/MtProtoProber;",
            sourceMethod = "probeMtProto",
            strings = listOf("MtProtoProber", "UDP ASSOCIATE"),
            invokedMethods = emptyList(),
        )

        val result = analyzer.analyze(
            packageName = "com.example.security",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "Proxy prober context without exit-IP/network flow should stay diagnostic:\n${result.describeForFailure()}",
            AppRiskLevel.MEDIUM,
            result.riskLevel,
        )
        assertTrue(result.signals.any { it.title == "local proxy/control API scanner context" })
        assertTrue(result.signals.none { it.title == "local proxy/control API probing with exit-IP context" })
    }

    @Test
    fun analyzeKeepsStandaloneNativeCompoundSignalsBelowHigh() {
        val apk = apkWithEntries(
            name = "semantic-standalone-native.apk",
            entries = mapOf(
                "lib/arm64-v8a/libprobe.so" to nativeBlob(
                    "SO_BINDTODEVICE",
                    "127.0.0.1",
                    "https://api.ipify.org",
                ),
            ),
        )

        val result = analyzer.analyze(
            packageName = "com.example.clean",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "Native-only indicators are suspicious, but must not become HIGH without a Java bridge/context:\n${result.describeForFailure()}",
            AppRiskLevel.MEDIUM,
            result.riskLevel,
        )
        assertEquals(AppSemanticProofLevel.LOW, result.proofLevel)
        assertTrue(result.signals.none { it.title.contains("Java-to-native") })
    }

    @Test
    fun analyzeKeepsNativeBridgeWithoutJavaDetectionContextMedium() {
        val loadMethod = immutableMethod(
            sourceClass = "Lcom/example/security/NativeProbe;",
            sourceMethod = "loadNative",
            registerCount = 1,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("vpnprobe"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Ljava/lang/System;",
                        "loadLibrary",
                        listOf("Ljava/lang/String;"),
                        "V",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val apk = apkWithEntries(
            name = "semantic-native-bridge-medium.apk",
            entries = mapOf(
                "classes.dex" to dexWithMethods(
                    listOf(
                        loadMethod,
                        immutableNativeMethod(
                            sourceClass = "Lcom/example/security/NativeProbe;",
                            sourceMethod = "nativeCheck",
                        ),
                    ),
                ),
                "lib/arm64-v8a/libvpnprobe.so" to nativeBlob(
                    "Java_com_example_security_NativeProbe_nativeCheck",
                    "127.0.0.1",
                    "https://api.ipify.org",
                ),
            ),
        )

        val result = analyzer.analyze(
            packageName = "com.example.security",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "JNI bridge plus native compound strings stays medium when Java side has no detection/probing context:\n${result.describeForFailure()}",
            AppRiskLevel.MEDIUM,
            result.riskLevel,
        )
        assertEquals(AppSemanticProofLevel.LOW, result.proofLevel)
        assertTrue(result.signals.any { it.title == "native bridge with compound VPN/proxy indicators" })
        assertTrue(result.signals.none { it.confidence >= 50 })
    }

    @Test
    fun analyzeKeepsNativeProxyProtocolSupportLowWithoutJavaProbeContext() {
        val loadMethod = immutableMethod(
            sourceClass = "Lcom/example/messenger/NativeEngine;",
            sourceMethod = "<clinit>",
            registerCount = 1,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("messenger"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Ljava/lang/System;",
                        "loadLibrary",
                        listOf("Ljava/lang/String;"),
                        "V",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val apk = apkWithEntries(
            name = "semantic-native-proxy-support-low.apk",
            entries = mapOf(
                "classes.dex" to dexWithMethods(
                    listOf(
                        loadMethod,
                        immutableNativeMethod(
                            sourceClass = "Lcom/example/messenger/NativeEngine;",
                            sourceMethod = "nativeTick",
                        ),
                    ),
                ),
                "lib/arm64-v8a/libmessenger.so" to nativeBlob(
                    "Java_com_example_messenger_NativeEngine_nativeTick",
                    "socks5: received packet too big",
                    "socks5: incorrect VER in response",
                    "BindSocketToNetwork got error:",
                    "localhost",
                    "127.0.0.1",
                ),
            ),
        )

        val result = analyzer.analyze(
            packageName = "com.example.messenger",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "Native proxy/RTC protocol strings without Java detection context must stay low-risk:\n${result.describeForFailure()}",
            AppRiskLevel.LOW,
            result.riskLevel,
        )
        assertEquals(AppSemanticVerdictStatus.PROVEN_LOW_RISK, result.verdictStatus)
        assertTrue(result.signals.none { it.title == "native bridge with compound VPN/proxy indicators" })
    }

    @Test
    fun analyzeRaisesNativeBridgeWhenJavaProxyContextReachesNative() {
        val loadMethod = immutableMethod(
            sourceClass = "Lcom/example/security/NativeProxyProbe;",
            sourceMethod = "loadNative",
            registerCount = 1,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("vpnprobe"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Ljava/lang/System;",
                        "loadLibrary",
                        listOf("Ljava/lang/String;"),
                        "V",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val proxyProbeMethod = immutableMethod(
            sourceClass = "Lcom/example/security/NativeProxyProbe;",
            sourceMethod = "probeViaNative",
            registerCount = 1,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("probeSocks5"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Lcom/example/security/NativeProxyProbe;",
                        "nativeCheck",
                        emptyList(),
                        "I",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val apk = apkWithEntries(
            name = "semantic-native-bridge-high.apk",
            entries = mapOf(
                "classes.dex" to dexWithMethods(
                    listOf(
                        loadMethod,
                        proxyProbeMethod,
                        immutableNativeMethod(
                            sourceClass = "Lcom/example/security/NativeProxyProbe;",
                            sourceMethod = "nativeCheck",
                            returnType = "I",
                        ),
                    ),
                ),
                "lib/arm64-v8a/libvpnprobe.so" to nativeBlob(
                    "Java_com_example_security_NativeProxyProbe_nativeCheck",
                    "127.0.0.1",
                    "https://api.ipify.org",
                ),
            ),
        )

        val result = analyzer.analyze(
            packageName = "com.example.security",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )
        val bridgeSignal = result.signals.firstOrNull { it.title == "Java-to-native VPN/proxy semantic bridge" }

        assertEquals(
            "Java proxy context linked to native compound evidence should become HIGH, but stay below critical Java-only chains:\n${result.describeForFailure()}",
            AppRiskLevel.HIGH,
            result.riskLevel,
        )
        assertTrue(bridgeSignal != null)
        assertTrue((bridgeSignal?.confidence ?: 0) in 50 until 70)
        assertEquals(AppSemanticProofLevel.MEDIUM, bridgeSignal?.proofLevel)
    }

    @Test
    fun analyzeKeepsLocalProxyScannerWithExitIpHighRisk() {
        val proxyScannerMethod = immutableMethod(
            sourceClass = "Lcom/example/security/ProxyScanner;",
            sourceMethod = "probeSocks5ExitIp",
            registerCount = 4,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("probeSocks5"),
                ),
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    1,
                    ImmutableStringReference("127.0.0.1"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    1,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Ljava/net/Socket;",
                        "connect",
                        listOf("Ljava/net/SocketAddress;"),
                        "V",
                    ),
                ),
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    2,
                    ImmutableStringReference("https://api.ipify.org"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    2,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Ljava/net/URL;",
                        "openConnection",
                        listOf("Ljava/lang/String;"),
                        "Ljava/net/URLConnection;",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val apk = apkWithEntry(
            name = "semantic-local-proxy-scanner.apk",
            entryName = "classes.dex",
            content = dexWithMethods(listOf(proxyScannerMethod)),
        )

        val result = analyzer.analyze(
            packageName = "com.example.security",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertTrue(
            "Local proxy scanner with exit-IP comparison should stay high-risk:\n${result.describeForFailure()}",
            result.riskLevel == AppRiskLevel.HIGH || result.riskLevel == AppRiskLevel.CRITICAL,
        )
        assertTrue(result.signals.any { it.title == "localhost proxy probe with exit-IP comparison" })
    }

    @Test
    fun analyzeDoesNotTreatDatamatrixReportScannerAsProxyPortScanner() {
        val datamatrixMethod = immutableMethod(
            sourceClass = "Lru/minsvyaz/verifiedmark/presentation/viewModel/VerifiedMarkViewModel;",
            sourceMethod = "reportScannerDatamatrixCategoryEvent",
            registerCount = 1,
            instructions = listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
        )
        val apk = apkWithEntry(
            name = "semantic-datamatrix-scanner.apk",
            entryName = "classes.dex",
            content = dexWithMethods(listOf(datamatrixMethod)),
        )

        val result = analyzer.analyze(
            packageName = "ru.rostel",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertTrue(
            "DataMatrix scanner reporting must not be confused with PortScanner/proxy probing:\n${result.describeForFailure()}",
            result.signals.none { it.title == "local proxy/control API scanner context" },
        )
    }

    @Test
    fun analyzeRaisesPublicIpVpnTransportAnalyticsPathToProvenThreat() {
        val telemetryMethod = immutableMethod(
            sourceClass = "Lru/minsvyaz/coreproject/tasks/NetworkAudit;",
            sourceMethod = "collectNetworkTelemetry",
            registerCount = 3,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("https://api.ipify.org"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Ljava/net/URL;",
                        "openConnection",
                        listOf("Ljava/lang/String;"),
                        "Ljava/net/URLConnection;",
                    ),
                ),
                ImmutableInstruction31i(Opcode.CONST, 1, 4),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    1,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/net/NetworkCapabilities;",
                        "hasTransport",
                        listOf("I"),
                        "Z",
                    ),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    2,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Lru/minsvyaz/analytics/c;",
                        "g",
                        listOf("Lru/minsvyaz/analytics/a;"),
                        "V",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val apk = apkWithEntry(
            name = "semantic-public-ip-vpn-analytics.apk",
            entryName = "classes.dex",
            content = dexWithMethods(listOf(telemetryMethod)),
        )

        val result = analyzer.analyze(
            packageName = "ru.rostel",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.HIGH, result.riskLevel)
        assertEquals(AppSemanticProofLevel.HIGH, result.proofLevel)
        assertEquals(AppSemanticVerdictStatus.PROVEN_THREAT, result.verdictStatus)
        assertTrue(result.signals.any { it.title == "public-IP and VPN-state analytics telemetry path" })
    }

    @Test
    fun analyzeScansAppCodeInSecondaryDexWithoutMethodLimit() {
        val libraryDex = dexWithInstructions(
            sourceClass = "Lcom/sdk/Noise;",
            sourceMethod = "noop",
            registerCount = 1,
            instructions = listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
        )
        val appDex = dexWithInstructions(
            sourceClass = "Lcom/example/security/VpnInventory;",
            sourceMethod = "collectAndReport",
            registerCount = 5,
            instructions = criticalVpnInventoryInstructions(),
        )
        val apk = apkWithEntries(
            name = "semantic-secondary-dex.apk",
            entries = mapOf(
                "classes.dex" to libraryDex,
                "classes2.dex" to appDex,
            ),
        )

        val result = analyzer.analyze(
            packageName = "com.example.security",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CRITICAL, result.riskLevel)
        assertTrue(result.methodsAnalyzed >= 2)
        assertTrue(result.signals.any { it.evidenceChain.any { evidence -> evidence.contains("classes2.dex") } })
    }

    @Test
    fun analyzeBuildsCrossLayerRiskWhenAppHandsVpnDataToSdk() {
        val appMethod = immutableMethod(
            sourceClass = "Lcom/example/app/VpnCollector;",
            sourceMethod = "collect",
            registerCount = 4,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("android.net.VpnService"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/content/pm/PackageManager;",
                        "queryIntentServices",
                        listOf("Landroid/content/Intent;", "I"),
                        "Ljava/util/List;",
                    ),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    1,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Lcom/vendor/antifraud/Telemetry;",
                        "reportVpnApps",
                        listOf("Ljava/util/List;"),
                        "V",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val sdkMethod = immutableMethod(
            sourceClass = "Lcom/vendor/antifraud/Telemetry;",
            sourceMethod = "reportVpnApps",
            registerCount = 2,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("vpn_status"),
                ),
                ImmutableInstruction3rc(
                    Opcode.INVOKE_STATIC_RANGE,
                    0,
                    1,
                    ImmutableMethodReference(
                        "Ljava/net/URL;",
                        "openConnection",
                        listOf("Ljava/lang/String;"),
                        "Ljava/net/URLConnection;",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val apk = apkWithEntry(
            name = "semantic-app-sdk.apk",
            entryName = "classes.dex",
            content = dexWithMethods(listOf(appMethod, sdkMethod)),
        )

        val result = analyzer.analyze(
            packageName = "com.example.app",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.HIGH, result.crossLayerRisk.riskLevel)
        assertTrue(result.crossLayerRisk.signals.any { it.title == "app-to-SDK VPN telemetry handoff" })
        assertTrue(result.dfgEdgeCount > 0)
    }

    @Test
    fun analyzeKeepsGenericNetworkStateSdkBranchAsDiagnostic() {
        val apk = apkWithInstructions(
            name = "semantic-sentry-vpn-state.apk",
            sourceClass = "Lio/sentry/android/core/NetworkBreadcrumbsIntegration\$NetworkBreadcrumbConnectionDetail;",
            sourceMethod = "captureNetworkState",
            registerCount = 3,
            instructions = vpnStateBranchInstructions(telemetryLabel = "isVpn"),
        )

        val result = analyzer.analyze(
            packageName = "com.example.app",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )
        val branchSignal = result.signals.firstOrNull { it.title == "branching VPN-state telemetry decision" }

        assertTrue(
            "Generic network-observability SDK must not become HIGH from a VPN-state branch alone:\n${result.describeForFailure()}",
            result.riskLevel <= AppRiskLevel.MEDIUM,
        )
        assertEquals(18, branchSignal?.confidence)
    }

    @Test
    fun analyzeCapsAccumulatedWeakSdkDiagnosticsBelowHigh() {
        val sentryMethod = immutableMethod(
            sourceClass = "Lio/sentry/android/core/NetworkBreadcrumbsIntegration\$NetworkBreadcrumbConnectionDetail;",
            sourceMethod = "captureNetworkState",
            registerCount = 3,
            instructions = vpnStateBranchInstructions(telemetryLabel = "isVpn"),
        )
        val webRtcMethod = immutableMethod(
            sourceClass = "Lorg/webrtc/NetworkMonitorAutoDetect;",
            sourceMethod = "inspectConnectivity",
            registerCount = 3,
            instructions = listOf(
                ImmutableInstruction31i(Opcode.CONST, 0, 4),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/net/NetworkCapabilities;",
                        "hasTransport",
                        listOf("I"),
                        "Z",
                    ),
                ),
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    1,
                    ImmutableStringReference("tun0"),
                ),
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    2,
                    ImmutableStringReference("/proc/net/tcp"),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        val apk = apkWithEntry(
            name = "semantic-generic-sdk-diagnostics.apk",
            entryName = "classes.dex",
            content = dexWithMethods(listOf(sentryMethod, webRtcMethod)),
        )

        val result = analyzer.analyze(
            packageName = "com.example.app",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "Weak generic SDK diagnostics must stay below HIGH even when several are present:\n${result.describeForFailure()}",
            AppRiskLevel.LOW,
            result.riskLevel,
        )
        assertEquals(AppRiskLevel.LOW, result.sdkCodeRisk.riskLevel)
    }

    @Test
    fun analyzeKeepsTrackerSdkVpnStateBranchHigh() {
        val apk = apkWithInstructions(
            name = "semantic-meta-warp-vpn-state.apk",
            sourceClass = "Lcom/meta/wearable/warp/core/api/transport/acdc/Device;",
            sourceMethod = "updateNetworkAttributes",
            registerCount = 3,
            instructions = vpnStateBranchInstructions(telemetryLabel = "vpn_enabled"),
        )

        val result = analyzer.analyze(
            packageName = "com.whatsapp",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )
        val branchSignal = result.signals.firstOrNull { it.title == "branching VPN-state telemetry decision" }

        assertEquals(
            "Tracker SDK VPN-state branch should remain HIGH:\n${result.describeForFailure()}",
            AppRiskLevel.HIGH,
            result.riskLevel,
        )
        assertEquals(57, branchSignal?.confidence)
    }

    @Test
    fun analyzeDoesNotTreatSelfScopedVpnServiceQueryAsDeviceInventory() {
        val apk = apkWithInstructions(
            name = "semantic-self-vpn-service-query.apk",
            sourceClass = "Lcom/example/sdk/SelfVpnCapability;",
            sourceMethod = "checkOwnVpnService",
            registerCount = 4,
            instructions = listOf(
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    3,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/content/Context;",
                        "getPackageName",
                        emptyList(),
                        "Ljava/lang/String;",
                    ),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3),
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    2,
                    ImmutableStringReference("android.net.VpnService"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    2,
                    1,
                    2,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/content/Intent;",
                        "<init>",
                        listOf("Ljava/lang/String;"),
                        "V",
                    ),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    2,
                    1,
                    3,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/content/Intent;",
                        "setPackage",
                        listOf("Ljava/lang/String;"),
                        "Landroid/content/Intent;",
                    ),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    2,
                    0,
                    1,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/content/pm/PackageManager;",
                        "queryIntentServices",
                        listOf("Landroid/content/Intent;", "I"),
                        "Ljava/util/List;",
                    ),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Ljava/util/List;",
                        "iterator",
                        emptyList(),
                        "Ljava/util/Iterator;",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )

        val result = analyzer.analyze(
            packageName = "com.example.app",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertTrue(
            "Self-scoped VpnService queries must not be treated as installed VPN inventory:\n${result.describeForFailure()}",
            result.riskLevel == AppRiskLevel.CLEAN || result.riskLevel == AppRiskLevel.LOW,
        )
        assertTrue(result.signals.none { it.title == "VPN app inventory collection" })
        assertTrue(result.signals.none { it.title == "VPN app inventory telemetry path" })
    }

    @Test
    fun analyzeRaisesVpnStateInsideNetworkFingerprintPayloadToHigh() {
        val apk = apkWithInstructions(
            name = "semantic-vpn-network-fingerprint.apk",
            sourceClass = "Lcom/example/antifraud/NetworkFingerprint;",
            sourceMethod = "build",
            registerCount = 4,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("isVpnTransportActive"),
                ),
                ImmutableInstruction31i(Opcode.CONST, 1, 4),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    1,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/net/NetworkCapabilities;",
                        "hasTransport",
                        listOf("I"),
                        "Z",
                    ),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 2),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    3,
                    3,
                    0,
                    2,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Lorg/json/JSONObject;",
                        "put",
                        listOf("Ljava/lang/String;", "Z"),
                        "Lorg/json/JSONObject;",
                    ),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    3,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/net/NetworkInfo;",
                        "getExtraInfo",
                        emptyList(),
                        "Ljava/lang/String;",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )

        val result = analyzer.analyze(
            packageName = "com.example.app",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "VPN state embedded in a network fingerprint payload is high-risk:\n${result.describeForFailure()}",
            AppRiskLevel.HIGH,
            result.riskLevel,
        )
        assertTrue(result.signals.any { it.title == "VPN state embedded in device/network fingerprint" })
        assertEquals(AppSemanticProofLevel.HIGH, result.proofLevel)
    }

    @Test
    fun analyzeRaisesVpnInventoryWithDeviceFingerprintToHigh() {
        val apk = apkWithInstructions(
            name = "semantic-vpn-inventory-fingerprint.apk",
            sourceClass = "Lcom/example/antifraud/VpnInventoryFingerprint;",
            sourceMethod = "build",
            registerCount = 4,
            instructions = listOf(
                ImmutableInstruction21c(
                    Opcode.CONST_STRING,
                    0,
                    ImmutableStringReference("android.net.VpnService"),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/content/pm/PackageManager;",
                        "queryIntentServices",
                        listOf("Landroid/content/Intent;", "I"),
                        "Ljava/util/List;",
                    ),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    1,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Ljava/util/List;",
                        "iterator",
                        emptyList(),
                        "Ljava/util/Iterator;",
                    ),
                ),
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    2,
                    0,
                    0,
                    0,
                    0,
                    ImmutableMethodReference(
                        "Landroid/telephony/TelephonyManager;",
                        "getNetworkOperatorName",
                        emptyList(),
                        "Ljava/lang/String;",
                    ),
                ),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )

        val result = analyzer.analyze(
            packageName = "com.example.app",
            versionCode = 1,
            apkPaths = listOf(apk.path),
        )

        assertEquals(
            "VPN inventory collected together with device/network fingerprint should become high-risk:\n${result.describeForFailure()}",
            AppRiskLevel.HIGH,
            result.riskLevel,
        )
        assertTrue(result.signals.any { it.title == "VPN app inventory with device/network fingerprint" })
        assertEquals(AppSemanticProofLevel.HIGH, result.proofLevel)
    }

    private fun apkWithDexMethodBody(
        name: String,
        sourceClass: String,
        sourceMethod: String,
        strings: List<String>,
        invokedMethods: List<DexMethodRef>,
    ): File {
        val stringInstructions = strings.mapIndexed { register, value ->
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                register,
                ImmutableStringReference(value),
            )
        }
        val invokeInstructions = invokedMethods.map { method ->
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                method.registerCount,
                0,
                0,
                0,
                0,
                0,
                ImmutableMethodReference(
                    method.definingClass,
                    method.name,
                    method.parameterTypes,
                    method.returnType,
                ),
            )
        }
        val implementation = ImmutableMethodImplementation(
            2,
            stringInstructions + invokeInstructions + ImmutableInstruction10x(Opcode.RETURN_VOID),
            emptyList(),
            emptyList(),
        )
        val method = ImmutableMethod(
            sourceClass,
            sourceMethod,
            emptyList(),
            "V",
            0x1,
            emptySet(),
            emptySet(),
            implementation,
        )
        val classDef = ImmutableClassDef(
            sourceClass,
            0x1,
            "Ljava/lang/Object;",
            emptyList<String>(),
            null,
            emptySet(),
            emptyList(),
            listOf(method),
        )
        val dexFile = object : DexFile {
            override fun getOpcodes(): Opcodes = Opcodes.getDefault()

            override fun getClasses(): Set<ImmutableClassDef> = setOf(classDef)
        }
        val dataStore = MemoryDataStore()
        DexPool.writeTo(dataStore, dexFile)
        return apkWithEntry(
            name = name,
            entryName = "classes.dex",
            content = dataStore.data,
        )
    }

    private fun apkWithInstructions(
        name: String,
        sourceClass: String,
        sourceMethod: String,
        registerCount: Int,
        instructions: List<org.jf.dexlib2.iface.instruction.Instruction>,
    ): File {
        val dataStore = MemoryDataStore()
        DexPool.writeTo(dataStore, dexFileWithMethods(listOf(immutableMethod(sourceClass, sourceMethod, registerCount, instructions))))
        return apkWithEntry(
            name = name,
            entryName = "classes.dex",
            content = dataStore.data,
        )
    }

    private fun criticalVpnInventoryInstructions(): List<org.jf.dexlib2.iface.instruction.Instruction> {
        return listOf(
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                0,
                ImmutableStringReference("android.net.VpnService"),
            ),
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                1,
                0,
                0,
                0,
                0,
                0,
                ImmutableMethodReference(
                    "Landroid/content/pm/PackageManager;",
                    "queryIntentServices",
                    listOf("Landroid/content/Intent;", "I"),
                    "Ljava/util/List;",
                ),
            ),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                1,
                1,
                0,
                0,
                0,
                0,
                ImmutableMethodReference(
                    "Ljava/util/List;",
                    "size",
                    emptyList(),
                    "I",
                ),
            ),
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                2,
                ImmutableStringReference("vpn_status"),
            ),
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                2,
                1,
                2,
                0,
                0,
                0,
                ImmutableMethodReference(
                    "Lorg/json/JSONObject;",
                    "put",
                    listOf("Ljava/lang/String;", "Ljava/lang/Object;"),
                    "Lorg/json/JSONObject;",
                ),
            ),
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                3,
                ImmutableStringReference("SOCKS5"),
            ),
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                4,
                ImmutableStringReference("https://api.ipify.org"),
            ),
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                1,
                4,
                0,
                0,
                0,
                0,
                ImmutableMethodReference(
                    "Ljava/net/URL;",
                    "openConnection",
                    listOf("Ljava/lang/String;"),
                    "Ljava/net/URLConnection;",
                ),
            ),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
    }

    private fun vpnStateBranchInstructions(
        telemetryLabel: String,
    ): List<org.jf.dexlib2.iface.instruction.Instruction> {
        return listOf(
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                0,
                ImmutableStringReference(telemetryLabel),
            ),
            ImmutableInstruction31i(Opcode.CONST, 1, 4),
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                1,
                1,
                0,
                0,
                0,
                0,
                ImmutableMethodReference(
                    "Landroid/net/NetworkCapabilities;",
                    "hasTransport",
                    listOf("I"),
                    "Z",
                ),
            ),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 2),
            ImmutableInstruction21t(Opcode.IF_EQZ, 2, 2),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
    }

    private fun dexWithInstructions(
        sourceClass: String,
        sourceMethod: String,
        registerCount: Int,
        instructions: List<org.jf.dexlib2.iface.instruction.Instruction>,
    ): ByteArray {
        val dataStore = MemoryDataStore()
        DexPool.writeTo(dataStore, dexFileWithMethods(listOf(immutableMethod(sourceClass, sourceMethod, registerCount, instructions))))
        return dataStore.data
    }

    private fun immutableMethod(
        sourceClass: String,
        sourceMethod: String,
        registerCount: Int,
        instructions: List<org.jf.dexlib2.iface.instruction.Instruction>,
    ): ImmutableMethod {
        val implementation = ImmutableMethodImplementation(
            registerCount,
            instructions,
            emptyList(),
            emptyList(),
        )
        return ImmutableMethod(
            sourceClass,
            sourceMethod,
            emptyList(),
            "V",
            0x1,
            emptySet(),
            emptySet(),
            implementation,
        )
    }

    private fun immutableNativeMethod(
        sourceClass: String,
        sourceMethod: String,
        returnType: String = "V",
    ): ImmutableMethod {
        return ImmutableMethod(
            sourceClass,
            sourceMethod,
            emptyList(),
            returnType,
            0x0101,
            emptySet(),
            emptySet(),
            null,
        )
    }

    private fun dexWithMethods(methods: List<ImmutableMethod>): ByteArray {
        val dataStore = MemoryDataStore()
        DexPool.writeTo(dataStore, dexFileWithMethods(methods))
        return dataStore.data
    }

    private fun dexFileWithMethods(methods: List<ImmutableMethod>): DexFile {
        val classes = methods
            .groupBy { it.definingClass }
            .map { (sourceClass, classMethods) ->
                ImmutableClassDef(
                    sourceClass,
                    0x1,
                    "Ljava/lang/Object;",
                    emptyList<String>(),
                    null,
                    emptySet(),
                    emptyList(),
                    classMethods,
                )
            }
            .toSet()
        return object : DexFile {
            override fun getOpcodes(): Opcodes = Opcodes.getDefault()

            override fun getClasses(): Set<ImmutableClassDef> = classes
        }
    }

    private fun apkWithEntry(
        name: String,
        entryName: String,
        content: ByteArray,
    ): File {
        return apkWithEntries(name, mapOf(entryName to content))
    }

    private fun apkWithEntries(
        name: String,
        entries: Map<String, ByteArray>,
    ): File {
        val apk = temporaryFolder.newFile(name)
        ZipOutputStream(apk.outputStream()).use { zip ->
            entries.forEach { (entryName, content) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return apk
    }

    private fun nativeBlob(
        vararg strings: String,
    ): ByteArray {
        return strings.joinToString(separator = "\u0000").toByteArray(Charsets.US_ASCII)
    }

    private data class DexMethodRef(
        val definingClass: String,
        val name: String,
        val parameterTypes: List<String>,
        val returnType: String,
        val registerCount: Int,
    )

    private fun org.debs.mayday.core.model.AppSemanticAnalysisResult.describeForFailure(): String {
        return buildString {
            appendLine("score=$score level=$riskLevel")
            appendLine("app=${appCodeRisk.score}/${appCodeRisk.riskLevel}")
            appendLine("sdk=${sdkCodeRisk.score}/${sdkCodeRisk.riskLevel}")
            appendLine("cross=${crossLayerRisk.score}/${crossLayerRisk.riskLevel}")
            appendLine("native=${nativeCodeRisk.score}/${nativeCodeRisk.riskLevel}")
            appendLine("manifest=${manifestRisk.score}/${manifestRisk.riskLevel}")
            appendLine("methods=$methodsAnalyzed cfg=$cfgNodeCount/$cfgEdgeCount dfg=$dfgEdgeCount signals=${signals.size}")
            signals.take(20).forEachIndexed { index, signal ->
                appendLine("#${index + 1} [${signal.scope}/${signal.source}] +${signal.confidence} ${signal.type} :: ${signal.title}")
                signal.evidenceChain.take(4).forEach { evidence ->
                    appendLine("  - $evidence")
                }
            }
        }
    }
}
