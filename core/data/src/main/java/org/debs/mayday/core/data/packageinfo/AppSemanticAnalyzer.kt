package org.debs.mayday.core.data.packageinfo

import net.dongliu.apk.parser.ApkFile
import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppSemanticAnalysisResult
import org.debs.mayday.core.model.AppSemanticEvidenceSource
import org.debs.mayday.core.model.AppSemanticRiskBucket
import org.debs.mayday.core.model.AppSemanticRiskScope
import org.debs.mayday.core.model.AppSemanticSignal
import org.debs.mayday.core.model.AppSemanticSignalType
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.jf.dexlib2.iface.instruction.OffsetInstruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.instruction.VariableRegisterInstruction
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.Reference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class AppSemanticAnalyzer @Inject constructor() {

    fun analyze(
        packageName: String,
        versionCode: Long?,
        apkPaths: List<String>,
        cancellationCheck: () -> Unit = {},
    ): AppSemanticAnalysisResult {
        val summary = MutableSemanticSummary(packageName = packageName)
        apkPaths
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .forEach { path ->
                cancellationCheck()
                scanApk(
                    path = path,
                    summary = summary,
                    cancellationCheck = cancellationCheck,
                )
            }

        val trustedVpnClient = packageName in AppRiskRules.vpnClientPackageNames &&
            summary.facts.any { it.kind == SemanticFactKind.MANIFEST_VPN_SERVICE_QUERY }
        val signals = buildSignals(summary, trustedVpnClient)
            .distinctBy { signal ->
                "${signal.scope}:${signal.source}:${signal.type}:${signal.title}:${signal.evidenceChain.joinToString("|")}"
            }
            .sortedWith(
                compareByDescending<AppSemanticSignal> { it.confidence }
                    .thenBy { it.scope.name }
                    .thenBy { it.type.name }
                    .thenBy { it.title },
            )
            .take(MAX_SIGNALS)

        val appBucket = bucketFor(signals, AppSemanticRiskScope.APP_CODE)
        val sdkBucket = bucketFor(signals, AppSemanticRiskScope.SDK_CODE)
        val nativeBucket = bucketFor(signals, AppSemanticRiskScope.NATIVE_CODE)
        val manifestBucket = bucketFor(signals, AppSemanticRiskScope.MANIFEST)
        val crossLayerBucket = bucketFor(signals, AppSemanticRiskScope.CROSS_LAYER)
        val score = listOf(
            appBucket.score,
            sdkBucket.score,
            nativeBucket.score,
            manifestBucket.score,
            crossLayerBucket.score,
        ).maxOrNull() ?: 0

        return AppSemanticAnalysisResult(
            score = score,
            riskLevel = riskLevelFor(score, signals),
            signals = signals,
            appCodeRisk = appBucket,
            sdkCodeRisk = sdkBucket,
            nativeCodeRisk = nativeBucket,
            manifestRisk = manifestBucket,
            crossLayerRisk = crossLayerBucket,
            methodsAnalyzed = summary.methodsAnalyzed,
            cfgNodeCount = summary.cfgNodeCount,
            cfgEdgeCount = summary.cfgEdgeCount,
            dfgEdgeCount = summary.dfgEdgeCount,
            scannedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun scanApk(
        path: String,
        summary: MutableSemanticSummary,
        cancellationCheck: () -> Unit,
    ) {
        cancellationCheck()
        val apkFile = File(path)
        if (!apkFile.isFile || !apkFile.canRead()) return

        scanManifest(apkFile, summary)
        try {
            ZipFile(apkFile).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    cancellationCheck()
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val evidence = "${apkFile.name}!/${entry.name}"
                    when {
                        entry.name.matches(DEX_ENTRY_PATTERN) -> zipFile.getInputStream(entry).use { input ->
                            scanDexEntry(
                                input = input,
                                evidencePrefix = evidence,
                                summary = summary,
                                cancellationCheck = cancellationCheck,
                            )
                        }
                        entry.name.endsWith(".so") -> zipFile.getInputStream(entry).use { input ->
                            scanNativeEntry(
                                input = input,
                                evidencePrefix = evidence,
                                summary = summary,
                                cancellationCheck = cancellationCheck,
                            )
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
        }
    }

    private fun scanManifest(
        apkFile: File,
        summary: MutableSemanticSummary,
    ) {
        runCatching {
            ApkFile(apkFile).use { parsedApk ->
                val manifestXml = parsedApk.manifestXml
                if (manifestXml.isNullOrBlank()) return@use
                val evidence = "${apkFile.name}!/AndroidManifest.xml:axml"
                if (manifestXml.contains("android.net.VpnService")) {
                    summary.addFact(
                        SemanticFact(
                            kind = SemanticFactKind.MANIFEST_VPN_SERVICE_QUERY,
                            scope = AppSemanticRiskScope.MANIFEST,
                            source = AppSemanticEvidenceSource.MANIFEST_ONLY,
                            evidence = "$evidence -> android.net.VpnService",
                            value = "android.net.VpnService",
                        ),
                    )
                }
                if (manifestXml.contains("android.permission.QUERY_ALL_PACKAGES")) {
                    summary.addFact(
                        SemanticFact(
                            kind = SemanticFactKind.MANIFEST_QUERY_ALL_PACKAGES,
                            scope = AppSemanticRiskScope.MANIFEST,
                            source = AppSemanticEvidenceSource.MANIFEST_ONLY,
                            evidence = "$evidence -> QUERY_ALL_PACKAGES",
                            value = "QUERY_ALL_PACKAGES",
                        ),
                    )
                }
                AppRiskRules.vpnClientPackageNames.forEach { packageName ->
                    if (manifestXml.contains(packageName)) {
                        summary.addFact(
                            SemanticFact(
                                kind = SemanticFactKind.MANIFEST_KNOWN_VPN_PACKAGE_QUERY,
                                scope = AppSemanticRiskScope.MANIFEST,
                                source = AppSemanticEvidenceSource.MANIFEST_ONLY,
                                evidence = "$evidence -> $packageName",
                                value = packageName,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun scanDexEntry(
        input: InputStream,
        evidencePrefix: String,
        summary: MutableSemanticSummary,
        cancellationCheck: () -> Unit,
    ) {
        cancellationCheck()
        val bytes = input.readBytes()
        val dexFile = runCatching {
            DexBackedDexFile(Opcodes.getDefault(), bytes)
        }.getOrNull() ?: return

        dexFile.classes.forEachIndexed { classIndex, classDef ->
            if (classIndex % CANCELLATION_CHECK_CLASS_INTERVAL == 0) {
                cancellationCheck()
            }
            val className = dexTypeName(classDef.type)
            val scope = if (className.startsWith(summary.packageName)) {
                AppSemanticRiskScope.APP_CODE
            } else {
                AppSemanticRiskScope.SDK_CODE
            }
            if (scope == AppSemanticRiskScope.SDK_CODE && shouldSkipSdkInfrastructureClass(className)) {
                return@forEachIndexed
            }
            val source = when (scope) {
                AppSemanticRiskScope.APP_CODE -> AppSemanticEvidenceSource.DIRECT_APP_CODE
                AppSemanticRiskScope.SDK_CODE -> AppSemanticEvidenceSource.SDK
                AppSemanticRiskScope.NATIVE_CODE -> AppSemanticEvidenceSource.NATIVE
                AppSemanticRiskScope.MANIFEST -> AppSemanticEvidenceSource.MANIFEST_ONLY
                AppSemanticRiskScope.CROSS_LAYER -> AppSemanticEvidenceSource.APP_TO_SDK
            }

            classDef.methods.forEachIndexed { methodIndex, method ->
                if (methodIndex % CANCELLATION_CHECK_METHOD_INTERVAL == 0) {
                    cancellationCheck()
                }
                val implementation = method.implementation ?: return@forEachIndexed
                val methodName = "${method.name}${method.parameterTypes.joinToString(prefix = "(", postfix = ")")}"
                val evidence = "$evidencePrefix:$className#${method.name}"
                val semantics = analyzeMethod(
                    evidence = evidence,
                    className = className,
                    methodName = methodName,
                    packageName = summary.packageName,
                    scope = scope,
                    source = source,
                    instructions = implementation.instructions,
                    cancellationCheck = cancellationCheck,
                )
                summary.methodsAnalyzed += 1
                summary.cfgNodeCount += semantics.cfgNodes
                summary.cfgEdgeCount += semantics.cfgEdges
                summary.dfgEdgeCount += semantics.dfgEdges
                semantics.facts.forEach(summary::addFact)
            }
        }
    }

    private fun scanNativeEntry(
        input: InputStream,
        evidencePrefix: String,
        summary: MutableSemanticSummary,
        cancellationCheck: () -> Unit,
    ) {
        cancellationCheck()
        extractAsciiStrings(input.readBytes()).forEachIndexed { index, value ->
            if (index % CANCELLATION_CHECK_NATIVE_STRING_INTERVAL == 0) {
                cancellationCheck()
            }
            scanNativeText(
                value = value,
                evidence = "$evidencePrefix -> $value",
                summary = summary,
            )
        }
    }

    private fun scanNativeText(
        value: String,
        evidence: String,
        summary: MutableSemanticSummary,
    ) {
        val fact = when {
            value in AppRiskRules.vpnClientPackageNames -> SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE
            value == "SO_BINDTODEVICE" || value.contains("bindSocket", ignoreCase = true) -> {
                SemanticFactKind.NETWORK_BYPASS_BINDING
            }
            value.contains("/proc/net/") || value.contains("/proc/self/net/") -> SemanticFactKind.PROC_SOCKET_TABLE
            isTunnelInterfaceText(value) -> SemanticFactKind.TUNNEL_INTERFACE_PROBE
            isPublicIpEndpoint(value) -> SemanticFactKind.PUBLIC_IP_PROBE
            isSocksOrLocalProxyText(value) -> SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE
            else -> null
        } ?: return
        summary.addFact(
            SemanticFact(
                kind = fact,
                scope = AppSemanticRiskScope.NATIVE_CODE,
                source = AppSemanticEvidenceSource.NATIVE,
                evidence = evidence,
                value = value,
            ),
        )
    }

    private fun analyzeMethod(
        evidence: String,
        className: String,
        methodName: String,
        packageName: String,
        scope: AppSemanticRiskScope,
        source: AppSemanticEvidenceSource,
        instructions: Iterable<Instruction>,
        cancellationCheck: () -> Unit,
    ): MethodSemanticResult {
        cancellationCheck()
        val facts = mutableListOf<SemanticFact>()
        val registerStrings = mutableMapOf<Int, String>()
        val registerInts = mutableMapOf<Int, Long>()
        val registerTags = mutableMapOf<Int, MutableSet<DataTag>>()
        var pendingResultTags = emptySet<DataTag>()
        var dfgEdges = 0
        var branchCount = 0

        fun addFact(
            kind: SemanticFactKind,
            detail: String,
            value: String = "",
        ) {
            facts += fact(
                kind = kind,
                scope = scope,
                source = source,
                evidence = detail,
                value = value,
                className = className,
                methodName = methodName,
            )
        }

        fun tagRegister(
            register: Int,
            vararg tags: DataTag,
        ) {
            if (tags.isEmpty()) return
            registerTags.getOrPut(register) { mutableSetOf() }.addAll(tags)
        }

        fun clearRegister(register: Int) {
            registerTags.remove(register)
            registerStrings.remove(register)
            registerInts.remove(register)
        }

        instructions.forEachIndexed { index, instruction ->
            if (instruction is OffsetInstruction) {
                branchCount += 1
            }
            if (index % CANCELLATION_CHECK_INSTRUCTION_INTERVAL == 0) {
                cancellationCheck()
            }
            val opcode = opcodeKey(instruction)
            val instructionEvidence = "$evidence@$index:$opcode"

            if (opcode.startsWith("move-result")) {
                val register = (instruction as? OneRegisterInstruction)?.registerA
                if (register != null && pendingResultTags.isNotEmpty()) {
                    tagRegister(register, *pendingResultTags.toTypedArray())
                    dfgEdges += pendingResultTags.size
                }
                pendingResultTags = emptySet()
                return@forEachIndexed
            } else if (!opcode.startsWith("move")) {
                pendingResultTags = emptySet()
            }

            val reference = runCatching {
                (instruction as? ReferenceInstruction)?.reference
            }.getOrNull()

            if (isConstStringInstruction(instruction) && reference is StringReference) {
                val register = (instruction as? OneRegisterInstruction)?.registerA
                if (register != null) {
                    clearRegister(register)
                    registerStrings[register] = reference.string
                    textTags(reference.string).forEach { tag -> tagRegister(register, tag) }
                }
            }
            if (isConstNumberInstruction(instruction)) {
                val register = (instruction as? OneRegisterInstruction)?.registerA
                val literal = (instruction as? NarrowLiteralInstruction)?.narrowLiteral?.toLong()
                    ?: (instruction as? WideLiteralInstruction)?.wideLiteral
                if (register != null && literal != null) {
                    clearRegister(register)
                    registerInts[register] = literal
                }
            }

            if (reference != null) {
                scanReference(
                    reference = reference,
                    evidence = instructionEvidence,
                    addFact = ::addFact,
                    tagRegister = { tag ->
                        (instruction as? OneRegisterInstruction)?.registerA?.let { register ->
                            tagRegister(register, tag)
                        }
                    },
                )
            }

            if (opcode.startsWith("invoke")) {
                val invokedMethod = reference as? MethodReference
                val argumentRegisters = instruction.registerList()
                val argumentTags = argumentRegisters.flatMap { registerTags[it].orEmpty() }.toSet()
                val argumentStrings = argumentRegisters.mapNotNull(registerStrings::get)
                val argumentInts = argumentRegisters.mapNotNull(registerInts::get)
                if (invokedMethod != null) {
                    val invokeSemantics = handleInvoke(
                        method = invokedMethod,
                        packageName = packageName,
                        callerScope = scope,
                        argumentRegisters = argumentRegisters,
                        argumentTags = argumentTags,
                        argumentStrings = argumentStrings,
                        argumentInts = argumentInts,
                        evidence = instructionEvidence,
                        addFact = ::addFact,
                        tagRegister = ::tagRegister,
                    )
                    pendingResultTags = invokeSemantics.resultTags
                    dfgEdges += invokeSemantics.dfgEdges

                    if (
                        argumentStrings.any(::isPublicIpEndpoint) &&
                        invokedMethod.isNetworkTransportCall()
                    ) {
                        dfgEdges += argumentStrings.distinct().count(::isPublicIpEndpoint)
                        addFact(
                            SemanticFactKind.PUBLIC_IP_NETWORK_FLOW,
                            "$instructionEvidence -> ${methodSignature(invokedMethod)}",
                            methodSignature(invokedMethod),
                        )
                    }
                }
            }

            if (!opcode.startsWith("move-result")) {
                val movedTags = movedRegisterPair(instruction)
                if (movedTags != null) {
                    val (destination, sourceRegister) = movedTags
                    clearRegister(destination)
                    registerTags[sourceRegister]?.let { tags ->
                        tagRegister(destination, *tags.toTypedArray())
                        dfgEdges += tags.size
                    }
                    registerStrings[sourceRegister]?.let { value -> registerStrings[destination] = value }
                    registerInts[sourceRegister]?.let { value -> registerInts[destination] = value }
                } else {
                    definedRegister(instruction)?.let(::clearRegister)
                }
            }
        }

        val cfg = if (facts.isNotEmpty()) {
            buildCfg(instructions.toList())
        } else {
            CfgStats(nodes = 0, edges = 0, branchCount = branchCount)
        }
        if (facts.isNotEmpty() && cfg.branchCount > 0) {
            facts += fact(
                kind = SemanticFactKind.CONDITIONAL_BRANCH,
                scope = scope,
                source = source,
                evidence = "$evidence -> branchCount=${cfg.branchCount}",
                className = className,
                methodName = methodName,
            )
        }

        return MethodSemanticResult(
            cfgNodes = cfg.nodes,
            cfgEdges = cfg.edges,
            dfgEdges = dfgEdges,
            facts = facts,
        )
    }

    private fun scanReference(
        reference: Reference,
        evidence: String,
        addFact: (SemanticFactKind, String, String) -> Unit,
        tagRegister: (DataTag) -> Unit,
    ) {
        when (reference) {
            is StringReference -> scanText(reference.string, evidence, addFact, allowTunnelInterface = true)
            is TypeReference -> scanType(reference.type, evidence, addFact)
            is FieldReference -> {
                scanType(reference.definingClass, evidence, addFact)
                scanText(reference.name, evidence, addFact, allowTunnelInterface = false)
                scanType(reference.type, evidence, addFact)
                if (reference.definingClass == "Landroid/net/VpnService;" && reference.name == "SERVICE_INTERFACE") {
                    addFact(SemanticFactKind.VPN_SERVICE_ACTION, "$evidence -> VpnService.SERVICE_INTERFACE", "VpnService.SERVICE_INTERFACE")
                    tagRegister(DataTag.VPN_SERVICE_ACTION)
                }
            }
            is MethodReference -> {
                scanType(reference.definingClass, evidence, addFact)
            }
        }
    }

    private fun handleInvoke(
        method: MethodReference,
        packageName: String,
        callerScope: AppSemanticRiskScope,
        argumentRegisters: List<Int>,
        argumentTags: Set<DataTag>,
        argumentStrings: List<String>,
        argumentInts: List<Long>,
        evidence: String,
        addFact: (SemanticFactKind, String, String) -> Unit,
        tagRegister: (Int, Array<out DataTag>) -> Unit,
    ): InvokeSemanticResult {
        val className = dexTypeName(method.definingClass)
        val signature = methodSignature(method)
        val name = method.name
        val resultTags = mutableSetOf<DataTag>()
        var dfgEdges = 0
        val hasVpnDataArgument = argumentTags.any { it.isVpnOrProxyData() }
        val hasPublicIpArgument = argumentStrings.any(::isPublicIpEndpoint)
        val hasTelemetryPayloadArgument = DataTag.VPN_TELEMETRY_PAYLOAD in argumentTags
        val hasVpnTelemetryKeyArgument = DataTag.VPN_TELEMETRY_VALUE in argumentTags
        val isNetworkTransportCall = (hasVpnDataArgument || hasPublicIpArgument || hasTelemetryPayloadArgument) &&
            method.isNetworkTransportCall()
        val isSdkCall = callerScope == AppSemanticRiskScope.APP_CODE && isSdkBoundaryCall(className, packageName)

        if (className == "android.content.Intent" && (name == "<init>" || name == "setAction")) {
            if (DataTag.VPN_SERVICE_ACTION in argumentTags || argumentStrings.any(::isVpnServiceAction)) {
                argumentRegisters.firstOrNull()?.let { register ->
                    tagRegister(register, arrayOf(DataTag.VPN_INTENT))
                }
                dfgEdges += 1
                addFact(SemanticFactKind.VPN_SERVICE_INTENT, "$evidence -> $signature", signature)
            }
        }

        if (className == "android.content.pm.PackageManager" && name == "queryIntentServices") {
            addFact(SemanticFactKind.PACKAGE_QUERY_API, "$evidence -> $signature", signature)
            if (
                DataTag.VPN_INTENT in argumentTags ||
                DataTag.VPN_SERVICE_ACTION in argumentTags ||
                argumentStrings.any(::isVpnServiceAction)
            ) {
                addFact(SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE, "$evidence -> $signature", signature)
                resultTags += DataTag.VPN_QUERY_RESULT
                dfgEdges += 1
            }
        }

        if (className == "android.content.pm.PackageManager" && name in BROAD_PACKAGE_INVENTORY_METHODS) {
            addFact(SemanticFactKind.BROAD_PACKAGE_INVENTORY, "$evidence -> $signature", signature)
            resultTags += DataTag.BROAD_PACKAGE_RESULT
            dfgEdges += 1
        }

        if (className == "android.content.pm.PackageManager" && name == "getPackageInfo") {
            addFact(SemanticFactKind.PACKAGE_QUERY_API, "$evidence -> $signature", signature)
            if (DataTag.KNOWN_VPN_PACKAGE in argumentTags || argumentStrings.any { it in AppRiskRules.vpnClientPackageNames }) {
                addFact(SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK, "$evidence -> $signature", signature)
                resultTags += DataTag.VPN_PACKAGE_VALUE
                dfgEdges += 1
            }
        }

        val hasVpnPackageInventoryArgument =
            DataTag.VPN_QUERY_RESULT in argumentTags ||
                DataTag.VPN_PACKAGE_VALUE in argumentTags ||
                DataTag.KNOWN_VPN_PACKAGE in argumentTags ||
                (
                    DataTag.BROAD_PACKAGE_RESULT in argumentTags &&
                        (
                            DataTag.KNOWN_VPN_PACKAGE in argumentTags ||
                                argumentStrings.any { it in AppRiskRules.vpnClientPackageNames }
                            )
                    )
        if (hasVpnPackageInventoryArgument) {
            when {
                name in RESULT_COLLECTION_METHODS -> {
                    addFact(SemanticFactKind.VPN_RESULT_COLLECTION, "$evidence -> $signature", signature)
                    dfgEdges += 1
                    resultTags.addAll(argumentTags.filter { it.isVpnOrProxyData() })
                }
                name in SERIALIZATION_METHODS || className in SERIALIZATION_CLASSES -> {
                    addFact(SemanticFactKind.VPN_RESULT_COLLECTION, "$evidence -> $signature", signature)
                    if (hasVpnTelemetryKeyArgument || hasVpnDataArgument) {
                        addFact(SemanticFactKind.TELEMETRY_PREPARATION, "$evidence -> $signature", signature)
                    }
                    addFact(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW, "$evidence -> $signature", signature)
                    resultTags += DataTag.VPN_TELEMETRY_PAYLOAD
                    dfgEdges += 2
                }
                isNetworkTransportCall -> {
                    addFact(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK, "$evidence -> $signature", signature)
                    addFact(SemanticFactKind.VPN_DATA_NETWORK_FLOW, "$evidence -> $signature", signature)
                    dfgEdges += 2
                }
            }
        }

        if (isSdkCall && hasVpnDataArgument) {
            addFact(SemanticFactKind.VPN_DATA_SDK_HANDOFF, "$evidence -> $signature", signature)
            dfgEdges += 2
        }

        if (
            hasTelemetryPayloadArgument
        ) {
            when {
                isNetworkTransportCall -> {
                    addFact(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK, "$evidence -> $signature", signature)
                    addFact(SemanticFactKind.VPN_DATA_NETWORK_FLOW, "$evidence -> $signature", signature)
                    dfgEdges += 2
                }
                isSdkCall || name in TELEMETRY_METHOD_NAMES || className in TELEMETRY_CLASS_NAMES -> {
                    addFact(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK, "$evidence -> $signature", signature)
                    dfgEdges += 1
                    if (isSdkCall) {
                        addFact(SemanticFactKind.VPN_DATA_SDK_HANDOFF, "$evidence -> $signature", signature)
                        dfgEdges += 1
                    }
                }
            }
        }

        if (isNetworkTransportCall) {
            addFact(SemanticFactKind.NETWORK_LIBRARY_CALL, "$evidence -> $signature", signature)
        }
        if ((name in TELEMETRY_METHOD_NAMES || className in TELEMETRY_CLASS_NAMES) && hasVpnDataArgument) {
            addFact(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK, "$evidence -> $signature", signature)
            dfgEdges += 1
        }

        if (
            className == "android.net.NetworkCapabilities" &&
            name == "hasTransport" &&
            (
                VPN_TRANSPORT_ID in argumentInts ||
                    argumentStrings.any { it == "TRANSPORT_VPN" || it == "NetworkCapabilities.TRANSPORT_VPN" }
                )
        ) {
            addFact(SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK, "$evidence -> $signature", signature)
        }
        if (className == "java.net.NetworkInterface" && name == "getNetworkInterfaces") {
            addFact(SemanticFactKind.TUNNEL_INTERFACE_API, "$evidence -> $signature", signature)
        }
        if (className == "java.net.NetworkInterface" && name == "getMTU") {
            addFact(SemanticFactKind.MTU_PROBE, "$evidence -> $signature", signature)
        }
        if (className == "android.net.ConnectivityManager" && name == "getAllNetworks") {
            addFact(SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION, "$evidence -> $signature", signature)
        }
        if (
            (className == "android.net.ConnectivityManager" && name in CONNECTIVITY_BINDING_METHODS) ||
            (className == "android.net.Network" && name in NETWORK_BINDING_METHODS) ||
            (className == "android.system.Os" && name.startsWith("setsockopt"))
        ) {
            addFact(SemanticFactKind.NETWORK_BYPASS_BINDING, "$evidence -> $signature", signature)
        }

        if (name == "exec" && argumentStrings.any { it.contains("dumpsys") || it.contains("vpn_management") }) {
            addFact(SemanticFactKind.ACTIVE_VPN_DUMPSYS, "$evidence -> $signature", signature)
        }

        if (
            method.isSocketConnectCall() &&
            (
                DataTag.LOCAL_PROXY_ENDPOINT in argumentTags ||
                    argumentStrings.any(::isSocksOrLocalProxyText)
                )
        ) {
            addFact(SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE, "$evidence -> $signature", signature)
            dfgEdges += 1
        }

        return InvokeSemanticResult(
            resultTags = resultTags,
            dfgEdges = dfgEdges,
        )
    }

    private fun scanText(
        value: String?,
        evidence: String,
        addFact: (SemanticFactKind, String, String) -> Unit,
        allowTunnelInterface: Boolean = true,
    ) {
        if (value.isNullOrBlank()) return
        when {
            isVpnServiceAction(value) -> addFact(SemanticFactKind.VPN_SERVICE_ACTION, "$evidence -> $value", value)
            value in AppRiskRules.vpnClientPackageNames -> addFact(SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE, "$evidence -> $value", value)
            isPublicIpEndpoint(value) -> addFact(SemanticFactKind.PUBLIC_IP_PROBE, "$evidence -> $value", value)
            isVpnTelemetryText(value) -> addFact(SemanticFactKind.VPN_TELEMETRY_LABEL, "$evidence -> $value", value)
            isSocksOrLocalProxyText(value) -> addFact(SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE, "$evidence -> $value", value)
            value.startsWith("/proc/net/") || value.startsWith("/proc/self/net/") -> {
                addFact(SemanticFactKind.PROC_SOCKET_TABLE, "$evidence -> $value", value)
            }
            value == "SO_BINDTODEVICE" || value.contains("SO_BINDTODEVICE") -> {
                addFact(SemanticFactKind.NETWORK_BYPASS_BINDING, "$evidence -> $value", value)
            }
            value == "TRANSPORT_VPN" ||
                value == "NetworkCapabilities.TRANSPORT_VPN" ||
                value == "NET_CAPABILITY_NOT_VPN" ||
                value == "IS_VPN" ||
                value == "VpnTransportInfo" -> {
                addFact(SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK, "$evidence -> $value", value)
            }
            allowTunnelInterface && isTunnelInterfaceText(value) -> {
                addFact(SemanticFactKind.TUNNEL_INTERFACE_PROBE, "$evidence -> $value", value)
            }
        }
    }

    private fun scanType(
        value: String?,
        evidence: String,
        addFact: (SemanticFactKind, String, String) -> Unit,
    ) {
        val typeName = dexTypeName(value)
        scanText(typeName, evidence, addFact, allowTunnelInterface = false)
    }

    private fun textTags(value: String): List<DataTag> {
        return buildList {
            if (isVpnServiceAction(value)) add(DataTag.VPN_SERVICE_ACTION)
            if (value in AppRiskRules.vpnClientPackageNames) add(DataTag.KNOWN_VPN_PACKAGE)
            if (isPublicIpEndpoint(value)) add(DataTag.PUBLIC_IP_ENDPOINT)
            if (isSocksOrLocalProxyText(value)) add(DataTag.LOCAL_PROXY_ENDPOINT)
            if (isVpnTelemetryText(value)) add(DataTag.VPN_TELEMETRY_VALUE)
        }
    }

    private fun buildSignals(
        summary: MutableSemanticSummary,
        trustedVpnClient: Boolean,
    ): List<AppSemanticSignal> {
        val signals = mutableListOf<AppSemanticSignal>()
        val facts = summary.facts

        facts.groupBy { MethodGroup(it.scope, it.source, it.className, it.methodName) }
            .forEach { (group, groupFacts) ->
                signals += buildGroupSignals(
                    facts = groupFacts,
                    groupLabel = listOfNotNull(group.className, group.methodName).joinToString("#"),
                    trustedVpnClient = trustedVpnClient,
                    classLevel = false,
                )
            }

        facts
            .filter { it.className.isNotBlank() }
            .groupBy { ClassGroup(it.scope, it.source, it.className) }
            .forEach { (group, groupFacts) ->
                signals += buildGroupSignals(
                    facts = groupFacts,
                    groupLabel = group.className,
                    trustedVpnClient = trustedVpnClient,
                    classLevel = true,
                )
            }

        facts
            .filter { it.scope == AppSemanticRiskScope.APP_CODE }
            .groupBy { ScopeGroup(it.scope, it.source) }
            .forEach { (group, groupFacts) ->
                signals += buildGroupSignals(
                    facts = groupFacts,
                    groupLabel = group.scope.name,
                    trustedVpnClient = trustedVpnClient,
                    classLevel = true,
                )
            }

        facts
            .filter { it.scope == AppSemanticRiskScope.MANIFEST || it.scope == AppSemanticRiskScope.NATIVE_CODE }
            .groupBy { ScopeGroup(it.scope, it.source) }
            .forEach { (group, groupFacts) ->
                signals += buildGroupSignals(
                    facts = groupFacts,
                    groupLabel = group.scope.name,
                    trustedVpnClient = trustedVpnClient,
                    classLevel = true,
                )
            }

        signals += buildCrossLayerSignals(facts, trustedVpnClient)
        if (trustedVpnClient) {
            signals += signal(
                facts = facts.filter {
                    it.kind == SemanticFactKind.MANIFEST_VPN_SERVICE_QUERY ||
                        it.kind == SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK ||
                        it.kind == SemanticFactKind.TUNNEL_INTERFACE_API ||
                        it.kind == SemanticFactKind.TUNNEL_INTERFACE_PROBE
                }.ifEmpty { facts.take(1) },
                type = AppSemanticSignalType.CALL_GRAPH,
                title = "VPN client self-check context",
                description = "This package looks like a trusted VPN client, so standalone VPN/tunnel self-checks are treated separately from spyware-style app surveillance.",
                confidence = 0,
                scope = AppSemanticRiskScope.APP_CODE,
                source = AppSemanticEvidenceSource.DIRECT_APP_CODE,
            )
        }

        return signals
    }

    private fun buildCrossLayerSignals(
        facts: List<SemanticFact>,
        trustedVpnClient: Boolean,
    ): List<AppSemanticSignal> {
        if (trustedVpnClient) return emptyList()
        val appFacts = facts.filter { it.scope == AppSemanticRiskScope.APP_CODE }
        val sdkFacts = facts.filter { it.scope == AppSemanticRiskScope.SDK_CODE }
        val handoffFacts = appFacts.filter { it.kind == SemanticFactKind.VPN_DATA_SDK_HANDOFF }
        if (handoffFacts.isEmpty()) return emptyList()
        val handoffTargetClasses = handoffFacts.mapNotNull { fact ->
            fact.value.substringBefore("#").takeIf(String::isNotBlank)
        }.toSet()
        val relatedSdkFacts = sdkFacts.filter { fact ->
            fact.className in handoffTargetClasses
        }

        val hasAppVpnPackageScan = appFacts.hasVpnPackageScan()
        val hasAppTelemetryPreparation = appFacts.has(SemanticFactKind.TELEMETRY_PREPARATION) ||
            appFacts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW)
        val hasSdkTelemetryOrNetwork = relatedSdkFacts.has(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK) ||
            relatedSdkFacts.has(SemanticFactKind.VPN_DATA_NETWORK_FLOW) ||
            relatedSdkFacts.has(SemanticFactKind.TELEMETRY_PREPARATION) ||
            relatedSdkFacts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW) ||
            relatedSdkFacts.has(SemanticFactKind.VPN_TELEMETRY_LABEL)
        val hasProxyProbe = appFacts.has(SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE) ||
            relatedSdkFacts.has(SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE)
        val hasPublicIp = appFacts.has(SemanticFactKind.PUBLIC_IP_PROBE) ||
            appFacts.has(SemanticFactKind.PUBLIC_IP_NETWORK_FLOW) ||
            relatedSdkFacts.has(SemanticFactKind.PUBLIC_IP_PROBE) ||
            relatedSdkFacts.has(SemanticFactKind.PUBLIC_IP_NETWORK_FLOW)
        val hasBypass = appFacts.has(SemanticFactKind.NETWORK_BYPASS_BINDING) ||
            relatedSdkFacts.has(SemanticFactKind.NETWORK_BYPASS_BINDING)
        val hasUnderlying = appFacts.has(SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION) ||
            relatedSdkFacts.has(SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION)

        val supportingFacts = (
            handoffFacts +
                appFacts.filter { it.kind in CROSS_LAYER_APP_FACTS } +
                relatedSdkFacts.filter { it.kind in CROSS_LAYER_SDK_FACTS }
            ).distinct()

        return when {
            hasAppVpnPackageScan && hasBypass && (hasUnderlying || hasPublicIp || hasSdkTelemetryOrNetwork) -> listOf(
                signal(
                    facts = supportingFacts,
                    type = AppSemanticSignalType.COMBINATION,
                    title = "critical app-to-SDK VPN discovery and bypass path",
                    description = "Application code collects VPN-related data and passes it across an SDK boundary while bypass/network-binding evidence is present.",
                    confidence = if (hasPublicIp) 98 else 94,
                    scope = AppSemanticRiskScope.CROSS_LAYER,
                    source = AppSemanticEvidenceSource.APP_TO_SDK,
                ),
            )
            hasAppVpnPackageScan && hasSdkTelemetryOrNetwork && hasProxyProbe && hasPublicIp -> listOf(
                signal(
                    facts = supportingFacts,
                    type = AppSemanticSignalType.COMBINATION,
                    title = "critical app-to-SDK VPN telemetry with proxy and public-IP probing",
                    description = "Application VPN discovery data crosses into SDK code, and the combined graph includes telemetry/network flow, localhost/SOCKS probing, and public-IP probing.",
                    confidence = 93,
                    scope = AppSemanticRiskScope.CROSS_LAYER,
                    source = AppSemanticEvidenceSource.APP_TO_SDK,
                ),
            )
            hasAppVpnPackageScan && (hasSdkTelemetryOrNetwork || hasAppTelemetryPreparation) && hasProxyProbe -> listOf(
                signal(
                    facts = supportingFacts,
                    type = AppSemanticSignalType.COMBINATION,
                    title = "app-to-SDK VPN telemetry with proxy probing",
                    description = "Application VPN discovery data crosses into SDK code and is combined with telemetry/network handling plus SOCKS or localhost proxy probing.",
                    confidence = 82,
                    scope = AppSemanticRiskScope.CROSS_LAYER,
                    source = AppSemanticEvidenceSource.APP_TO_SDK,
                ),
            )
            hasAppVpnPackageScan && (hasSdkTelemetryOrNetwork || hasAppTelemetryPreparation) -> listOf(
                signal(
                    facts = supportingFacts,
                    type = AppSemanticSignalType.COMBINATION,
                    title = "app-to-SDK VPN telemetry handoff",
                    description = "Application code collects VPN app data and hands the tagged value to SDK or telemetry code. Static analysis did not confirm final network transmission.",
                    confidence = 70,
                    scope = AppSemanticRiskScope.CROSS_LAYER,
                    source = AppSemanticEvidenceSource.APP_TO_SDK,
                ),
            )
            else -> listOf(
                signal(
                    facts = supportingFacts,
                    type = AppSemanticSignalType.DFG,
                    title = "app-to-SDK VPN data handoff",
                    description = "Application code passes VPN/proxy/public-IP tagged data into an SDK boundary; this is a bridge signal until a sink is confirmed.",
                    confidence = 45,
                    scope = AppSemanticRiskScope.CROSS_LAYER,
                    source = AppSemanticEvidenceSource.APP_TO_SDK,
                ),
            )
        }
    }

    private fun buildGroupSignals(
        facts: List<SemanticFact>,
        groupLabel: String,
        trustedVpnClient: Boolean,
        classLevel: Boolean,
    ): List<AppSemanticSignal> {
        if (facts.isEmpty()) return emptyList()
        val scope = facts.first().scope
        val source = facts.first().source
        val result = mutableListOf<AppSemanticSignal>()
        val hasVpnServiceQuery = facts.has(SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE)
        val hasBroadInventory = facts.has(SemanticFactKind.BROAD_PACKAGE_INVENTORY)
        val hasKnownVpnPackage = facts.has(SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE) ||
            facts.has(SemanticFactKind.MANIFEST_KNOWN_VPN_PACKAGE_QUERY) ||
            facts.has(SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK)
        val hasVpnPackageScan = facts.hasVpnPackageScan()
        val hasManifestOnlyVpnVisibility = scope == AppSemanticRiskScope.MANIFEST &&
            (
                facts.has(SemanticFactKind.MANIFEST_VPN_SERVICE_QUERY) ||
                    facts.has(SemanticFactKind.MANIFEST_QUERY_ALL_PACKAGES) ||
                    facts.has(SemanticFactKind.MANIFEST_KNOWN_VPN_PACKAGE_QUERY)
                )
        val hasCollection = facts.has(SemanticFactKind.VPN_RESULT_COLLECTION) ||
            facts.has(SemanticFactKind.TELEMETRY_PREPARATION) ||
            facts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW)
        val hasTelemetryPreparation = facts.has(SemanticFactKind.TELEMETRY_PREPARATION) ||
            facts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW) ||
            facts.has(SemanticFactKind.VPN_TELEMETRY_LABEL)
        val hasNetworkSink = facts.has(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK) ||
            facts.has(SemanticFactKind.VPN_DATA_NETWORK_FLOW)
        val hasTelemetry = hasTelemetryPreparation || hasNetworkSink
        val hasSocksProbe = facts.has(SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE)
        val hasPublicIp = facts.has(SemanticFactKind.PUBLIC_IP_PROBE) ||
            facts.has(SemanticFactKind.PUBLIC_IP_NETWORK_FLOW)
        val hasBypassBinding = facts.has(SemanticFactKind.NETWORK_BYPASS_BINDING)
        val hasUnderlyingEnum = facts.has(SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION)
        val hasNetworkCapabilitiesVpn = facts.has(SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK)
        val hasTunnelInterface = facts.has(SemanticFactKind.TUNNEL_INTERFACE_PROBE) ||
            facts.has(SemanticFactKind.TUNNEL_INTERFACE_API)
        val hasMtuProbe = facts.has(SemanticFactKind.MTU_PROBE)
        val hasProcSocketTable = facts.has(SemanticFactKind.PROC_SOCKET_TABLE)
        val hasDumpsys = facts.has(SemanticFactKind.ACTIVE_VPN_DUMPSYS)
        val hasVpnStateContext = hasNetworkCapabilitiesVpn ||
            hasTunnelInterface ||
            hasMtuProbe ||
            hasProcSocketTable ||
            hasDumpsys
        val hasStrongVpnIntentContext = hasPublicIp ||
            hasVpnPackageScan ||
            hasTelemetry ||
            hasSocksProbe
        val hasBranch = facts.has(SemanticFactKind.CONDITIONAL_BRANCH)
        val hasTrackerSdkContext = facts.hasTrackerSdkContext()
        val hasGenericNetworkStateSdkContext = scope == AppSemanticRiskScope.SDK_CODE &&
            facts.hasGenericNetworkStateSdkContext()

        if (hasManifestOnlyVpnVisibility && !trustedVpnClient) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.STRING_FLOW,
                title = "manifest VPN package visibility",
                description = "Manifest declares visibility for VPN services, known VPN packages, or broad package inventory. This is diagnostic until code usage is confirmed.",
                confidence = if (facts.has(SemanticFactKind.MANIFEST_VPN_SERVICE_QUERY) && facts.has(SemanticFactKind.MANIFEST_KNOWN_VPN_PACKAGE_QUERY)) {
                    15
                } else {
                    8
                },
                scope = scope,
                source = source,
            )
        }

        if (hasVpnServiceQuery && !hasCollection && !trustedVpnClient) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.CALL_GRAPH,
                title = "VpnService query without result use",
                description = "Code queries PackageManager for android.net.VpnService, but this graph did not prove package/label collection.",
                confidence = 10,
                scope = scope,
                source = source,
            )
        }

        if (hasNetworkCapabilitiesVpn && !hasVpnPackageScan && !hasTelemetry) {
            result += signal(
                facts = facts.filter { it.kind == SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK },
                type = AppSemanticSignalType.CALL_GRAPH,
                title = "VPN transport state check",
                description = "The graph checks Android network capabilities for VPN transport. This is a low standalone diagnostic signal and an amplifier in broader combinations.",
                confidence = 12,
                scope = scope,
                source = source,
            )
        }

        if (hasTunnelInterface && !hasVpnPackageScan && !hasTelemetry) {
            result += signal(
                facts = facts.filter {
                    it.kind == SemanticFactKind.TUNNEL_INTERFACE_API ||
                        it.kind == SemanticFactKind.TUNNEL_INTERFACE_PROBE
                },
                type = AppSemanticSignalType.STRING_FLOW,
                title = "tunnel interface inspection",
                description = "Code inspects NetworkInterface or tunnel interface names such as tun/tap/ppp. This is low by itself and becomes stronger in VPN detection combinations.",
                confidence = 12,
                scope = scope,
                source = source,
            )
        }

        if (hasMtuProbe && !hasVpnPackageScan && !hasTelemetry) {
            result += signal(
                facts = facts.filter { it.kind == SemanticFactKind.MTU_PROBE },
                type = AppSemanticSignalType.CALL_GRAPH,
                title = "MTU VPN heuristic",
                description = "Code reads interface MTU, a weak VPN/proxy heuristic unless it is combined with VPN discovery or telemetry.",
                confidence = 8,
                scope = scope,
                source = source,
            )
        }

        if (hasProcSocketTable && !hasVpnPackageScan && !hasTelemetry) {
            result += signal(
                facts = facts.filter { it.kind == SemanticFactKind.PROC_SOCKET_TABLE },
                type = AppSemanticSignalType.STRING_FLOW,
                title = "proc socket table inspection",
                description = "Code references /proc network socket tables. This is low alone and becomes stronger with proxy probing or VPN telemetry.",
                confidence = 10,
                scope = scope,
                source = source,
            )
        }

        if (hasDumpsys && !hasVpnPackageScan && !hasTelemetry) {
            result += signal(
                facts = facts.filter { it.kind == SemanticFactKind.ACTIVE_VPN_DUMPSYS },
                type = AppSemanticSignalType.CALL_GRAPH,
                title = "active VPN dumpsys probe",
                description = "Code executes a dumpsys-based VPN probe. This is low standalone but suspicious in larger VPN detection flows.",
                confidence = 15,
                scope = scope,
                source = source,
            )
        }

        if (hasVpnPackageScan && hasCollection && !hasTelemetry) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.DFG,
                title = "VPN app inventory collection",
                description = "The graph shows VPN app discovery and extraction or collection of package/service metadata.",
                confidence = 35,
                scope = scope,
                source = source,
            )
        } else if (hasBroadInventory && hasKnownVpnPackage && !hasTelemetry && !trustedVpnClient) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.DFG,
                title = "known VPN package inventory path",
                description = "Broad package inventory is correlated with known VPN package identifiers.",
                confidence = 30,
                scope = scope,
                source = source,
            )
        }

        if (hasVpnPackageScan && hasTelemetry && hasBypassBinding) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.COMBINATION,
                title = "critical VPN app discovery with network bypass",
                description = "VPN app discovery is combined with telemetry/network handling and an API that can bind traffic outside the VPN path.",
                confidence = if (hasPublicIp || hasNetworkSink) 98 else 90,
                scope = scope,
                source = source,
            )
        } else if (hasVpnPackageScan && hasTelemetry && hasSocksProbe && hasPublicIp) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.COMBINATION,
                title = "critical VPN inventory with proxy and public-IP probing",
                description = "VPN app inventory is combined with telemetry, localhost/SOCKS probing, and public-IP probing.",
                confidence = 92,
                scope = scope,
                source = source,
            )
        } else if (hasVpnPackageScan && hasTelemetry && hasSocksProbe) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.COMBINATION,
                title = "VPN inventory with SOCKS/local proxy telemetry",
                description = "VPN app inventory is combined with telemetry and localhost/SOCKS proxy probing. This is high risk, but not critical without public-IP comparison or network bypass.",
                confidence = 78,
                scope = scope,
                source = source,
            )
        } else if (hasVpnPackageScan && hasTelemetry) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.COMBINATION,
                title = "VPN app inventory telemetry path",
                description = if (hasNetworkSink) {
                    "VPN app discovery or known VPN package matching is connected to a network or telemetry sink."
                } else {
                    "VPN app discovery or known VPN package matching reaches a VPN telemetry payload. Static analysis did not confirm final network transmission."
                },
                confidence = if (hasNetworkSink) 78 else 70,
                scope = scope,
                source = source,
            )
        }

        if (hasUnderlyingEnum && hasBypassBinding && hasNetworkCapabilitiesVpn && hasStrongVpnIntentContext) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.COMBINATION,
                title = "underlying-network bypass semantic path",
                description = "The graph combines network enumeration, VPN transport context, and network binding with VPN telemetry, proxy/public-IP, or VPN-app discovery context.",
                confidence = if (hasPublicIp || hasNetworkSink || hasVpnPackageScan) 95 else 82,
                scope = scope,
                source = source,
            )
        }

        if (facts.has(SemanticFactKind.PUBLIC_IP_NETWORK_FLOW)) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.DFG,
                title = "public-IP endpoint flows into network call",
                description = "A public-IP endpoint reaches a network invocation argument in the same method.",
                confidence = 35,
                scope = scope,
                source = source,
            )
        }

        if (hasSocksProbe && hasPublicIp && !hasVpnPackageScan) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.COMBINATION,
                title = "localhost proxy probe with exit-IP comparison",
                description = "Localhost/SOCKS probing is combined with public-IP lookup, which can expose VPN proxy exit behavior.",
                confidence = 70,
                scope = scope,
                source = source,
            )
        }

        if (hasBranch && (hasVpnPackageScan || hasVpnStateContext) && hasTelemetry) {
            val confidence = when {
                hasNetworkSink || facts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW) -> 70
                hasTrackerSdkContext -> 57
                scope == AppSemanticRiskScope.APP_CODE -> 50
                hasGenericNetworkStateSdkContext -> 18
                else -> 30
            }
            val description = when {
                hasNetworkSink || facts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW) -> {
                    "Conditional control flow links VPN discovery/state facts with serialization or a telemetry/network sink."
                }
                hasTrackerSdkContext -> {
                    "Conditional control flow links VPN state with a known tracker SDK context; this remains high risk even when static analysis does not prove the final network sink."
                }
                hasGenericNetworkStateSdkContext -> {
                    "Conditional control flow is present around VPN state inside a generic network-observability SDK. This is diagnostic unless a sink or broader VPN-detection combination is confirmed."
                }
                else -> {
                    "Conditional control flow is present around VPN discovery/state facts and telemetry or blocking context."
                }
            }
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.CFG,
                title = "branching VPN-state telemetry decision",
                description = description,
                confidence = confidence,
                scope = scope,
                source = source,
            )
        }

        if (classLevel && scope == AppSemanticRiskScope.NATIVE_CODE && hasBypassBinding && (hasPublicIp || hasSocksProbe)) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.STRING_FLOW,
                title = "native network bypass probe strings",
                description = "Native code contains network bypass indicators together with public-IP or localhost proxy probe strings. This is diagnostic unless Java/Kotlin or JNI data-flow confirms VPN/proxy telemetry.",
                confidence = if (hasPublicIp) 35 else 20,
                scope = scope,
                source = source,
            )
        }

        return result.map { signal ->
            if (classLevel && groupLabel.isNotBlank() && groupLabel !in signal.description) {
                signal.copy(description = "${signal.description} Context: $groupLabel.")
            } else {
                signal
            }
        }
    }

    private fun signal(
        facts: List<SemanticFact>,
        type: AppSemanticSignalType,
        title: String,
        description: String,
        confidence: Int,
        scope: AppSemanticRiskScope,
        source: AppSemanticEvidenceSource,
    ): AppSemanticSignal {
        val chain = facts
            .sortedBy { it.kind.ordinal }
            .map { it.evidenceLine() }
            .distinct()
            .take(MAX_EVIDENCE_CHAIN)
        return AppSemanticSignal(
            type = type,
            title = title,
            description = description,
            evidence = chain.firstOrNull().orEmpty(),
            confidence = confidence.coerceIn(0, 100),
            scope = scope,
            source = source,
            evidenceChain = chain,
        )
    }

    private fun bucketFor(
        signals: List<AppSemanticSignal>,
        scope: AppSemanticRiskScope,
    ): AppSemanticRiskBucket {
        val scopedSignals = signals.filter { it.scope == scope }
        val rawScore = scopedSignals
            .groupBy { signal -> "${signal.type}:${signal.title}" }
            .values
            .sumOf { titleSignals -> titleSignals.maxOf(AppSemanticSignal::confidence) }
            .coerceAtMost(100)
        val hasExplicitCritical = scopedSignals.any { it.confidence >= 90 }
        val hasOnlyWeakDiagnostics = scopedSignals.isNotEmpty() &&
            scopedSignals.all { signal ->
                signal.confidence <= 20 &&
                    signal.type != AppSemanticSignalType.COMBINATION &&
                    signal.type != AppSemanticSignalType.DFG
            }
        val score = when {
            hasExplicitCritical -> rawScore
            hasOnlyWeakDiagnostics -> rawScore.coerceAtMost(42)
            else -> rawScore.coerceAtMost(89)
        }
        return AppSemanticRiskBucket(
            score = score,
            riskLevel = riskLevelFor(score, scopedSignals),
            signals = scopedSignals,
        )
    }

    private fun buildCfg(instructions: List<Instruction>): CfgStats {
        if (instructions.isEmpty()) return CfgStats(nodes = 0, edges = 0, branchCount = 0)
        val addresses = mutableListOf<Int>()
        var address = 0
        instructions.forEach { instruction ->
            addresses += address
            address += instruction.codeUnits
        }
        val indexByAddress = addresses.withIndex().associate { it.value to it.index }
        val leaders = sortedSetOf(0)
        var branchCount = 0

        instructions.forEachIndexed { index, instruction ->
            val opcodeName = opcodeKey(instruction)
            if (instruction is OffsetInstruction) {
                branchCount += 1
                indexByAddress[addresses[index] + instruction.codeOffset]?.let(leaders::add)
                if (canFallThrough(opcodeName) && index + 1 < instructions.size) {
                    leaders += index + 1
                }
            } else if (!canFallThrough(opcodeName) && index + 1 < instructions.size) {
                leaders += index + 1
            }
        }

        val leaderList = leaders.toList()
        val blockByInstruction = IntArray(instructions.size)
        leaderList.forEachIndexed { blockIndex, start ->
            val endExclusive = leaderList.getOrNull(blockIndex + 1) ?: instructions.size
            for (instructionIndex in start until endExclusive) {
                blockByInstruction[instructionIndex] = blockIndex
            }
        }

        val edges = mutableSetOf<Pair<Int, Int>>()
        leaderList.forEachIndexed { blockIndex, start ->
            val endExclusive = leaderList.getOrNull(blockIndex + 1) ?: instructions.size
            val lastIndex = endExclusive - 1
            val lastInstruction = instructions[lastIndex]
            val opcodeName = opcodeKey(lastInstruction)
            if (lastInstruction is OffsetInstruction) {
                indexByAddress[addresses[lastIndex] + lastInstruction.codeOffset]?.let { targetIndex ->
                    edges += blockIndex to blockByInstruction[targetIndex]
                }
            }
            if (canFallThrough(opcodeName) && lastIndex + 1 < instructions.size) {
                edges += blockIndex to blockByInstruction[lastIndex + 1]
            }
        }

        return CfgStats(
            nodes = leaderList.size,
            edges = edges.size,
            branchCount = branchCount,
        )
    }

    private fun canFallThrough(opcodeName: String): Boolean {
        return !opcodeName.startsWith("return") &&
            opcodeName != "throw" &&
            !opcodeName.startsWith("goto")
    }

    private fun isConstStringInstruction(instruction: Instruction): Boolean {
        val opcodeName = opcodeKey(instruction)
        return opcodeName == "const-string" || opcodeName == "const-string-jumbo"
    }

    private fun isConstNumberInstruction(instruction: Instruction): Boolean {
        val opcodeName = opcodeKey(instruction)
        return opcodeName == "const" ||
            opcodeName == "const/4" ||
            opcodeName == "const/16" ||
            opcodeName == "const/high16" ||
            opcodeName == "const-4" ||
            opcodeName == "const-16" ||
            opcodeName == "const-high16"
    }

    private fun definedRegister(instruction: Instruction): Int? {
        val opcodeName = opcodeKey(instruction)
        if (
            isConstStringInstruction(instruction) ||
            isConstNumberInstruction(instruction) ||
            opcodeName.startsWith("move-result")
        ) {
            return null
        }
        return when {
            opcodeName.startsWith("const") ||
                opcodeName.startsWith("move") ||
                opcodeName.startsWith("new-instance") ||
                opcodeName.startsWith("iget") ||
                opcodeName.startsWith("sget") ||
                opcodeName.startsWith("check-cast") -> (instruction as? OneRegisterInstruction)?.registerA
            else -> null
        }
    }

    private fun movedRegisterPair(instruction: Instruction): Pair<Int, Int>? {
        val opcodeName = opcodeKey(instruction)
        if (!opcodeName.startsWith("move") || opcodeName.startsWith("move-result")) return null
        val twoRegisterInstruction = instruction as? TwoRegisterInstruction ?: return null
        return twoRegisterInstruction.registerA to twoRegisterInstruction.registerB
    }

    private fun opcodeKey(instruction: Instruction): String {
        return instruction.opcode.name.lowercase().replace('_', '-')
    }

    private fun Instruction.registerList(): List<Int> {
        return when (this) {
            is FiveRegisterInstruction -> listOf(
                registerC,
                registerD,
                registerE,
                registerF,
                registerG,
            ).take(registerCount)
            is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
            is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
            is TwoRegisterInstruction -> listOf(registerA, registerB)
            is OneRegisterInstruction -> listOf(registerA)
            is VariableRegisterInstruction -> emptyList()
            else -> emptyList()
        }
    }

    private fun MethodReference.isNetworkTransportCall(): Boolean {
        val className = dexTypeName(definingClass)
        return className.startsWith("java.net.") ||
            className.startsWith("javax.net.") ||
            className.startsWith("okhttp3.") ||
            className.startsWith("retrofit2.") ||
            className.startsWith("android.net.") ||
            className.startsWith("io.grpc.") ||
            name in NETWORK_METHOD_NAMES
    }

    private fun MethodReference.isSocketConnectCall(): Boolean {
        val className = dexTypeName(definingClass)
        return (className == "java.net.Socket" || className == "java.nio.channels.SocketChannel") &&
            name in setOf("connect", "<init>")
    }

    private fun isSdkBoundaryCall(
        className: String,
        packageName: String,
    ): Boolean {
        if (className.isBlank()) return false
        if (className.startsWith(packageName)) return false
        return !PLATFORM_CLASS_PREFIXES.any { prefix -> className.startsWith(prefix) }
    }

    private fun shouldSkipSdkInfrastructureClass(className: String): Boolean {
        return SDK_INFRASTRUCTURE_CLASS_PREFIXES.any { prefix -> className.startsWith(prefix) }
    }

    private fun DataTag.isVpnOrProxyData(): Boolean {
        return when (this) {
            DataTag.VPN_QUERY_RESULT,
            DataTag.KNOWN_VPN_PACKAGE,
            DataTag.VPN_PACKAGE_VALUE,
            DataTag.PUBLIC_IP_ENDPOINT,
            DataTag.LOCAL_PROXY_ENDPOINT,
            DataTag.VPN_TELEMETRY_PAYLOAD,
            -> true
            DataTag.VPN_SERVICE_ACTION,
            DataTag.VPN_INTENT,
            DataTag.BROAD_PACKAGE_RESULT,
            DataTag.VPN_TELEMETRY_VALUE,
            -> false
        }
    }

    private fun isVpnServiceAction(value: String): Boolean {
        return value == "android.net.VpnService" ||
            value == "VpnService.SERVICE_INTERFACE" ||
            value.endsWith(".VpnService.SERVICE_INTERFACE")
    }

    private fun isPublicIpEndpoint(value: String): Boolean {
        return PUBLIC_IP_ENDPOINTS.any { endpoint -> value.contains(endpoint, ignoreCase = true) }
    }

    private fun isVpnTelemetryText(value: String): Boolean {
        return VPN_TELEMETRY_TERMS.any { term -> value.contains(term, ignoreCase = true) }
    }

    private fun isSocksOrLocalProxyText(value: String): Boolean {
        return value == "127.0.0.1" ||
            value == "::1" ||
            value.equals("localhost", ignoreCase = true) ||
            value.contains("SOCKS5", ignoreCase = true) ||
            value.contains("socksProxyHost", ignoreCase = true) ||
            value.contains("socksProxyPort", ignoreCase = true) ||
            value.contains("http.proxyHost", ignoreCase = true) ||
            value.contains("http.proxyPort", ignoreCase = true)
    }

    private fun isTunnelInterfaceText(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TUNNEL_INTERFACE_TEXT_LENGTH) return false
        return TUNNEL_TOKEN_SEPARATOR
            .split(trimmed)
            .any { token -> TUNNEL_NAME_TOKEN_PATTERN.matches(token) }
    }

    private fun methodSignature(reference: MethodReference): String {
        return "${dexTypeName(reference.definingClass)}#${reference.name}"
    }

    private fun dexTypeName(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value
            .removePrefix("L")
            .removeSuffix(";")
            .replace('/', '.')
            .replace('$', '.')
    }

    private fun riskLevelFor(
        score: Int,
        signals: List<AppSemanticSignal>,
    ): AppRiskLevel {
        val hasCritical = signals.any { it.confidence >= 90 }
        return when {
            hasCritical || score >= 90 -> AppRiskLevel.CRITICAL
            score >= 50 -> AppRiskLevel.HIGH
            score >= 20 -> AppRiskLevel.MEDIUM
            score > 0 -> AppRiskLevel.LOW
            else -> AppRiskLevel.CLEAN
        }
    }

    private fun fact(
        kind: SemanticFactKind,
        scope: AppSemanticRiskScope,
        source: AppSemanticEvidenceSource,
        evidence: String,
        value: String = "",
        className: String = "",
        methodName: String = "",
    ): SemanticFact {
        return SemanticFact(
            kind = kind,
            scope = scope,
            source = source,
            evidence = evidence,
            value = value,
            className = className,
            methodName = methodName,
        )
    }

    private fun List<SemanticFact>.has(kind: SemanticFactKind): Boolean {
        return any { it.kind == kind }
    }

    private fun List<SemanticFact>.hasVpnPackageScan(): Boolean {
        val hasVpnServiceQuery = has(SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE)
        val hasPackageQueryApi = has(SemanticFactKind.PACKAGE_QUERY_API)
        val hasBroadInventory = has(SemanticFactKind.BROAD_PACKAGE_INVENTORY)
        val hasKnownVpnPackage = has(SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE) ||
            has(SemanticFactKind.MANIFEST_KNOWN_VPN_PACKAGE_QUERY) ||
            has(SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK)
        return hasVpnServiceQuery ||
            has(SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK) ||
            (hasPackageQueryApi && hasKnownVpnPackage) ||
            (hasBroadInventory && hasKnownVpnPackage)
    }

    private fun List<SemanticFact>.hasTrackerSdkContext(): Boolean {
        return any { fact ->
            TRACKER_SDK_CLASS_PREFIXES.any { prefix -> fact.className.startsWith(prefix) } ||
                TRACKER_CONTEXT_TERMS.any { term ->
                    fact.value.contains(term, ignoreCase = true) ||
                        fact.evidence.contains(term, ignoreCase = true)
                }
        }
    }

    private fun List<SemanticFact>.hasGenericNetworkStateSdkContext(): Boolean {
        return any { fact ->
            GENERIC_NETWORK_STATE_SDK_CLASS_PREFIXES.any { prefix -> fact.className.startsWith(prefix) } ||
                GENERIC_NETWORK_STATE_SDK_TERMS.any { term ->
                    fact.className.contains(term, ignoreCase = true) ||
                        fact.evidence.contains(term, ignoreCase = true)
                }
        }
    }

    private fun SemanticFact.evidenceLine(): String {
        return "Signal: ${kind.signalLabel()} | Location: $evidence"
    }

    private fun SemanticFactKind.signalLabel(): String {
        return when (this) {
            SemanticFactKind.MANIFEST_VPN_SERVICE_QUERY -> "manifest query android.net.VpnService"
            SemanticFactKind.MANIFEST_QUERY_ALL_PACKAGES -> "manifest permission QUERY_ALL_PACKAGES"
            SemanticFactKind.MANIFEST_KNOWN_VPN_PACKAGE_QUERY -> "manifest known VPN package visibility"
            SemanticFactKind.VPN_SERVICE_ACTION -> "android.net.VpnService action"
            SemanticFactKind.VPN_SERVICE_INTENT -> "Intent for android.net.VpnService"
            SemanticFactKind.PACKAGE_QUERY_API -> "PackageManager query API"
            SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE -> "queryIntentServices(android.net.VpnService)"
            SemanticFactKind.BROAD_PACKAGE_INVENTORY -> "broad installed package inventory"
            SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE -> "known VPN package reference"
            SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK -> "known VPN package check"
            SemanticFactKind.VPN_RESULT_COLLECTION -> "VPN result collection/use"
            SemanticFactKind.TELEMETRY_PREPARATION -> "VPN data serialization/telemetry preparation"
            SemanticFactKind.TELEMETRY_OR_NETWORK_SINK -> "telemetry or network sink receiving VPN data"
            SemanticFactKind.VPN_TELEMETRY_LABEL -> "VPN telemetry label"
            SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW -> "VPN data flows into serialized payload"
            SemanticFactKind.VPN_DATA_NETWORK_FLOW -> "VPN/proxy data flows into network call"
            SemanticFactKind.VPN_DATA_SDK_HANDOFF -> "VPN/proxy data handed from app code to SDK boundary"
            SemanticFactKind.NETWORK_LIBRARY_CALL -> "network library/API call"
            SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE -> "SOCKS or localhost proxy probe"
            SemanticFactKind.PUBLIC_IP_PROBE -> "public-IP endpoint reference"
            SemanticFactKind.PUBLIC_IP_NETWORK_FLOW -> "public-IP endpoint flows into network call"
            SemanticFactKind.NETWORK_BYPASS_BINDING -> "underlying network/socket binding API"
            SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION -> "underlying network enumeration"
            SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK -> "NetworkCapabilities VPN transport check"
            SemanticFactKind.TUNNEL_INTERFACE_API -> "NetworkInterface tunnel API"
            SemanticFactKind.TUNNEL_INTERFACE_PROBE -> "tunnel interface name probe"
            SemanticFactKind.MTU_PROBE -> "MTU heuristic probe"
            SemanticFactKind.PROC_SOCKET_TABLE -> "proc socket table inspection"
            SemanticFactKind.ACTIVE_VPN_DUMPSYS -> "active dumpsys VPN probe"
            SemanticFactKind.CONDITIONAL_BRANCH -> "conditional control-flow branch"
        }
    }

    private fun extractAsciiStrings(bytes: ByteArray): Sequence<String> {
        return sequence {
            val buffer = StringBuilder()
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                if (value in 0x20..0x7e) {
                    buffer.append(value.toChar())
                } else {
                    if (buffer.length >= MIN_NATIVE_STRING_LENGTH) {
                        yield(buffer.toString())
                    }
                    buffer.clear()
                }
            }
            if (buffer.length >= MIN_NATIVE_STRING_LENGTH) {
                yield(buffer.toString())
            }
        }
    }

    private data class MethodSemanticResult(
        val cfgNodes: Int,
        val cfgEdges: Int,
        val dfgEdges: Int,
        val facts: List<SemanticFact>,
    )

    private data class CfgStats(
        val nodes: Int,
        val edges: Int,
        val branchCount: Int,
    )

    private data class InvokeSemanticResult(
        val resultTags: Set<DataTag>,
        val dfgEdges: Int,
    )

    private class MutableSemanticSummary(
        val packageName: String,
    ) {
        var methodsAnalyzed: Int = 0
        var cfgNodeCount: Int = 0
        var cfgEdgeCount: Int = 0
        var dfgEdgeCount: Int = 0
        val facts = mutableListOf<SemanticFact>()

        fun addFact(fact: SemanticFact) {
            facts += fact
        }
    }

    private data class SemanticFact(
        val kind: SemanticFactKind,
        val scope: AppSemanticRiskScope,
        val source: AppSemanticEvidenceSource,
        val evidence: String,
        val value: String = "",
        val className: String = "",
        val methodName: String = "",
    )

    private data class MethodGroup(
        val scope: AppSemanticRiskScope,
        val source: AppSemanticEvidenceSource,
        val className: String,
        val methodName: String,
    )

    private data class ClassGroup(
        val scope: AppSemanticRiskScope,
        val source: AppSemanticEvidenceSource,
        val className: String,
    )

    private data class ScopeGroup(
        val scope: AppSemanticRiskScope,
        val source: AppSemanticEvidenceSource,
    )

    private enum class SemanticFactKind {
        MANIFEST_VPN_SERVICE_QUERY,
        MANIFEST_QUERY_ALL_PACKAGES,
        MANIFEST_KNOWN_VPN_PACKAGE_QUERY,
        VPN_SERVICE_ACTION,
        VPN_SERVICE_INTENT,
        PACKAGE_QUERY_API,
        PACKAGE_QUERY_VPN_SERVICE,
        BROAD_PACKAGE_INVENTORY,
        KNOWN_VPN_PACKAGE_REFERENCE,
        KNOWN_VPN_PACKAGE_CHECK,
        VPN_RESULT_COLLECTION,
        TELEMETRY_PREPARATION,
        TELEMETRY_OR_NETWORK_SINK,
        VPN_TELEMETRY_LABEL,
        VPN_DATA_SERIALIZATION_FLOW,
        VPN_DATA_NETWORK_FLOW,
        VPN_DATA_SDK_HANDOFF,
        NETWORK_LIBRARY_CALL,
        SOCKS_OR_LOCAL_PROXY_PROBE,
        PUBLIC_IP_PROBE,
        PUBLIC_IP_NETWORK_FLOW,
        NETWORK_BYPASS_BINDING,
        UNDERLYING_NETWORK_ENUMERATION,
        NETWORK_CAPABILITIES_VPN_CHECK,
        TUNNEL_INTERFACE_API,
        TUNNEL_INTERFACE_PROBE,
        MTU_PROBE,
        PROC_SOCKET_TABLE,
        ACTIVE_VPN_DUMPSYS,
        CONDITIONAL_BRANCH,
    }

    private enum class DataTag {
        VPN_SERVICE_ACTION,
        VPN_INTENT,
        VPN_QUERY_RESULT,
        BROAD_PACKAGE_RESULT,
        KNOWN_VPN_PACKAGE,
        VPN_PACKAGE_VALUE,
        PUBLIC_IP_ENDPOINT,
        LOCAL_PROXY_ENDPOINT,
        VPN_TELEMETRY_VALUE,
        VPN_TELEMETRY_PAYLOAD,
    }

    companion object {
        const val ANALYZER_VERSION = 7
        private const val VPN_TRANSPORT_ID = 4L
        private const val MAX_SIGNALS = 100
        private const val MAX_EVIDENCE_CHAIN = 8
        private const val CANCELLATION_CHECK_CLASS_INTERVAL = 8
        private const val CANCELLATION_CHECK_METHOD_INTERVAL = 16
        private const val CANCELLATION_CHECK_INSTRUCTION_INTERVAL = 128
        private const val CANCELLATION_CHECK_NATIVE_STRING_INTERVAL = 128
        private const val MIN_NATIVE_STRING_LENGTH = 4
        private val DEX_ENTRY_PATTERN = Regex("""classes(?:\d*)\.dex""")
        private const val MAX_TUNNEL_INTERFACE_TEXT_LENGTH = 64
        private val TUNNEL_NAME_TOKEN_PATTERN = Regex("""^(?:tun|ppp|tap|pptp|wg)\d+$""")
        private val TUNNEL_TOKEN_SEPARATOR = Regex("""[^A-Za-z0-9]+""")
        private val PUBLIC_IP_ENDPOINTS = listOf(
            "ifconfig.me",
            "checkip.amazonaws.com",
            "api.ipify.org",
            "api4.ipify.org",
            "ip.sb",
            "api.ipapi.is",
            "iplocate.io",
            "ipv4-internet.yandex.net",
            "ipv6-internet.yandex.net",
            "ip.mail.ru",
            "ip-api.com",
            "vpn-detection-free",
        )
        private val VPN_TELEMETRY_TERMS = listOf(
            "is_vpn",
            "isVpn",
            "vpn_enabled",
            "vpnEnabled",
            "isVpnConnected",
            "vpn_status",
            "VpnStatus",
            "VpnStatusResponse",
            "is_vpn_on",
            "installedVpn",
            "vpnClients",
            "vpn_apps",
            "setVpn",
            "CheckVpnStatus",
            "android_block_vpn",
            "VpnChallenge",
            "vpn-detection-free",
        )
        private val BROAD_PACKAGE_INVENTORY_METHODS = setOf(
            "getInstalledPackages",
            "getInstalledApplications",
        )
        private val RESULT_COLLECTION_METHODS = setOf(
            "size",
            "isEmpty",
            "iterator",
            "get",
            "loadLabel",
            "loadIcon",
            "add",
            "addAll",
            "contains",
            "map",
            "filter",
        )
        private val SERIALIZATION_METHODS = setOf(
            "put",
            "putString",
            "putBoolean",
            "putInt",
            "add",
            "addProperty",
            "toJson",
            "encodeToString",
            "writeString",
        )
        private val SERIALIZATION_CLASSES = setOf(
            "org.json.JSONObject",
            "org.json.JSONArray",
            "android.os.Bundle",
            "java.util.Map",
            "java.util.HashMap",
            "com.google.gson.JsonObject",
            "com.google.gson.Gson",
        )
        private val TELEMETRY_METHOD_NAMES = setOf(
            "track",
            "report",
            "send",
            "sendEvent",
            "logEvent",
            "onEvent",
            "enqueue",
            "execute",
        )
        private val TELEMETRY_CLASS_NAMES = setOf(
            "com.yandex.metrica.YandexMetrica",
            "com.google.firebase.analytics.FirebaseAnalytics",
        )
        private val TRACKER_SDK_CLASS_PREFIXES = listOf(
            "com.facebook.",
            "com.meta.",
            "com.instagram.",
            "com.yandex.metrica.",
            "io.appmetrica.",
        )
        private val TRACKER_CONTEXT_TERMS = listOf(
            "yandexmetrica",
            "appmetrica",
            "startup.mobile.yandex.net",
            "mc.yango.com",
            "graph.facebook.com",
            "facebook.com/tr",
            "fbp",
            "_fbp",
            "Meta Pixel",
        )
        private val GENERIC_NETWORK_STATE_SDK_CLASS_PREFIXES = listOf(
            "io.sentry.",
            "com.reactnativecommunity.netinfo.",
            "org.webrtc.",
            "io.agora.",
            "com.adjust.sdk.",
            "com.appsflyer.",
            "org.chromium.net.",
        )
        private val GENERIC_NETWORK_STATE_SDK_TERMS = listOf(
            "NetworkBreadcrumbsIntegration",
            "ConnectivityReceiver",
            "NetworkCallbackConnectivityReceiver",
            "NetworkMonitorAutoDetect",
            "ConnectivityUtility",
            "AndroidNetworkLibrary",
        )
        private val NETWORK_METHOD_NAMES = setOf(
            "openConnection",
            "newCall",
            "execute",
            "enqueue",
            "connect",
            "getInputStream",
            "getOutputStream",
        )
        private val CONNECTIVITY_BINDING_METHODS = setOf(
            "bindProcessToNetwork",
            "setProcessDefaultNetwork",
        )
        private val NETWORK_BINDING_METHODS = setOf(
            "bindSocket",
            "getSocketFactory",
        )
        private val CROSS_LAYER_APP_FACTS = setOf(
            SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE,
            SemanticFactKind.BROAD_PACKAGE_INVENTORY,
            SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE,
            SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK,
            SemanticFactKind.VPN_RESULT_COLLECTION,
            SemanticFactKind.TELEMETRY_PREPARATION,
            SemanticFactKind.VPN_TELEMETRY_LABEL,
            SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW,
            SemanticFactKind.VPN_DATA_SDK_HANDOFF,
            SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE,
            SemanticFactKind.PUBLIC_IP_PROBE,
            SemanticFactKind.PUBLIC_IP_NETWORK_FLOW,
            SemanticFactKind.NETWORK_BYPASS_BINDING,
            SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION,
            SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK,
            SemanticFactKind.TUNNEL_INTERFACE_PROBE,
            SemanticFactKind.MTU_PROBE,
            SemanticFactKind.PROC_SOCKET_TABLE,
            SemanticFactKind.ACTIVE_VPN_DUMPSYS,
        )
        private val CROSS_LAYER_SDK_FACTS = setOf(
            SemanticFactKind.TELEMETRY_PREPARATION,
            SemanticFactKind.TELEMETRY_OR_NETWORK_SINK,
            SemanticFactKind.VPN_TELEMETRY_LABEL,
            SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW,
            SemanticFactKind.VPN_DATA_NETWORK_FLOW,
            SemanticFactKind.NETWORK_LIBRARY_CALL,
            SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE,
            SemanticFactKind.PUBLIC_IP_PROBE,
            SemanticFactKind.PUBLIC_IP_NETWORK_FLOW,
            SemanticFactKind.NETWORK_BYPASS_BINDING,
            SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION,
            SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK,
            SemanticFactKind.TUNNEL_INTERFACE_PROBE,
            SemanticFactKind.MTU_PROBE,
            SemanticFactKind.PROC_SOCKET_TABLE,
            SemanticFactKind.ACTIVE_VPN_DUMPSYS,
        )
        private val PLATFORM_CLASS_PREFIXES = listOf(
            "android.",
            "androidx.",
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "dalvik.",
            "org.json.",
            "okhttp3.",
            "retrofit2.",
            "io.grpc.",
            "com.google.gson.",
        )
        private val SDK_INFRASTRUCTURE_CLASS_PREFIXES = listOf(
            "androidx.",
            "android.support.",
            "kotlin.",
            "kotlinx.",
            "okhttp3.",
            "okio.",
            "retrofit2.",
            "io.grpc.",
            "com.google.gson.",
            "com.google.protobuf.",
            "com.google.common.",
            "com.squareup.",
            "org.intellij.",
            "org.jetbrains.",
        )
    }
}
