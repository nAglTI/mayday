package org.debs.mayday.core.data.packageinfo

import org.debs.mayday.core.model.AppRiskLevel
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.writer.builder.DexBuilder
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstalledAppRiskScannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scanner = InstalledAppRiskScanner()

    @Test
    fun scanMarksVpnApiTelemetryPathAsHighRisk() {
        val apk = apkWithEntry(
            name = "common.apk",
            entryName = "classes.dex",
            content = "NetworkCapabilities.TRANSPORT_VPN getNetworkCapabilities isVpnConnected",
        )

        val result = scanner.scan(
            packageName = "com.example.common",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.HIGH, result.riskLevel)
        assertTrue(result.riskScore in 50 until 90)
        assertTrue(result.findings.any { it.indicator == "TRANSPORT_VPN telemetry path" })
    }

    @Test
    fun scanKeepsGenericNetworkCapabilitiesCheckLowRisk() {
        val apk = apkWithEntry(
            name = "generic.apk",
            entryName = "classes.dex",
            content = "NetworkCapabilities hasTransport getNetworkCapabilities",
        )

        val result = scanner.scan(
            packageName = "com.example.generic",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CLEAN, result.riskLevel)
        assertEquals(0, result.riskScore)
    }

    @Test
    fun scanMatchesTunnelInterfaceNamesWithAnySystemNumber() {
        val apk = apkWithEntry(
            name = "interface.apk",
            entryName = "classes.dex",
            content = "NetworkInterface pptp42",
        )

        val result = scanner.scan(
            packageName = "com.example.interface",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.LOW, result.riskLevel)
        assertEquals(0, result.riskScore)
        val finding = result.findings.first { it.indicator == "tunnel interface name" }
        assertTrue(finding.matchedSignals.any { it.indicator == AppRiskRules.TUNNEL_INTERFACE_INDICATOR })
        assertTrue(finding.matchedSignals.any { it.evidence.contains("pptp42") })
    }

    @Test
    fun scanMarksUnderlyingNetworkBypassProbeAsCritical() {
        val apk = apkWithEntry(
            name = "bypass.apk",
            entryName = "classes.dex",
            content = """
                NetworkCapabilities.TRANSPORT_VPN getNetworkCapabilities getAllNetworks
                bindProcessToNetwork openConnection ifconfig.me checkip.amazonaws.com
            """.trimIndent(),
        )

        val result = scanner.scan(
            packageName = "com.example.bypass",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CRITICAL, result.riskLevel)
        assertTrue(result.findings.any { it.indicator.startsWith("underlying-network bypass probing") })
    }

    @Test
    fun scanMarksUnderlyingNetworkBypassWithoutKnownPublicIpAsCritical() {
        val apk = apkWithEntry(
            name = "bypass-no-public-ip.apk",
            entryName = "classes.dex",
            content = """
                NetworkCapabilities.TRANSPORT_VPN getNetworkCapabilities getAllNetworks
                bindProcessToNetwork
            """.trimIndent(),
        )

        val result = scanner.scan(
            packageName = "com.example.bypass.no.public.ip",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CRITICAL, result.riskLevel)
        assertTrue(result.riskScore >= 90)
        assertTrue(result.findings.any { it.indicator == "underlying-network bypass probing" })
    }

    @Test
    fun scanMarksXrayApiProbeAsCritical() {
        val apk = apkWithEntry(
            name = "xray.apk",
            entryName = "classes.dex",
            content = "127.0.0.1 HandlerServiceGrpc listOutbounds XrayApiClient",
        )

        val result = scanner.scan(
            packageName = "com.example.xray",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CRITICAL, result.riskLevel)
        assertTrue(result.findings.any { it.indicator == "Xray gRPC API probing" })
    }

    @Test
    fun scanMarksYourVpnDeadHttp2XrayProbeAsCritical() {
        val apk = apkWithEntry(
            name = "xray-http2.apk",
            entryName = "classes.dex",
            content = "127.0.0.1 XrayAPIDetector XrayAPIInfo PRI * HTTP/2.0 HTTP/2 SETTINGS frame HandlerService",
        )

        val result = scanner.scan(
            packageName = "com.example.xray.http2",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CRITICAL, result.riskLevel)
        assertTrue(result.findings.any { it.indicator == "Xray gRPC API probing" })
    }

    @Test
    fun scanKeepsGenericGrpcHttp2BelowXrayRisk() {
        val apk = apkWithEntry(
            name = "generic-grpc.apk",
            entryName = "classes.dex",
            content = """
                io.grpc OkHttpChannelBuilder usePlaintext withDeadlineAfter
                newBlockingStub PRI * HTTP/2.0 HandlerService StatsService 127.0.0.1
            """.trimIndent(),
        )

        val result = scanner.scan(
            packageName = "com.example.generic.grpc",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertFalse(result.findings.any { it.indicator == "Xray gRPC API probing" })
        assertTrue(result.riskLevel < AppRiskLevel.HIGH)
    }

    @Test
    fun scanMarksClashApiProbeAsCritical() {
        val apk = apkWithEntry(
            name = "clash-api.apk",
            entryName = "classes.dex",
            content = "127.0.0.1 ClashAPIProbe ClashAPIResult /connections /configs /proxies destinationIP leakedDestIPs",
        )

        val result = scanner.scan(
            packageName = "com.example.clash.api",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CRITICAL, result.riskLevel)
        assertTrue(result.findings.any { it.indicator == "Clash REST API probing" })
    }

    @Test
    fun scanMarksSocksExitIpResolverAsCritical() {
        val apk = apkWithEntry(
            name = "socks-exit-ip.apk",
            entryName = "classes.dex",
            content = "127.0.0.1 ExitIPResolver ExitIPInfo SOCKS5 api.ipify.org",
        )

        val result = scanner.scan(
            packageName = "com.example.socks.exit.ip",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CRITICAL, result.riskLevel)
        assertTrue(result.findings.any { it.indicator == "localhost SOCKS exit-IP resolver" })
    }

    @Test
    fun scanKeepsMtuAndProcNetFingerprintingNonScoring() {
        val apk = apkWithEntry(
            name = "diagnostic-fingerprint.apk",
            entryName = "classes.dex",
            content = "ProcNetScanner scanListeningPorts CLIENT_SIGNATURES NetworkInterface.getMTU MtuCheckResult",
        )

        val result = scanner.scan(
            packageName = "com.example.diagnostic.fingerprint",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertTrue(result.riskLevel < AppRiskLevel.HIGH)
        assertEquals(0, result.riskScore)
    }

    @Test
    fun scanKeepsXrayApiWithoutLoopbackBelowCritical() {
        val apk = apkWithEntry(
            name = "xray-no-loopback.apk",
            entryName = "classes.dex",
            content = "HandlerServiceGrpc listOutbounds XrayApiClient",
        )

        val result = scanner.scan(
            packageName = "com.example.xray.no.loopback",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.HIGH, result.riskLevel)
        assertTrue(result.riskScore < 90)
    }

    @Test
    fun scanMarksLocalhostProxyProbeAsMediumWithoutBypassProof() {
        val apk = apkWithEntry(
            name = "proxy.apk",
            entryName = "classes.dex",
            content = "127.0.0.1 CONNECT ifconfig.me:443 SOCKS5",
        )

        val result = scanner.scan(
            packageName = "com.example.proxy",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.MEDIUM, result.riskLevel)
        assertTrue(result.riskScore < 90)
    }

    @Test
    fun scanKeepsNetworkBindingDiagnosticsWithoutVpnContextBelowCritical() {
        val apk = apkWithEntry(
            name = "network-diagnostics.apk",
            entryName = "classes.dex",
            content = "getAllNetworks getSocketFactory api.ipify.org 127.0.0.1 /proc/net/tcp ProxyInfo socksProxyHost",
        )

        val result = scanner.scan(
            packageName = "com.example.network.diagnostics",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.LOW, result.riskLevel)
        assertEquals(0, result.riskScore)
        assertTrue(result.findings.none { it.indicator.startsWith("underlying-network bypass probing") })
    }

    @Test
    fun scanDoesNotMarkGenericSocketFactoryAsUnderlyingBypass() {
        val apk = apkWithEntry(
            name = "generic-socket-factory.apk",
            entryName = "classes.dex",
            content = """
                NetworkCapabilities.TRANSPORT_VPN getNetworkCapabilities
                getAllNetworks getSocketFactory api.ipify.org
            """.trimIndent(),
        )

        val result = scanner.scan(
            packageName = "com.example.generic.socket.factory",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertTrue(result.findings.none { it.indicator.startsWith("underlying-network bypass probing") })
        assertTrue(result.riskScore < 90)
    }

    @Test
    fun scanDoesNotUseDisabledExactNetworkSocketFactoryAsBypass() {
        val apk = apkWithDexMethodsAndStrings(
            name = "exact-network-socket-factory.apk",
            strings = listOf(
                "NetworkCapabilities.TRANSPORT_VPN",
                "getNetworkCapabilities",
            ),
            methods = listOf(
                DexMethodRef(
                    definingClass = "Landroid/net/ConnectivityManager;",
                    name = "getAllNetworks",
                    returnType = "[Landroid/net/Network;",
                ),
                DexMethodRef(
                    definingClass = "Landroid/net/Network;",
                    name = "getSocketFactory",
                    returnType = "Ljavax/net/SocketFactory;",
                ),
            ),
        )

        val result = scanner.scan(
            packageName = "com.example.exact.network.socket.factory",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.LOW, result.riskLevel)
        assertTrue(result.findings.none { it.indicator.startsWith("underlying-network bypass probing") })
        assertTrue(
            result.findings.none { finding ->
                finding.matchedSignals.any { signal -> signal.indicator == "android.net.Network#getSocketFactory" }
            },
        )
    }

    @Test
    fun scanUsesRknBypassSignaturesForUnderlyingNetworkBypass() {
        val apk = apkWithEntry(
            name = "rkn-underlying-bypass.apk",
            entryName = "classes.dex",
            content = """
                UnderlyingNetworkProber VPN_NETWORK_BINDING AndroidNetworkBinding
                VPN_GATEWAY_LEAK PublicIpNetworkComparison
            """.trimIndent(),
        )

        val result = scanner.scan(
            packageName = "com.example.rkn.underlying.bypass",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CRITICAL, result.riskLevel)
        assertTrue(result.findings.any { it.indicator == "RKN underlying-network probe signatures" })
        val finding = result.findings.first { it.indicator == "underlying-network bypass probing" }
        assertTrue(finding.matchedSignals.any { it.indicator == "VPN_NETWORK_BINDING" })
        assertTrue(finding.matchedSignals.any { it.indicator == "VPN_GATEWAY_LEAK" })
    }

    @Test
    fun scanCapsAccumulatedWeakSignalsWithoutCriticalPath() {
        val apk = apkWithEntry(
            name = "weak-signals.apk",
            entryName = "classes.dex",
            content = """
                IS_VPN NetworkCapabilities getLinkProperties dnsServers /proc/net/tcp
                socksProxyHost ProxyInfo api.ipify.org isVpnConnected
                android.net.VpnService queryIntentServices QUERY_ALL_PACKAGES
            """.trimIndent(),
        )

        val result = scanner.scan(
            packageName = "com.example.weak.signals",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.HIGH, result.riskLevel)
        assertTrue(result.riskScore in 80 until 90)
    }

    @Test
    fun scanKeepsSpeedtestLikeNetworkLibrariesBelowHighRisk() {
        val apk = apkWithEntry(
            name = "speedtest-like.apk",
            entryName = "classes.dex",
            content = """
                okhttp3 OkHttpClient Retrofit java.net.Socket DatagramSocket
                getAllNetworks getSocketFactory api.ipify.org ifconfig.me
                /proc/net/tcp ProxyInfo socksProxyHost
            """.trimIndent(),
        )

        val result = scanner.scan(
            packageName = "com.example.speedtest",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertTrue(result.riskLevel < AppRiskLevel.HIGH)
        assertEquals(0, result.riskScore)
    }

    @Test
    fun scanKeepsGameNetworkingLibrariesNonScoring() {
        val apk = apkWithEntry(
            name = "game-like.apk",
            entryName = "classes.dex",
            content = "CronetEngine okhttp3 OkHttpClient io.grpc java.net.Socket api.ipify.org",
        )

        val result = scanner.scan(
            packageName = "com.example.game",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.LOW, result.riskLevel)
        assertEquals(0, result.riskScore)
    }

    @Test
    fun scanAmplifiesVpnAppDiscoveryWithNetworkSignals() {
        val apk = apkWithEntry(
            name = "vpn-discovery-combo.apk",
            entryName = "classes.dex",
            content = """
                android.net.VpnService queryIntentServices getLinkProperties
                NetworkCapabilities.TRANSPORT_VPN getNetworkCapabilities api.ipify.org
            """.trimIndent(),
        )

        val result = scanner.scan(
            packageName = "com.example.discovery.combo",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.HIGH, result.riskLevel)
        assertTrue(result.riskScore in 70 until 90)
        assertTrue(result.findings.any { it.indicator == "multi-vector VPN detection" })
    }

    @Test
    fun scanMarksVpnAppEnumerationWithTelemetryAsHighRisk() {
        val apk = apkWithEntry(
            name = "vpn-discovery-telemetry.apk",
            entryName = "classes.dex",
            content = """
                android.net.VpnService queryIntentServices
                VpnStatusChangedCommand trace-flow.ru/api/v1/report
            """.trimIndent(),
        )

        val result = scanner.scan(
            packageName = "com.example.discovery.telemetry",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.HIGH, result.riskLevel)
        assertTrue(result.riskScore in 70 until 90)
        val finding = result.findings.first { it.indicator == "VPN app enumeration with telemetry/server path" }
        assertTrue(finding.relatedIndicators.contains("VpnService.SERVICE_INTERFACE query"))
        assertTrue(finding.relatedIndicators.contains("VPN status telemetry field"))
        assertTrue(finding.matchedSignals.any { it.indicator == "android.net.VpnService" })
        assertTrue(finding.matchedSignals.any { it.indicator == "queryIntentServices" })
        assertFalse(finding.matchedSignals.any { it.indicator == "VpnStatusChangedCommand" })
        assertTrue(finding.matchedSignals.any { it.indicator == "trace-flow.ru/api/v1/report" })
    }

    @Test
    fun scanKeepsDisabledProbabilisticModelSignatureNonScoring() {
        val apk = apkWithEntry(
            name = "vpn-probability-model.apk",
            entryName = "classes.dex",
            content = """
                VpnProbabilityConnectivityChecker NetworkCapabilities.TRANSPORT_VPN getNetworkCapabilities
                NetworkInterface tun0 getMTU VPN_MTU_RANGE
            """.trimIndent(),
        )

        val result = scanner.scan(
            packageName = "com.example.vpn.probability",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.MEDIUM, result.riskLevel)
        assertEquals(0, result.riskScore)
        assertTrue(result.findings.none { it.indicator == "weighted multi-signal VPN probability model" })
        assertTrue(
            result.findings.none { finding ->
                finding.matchedSignals.any { signal -> signal.indicator == "VpnProbabilityConnectivityChecker" }
            },
        )
    }

    @Test
    fun scanReadsDexStringPoolWithDexlib2() {
        val apk = apkWithDexStrings(
            name = "dexlib2-string-pool.apk",
            "android.net.VpnService",
            "queryIntentServices",
            "NetworkCapabilities.TRANSPORT_VPN",
            "getNetworkCapabilities",
            "api.ipify.org",
        )

        val result = scanner.scan(
            packageName = "com.example.dexlib2.string.pool",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.HIGH, result.riskLevel)
        assertTrue(result.findings.any { it.evidence.contains(":dex-string-pool") })
        assertTrue(result.findings.any { it.indicator == "multi-vector VPN detection" })
    }

    @Test
    fun scanReadsDexMethodReferencesWithDexlib2() {
        val apk = apkWithDexMethodsAndStrings(
            name = "dexlib2-method-refs.apk",
            strings = listOf(
                "NetworkCapabilities.TRANSPORT_VPN",
                "getNetworkCapabilities",
                "ifconfig.me",
            ),
            methods = listOf(
                DexMethodRef(
                    definingClass = "Landroid/net/ConnectivityManager;",
                    name = "getAllNetworks",
                    returnType = "[Landroid/net/Network;",
                ),
                DexMethodRef(
                    definingClass = "Landroid/net/ConnectivityManager;",
                    name = "bindProcessToNetwork",
                    returnType = "Z",
                ),
            ),
        )

        val result = scanner.scan(
            packageName = "com.example.dexlib2.method.refs",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CRITICAL, result.riskLevel)
        assertTrue(result.findings.any { it.evidence.contains(":dex-method-refs") })
        assertTrue(result.findings.any { it.indicator.startsWith("underlying-network bypass probing") })
    }

    @Test
    fun scanReportsDexInstructionSourceClassAndMethod() {
        val apk = apkWithDexMethodBody(
            name = "dexlib2-method-source.apk",
            sourceClass = "Lcom/example/security/VpnProbe;",
            sourceMethod = "checkNetworkBypass",
            strings = listOf(
                "NetworkCapabilities.TRANSPORT_VPN",
                "getNetworkCapabilities",
                "ifconfig.me",
            ),
            invokedMethods = listOf(
                DexMethodRef(
                    definingClass = "Landroid/net/ConnectivityManager;",
                    name = "getAllNetworks",
                    returnType = "[Landroid/net/Network;",
                ),
                DexMethodRef(
                    definingClass = "Landroid/net/ConnectivityManager;",
                    name = "bindProcessToNetwork",
                    returnType = "Z",
                ),
            ),
        )

        val result = scanner.scan(
            packageName = "com.example.dexlib2.method.source",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CRITICAL, result.riskLevel)
        val bypassFinding = result.findings.first { it.indicator.startsWith("underlying-network bypass probing") }
        assertTrue(bypassFinding.evidence.contains("classes.dex:com.example.security.VpnProbe#checkNetworkBypass"))
        assertTrue(bypassFinding.evidence.contains("-> android.net.ConnectivityManager#getAllNetworks"))
        assertTrue(
            bypassFinding.matchedSignals.any {
                it.indicator == "getAllNetworks" &&
                    it.evidence.contains("classes.dex:com.example.security.VpnProbe#checkNetworkBypass")
            },
        )
    }

    @Test
    fun scanDoesNotMatchTunnelWordsWithoutInterfaceNumber() {
        val apk = apkWithEntry(
            name = "words.apk",
            entryName = "classes.dex",
            content = "NetworkInterface tunnel setup taproot pppoe",
        )

        val result = scanner.scan(
            packageName = "com.example.words",
            versionCode = 1,
            requestedPermissions = emptyList(),
            apkPaths = listOf(apk.path),
        )

        assertEquals(AppRiskLevel.CLEAN, result.riskLevel)
        assertEquals(0, result.riskScore)
    }

    private fun apkWithEntry(
        name: String,
        entryName: String,
        content: String,
    ): File {
        return apkWithEntry(
            name = name,
            entryName = entryName,
            content = content.toByteArray(),
        )
    }

    private fun apkWithDexStrings(
        name: String,
        vararg strings: String,
    ): File {
        val dexBuilder = DexBuilder(Opcodes.getDefault())
        strings.forEach(dexBuilder::internStringReference)
        return apkWithDexBuilder(name, dexBuilder)
    }

    private fun apkWithDexMethodsAndStrings(
        name: String,
        strings: List<String>,
        methods: List<DexMethodRef>,
    ): File {
        val dexBuilder = DexBuilder(Opcodes.getDefault())
        strings.forEach(dexBuilder::internStringReference)
        methods.forEach { method ->
            dexBuilder.internMethod(
                method.definingClass,
                method.name,
                emptyList(),
                method.returnType,
                0,
                emptySet(),
                emptySet(),
                null,
            )
        }
        return apkWithDexBuilder(name, dexBuilder)
    }

    private fun apkWithDexMethodBody(
        name: String,
        sourceClass: String,
        sourceMethod: String,
        strings: List<String>,
        invokedMethods: List<DexMethodRef>,
    ): File {
        val stringInstructions = strings.map { value ->
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                0,
                ImmutableStringReference(value),
            )
        }
        val invokeInstructions = invokedMethods.map { method ->
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                0,
                0,
                0,
                0,
                0,
                0,
                ImmutableMethodReference(
                    method.definingClass,
                    method.name,
                    emptyList<String>(),
                    method.returnType,
                ),
            )
        }
        val implementation = ImmutableMethodImplementation(
            0,
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

    private fun apkWithDexBuilder(
        name: String,
        dexBuilder: DexBuilder,
    ): File {
        val dataStore = MemoryDataStore()
        dexBuilder.writeTo(dataStore)
        return apkWithEntry(
            name = name,
            entryName = "classes.dex",
            content = dataStore.data,
        )
    }

    private fun apkWithEntry(
        name: String,
        entryName: String,
        content: ByteArray,
    ): File {
        val apk = temporaryFolder.newFile(name)
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content)
            zip.closeEntry()
        }
        return apk
    }

    private data class DexMethodRef(
        val definingClass: String,
        val name: String,
        val returnType: String,
    )
}
