package org.debs.mayday.core.data.packageinfo

import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppSemanticRiskScope
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
            strings = listOf("wg", "tun", "tap", "ppp", "pptp"),
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
            strings = listOf("wg0", "tun1"),
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
            AppRiskLevel.MEDIUM,
            result.riskLevel,
        )
        assertEquals(AppRiskLevel.MEDIUM, result.sdkCodeRisk.riskLevel)
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
