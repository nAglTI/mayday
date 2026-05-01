package org.debs.mayday.core.data.packageinfo

import android.Manifest
import org.debs.mayday.core.model.AppRiskFinding
import org.debs.mayday.core.model.AppRiskFindingType
import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppRiskMatchedSignal
import org.debs.mayday.core.model.AppRiskScanResult
import org.debs.mayday.core.model.AppRiskSignalStrength
import net.dongliu.apk.parser.ApkFile
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.ReferenceType
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.Reference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstalledAppRiskScanner @Inject constructor() {
    private val cache = ConcurrentHashMap<String, AppRiskScanResult>()

    fun scan(
        packageName: String,
        versionCode: Long?,
        requestedPermissions: List<String>,
        apkPaths: List<String>,
    ): AppRiskScanResult {
        val cacheKey = buildCacheKey(packageName, versionCode, apkPaths)
        return cache.getOrPut(cacheKey) {
            scanUncached(
                packageName = packageName,
                requestedPermissions = requestedPermissions,
                apkPaths = apkPaths,
            )
        }
    }

    private fun scanUncached(
        packageName: String,
        requestedPermissions: List<String>,
        apkPaths: List<String>,
    ): AppRiskScanResult {
        val detected = linkedMapOf<String, String>()
        apkPaths
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .forEach { path ->
                scanApk(
                    path = path,
                    detected = detected,
                )
            }

        val knownApp = findKnownApp(packageName)
        return score(
            requestedPermissions = requestedPermissions,
            detected = detected,
            knownApp = knownApp,
        )
    }

    private fun scanApk(
        path: String,
        detected: MutableMap<String, String>,
    ) {
        val apkFile = File(path)
        if (!apkFile.isFile || !apkFile.canRead()) return

        runCatching {
            ZipFile(apkFile).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements() && detected.size < compiledRules.size) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.shouldScan()) continue
                    val evidence = "${apkFile.name}!/${entry.name}"
                    if (entry.isManifest()) {
                        val parsed = scanManifestEntry(
                            apkFile = apkFile,
                            evidence = evidence,
                            detected = detected,
                        )
                        if (!parsed) {
                            zipFile.getInputStream(entry).use { input ->
                                scanBinaryEntry(
                                    input = input,
                                    evidence = evidence,
                                    detected = detected,
                                )
                            }
                        }
                    } else {
                        zipFile.getInputStream(entry).use { input ->
                            if (entry.isDex()) {
                                scanDexEntry(
                                    input = input,
                                    evidence = evidence,
                                    detected = detected,
                                )
                            } else {
                                scanBinaryEntry(
                                    input = input,
                                    evidence = evidence,
                                    detected = detected,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun scanManifestEntry(
        apkFile: File,
        evidence: String,
        detected: MutableMap<String, String>,
    ): Boolean {
        return runCatching {
            ApkFile(apkFile).use { parsedApk ->
                val manifestXml = parsedApk.manifestXml
                if (manifestXml.isNullOrBlank()) return@runCatching false
                scanTextReference(
                    value = manifestXml,
                    evidence = "$evidence:axml",
                    detected = detected,
                )
                true
            }
        }.getOrDefault(false)
    }

    private fun scanTextReference(
        value: String?,
        evidence: String,
        detected: MutableMap<String, String>,
    ) {
        if (value.isNullOrEmpty() || detected.size >= compiledRules.size) return
        compiledRules.forEach { compiledRule ->
            val match = compiledRule.matchText(value)
            if (compiledRule.rule.indicator !in detected && match != null) {
                detected[compiledRule.rule.indicator] = formatMatchedEvidence(evidence, match)
            }
        }
    }

    private fun scanExactTextReference(
        value: String?,
        evidence: String,
        detected: MutableMap<String, String>,
    ) {
        if (value.isNullOrEmpty() || detected.size >= compiledRules.size) return
        literalRulesByIndicator[value]?.forEach { compiledRule ->
            if (compiledRule.rule.indicator !in detected) {
                detected[compiledRule.rule.indicator] = evidence
            }
        }
    }

    private fun scanDexEntry(
        input: InputStream,
        evidence: String,
        detected: MutableMap<String, String>,
    ) {
        val bytes = input.readBytes()
        val parsed = runCatching {
            val dexFile = DexBackedDexFile(Opcodes.getDefault(), bytes)
            scanDexReferences(
                dexFile = dexFile,
                evidence = evidence,
                detected = detected,
            )
        }.isSuccess

        if (!parsed) {
            scanBinaryEntry(
                input = bytes.inputStream(),
                evidence = evidence,
                detected = detected,
            )
        }
    }

    private fun scanDexReferences(
        dexFile: DexBackedDexFile,
        evidence: String,
        detected: MutableMap<String, String>,
    ) {
        dexFile.classes.forEach { classDef ->
            val classEvidence = "$evidence:${dexTypeName(classDef.type)}"
            scanDexType(
                value = classDef.type,
                evidence = classEvidence,
                detected = detected,
            )
            classDef.superclass?.let { superclass ->
                scanDexType(
                    value = superclass,
                    evidence = classEvidence,
                    detected = detected,
                )
            }
            classDef.interfaces.forEach { interfaceType ->
                scanDexType(
                    value = interfaceType,
                    evidence = classEvidence,
                    detected = detected,
                )
            }
            if (detected.size >= compiledRules.size) return

            classDef.fields.forEach { field ->
                val fieldEvidence = "$classEvidence#${field.name}"
                scanDexType(
                    value = field.definingClass,
                    evidence = fieldEvidence,
                    detected = detected,
                )
                scanExactTextReference(
                    value = field.name,
                    evidence = fieldEvidence,
                    detected = detected,
                )
                scanDexType(
                    value = field.type,
                    evidence = fieldEvidence,
                    detected = detected,
                )
                if (detected.size >= compiledRules.size) return
            }

            classDef.methods.forEach { method ->
                val methodEvidence = dexMethodEvidence(
                    baseEvidence = evidence,
                    definingClass = method.definingClass,
                    methodName = method.name,
                )
                scanDexMethodReference(
                    reference = method,
                    evidence = methodEvidence,
                    detected = detected,
                )
                val implementation = method.implementation ?: return@forEach
                implementation.instructions.forEach { instruction ->
                    val referenceInstruction = instruction as? ReferenceInstruction ?: return@forEach
                    val reference = runCatching { referenceInstruction.reference }.getOrNull() ?: return@forEach
                    scanDexReference(
                        reference = reference,
                        evidence = dexReferenceEvidence(methodEvidence, reference),
                        detected = detected,
                    )
                    if (detected.size >= compiledRules.size) return
                }
            }
            if (detected.size >= compiledRules.size) return
        }

        dexFile.getReferences(ReferenceType.FIELD).forEach { reference ->
            val field = reference as? FieldReference ?: return@forEach
            scanDexReference(
                reference = field,
                evidence = dexReferenceEvidence("$evidence:dex-field-refs", field),
                detected = detected,
            )
            if (detected.size >= compiledRules.size) return
        }
        dexFile.getReferences(ReferenceType.METHOD).forEach { reference ->
            val method = reference as? MethodReference ?: return@forEach
            scanDexReference(
                reference = method,
                evidence = dexReferenceEvidence("$evidence:dex-method-refs", method),
                detected = detected,
            )
            if (detected.size >= compiledRules.size) return
        }
        dexFile.typeReferences.forEach { reference: TypeReference ->
            scanDexReference(
                reference = reference,
                evidence = "$evidence:dex-type-refs",
                detected = detected,
            )
            if (detected.size >= compiledRules.size) return
        }
        dexFile.stringReferences.forEach { reference: StringReference ->
            scanDexReference(
                reference = reference,
                evidence = "$evidence:dex-string-pool",
                detected = detected,
            )
            if (detected.size >= compiledRules.size) return
        }
    }

    private fun scanDexReference(
        reference: Reference,
        evidence: String,
        detected: MutableMap<String, String>,
    ) {
        when (reference) {
            is StringReference -> scanTextReference(
                value = reference.string,
                evidence = evidence,
                detected = detected,
            )
            is TypeReference -> scanDexType(
                value = reference.type,
                evidence = evidence,
                detected = detected,
            )
            is FieldReference -> {
                scanDexType(
                    value = reference.definingClass,
                    evidence = evidence,
                    detected = detected,
                )
                scanExactTextReference(
                    value = reference.name,
                    evidence = evidence,
                    detected = detected,
                )
                scanDexType(
                    value = reference.type,
                    evidence = evidence,
                    detected = detected,
                )
            }
            is MethodReference -> scanDexMethodReference(
                reference = reference,
                evidence = evidence,
                detected = detected,
            )
        }
    }

    private fun scanDexMethodReference(
        reference: MethodReference,
        evidence: String,
        detected: MutableMap<String, String>,
    ) {
        val definingClassTokens = dexTypeTokens(reference.definingClass)
        definingClassTokens.forEach { token ->
            scanExactTextReference(
                value = token,
                evidence = evidence,
                detected = detected,
            )
        }
        scanExactTextReference(
            value = reference.name,
            evidence = evidence,
            detected = detected,
        )
        definingClassTokens.forEach { className ->
            scanExactTextReference(
                value = "$className.${reference.name}",
                evidence = evidence,
                detected = detected,
            )
            scanExactTextReference(
                value = "$className#${reference.name}",
                evidence = evidence,
                detected = detected,
            )
        }
        reference.parameterTypes.forEach { parameterType ->
            scanDexType(
                value = parameterType.toString(),
                evidence = evidence,
                detected = detected,
            )
        }
        scanDexType(
            value = reference.returnType,
            evidence = evidence,
            detected = detected,
        )
    }

    private fun scanDexType(
        value: String?,
        evidence: String,
        detected: MutableMap<String, String>,
    ) {
        dexTypeTokens(value).forEach { token ->
            scanExactTextReference(
                value = token,
                evidence = evidence,
                detected = detected,
            )
        }
    }

    private fun scanBinaryEntry(
        input: InputStream,
        evidence: String,
        detected: MutableMap<String, String>,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var tail = ByteArray(0)
        while (detected.size < compiledRules.size) {
            val read = input.read(buffer)
            if (read <= 0) break

            val window = ByteArray(tail.size + read)
            System.arraycopy(tail, 0, window, 0, tail.size)
            System.arraycopy(buffer, 0, window, tail.size, read)

            compiledRules.forEach { compiledRule ->
                val match = compiledRule.matchBytes(window)
                if (compiledRule.rule.indicator !in detected && match != null) {
                    detected[compiledRule.rule.indicator] = formatMatchedEvidence(evidence, match)
                }
            }

            tail = window.tail(scanTailSize)
        }
    }

    private fun score(
        requestedPermissions: List<String>,
        detected: Map<String, String>,
        knownApp: KnownAppRule?,
    ): AppRiskScanResult {
        val findings = mutableListOf<AppRiskFinding>()

        fun addFinding(
            type: AppRiskFindingType,
            indicator: String,
            strength: AppRiskSignalStrength,
            evidence: String,
            score: Int = 0,
            matchedSignals: List<AppRiskMatchedSignal> = if (evidence.isBlank()) {
                emptyList()
            } else {
                listOf(AppRiskMatchedSignal(indicator = indicator, evidence = evidence))
            },
            relatedIndicators: List<String> = emptyList(),
            description: String = "",
        ) {
            findings += finding(
                type = type,
                indicator = indicator,
                strength = strength,
                evidence = evidence,
                score = score,
                matchedSignals = matchedSignals,
                relatedIndicators = relatedIndicators,
                description = description,
            )
        }

        fun addFinding(
            type: AppRiskFindingType,
            indicator: String,
            strength: AppRiskSignalStrength,
            score: Int,
            vararg evidenceIndicators: String,
        ) {
            val matchedSignals = signalsFor(detected, *evidenceIndicators)
            addFinding(
                type = type,
                indicator = indicator,
                strength = strength,
                evidence = matchedSignals.firstOrNull()?.evidence.orEmpty(),
                score = score,
                matchedSignals = matchedSignals,
            )
        }

        fun addExplanation(
            type: AppRiskFindingType,
            indicator: String,
            strength: AppRiskSignalStrength,
            vararg evidenceIndicators: String,
        ) {
            val matchedSignals = signalsFor(detected, *evidenceIndicators)
            addFinding(
                type = type,
                indicator = indicator,
                strength = strength,
                evidence = matchedSignals.firstOrNull()?.evidence.orEmpty(),
                score = 0,
                matchedSignals = matchedSignals,
            )
        }

        fun addCombinationFinding(
            indicator: String,
            strength: AppRiskSignalStrength,
            score: Int,
            description: String,
            relatedIndicators: List<String>,
            vararg evidenceIndicators: String,
        ) {
            val matchedSignals = signalsFor(detected, *evidenceIndicators)
            val existingFindingIndicators = findings.mapTo(mutableSetOf()) { it.indicator }
            addFinding(
                type = AppRiskFindingType.COMBINED,
                indicator = indicator,
                strength = strength,
                evidence = matchedSignals.firstOrNull()?.evidence ?: description,
                score = score,
                matchedSignals = matchedSignals,
                relatedIndicators = relatedIndicators
                    .filter { relatedIndicator -> relatedIndicator in existingFindingIndicators }
                    .distinct(),
                description = description,
            )
        }

        val hasNetworkLibrary = hasAny(
            detected,
            "okhttp3",
            "OkHttpClient",
            "Retrofit",
            "HttpURLConnection",
            "CronetEngine",
            "java.net.Socket",
            "DatagramSocket",
            "SocketFactory",
            "DnsOverHttps",
            "io.grpc",
        )
        if (hasNetworkLibrary) {
            addExplanation(
                type = AppRiskFindingType.NETWORK_LIBRARY,
                indicator = "network stack library",
                strength = AppRiskSignalStrength.LOW,
                "okhttp3",
                "OkHttpClient",
                "Retrofit",
                "HttpURLConnection",
                "CronetEngine",
                "java.net.Socket",
                "DatagramSocket",
                "SocketFactory",
                "DnsOverHttps",
                "io.grpc",
            )
        }

        val hasTransportVpnConstant = hasAny(
            detected,
            "NetworkCapabilities.TRANSPORT_VPN",
            "TRANSPORT_VPN",
            "hasTransport(4)",
        )
        val hasVpnCapabilitiesStringMarker = hasAny(detected, "IS_VPN", "VpnTransportInfo")
        val hasNotVpnCapabilityCheck = hasAny(detected, "NOT_VPN", "NET_CAPABILITY_NOT_VPN")
        val hasNetworkCapabilityContext = hasAny(
            detected,
            "NetworkCapabilities",
            "ConnectivityManager",
            "getNetworkCapabilities",
            "activeNetwork",
        )
        val hasGenericNetworkCapabilitiesCheck = has("hasTransport", detected) && hasNetworkCapabilityContext
        val hasStrongNetworkCapabilitiesCheck = (hasTransportVpnConstant && hasNetworkCapabilityContext) ||
            hasVpnCapabilitiesStringMarker
        if (hasVpnCapabilitiesStringMarker) {
            addExplanation(
                type = AppRiskFindingType.ANDROID_API,
                indicator = "NetworkCapabilities VPN string markers",
                strength = AppRiskSignalStrength.LOW,
                "IS_VPN",
                "VpnTransportInfo",
            )
        } else if (hasTransportVpnConstant && hasNetworkCapabilityContext) {
            addExplanation(
                type = AppRiskFindingType.ANDROID_API,
                indicator = "TRANSPORT_VPN / hasTransport",
                strength = AppRiskSignalStrength.LOW,
                "TRANSPORT_VPN",
                "NetworkCapabilities.TRANSPORT_VPN",
                "hasTransport(4)",
                "getNetworkCapabilities",
                "activeNetwork",
            )
        } else if (hasNotVpnCapabilityCheck) {
            addExplanation(
                type = AppRiskFindingType.ANDROID_API,
                indicator = "NOT_VPN capability inspection",
                strength = AppRiskSignalStrength.LOW,
                "NOT_VPN",
                "NET_CAPABILITY_NOT_VPN",
            )
        } else if (hasGenericNetworkCapabilitiesCheck) {
            addExplanation(
                type = AppRiskFindingType.ANDROID_API,
                indicator = "NetworkCapabilities.hasTransport",
                strength = AppRiskSignalStrength.LOW,
                "hasTransport",
                "NetworkCapabilities",
                "getNetworkCapabilities",
            )
        }

        val hasVpnService = has("android.net.VpnService", detected)
        val hasQueryIntentServices = has("queryIntentServices", detected)
        val hasQueryIntent = hasQueryIntentServices || has("queryIntentActivities", detected)
        val hasVpnAppCatalog = hasAny(
            detected,
            "InstalledVpnAppDetector",
            "VpnAppCatalog",
            "VpnClientSignal",
            "MatchedVpnApp",
            "VPN_SERVICE_DECLARATION",
        )
        val hasKnownVpnClientPackage = AppRiskRules.vpnClientPackageNames.any { packageName -> has(packageName, detected) }
        val hasVpnAppDiscovery = (hasVpnService && hasQueryIntentServices) ||
            hasVpnAppCatalog ||
            hasKnownVpnClientPackage
        if (hasVpnService && hasQueryIntentServices) {
            addFinding(
                type = AppRiskFindingType.VPN_APP_DISCOVERY,
                indicator = "VpnService.SERVICE_INTERFACE query",
                strength = AppRiskSignalStrength.HIGH,
                score = 35,
                "android.net.VpnService",
                "VpnService.SERVICE_INTERFACE",
                "queryIntentServices",
            )
        } else if (hasVpnAppCatalog) {
            addExplanation(
                type = AppRiskFindingType.VPN_APP_DISCOVERY,
                indicator = "RKN-style VPN app catalog",
                strength = AppRiskSignalStrength.MEDIUM,
                "InstalledVpnAppDetector",
                "VpnAppCatalog",
                "VpnClientSignal",
                "MatchedVpnApp",
                "VPN_SERVICE_DECLARATION",
            )
        } else if (hasKnownVpnClientPackage) {
            addExplanation(
                type = AppRiskFindingType.VPN_APP_DISCOVERY,
                indicator = "known VPN/proxy package signatures",
                strength = AppRiskSignalStrength.MEDIUM,
                *AppRiskRules.vpnClientPackageNames.toTypedArray(),
            )
        } else if (hasVpnService) {
            addExplanation(
                type = AppRiskFindingType.VPN_APP_DISCOVERY,
                indicator = "android.net.VpnService",
                strength = AppRiskSignalStrength.LOW,
                "android.net.VpnService",
            )
        }

        val hasInterfaceApi = has("NetworkInterface", detected) || has("getNetworkInterfaces", detected)
        val hasTunnelName = has(AppRiskRules.TUNNEL_INTERFACE_INDICATOR, detected)
        val hasIpsecName = has("ipsec", detected)
        val hasExplicitVpnStateContext = hasStrongNetworkCapabilitiesCheck ||
            hasNotVpnCapabilityCheck ||
            (hasInterfaceApi && hasTunnelName)
        if (hasInterfaceApi && hasTunnelName) {
            val interfaceScore = if (hasVpnAppDiscovery || hasStrongNetworkCapabilitiesCheck) 20 else 0
            addFinding(
                type = AppRiskFindingType.NETWORK_INTERFACE,
                indicator = "NetworkInterface + tunnel interface name",
                strength = if (interfaceScore > 0) AppRiskSignalStrength.HIGH else AppRiskSignalStrength.MEDIUM,
                score = interfaceScore,
                "NetworkInterface",
                "getNetworkInterfaces",
                AppRiskRules.TUNNEL_INTERFACE_INDICATOR,
            )
        } else if (hasTunnelName) {
            addExplanation(
                type = AppRiskFindingType.NETWORK_INTERFACE,
                indicator = "tunnel interface name",
                strength = AppRiskSignalStrength.LOW,
                AppRiskRules.TUNNEL_INTERFACE_INDICATOR,
            )
        }
        if (hasInterfaceApi && hasIpsecName) {
            addExplanation(
                type = AppRiskFindingType.NETWORK_INTERFACE,
                indicator = "NetworkInterface + ipsec interface name",
                strength = AppRiskSignalStrength.LOW,
                "NetworkInterface",
                "getNetworkInterfaces",
                "ipsec",
            )
        }
        val hasMtuProbe = hasAny(
            detected,
            "NetworkInterface.getMTU",
            "getMTU",
            "VPN_MTU_RANGE",
            "MtuCheckResult",
        )
        if (hasMtuProbe) {
            addExplanation(
                type = AppRiskFindingType.NETWORK_INTERFACE,
                indicator = "MTU anomaly inspection",
                strength = if (hasInterfaceApi || hasTunnelName) {
                    AppRiskSignalStrength.MEDIUM
                } else {
                    AppRiskSignalStrength.LOW
                },
                "NetworkInterface.getMTU",
                "getMTU",
                "VPN_MTU_RANGE",
                "MtuCheckResult",
            )
        }

        val hasRoutingSignals = hasAny(detected, "getLinkProperties", "isDefaultRoute", "/proc/net/route")
        if (hasRoutingSignals && (hasInterfaceApi || hasTunnelName || hasStrongNetworkCapabilitiesCheck)) {
            addExplanation(
                type = AppRiskFindingType.ROUTING,
                indicator = "LinkProperties or /proc routing inspection",
                strength = AppRiskSignalStrength.MEDIUM,
                "getLinkProperties",
                "isDefaultRoute",
                "/proc/net/route",
            )
        } else if (hasRoutingSignals) {
            addExplanation(
                type = AppRiskFindingType.ROUTING,
                indicator = "routing table inspection",
                strength = AppRiskSignalStrength.LOW,
                "getLinkProperties",
                "isDefaultRoute",
                "/proc/net/route",
            )
        }

        val hasDnsSignals = hasAny(detected, "dnsServers", "getDnsServers")
        if (hasDnsSignals && (hasStrongNetworkCapabilitiesCheck || hasRoutingSignals)) {
            addExplanation(
                type = AppRiskFindingType.DNS,
                indicator = "LinkProperties DNS inspection",
                strength = AppRiskSignalStrength.LOW,
                "dnsServers",
                "getDnsServers",
            )
        }

        val hasProcNetSockets = hasAny(
            detected,
            "/proc/net/tcp",
            "/proc/net/tcp6",
            "/proc/net/udp",
            "/proc/net/udp6",
            "/proc/self/net/tcp",
        )
        if (hasProcNetSockets) {
            addExplanation(
                type = AppRiskFindingType.LINUX_PROC,
                indicator = "/proc/net socket tables",
                strength = AppRiskSignalStrength.LOW,
                "/proc/net/tcp",
                "/proc/net/tcp6",
                "/proc/net/udp",
                "/proc/net/udp6",
                "/proc/self/net/tcp",
            )
        }
        val hasProcNetFingerprinting = hasAny(
            detected,
            "ProcNetScanner",
            "CLIENT_SIGNATURES",
            "scanListeningPorts",
            "identifyVpnClient",
            "scanEstablishedConnections",
            "EstablishedConnection",
            "ListeningPort",
            "VpnClientGuess",
            "vpnLikelihood",
            "serverGuess",
        )
        if (hasProcNetFingerprinting) {
            addExplanation(
                type = AppRiskFindingType.LINUX_PROC,
                indicator = "/proc/net VPN port fingerprinting",
                strength = if (hasProcNetSockets) AppRiskSignalStrength.MEDIUM else AppRiskSignalStrength.LOW,
                "ProcNetScanner",
                "CLIENT_SIGNATURES",
                "scanListeningPorts",
                "identifyVpnClient",
                "scanEstablishedConnections",
                "EstablishedConnection",
                "ListeningPort",
                "VpnClientGuess",
                "vpnLikelihood",
                "serverGuess",
            )
        }

        val hasTorPackageProbe = hasAny(detected, "org.torproject.torbrowser", "org.torproject.torbrowser_alpha", "torbrowser")
        if (hasTorPackageProbe) {
            addExplanation(
                type = AppRiskFindingType.TOR,
                indicator = "Tor Browser package names",
                strength = AppRiskSignalStrength.LOW,
                "org.torproject.torbrowser",
                "org.torproject.torbrowser_alpha",
                "torbrowser",
            )
        }

        val hasSystemProxyApi = hasAny(
            detected,
            "http.proxyHost",
            "http.proxyPort",
            "https.proxyHost",
            "socksProxyHost",
            "socksProxyPort",
            "getDefaultProxy",
            "ProxyInfo",
            "getPacFileUrl",
            "ProxySelector",
        )
        if (hasSystemProxyApi) {
            addExplanation(
                type = AppRiskFindingType.PROXY,
                indicator = "system proxy inspection",
                strength = AppRiskSignalStrength.LOW,
                "http.proxyHost",
                "http.proxyPort",
                "https.proxyHost",
                "socksProxyHost",
                "socksProxyPort",
                "getDefaultProxy",
                "ProxyInfo",
                "getPacFileUrl",
                "ProxySelector",
            )
        }

        val hasLoopbackLiteral = hasAny(detected, "127.0.0.1", "::1")
        val hasLocalProxyHandshake = hasAny(detected, "SOCKS5", "CONNECT ifconfig.me:443")
        val hasLocalProxyScannerSignature = hasAny(
            detected,
            "ProxyScanner",
            "ProxyProber",
            "probeNoAuthProxyType",
            "SOCKS5_NO_AUTH",
            "HTTP_CONNECT_PROXY",
            "PortScanner",
            "scanKnownPorts",
            "scanFullRange",
            "Socks5Probe",
            "probeSocks5",
            "probeHTTP",
            "probeGrpc",
        )
        val hasLocalPortScannerSignature = hasAny(
            detected,
            "PortScanner",
            "KNOWN_PORTS",
            "scanKnownPorts",
            "scanFullRange",
            "probePort",
            "OpenPort",
        )
        val hasSocksAuthProbe = hasAny(
            detected,
            "AuthProbe",
            "supportsNoAuth",
            "supportsPassword",
            "bruteForceCredentials",
            "udpBypassPossible",
            "UDP ASSOCIATE",
            "COMMON_PASSWORDS",
        )
        val hasGrpcPrefaceProbe = hasAny(detected, "PRI * HTTP/2.0", "GRPC_SERVICE")
        val hasExitIpResolver = hasAny(detected, "ExitIPResolver", "ExitIPInfo")
        val hasLocalProxyPublicIpEndpoint = hasAny(
            detected,
            "ifconfig.me",
            "ifconfig.me/ip",
            "api.ipify.org",
            "api4.ipify.org",
            "checkip.amazonaws.com",
        )
        val hasLocalProxyBypassComparison = hasAny(
            detected,
            "fetchIpViaProxy",
            "fetchDirectIp",
            "LocalProxyCheckResult",
            "CONFIRMED_BYPASS",
            "SPLIT_TUNNEL_BYPASS",
            "proxyIp",
            "directIp",
            "ExitIPResolver",
            "ExitIPInfo",
        )
        val hasLocalProxyProbe = hasLoopbackLiteral && hasLocalProxyHandshake
        val hasLocalProxyContext = hasLoopbackLiteral && (hasProcNetSockets || hasSystemProxyApi)
        val hasSocksExitIpProbe = hasLoopbackLiteral && hasExitIpResolver && hasLocalProxyPublicIpEndpoint
        if ((hasLocalProxyProbe && hasLocalProxyBypassComparison) || hasSocksExitIpProbe) {
            addFinding(
                type = AppRiskFindingType.LOCAL_PROXY,
                indicator = if (hasSocksExitIpProbe) {
                    "localhost SOCKS exit-IP resolver"
                } else {
                    "confirmed localhost proxy bypass probe"
                },
                strength = AppRiskSignalStrength.HIGH,
                score = 90,
                "127.0.0.1",
                "::1",
                "SOCKS5",
                "CONNECT ifconfig.me:443",
                "ExitIPResolver",
                "ExitIPInfo",
                "fetchIpViaProxy",
                "fetchDirectIp",
                "CONFIRMED_BYPASS",
                "SPLIT_TUNNEL_BYPASS",
            )
        } else if (hasLoopbackLiteral && hasSocksAuthProbe && (hasLocalProxyHandshake || hasLocalProxyScannerSignature)) {
            addFinding(
                type = AppRiskFindingType.LOCAL_PROXY,
                indicator = "SOCKS5 auth and UDP-bypass probing",
                strength = AppRiskSignalStrength.HIGH,
                score = if (hasAny(detected, "bruteForceCredentials", "udpBypassPossible")) 70 else 55,
                "127.0.0.1",
                "::1",
                "SOCKS5",
                "SOCKS5_NO_AUTH",
                "SOCKS5_AUTH_REQUIRED",
                "AuthProbe",
                "supportsNoAuth",
                "supportsPassword",
                "bruteForceCredentials",
                "udpBypassPossible",
                "UDP ASSOCIATE",
            )
        } else if (hasLocalProxyProbe && hasLocalProxyScannerSignature) {
            addFinding(
                type = AppRiskFindingType.LOCAL_PROXY,
                indicator = "localhost proxy scanner",
                strength = AppRiskSignalStrength.HIGH,
                score = 55,
                "127.0.0.1",
                "::1",
                "SOCKS5",
                "CONNECT ifconfig.me:443",
                "ProxyScanner",
                "ProxyProber",
                "SOCKS5_NO_AUTH",
                "HTTP_CONNECT_PROXY",
            )
        } else if (hasLoopbackLiteral && hasLocalPortScannerSignature) {
            addFinding(
                type = AppRiskFindingType.LOCAL_PROXY,
                indicator = "localhost VPN port scanner",
                strength = AppRiskSignalStrength.HIGH,
                score = if (has("scanFullRange", detected)) 50 else 45,
                "127.0.0.1",
                "::1",
                "PortScanner",
                "KNOWN_PORTS",
                "scanKnownPorts",
                "scanFullRange",
                "probePort",
                "OpenPort",
            )
        } else if (hasLocalProxyProbe) {
            addFinding(
                type = AppRiskFindingType.LOCAL_PROXY,
                indicator = "localhost proxy probing",
                strength = AppRiskSignalStrength.HIGH,
                score = 45,
                "127.0.0.1",
                "::1",
                "SOCKS5",
                "CONNECT ifconfig.me:443",
            )
        } else if (hasLocalProxyHandshake) {
            addExplanation(
                type = AppRiskFindingType.LOCAL_PROXY,
                indicator = "proxy protocol probing",
                strength = AppRiskSignalStrength.MEDIUM,
                "SOCKS5",
                "CONNECT ifconfig.me:443",
            )
        } else if (hasSocksAuthProbe || hasLocalPortScannerSignature) {
            addExplanation(
                type = AppRiskFindingType.LOCAL_PROXY,
                indicator = "localhost proxy scanner signatures",
                strength = AppRiskSignalStrength.MEDIUM,
                "PortScanner",
                "KNOWN_PORTS",
                "scanKnownPorts",
                "scanFullRange",
                "AuthProbe",
                "supportsNoAuth",
                "supportsPassword",
                "udpBypassPossible",
                "COMMON_PASSWORDS",
            )
        } else if (hasLocalProxyContext) {
            addExplanation(
                type = AppRiskFindingType.LOCAL_PROXY,
                indicator = "localhost proxy context",
                strength = AppRiskSignalStrength.LOW,
                "127.0.0.1",
                "::1",
                "/proc/net/tcp",
                "/proc/net/tcp6",
                "/proc/net/udp",
                "/proc/net/udp6",
                "/proc/self/net/tcp",
                "getDefaultProxy",
                "ProxyInfo",
                "socksProxyHost",
                "socksProxyPort",
            )
        }

        val hasXrayGrpcApi = has("HandlerServiceGrpc", detected)
        val hasXrayListOutbounds = has("listOutbounds", detected) || has("ListOutboundsRequest", detected)
        val hasXrayHelperNames = hasAny(detected, "XrayApiScanner", "XrayApiClient", "XrayOutboundSummary")
        val hasXrayHttp2HelperNames = hasAny(detected, "XrayAPIDetector", "XrayAPIInfo")
        val hasXrayHttp2Probe = hasXrayHttp2HelperNames && hasGrpcPrefaceProbe
        val hasLikelyXrayApiProbe = hasXrayGrpcApi && hasXrayListOutbounds
        val hasHardXrayApiProbe = (hasLikelyXrayApiProbe || hasXrayHttp2Probe) && hasLoopbackLiteral
        if (hasLikelyXrayApiProbe || hasXrayHelperNames || hasXrayHttp2HelperNames) {
            addFinding(
                type = AppRiskFindingType.XRAY_API,
                indicator = "Xray gRPC API probing",
                strength = if (hasLikelyXrayApiProbe || hasXrayHttp2Probe) {
                    AppRiskSignalStrength.HIGH
                } else {
                    AppRiskSignalStrength.MEDIUM
                },
                score = when {
                    hasHardXrayApiProbe -> 95
                    hasLikelyXrayApiProbe || hasXrayHttp2Probe -> 60
                    else -> 0
                },
                "HandlerServiceGrpc",
                "listOutbounds",
                "ListOutboundsRequest",
                "XrayApiScanner",
                "XrayApiClient",
                "XrayOutboundSummary",
                "XrayAPIDetector",
                "XrayAPIInfo",
                "PRI * HTTP/2.0",
                "127.0.0.1",
                "::1",
            )
        }

        val hasClashApiEndpointSet = has("/connections", detected) &&
            hasAny(detected, "/configs", "/proxies") &&
            hasAny(detected, "destinationIP", "leakedDestIPs")
        val hasClashApiSignature = hasAny(
            detected,
            "ClashAPIProbe",
            "ClashAPIResult",
            "ClashConnection",
            "mihomo",
            "sing-box Clash API",
        )
        val hasHardClashApiProbe = hasLoopbackLiteral && hasClashApiEndpointSet
        if (hasClashApiSignature || hasClashApiEndpointSet) {
            addFinding(
                type = AppRiskFindingType.CLASH_API,
                indicator = "Clash REST API probing",
                strength = if (hasClashApiEndpointSet) {
                    AppRiskSignalStrength.HIGH
                } else {
                    AppRiskSignalStrength.MEDIUM
                },
                score = when {
                    hasHardClashApiProbe -> 95
                    hasClashApiEndpointSet && hasClashApiSignature -> 75
                    hasClashApiEndpointSet -> 60
                    else -> 0
                },
                "ClashAPIProbe",
                "ClashAPIResult",
                "ClashConnection",
                "/connections",
                "/configs",
                "/proxies",
                "destinationIP",
                "leakedDestIPs",
                "proxyNames",
                "uploadTotal",
                "downloadTotal",
                "mihomo",
                "sing-box Clash API",
                "127.0.0.1",
                "::1",
            )
        }

        val hasActiveVpnDumpsysProbe = has("dumpsys", detected) &&
            hasAny(detected, "vpn_management", "activity services android.net.VpnService", "Active package name:")
        val hasActiveVpnStatusModel = hasAny(detected, "VpnClientStatus", "VpnClientUnavailable")
        if (hasActiveVpnDumpsysProbe) {
            val hasBothDumpsysChecks = has("vpn_management", detected) &&
                has("activity services android.net.VpnService", detected)
            addFinding(
                type = AppRiskFindingType.ACTIVE_VPN,
                indicator = "dumpsys active VPN inspection",
                strength = AppRiskSignalStrength.HIGH,
                score = if (hasBothDumpsysChecks || hasVpnAppDiscovery) 90 else 60,
                "dumpsys",
                "vpn_management",
                "activity services android.net.VpnService",
                "Active package name:",
                "VpnDumpsysParser",
                "Runtime.getRuntime",
            )
        } else if (hasActiveVpnStatusModel) {
            addExplanation(
                type = AppRiskFindingType.ACTIVE_VPN,
                indicator = "VPN client status model",
                strength = AppRiskSignalStrength.MEDIUM,
                "VpnClientStatus",
                "VpnClientUnavailable",
            )
        }

        val exactUnderlyingNetworkEnumerationIndicators = arrayOf(
            "android.net.ConnectivityManager#getAllNetworks",
            "android.net.ConnectivityManager.getAllNetworks",
        )
        val exactUnderlyingNetworkBindingIndicators = arrayOf(
            "android.net.ConnectivityManager#bindProcessToNetwork",
            "android.net.ConnectivityManager.bindProcessToNetwork",
            "android.net.ConnectivityManager#setProcessDefaultNetwork",
            "android.net.ConnectivityManager.setProcessDefaultNetwork",
            "android.net.Network#getSocketFactory",
            "android.net.Network.getSocketFactory",
            "android.net.Network#bindSocket",
            "android.net.Network.bindSocket",
        )
        val genericStrongUnderlyingNetworkBindingIndicators = arrayOf(
            "bindProcessToNetwork",
            "setProcessDefaultNetwork",
            "bindSocket",
        )
        val underlyingRknBindingIndicators = arrayOf(
            "UnderlyingNetworkProber",
            "VPN_NETWORK_BINDING",
            "AndroidNetworkBinding",
            "OsDeviceBinding",
            "BindToDeviceSocketFactory",
            "ResolverBinding",
            "SO_BINDTODEVICE",
            "OsConstants.SO_BINDTODEVICE",
            "setsockoptIfreq",
            "Os::class.java.getMethod",
        )
        val underlyingRknLeakIndicators = arrayOf(
            "VPN_GATEWAY_LEAK",
            "SPLIT_TUNNEL_BYPASS",
            "PublicIpNetworkComparison",
            "TunProbeDiagnostics",
        )
        val underlyingRknIndicators = underlyingRknBindingIndicators + underlyingRknLeakIndicators
        val hasExactUnderlyingNetworkEnumeration = hasAny(detected, *exactUnderlyingNetworkEnumerationIndicators)
        val hasExactUnderlyingNetworkBinding = hasAny(detected, *exactUnderlyingNetworkBindingIndicators)
        val hasGenericStrongUnderlyingNetworkBinding = hasAny(
            detected,
            *genericStrongUnderlyingNetworkBindingIndicators,
        )
        val hasPublicIpProbe = hasAny(
            detected,
            "ifconfig.me",
            "checkip.amazonaws.com",
            "api.ipify.org",
            "ip.sb",
            "api.ipapi.is",
            "iplocate.io",
            "ipv4-internet.yandex.net",
            "ipv6-internet.yandex.net",
            "ip.mail.ru",
            "ifconfig.me/ip",
            "api4.ipify.org",
            "api-ipv4.ip.sb",
            "api-ipv6.ip.sb",
            "ip-api.com",
            "mobileproxy.passport.yandex.net/tmgrdfrend/checkvpn",
            "relay-api.eu.2gis.com/v1/vpn-detection-free",
            "vpn-detection-free",
        )
        val hasGeoOrLocationProbe = hasAny(
            detected,
            "api.ipapi.is",
            "iplocate.io",
            "beacondb.net",
            "ip-api.com",
            "GeoLocator",
            "isProxy",
            "isHosting",
        )
        val hasUnderlyingRknBindingSignature = hasAny(detected, *underlyingRknBindingIndicators)
        val hasUnderlyingRknLeakSignature = hasAny(detected, *underlyingRknLeakIndicators)
        val hasUnderlyingRknSignature = hasUnderlyingRknBindingSignature || hasUnderlyingRknLeakSignature
        val hasUnderlyingNetworkEnumeration = hasExactUnderlyingNetworkEnumeration ||
            has("getAllNetworks", detected)
        val hasUnderlyingNetworkBinding = hasExactUnderlyingNetworkBinding ||
            hasGenericStrongUnderlyingNetworkBinding ||
            hasUnderlyingRknBindingSignature ||
            (has("getSocketFactory", detected) && hasUnderlyingRknSignature)
        val hasNetworkBindingProbe = hasUnderlyingNetworkEnumeration && hasUnderlyingNetworkBinding
        val hasNetworkBindingPublicIpProbe = hasNetworkBindingProbe && hasPublicIpProbe
        val hasRknDeclaredUnderlyingBypassProbe = hasUnderlyingRknBindingSignature &&
            (
                hasUnderlyingRknLeakSignature ||
                    hasUnderlyingNetworkEnumeration ||
                    hasPublicIpProbe ||
                    hasExplicitVpnStateContext ||
                    hasVpnAppDiscovery
                )
        val hasUnderlyingNetworkBypassProbe = hasRknDeclaredUnderlyingBypassProbe ||
            (
                hasNetworkBindingProbe &&
                    (
                        hasUnderlyingRknSignature ||
                            (
                                hasExplicitVpnStateContext &&
                                    (hasExactUnderlyingNetworkBinding || hasGenericStrongUnderlyingNetworkBinding)
                                ) ||
                            (
                                hasVpnAppDiscovery &&
                                    hasPublicIpProbe &&
                                    (hasExactUnderlyingNetworkBinding || hasGenericStrongUnderlyingNetworkBinding)
                                )
                        )
                )
        val hasPublicIpProbeWithNetworkContext = hasPublicIpProbe &&
            (
                hasExplicitVpnStateContext ||
                    hasLocalProxyProbe ||
                    hasSocksExitIpProbe ||
                    hasHardXrayApiProbe ||
                    hasHardClashApiProbe ||
                    hasActiveVpnDumpsysProbe ||
                    hasUnderlyingNetworkBypassProbe
                )
        if (hasPublicIpProbeWithNetworkContext) {
            addExplanation(
                type = AppRiskFindingType.PUBLIC_IP,
                indicator = "public IP probing with network-state context",
                strength = AppRiskSignalStrength.MEDIUM,
                "ifconfig.me",
                "ifconfig.me/ip",
                "checkip.amazonaws.com",
                "api.ipify.org",
                "api4.ipify.org",
                "ip.sb",
                "api-ipv4.ip.sb",
                "api-ipv6.ip.sb",
                "api.ipapi.is",
                "iplocate.io",
                "ipv4-internet.yandex.net",
                "ipv6-internet.yandex.net",
                "ip.mail.ru",
                "ip-api.com",
                "mobileproxy.passport.yandex.net/tmgrdfrend/checkvpn",
                "relay-api.eu.2gis.com/v1/vpn-detection-free",
                "vpn-detection-free",
            )
        } else if (hasGeoOrLocationProbe) {
            addExplanation(
                type = AppRiskFindingType.PUBLIC_IP,
                indicator = "GeoIP/location probe endpoint",
                strength = AppRiskSignalStrength.LOW,
                "api.ipapi.is",
                "iplocate.io",
                "beacondb.net",
                "ip-api.com",
                "GeoLocator",
                "isProxy",
                "isHosting",
            )
        } else if (hasPublicIpProbe) {
            addExplanation(
                type = AppRiskFindingType.PUBLIC_IP,
                indicator = "public IP endpoint",
                strength = AppRiskSignalStrength.LOW,
                "ifconfig.me",
                "ifconfig.me/ip",
                "checkip.amazonaws.com",
                "api.ipify.org",
                "api4.ipify.org",
                "ip.sb",
                "api-ipv4.ip.sb",
                "api-ipv6.ip.sb",
                "ipv4-internet.yandex.net",
                "ipv6-internet.yandex.net",
                "ip.mail.ru",
                "ip-api.com",
                "mobileproxy.passport.yandex.net/tmgrdfrend/checkvpn",
                "relay-api.eu.2gis.com/v1/vpn-detection-free",
                "vpn-detection-free",
            )
        }

        if (hasUnderlyingRknSignature) {
            addExplanation(
                type = AppRiskFindingType.BYPASS,
                indicator = "RKN underlying-network probe signatures",
                strength = if (hasUnderlyingRknBindingSignature) {
                    AppRiskSignalStrength.HIGH
                } else {
                    AppRiskSignalStrength.MEDIUM
                },
                *underlyingRknIndicators,
            )
        }

        if (hasUnderlyingNetworkBypassProbe) {
            addFinding(
                type = AppRiskFindingType.BYPASS,
                indicator = if (hasPublicIpProbe) {
                    "underlying-network bypass probing + public IP comparison"
                } else {
                    "underlying-network bypass probing"
                },
                strength = AppRiskSignalStrength.HIGH,
                score = if (hasPublicIpProbe) 95 else 90,
                *exactUnderlyingNetworkEnumerationIndicators,
                "getAllNetworks",
                *exactUnderlyingNetworkBindingIndicators,
                *genericStrongUnderlyingNetworkBindingIndicators,
                "getSocketFactory",
                *underlyingRknIndicators,
                "ifconfig.me",
                "checkip.amazonaws.com",
                "ipv4-internet.yandex.net",
                "ipv6-internet.yandex.net",
            )
        } else if (hasNetworkBindingPublicIpProbe) {
            val score = if (hasVpnAppDiscovery) 55 else 0
            addFinding(
                type = AppRiskFindingType.BYPASS,
                indicator = "network binding + public IP probe",
                strength = when {
                    score > 0 -> AppRiskSignalStrength.HIGH
                    hasNetworkLibrary -> AppRiskSignalStrength.LOW
                    else -> AppRiskSignalStrength.MEDIUM
                },
                score = score,
                *exactUnderlyingNetworkEnumerationIndicators,
                "getAllNetworks",
                *exactUnderlyingNetworkBindingIndicators,
                *genericStrongUnderlyingNetworkBindingIndicators,
                "getSocketFactory",
                *underlyingRknIndicators,
                "ifconfig.me",
                "checkip.amazonaws.com",
                "api.ipify.org",
                "ip.sb",
            )
        }

        val hasTelemetry = hasAny(
            detected,
            "is_vpn",
            "isVpn",
            "vpn_enabled",
            "vpnEnabled",
            "isVpnConnected",
            "is_vpn_on",
            "vpn_status",
            "VpnStatusResponse",
            "setVpn",
            "CheckVpnStatusUseCase",
            "checkVpnStatusUseCase",
            "VpnStatusChangedCommand",
            "mobileproxy.passport.yandex.net/tmgrdfrend/checkvpn",
            "relay-api.eu.2gis.com/v1/vpn-detection-free",
            "vpn-detection-free",
            "trace-flow.ru/api/v1/report",
            "apptracer.ru",
            "vk-analytics.ru",
            "api.oneme.ru",
        )
        val hasVpnBlockingPolicy = hasAny(
            detected,
            "VpnChallengeActivity",
            "android_block_vpn",
        )
        val hasProbabilisticVpnModel = hasAny(
            detected,
            "VpnProbabilityConnectivityChecker",
            "ru.zen.zapret",
            "VpnClientStatus",
            "vpnLikelihood",
        )
        if (hasTelemetry) {
            addExplanation(
                type = AppRiskFindingType.TELEMETRY,
                indicator = "VPN status telemetry field",
                strength = AppRiskSignalStrength.LOW,
                "is_vpn",
                "isVpn",
                "vpn_enabled",
                "vpnEnabled",
                "isVpnConnected",
                "is_vpn_on",
                "vpn_status",
                "VpnStatusResponse",
                "setVpn",
                "CheckVpnStatusUseCase",
                "checkVpnStatusUseCase",
                "VpnStatusChangedCommand",
                "mobileproxy.passport.yandex.net/tmgrdfrend/checkvpn",
                "relay-api.eu.2gis.com/v1/vpn-detection-free",
                "vpn-detection-free",
                "trace-flow.ru/api/v1/report",
                "apptracer.ru",
                "vk-analytics.ru",
                "api.oneme.ru",
            )
        }
        if (hasVpnBlockingPolicy) {
            addExplanation(
                type = AppRiskFindingType.COMBINED,
                indicator = "server-controlled VPN blocking UI",
                strength = AppRiskSignalStrength.MEDIUM,
                "VpnChallengeActivity",
                "android_block_vpn",
            )
        }
        if (hasProbabilisticVpnModel) {
            addExplanation(
                type = AppRiskFindingType.COMBINED,
                indicator = "probabilistic VPN detection model",
                strength = AppRiskSignalStrength.MEDIUM,
                "VpnProbabilityConnectivityChecker",
                "ru.zen.zapret",
                "VpnClientStatus",
                "vpnLikelihood",
                "NetworkInterface.getMTU",
                "getMTU",
                "VPN_MTU_RANGE",
                "MtuCheckResult",
            )
        }

        val hasQueryAllPackages = has("QUERY_ALL_PACKAGES", detected) ||
            Manifest.permission.QUERY_ALL_PACKAGES in requestedPermissions
        if (hasQueryAllPackages) {
            val score = if (hasVpnAppDiscovery) 10 else 0
            addFinding(
                type = AppRiskFindingType.PACKAGE_VISIBILITY,
                indicator = "QUERY_ALL_PACKAGES",
                strength = if (score > 0) AppRiskSignalStrength.HIGH else AppRiskSignalStrength.LOW,
                evidence = evidenceFor(detected, "QUERY_ALL_PACKAGES").ifBlank { "manifest permission" },
                score = score,
            )
        } else if (hasQueryIntent && !hasVpnService) {
            addExplanation(
                type = AppRiskFindingType.PACKAGE_VISIBILITY,
                indicator = "PackageManager app queries",
                strength = AppRiskSignalStrength.LOW,
                "queryIntentServices",
                "queryIntentActivities",
            )
        }

        if (findings.isNotEmpty() && Manifest.permission.ACCESS_NETWORK_STATE in requestedPermissions) {
            addFinding(
                type = AppRiskFindingType.NETWORK_PERMISSION,
                indicator = "ACCESS_NETWORK_STATE",
                strength = AppRiskSignalStrength.LOW,
                evidence = "manifest permission",
                score = 0,
            )
        }

        val hasCallTransportProbe = hasAny(
            detected,
            "TELEGRAM_CALL_TRANSPORT",
            "STUN_PROBE",
            "StunBindingClient",
            "MtProtoProber",
            "Socks5UdpAssociateClient",
        )
        if (hasCallTransportProbe) {
            addExplanation(
                type = AppRiskFindingType.COMBINED,
                indicator = "call transport probe signatures",
                strength = AppRiskSignalStrength.MEDIUM,
                "TELEGRAM_CALL_TRANSPORT",
                "STUN_PROBE",
                "StunBindingClient",
                "MtProtoProber",
                "Socks5UdpAssociateClient",
            )
        }

        val hasRknVerdictMatrix = hasAny(
            detected,
            "VerdictEngine",
            "EvidenceSource",
            "DIRECT_NETWORK_CAPABILITIES",
            "INDIRECT_NETWORK_CAPABILITIES",
        )
        if (hasRknVerdictMatrix) {
            addExplanation(
                type = AppRiskFindingType.COMBINED,
                indicator = "RKNHardening verdict matrix signatures",
                strength = AppRiskSignalStrength.MEDIUM,
                "VerdictEngine",
                "EvidenceSource",
                "DIRECT_NETWORK_CAPABILITIES",
                "INDIRECT_NETWORK_CAPABILITIES",
            )
        }

        val hasInterfaceTunnelProbe = hasInterfaceApi && hasTunnelName
        val hasVpnEnumerationMethod = hasVpnService && hasQueryIntentServices
        val hasServerOrTelemetryPath = hasTelemetry || hasVpnBlockingPolicy
        val directVpnApiIndicators = arrayOf(
            "TRANSPORT_VPN",
            "NetworkCapabilities.TRANSPORT_VPN",
            "hasTransport(4)",
            "getNetworkCapabilities",
            "activeNetwork",
            "IS_VPN",
            "VpnTransportInfo",
        )
        val interfaceTunnelIndicators = arrayOf(
            "NetworkInterface",
            "getNetworkInterfaces",
            AppRiskRules.TUNNEL_INTERFACE_INDICATOR,
        )
        val procSocketIndicators = arrayOf(
            "/proc/net/tcp",
            "/proc/net/tcp6",
            "/proc/net/udp",
            "/proc/net/udp6",
            "/proc/self/net/tcp",
        )
        val proxyIndicators = arrayOf(
            "http.proxyHost",
            "http.proxyPort",
            "https.proxyHost",
            "socksProxyHost",
            "socksProxyPort",
            "getDefaultProxy",
            "ProxyInfo",
            "getPacFileUrl",
            "ProxySelector",
        )
        val torIndicators = arrayOf("org.torproject.torbrowser", "org.torproject.torbrowser_alpha", "torbrowser")
        val vpnEnumerationIndicators = arrayOf(
            "android.net.VpnService",
            "VpnService.SERVICE_INTERFACE",
            "queryIntentServices",
            "InstalledVpnAppDetector",
            "VpnAppCatalog",
            "VpnClientSignal",
            "MatchedVpnApp",
            "VPN_SERVICE_DECLARATION",
        ) + AppRiskRules.vpnClientPackageNames.toTypedArray()
        val mtuIndicators = arrayOf(
            "NetworkInterface.getMTU",
            "getMTU",
            "VPN_MTU_RANGE",
            "MtuCheckResult",
        )
        val probabilisticIndicators = arrayOf(
            "VpnProbabilityConnectivityChecker",
            "ru.zen.zapret",
            "VpnClientStatus",
            "vpnLikelihood",
        )
        val blockingPolicyIndicators = arrayOf("VpnChallengeActivity", "android_block_vpn")
        val publicIpIndicators = arrayOf(
            "ifconfig.me",
            "ifconfig.me/ip",
            "checkip.amazonaws.com",
            "api.ipify.org",
            "api4.ipify.org",
            "ip.sb",
            "api-ipv4.ip.sb",
            "api-ipv6.ip.sb",
            "api.ipapi.is",
            "iplocate.io",
            "ipv4-internet.yandex.net",
            "ipv6-internet.yandex.net",
            "ip.mail.ru",
            "ip-api.com",
            "mobileproxy.passport.yandex.net/tmgrdfrend/checkvpn",
            "relay-api.eu.2gis.com/v1/vpn-detection-free",
            "vpn-detection-free",
        )
        val underlyingBypassIndicators = (
            exactUnderlyingNetworkEnumerationIndicators +
                arrayOf("getAllNetworks") +
                exactUnderlyingNetworkBindingIndicators +
                genericStrongUnderlyingNetworkBindingIndicators +
                arrayOf("getSocketFactory") +
                underlyingRknIndicators
            )
            .distinct()
            .toTypedArray()
        val telemetryIndicators = arrayOf(
            "is_vpn",
            "isVpn",
            "vpn_enabled",
            "vpnEnabled",
            "isVpnConnected",
            "is_vpn_on",
            "vpn_status",
            "VpnStatusResponse",
            "setVpn",
            "CheckVpnStatusUseCase",
            "checkVpnStatusUseCase",
            "VpnStatusChangedCommand",
            "mobileproxy.passport.yandex.net/tmgrdfrend/checkvpn",
            "relay-api.eu.2gis.com/v1/vpn-detection-free",
            "vpn-detection-free",
            "trace-flow.ru/api/v1/report",
            "apptracer.ru",
            "vk-analytics.ru",
            "api.oneme.ru",
        )
        val runtimeProbeIndicators = arrayOf(
            "getLinkProperties",
            "isDefaultRoute",
            "/proc/net/route",
            "dnsServers",
            "getDnsServers",
            "ProcNetScanner",
            "CLIENT_SIGNATURES",
            "scanListeningPorts",
            "identifyVpnClient",
            "scanEstablishedConnections",
            "EstablishedConnection",
            "ListeningPort",
            "VpnClientGuess",
            "serverGuess",
            "PortScanner",
            "KNOWN_PORTS",
            "scanKnownPorts",
            "scanFullRange",
            "AuthProbe",
            "supportsNoAuth",
            "supportsPassword",
            "bruteForceCredentials",
            "udpBypassPossible",
            "COMMON_PASSWORDS",
            "dumpsys",
            "vpn_management",
            "activity services android.net.VpnService",
            "Active package name:",
            "ClashAPIProbe",
            "ClashAPIResult",
            "ClashConnection",
            "/connections",
            "/configs",
            "/proxies",
            "destinationIP",
            "leakedDestIPs",
            "TELEGRAM_CALL_TRANSPORT",
            "STUN_PROBE",
            "StunBindingClient",
            "MtProtoProber",
            "Socks5UdpAssociateClient",
        ) + underlyingBypassIndicators
        val activePdfMethodNames = buildList {
            if (hasStrongNetworkCapabilitiesCheck) add("Android NetworkCapabilities VPN state")
            if (hasInterfaceTunnelProbe) add("NetworkInterface tunnel name inspection")
            if (hasProcNetSockets) add("/proc/net socket table inspection")
            if (hasSystemProxyApi) add("system proxy inspection")
            if (hasTorPackageProbe) add("Tor package probing")
            if (hasVpnEnumerationMethod) add("VpnService package discovery")
            if (hasMtuProbe && (hasInterfaceApi || hasTunnelName || hasProbabilisticVpnModel)) {
                add("VPN MTU anomaly inspection")
            }
            if (hasProbabilisticVpnModel) add("weighted VPN probability model")
            if (hasVpnBlockingPolicy) add("server-controlled VPN blocking UI")
            if (hasPublicIpProbeWithNetworkContext) add("public IP endpoint with network-state context")
            if (hasUnderlyingNetworkBypassProbe) add("underlying-network bypass probing")
        }
        val pdfVpnDetectionMethodCount = activePdfMethodNames.size
        val pdfMethodIndicators = buildList {
            if (hasStrongNetworkCapabilitiesCheck) addAll(directVpnApiIndicators)
            if (hasInterfaceTunnelProbe) addAll(interfaceTunnelIndicators)
            if (hasProcNetSockets) addAll(procSocketIndicators)
            if (hasSystemProxyApi) addAll(proxyIndicators)
            if (hasTorPackageProbe) addAll(torIndicators)
            if (hasVpnEnumerationMethod) addAll(vpnEnumerationIndicators)
            if (hasMtuProbe && (hasInterfaceApi || hasTunnelName || hasProbabilisticVpnModel)) addAll(mtuIndicators)
            if (hasProbabilisticVpnModel) addAll(probabilisticIndicators)
            if (hasVpnBlockingPolicy) addAll(blockingPolicyIndicators)
            if (hasPublicIpProbeWithNetworkContext) addAll(publicIpIndicators)
            if (hasUnderlyingNetworkBypassProbe) addAll(underlyingBypassIndicators)
        }
            .distinct()
            .toTypedArray()
        val pdfRelatedIndicators = buildList {
            if (hasStrongNetworkCapabilitiesCheck) {
                add(
                    if (hasVpnCapabilitiesStringMarker) {
                        "NetworkCapabilities VPN string markers"
                    } else {
                        "TRANSPORT_VPN / hasTransport"
                    },
                )
            }
            if (hasInterfaceTunnelProbe) add("NetworkInterface + tunnel interface name")
            if (hasProcNetSockets) add("/proc/net socket tables")
            if (hasSystemProxyApi) add("system proxy inspection")
            if (hasTorPackageProbe) add("Tor Browser package names")
            if (hasVpnEnumerationMethod) add("VpnService.SERVICE_INTERFACE query")
            if (hasMtuProbe && (hasInterfaceApi || hasTunnelName || hasProbabilisticVpnModel)) add("MTU anomaly inspection")
            if (hasProbabilisticVpnModel) add("probabilistic VPN detection model")
            if (hasVpnBlockingPolicy) add("server-controlled VPN blocking UI")
            if (hasPublicIpProbeWithNetworkContext) add("public IP probing with network-state context")
            if (hasUnderlyingRknSignature) add("RKN underlying-network probe signatures")
            if (hasUnderlyingNetworkBypassProbe) {
                add(
                    if (hasPublicIpProbe) {
                        "underlying-network bypass probing + public IP comparison"
                    } else {
                        "underlying-network bypass probing"
                    },
                )
            }
        }
        val hasAdditionalVpnRuntimeProbe = hasRoutingSignals ||
            hasDnsSignals ||
            hasProcNetFingerprinting ||
            hasLocalProxyProbe ||
            hasLocalPortScannerSignature ||
            hasSocksAuthProbe ||
            hasActiveVpnDumpsysProbe ||
            hasClashApiEndpointSet ||
            hasUnderlyingRknSignature ||
            hasCallTransportProbe
        val activeMethodsDescription = if (activePdfMethodNames.isEmpty()) {
            "No RKS-methodology VPN detection method group was correlated."
        } else {
            "Triggered VPN detection method groups: ${activePdfMethodNames.joinToString(separator = "; ")}."
        }

        if (hasVpnAppDiscovery && hasTelemetry) {
            addCombinationFinding(
                indicator = "VPN app enumeration with telemetry/server path",
                strength = AppRiskSignalStrength.HIGH,
                score = 70,
                description = "VPN app discovery is paired with VPN-status telemetry or a server endpoint.",
                relatedIndicators = listOf(
                    "VpnService.SERVICE_INTERFACE query",
                    "RKN-style VPN app catalog",
                    "known VPN/proxy package signatures",
                    "VPN status telemetry field",
                ),
                *(vpnEnumerationIndicators + telemetryIndicators),
            )
        }
        if (hasVpnBlockingPolicy && (hasStrongNetworkCapabilitiesCheck || hasTelemetry || pdfVpnDetectionMethodCount >= 2)) {
            addCombinationFinding(
                indicator = "server-controlled VPN blocking flow",
                strength = AppRiskSignalStrength.HIGH,
                score = if (pdfVpnDetectionMethodCount >= 3) 85 else 70,
                description = "VPN detection signals are connected to a server-controlled blocking UI or policy branch. $activeMethodsDescription",
                relatedIndicators = pdfRelatedIndicators + listOf("VPN status telemetry field"),
                *(blockingPolicyIndicators + telemetryIndicators + pdfMethodIndicators),
            )
        }
        if (hasProbabilisticVpnModel && pdfVpnDetectionMethodCount >= 3) {
            addCombinationFinding(
                indicator = "weighted multi-signal VPN probability model",
                strength = AppRiskSignalStrength.HIGH,
                score = if (hasServerOrTelemetryPath) 80 else 65,
                description = "A probability model is fed by several VPN detection channels. $activeMethodsDescription",
                relatedIndicators = pdfRelatedIndicators,
                *(probabilisticIndicators + pdfMethodIndicators + telemetryIndicators),
            )
        }
        when {
            pdfVpnDetectionMethodCount >= 4 && hasServerOrTelemetryPath -> addCombinationFinding(
                indicator = "multi-vector VPN surveillance with telemetry",
                strength = AppRiskSignalStrength.HIGH,
                score = 85,
                description = "$activeMethodsDescription Telemetry or server-control signals are also present.",
                relatedIndicators = pdfRelatedIndicators + listOf("VPN status telemetry field"),
                *(pdfMethodIndicators + telemetryIndicators + blockingPolicyIndicators),
            )
            pdfVpnDetectionMethodCount >= 3 && hasServerOrTelemetryPath -> addCombinationFinding(
                indicator = "multi-vector VPN detection with telemetry",
                strength = AppRiskSignalStrength.HIGH,
                score = 75,
                description = "$activeMethodsDescription Telemetry or server-control signals are also present.",
                relatedIndicators = pdfRelatedIndicators + listOf("VPN status telemetry field"),
                *(pdfMethodIndicators + telemetryIndicators + blockingPolicyIndicators),
            )
            pdfVpnDetectionMethodCount >= 3 -> addCombinationFinding(
                indicator = "multi-vector VPN detection",
                strength = AppRiskSignalStrength.HIGH,
                score = 60,
                description = activeMethodsDescription,
                relatedIndicators = pdfRelatedIndicators,
                *pdfMethodIndicators,
            )
            pdfVpnDetectionMethodCount >= 2 && (hasServerOrTelemetryPath || hasVpnAppDiscovery) -> addCombinationFinding(
                indicator = "VPN detection method combination",
                strength = AppRiskSignalStrength.HIGH,
                score = 50,
                description = "$activeMethodsDescription The combination is amplified by telemetry, server-control, or VPN-app discovery context.",
                relatedIndicators = pdfRelatedIndicators + listOf(
                    "VPN status telemetry field",
                    "VpnService.SERVICE_INTERFACE query",
                    "RKN-style VPN app catalog",
                    "known VPN/proxy package signatures",
                ),
                *(pdfMethodIndicators + telemetryIndicators + vpnEnumerationIndicators + blockingPolicyIndicators),
            )
            hasStrongNetworkCapabilitiesCheck && hasTelemetry -> addCombinationFinding(
                indicator = "TRANSPORT_VPN telemetry path",
                strength = AppRiskSignalStrength.HIGH,
                score = 50,
                description = "Direct Android VPN state checks are paired with VPN-status telemetry fields or endpoints.",
                relatedIndicators = listOf(
                    if (hasVpnCapabilitiesStringMarker) {
                        "NetworkCapabilities VPN string markers"
                    } else {
                        "TRANSPORT_VPN / hasTransport"
                    },
                    "VPN status telemetry field",
                ),
                *(directVpnApiIndicators + telemetryIndicators),
            )
            hasInterfaceTunnelProbe && hasTelemetry -> addCombinationFinding(
                indicator = "tunnel interface telemetry path",
                strength = AppRiskSignalStrength.HIGH,
                score = 50,
                description = "Network-interface tunnel detection is paired with VPN-status telemetry fields or endpoints.",
                relatedIndicators = listOf(
                    "NetworkInterface + tunnel interface name",
                    "VPN status telemetry field",
                ),
                *(interfaceTunnelIndicators + telemetryIndicators),
            )
            hasAdditionalVpnRuntimeProbe && hasServerOrTelemetryPath && pdfVpnDetectionMethodCount >= 1 -> addCombinationFinding(
                indicator = "VPN runtime probe with telemetry context",
                strength = AppRiskSignalStrength.HIGH,
                score = 45,
                description = "Runtime network probes are paired with telemetry or server-control context. $activeMethodsDescription",
                relatedIndicators = pdfRelatedIndicators + listOf("VPN status telemetry field"),
                *(runtimeProbeIndicators + telemetryIndicators + blockingPolicyIndicators + pdfMethodIndicators),
            )
        }

        val score = findings.sumOf(AppRiskFinding::score).coerceAtMost(100)
        val hasCriticalRknPath = hasHardXrayApiProbe ||
            hasHardClashApiProbe ||
            hasSocksExitIpProbe ||
            hasUnderlyingNetworkBypassProbe ||
            (hasActiveVpnDumpsysProbe && (hasVpnAppDiscovery || has("vpn_management", detected))) ||
            (hasLocalProxyProbe && hasLocalProxyBypassComparison)
        val cappedScore = if (hasCriticalRknPath || score < CRITICAL_SCORE) {
            score
        } else {
            score.coerceAtMost(CRITICAL_SCORE - 1)
        }
        val riskLevel = riskLevelFor(cappedScore, findings)

        return AppRiskScanResult(
            riskScore = cappedScore,
            riskLevel = riskLevel,
            findings = findings
                .sortedWith(
                    compareByDescending<AppRiskFinding> { it.score }
                        .thenByDescending { it.strength.ordinal }
                        .thenBy { it.type.name }
                        .thenBy { it.indicator },
                ),
            knownGroup = knownApp?.group,
            knownAppName = knownApp?.appName,
            knownStatus = knownApp?.status,
            scannedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun finding(
        type: AppRiskFindingType,
        indicator: String,
        strength: AppRiskSignalStrength,
        evidence: String,
        score: Int,
        matchedSignals: List<AppRiskMatchedSignal>,
        relatedIndicators: List<String>,
        description: String,
    ) = AppRiskFinding(
        type = type,
        indicator = indicator,
        strength = strength,
        evidence = evidence,
        score = score,
        matchedSignals = matchedSignals,
        relatedIndicators = relatedIndicators,
        description = description,
    )

    private fun riskLevelFor(
        score: Int,
        findings: List<AppRiskFinding>,
    ): AppRiskLevel {
        return when {
            score >= CRITICAL_SCORE -> AppRiskLevel.CRITICAL
            score >= HIGH_SCORE -> AppRiskLevel.HIGH
            score >= MEDIUM_SCORE -> AppRiskLevel.MEDIUM
            findings.any { it.strength == AppRiskSignalStrength.MEDIUM } -> AppRiskLevel.MEDIUM
            findings.isNotEmpty() -> AppRiskLevel.LOW
            else -> AppRiskLevel.CLEAN
        }
    }

    private fun has(indicator: String, detected: Map<String, String>): Boolean {
        return indicator in detected
    }

    private fun hasAny(detected: Map<String, String>, vararg indicators: String): Boolean {
        return indicators.any { it in detected }
    }

    private fun evidenceFor(detected: Map<String, String>, vararg indicators: String): String {
        return indicators.firstNotNullOfOrNull { detected[it] }.orEmpty()
    }

    private fun signalsFor(
        detected: Map<String, String>,
        vararg indicators: String,
    ): List<AppRiskMatchedSignal> {
        return indicators
            .asSequence()
            .distinct()
            .mapNotNull { indicator ->
                detected[indicator]?.let { evidence ->
                    AppRiskMatchedSignal(
                        indicator = indicator,
                        evidence = evidence,
                    )
                }
            }
            .toList()
    }

    private fun formatMatchedEvidence(
        evidence: String,
        matchedValue: String,
    ): String {
        val normalizedMatch = matchedValue
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .truncateDexEvidence()
        if (normalizedMatch.isBlank()) return evidence
        if (evidence.contains(normalizedMatch)) return evidence
        return "$evidence -> $normalizedMatch"
    }

    private fun findKnownApp(packageName: String): KnownAppRule? {
        return AppRiskRules.knownApps
            .mapNotNull { rule ->
                val priority = rule.masks.maxOfOrNull { mask -> maskPriority(mask, packageName) } ?: -1
                if (priority >= 0) rule to priority else null
            }
            .maxWithOrNull(
                compareBy<Pair<KnownAppRule, Int>> { it.second }
                    .thenBy { if (it.first.status == "confirmed_pdf") 1 else 0 },
            )
            ?.first
    }

    private fun maskPriority(mask: String, packageName: String): Int {
        return when {
            "*" !in mask && packageName == mask -> 100
            mask.endsWith(".*") && packageName.startsWith(mask.removeSuffix("*")) -> 50
            mask.endsWith("*") && packageName.startsWith(mask.removeSuffix("*")) -> 40
            "*" in mask && wildcardRegex(mask).matches(packageName) -> 20
            else -> -1
        }
    }

    private fun wildcardRegex(mask: String): Regex {
        return Regex(
            pattern = "^" + mask.split("*").joinToString(".*") { Regex.escape(it) } + "$",
        )
    }

    private fun buildCacheKey(
        packageName: String,
        versionCode: Long?,
        apkPaths: List<String>,
    ): String {
        val apkSignature = apkPaths
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(separator = "|") { path ->
                val file = File(path)
                "${file.path}:${file.length()}:${file.lastModified()}"
            }
        return "$packageName:${versionCode ?: -1}:${AppRiskRules.RULES_VERSION}:$apkSignature"
    }

    private fun ZipEntry.shouldScan(): Boolean {
        return isManifest() ||
            name == "resources.arsc" ||
            isDex() ||
            (name.startsWith("lib/") && name.endsWith(".so"))
    }

    private fun ZipEntry.isManifest(): Boolean {
        return name == "AndroidManifest.xml"
    }

    private fun ZipEntry.isDex(): Boolean {
        return name.startsWith("classes") && name.endsWith(".dex")
    }

    private fun dexMethodEvidence(
        baseEvidence: String,
        definingClass: String?,
        methodName: String,
    ): String {
        return "$baseEvidence:${dexTypeName(definingClass).ifBlank { UNKNOWN_DEX_CLASS }}#$methodName"
    }

    private fun dexReferenceEvidence(
        baseEvidence: String,
        reference: Reference,
    ): String {
        val referenceLabel = dexReferenceLabel(reference)
        return if (referenceLabel.isBlank()) {
            baseEvidence
        } else {
            "$baseEvidence -> $referenceLabel"
        }
    }

    private fun dexReferenceLabel(reference: Reference): String {
        return when (reference) {
            is StringReference -> "\"${reference.string.truncateDexEvidence()}\""
            is TypeReference -> dexTypeName(reference.type)
            is FieldReference -> "${dexTypeName(reference.definingClass)}#${reference.name}"
            is MethodReference -> "${dexTypeName(reference.definingClass)}#${reference.name}"
            else -> ""
        }
    }

    private fun dexTypeName(value: String?): String {
        if (value.isNullOrBlank()) return ""

        val arrayStripped = value.dropWhile { it == '[' }
        return if (arrayStripped.startsWith("L") && arrayStripped.endsWith(";")) {
            arrayStripped
                .removePrefix("L")
                .removeSuffix(";")
                .replace('/', '.')
                .replace('$', '.')
        } else {
            arrayStripped.replace('/', '.').replace('$', '.')
        }
    }

    private fun dexTypeTokens(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()

        val tokens = linkedSetOf(value)
        val normalized = dexTypeName(value)
        if (normalized.isNotBlank()) {
            tokens += normalized
            normalized.substringAfterLast('.', missingDelimiterValue = normalized)
                .takeIf(String::isNotBlank)
                ?.let { tokens += it }
            normalized.substringBeforeLast('.', missingDelimiterValue = "")
                .takeIf(String::isNotBlank)
                ?.let { tokens += it }
        }
        return tokens.toList()
    }

    private fun String.truncateDexEvidence(): String {
        return if (length <= MAX_DEX_REFERENCE_EVIDENCE_LENGTH) {
            this
        } else {
            take(MAX_DEX_REFERENCE_EVIDENCE_LENGTH - 3) + "..."
        }
    }

    private fun ByteArray.tail(size: Int): ByteArray {
        if (size <= 0 || isEmpty()) return ByteArray(0)
        val tailSize = minOf(size, this.size)
        return copyOfRange(this.size - tailSize, this.size)
    }

    private data class CompiledStringRule(
        val rule: RiskStringRule,
        val patterns: List<ByteArray>,
    ) {
        fun matchBytes(bytes: ByteArray): String? {
            if (patterns.any { pattern -> bytes.containsSlice(pattern) }) return rule.indicator
            val regex = rule.regex ?: return null
            return regex.find(bytes.toString(StandardCharsets.ISO_8859_1))?.value
                ?: regex.find(bytes.toString(StandardCharsets.UTF_16LE))?.value
        }

        fun matchText(value: String): String? {
            val regex = rule.regex
            return if (regex != null) {
                regex.find(value)?.value
            } else {
                rule.indicator.takeIf(value::contains)
            }
        }
    }

    private companion object {
        private const val CRITICAL_SCORE = 90
        private const val HIGH_SCORE = 50
        private const val MEDIUM_SCORE = 20
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_DEX_REFERENCE_EVIDENCE_LENGTH = 96
        private const val UNKNOWN_DEX_CLASS = "<unknown-class>"

        val compiledRules = AppRiskRules.stringRules.map { rule ->
            CompiledStringRule(
                rule = rule,
                patterns = if (rule.regex == null) {
                    listOf(
                        rule.indicator.toByteArray(StandardCharsets.UTF_8),
                        rule.indicator.toUtf16LeBytes(),
                    )
                } else {
                    emptyList()
                },
            )
        }

        val literalRulesByIndicator = compiledRules
            .filter { compiledRule -> compiledRule.rule.regex == null }
            .groupBy { compiledRule -> compiledRule.rule.indicator }

        val maxPatternLength = compiledRules
            .flatMap(CompiledStringRule::patterns)
            .maxOf { it.size }
        val scanTailSize = maxOf(maxPatternLength - 1, REGEX_TAIL_SIZE)

        private const val REGEX_TAIL_SIZE = 32

        fun String.toUtf16LeBytes(): ByteArray {
            val result = ByteArray(length * 2)
            forEachIndexed { index, char ->
                result[index * 2] = char.code.toByte()
                result[index * 2 + 1] = (char.code shr 8).toByte()
            }
            return result
        }

        fun ByteArray.containsSlice(pattern: ByteArray): Boolean {
            if (pattern.isEmpty()) return true
            if (pattern.size > size) return false
            val maxStart = size - pattern.size
            for (start in 0..maxStart) {
                var matches = true
                for (offset in pattern.indices) {
                    if (this[start + offset] != pattern[offset]) {
                        matches = false
                        break
                    }
                }
                if (matches) return true
            }
            return false
        }
    }
}
