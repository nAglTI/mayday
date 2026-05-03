package org.debs.mayday.core.data.packageinfo

import net.dongliu.apk.parser.ApkFile
import org.debs.mayday.core.model.AppRiskLevel
import org.debs.mayday.core.model.AppSemanticAnalysisResult
import org.debs.mayday.core.model.AppSemanticEvidenceSource
import org.debs.mayday.core.model.AppSemanticProofLevel
import org.debs.mayday.core.model.AppSemanticRiskBucket
import org.debs.mayday.core.model.AppSemanticRiskScope
import org.debs.mayday.core.model.AppSemanticSignal
import org.debs.mayday.core.model.AppSemanticSignalType
import org.debs.mayday.core.model.AppSemanticVerdictConfidence
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
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
                compareByDescending<AppSemanticSignal> { it.proofConfidence }
                    .thenByDescending { it.confidence }
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
        val proofConfidence = proofConfidenceFor(signals)
        val cleanProofConfidence = cleanProofConfidenceFor(
            summary = summary,
            signals = signals,
            score = score,
            threatProofConfidence = proofConfidence,
        )
        val riskLevel = riskLevelFor(score, signals)
        val verdictConfidence = AppSemanticVerdictConfidence.from(
            score = score,
            riskLevel = riskLevel,
            threatProofConfidence = proofConfidence,
            cleanProofConfidence = cleanProofConfidence,
        )

        return AppSemanticAnalysisResult(
            score = score,
            riskLevel = riskLevel,
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
            proofConfidence = proofConfidence,
            proofLevel = AppSemanticProofLevel.from(proofConfidence),
            cleanScore = cleanProofConfidence,
            cleanProofConfidence = cleanProofConfidence,
            cleanProofLevel = AppSemanticProofLevel.from(cleanProofConfidence),
            verdictConfidence = verdictConfidence,
            verdictLevel = AppSemanticProofLevel.from(verdictConfidence),
            verdictStatus = AppSemanticVerdictConfidence.statusFor(
                score = score,
                riskLevel = riskLevel,
                threatProofConfidence = proofConfidence,
                cleanProofConfidence = cleanProofConfidence,
            ),
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

        val classes = dexFile.classes.toList()
        val appClassPrefixes = inferAppClassPrefixes(
            classes = classes,
            packageName = summary.packageName,
        )
        summary.appClassPrefixes += appClassPrefixes
        val semanticSummaries = buildDexSemanticSummaries(
            classes = classes,
            packageName = summary.packageName,
            appClassPrefixes = appClassPrefixes,
            cancellationCheck = cancellationCheck,
        )

        classes.forEachIndexed { classIndex, classDef ->
            if (classIndex % CANCELLATION_CHECK_CLASS_INTERVAL == 0) {
                cancellationCheck()
            }
            val className = dexTypeName(classDef.type)
            val scope = scopeForClass(
                className = className,
                packageName = summary.packageName,
                appClassPrefixes = appClassPrefixes,
            )
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
                val methodName = methodNameWithParameters(method.name, method.parameterTypes)
                val evidence = "$evidencePrefix:$className#${method.name}"
                if (method.isNativeDeclaration()) {
                    summary.addNativeBridgeCandidate(
                        NativeBridgeCandidate(
                            scope = scope,
                            source = source,
                            className = className,
                            methodName = methodName,
                            simpleMethodName = method.name,
                            evidence = "$evidence -> native declaration",
                        ),
                    )
                    summary.addFact(
                        fact(
                            kind = SemanticFactKind.NATIVE_METHOD_DECLARATION,
                            scope = scope,
                            source = source,
                            evidence = "$evidence -> native declaration",
                            value = method.name,
                            className = className,
                            methodName = methodName,
                        ),
                    )
                }
                val implementation = method.implementation ?: return@forEachIndexed
                val semantics = analyzeMethod(
                    evidence = evidence,
                    className = className,
                    methodName = methodName,
                    packageName = summary.packageName,
                    appClassPrefixes = appClassPrefixes,
                    semanticSummaries = semanticSummaries,
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
                semantics.calls.forEach(summary::addMethodCall)
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
        val libraryName = nativeLibraryName(evidencePrefix)
        val bytes = input.readBytes()
        extractAsciiStrings(bytes).forEachIndexed { index, value ->
            if (index % CANCELLATION_CHECK_NATIVE_STRING_INTERVAL == 0) {
                cancellationCheck()
            }
            summary.addNativeLibraryText(libraryName, evidencePrefix, value)
            scanNativeText(
                value = value,
                evidence = "$evidencePrefix -> $value",
                libraryName = libraryName,
                summary = summary,
            )
        }
    }

    private fun scanNativeText(
        value: String,
        evidence: String,
        libraryName: String,
        summary: MutableSemanticSummary,
    ) {
        if (value.length > MAX_NATIVE_REGEX_TEXT_LENGTH) return
        val fact = when {
            value in AppRiskRules.vpnClientPackageNames -> SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE
            value == "SO_BINDTODEVICE" || value.contains("bindSocket", ignoreCase = true) -> {
                SemanticFactKind.NETWORK_BYPASS_BINDING
            }
            isProcRouteText(value) -> SemanticFactKind.ROUTE_TABLE_INSPECTION
            value.contains("/proc/net/") || value.contains("/proc/self/net/") -> SemanticFactKind.PROC_SOCKET_TABLE
            isNativeTunnelInterfaceText(value) -> SemanticFactKind.TUNNEL_INTERFACE_PROBE
            isPublicIpEndpoint(value) -> SemanticFactKind.PUBLIC_IP_PROBE
            isSystemProxyPropertyText(value) -> SemanticFactKind.SYSTEM_PROXY_INSPECTION
            isNativeSocksOrLocalProxyProbeText(value) -> SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE
            else -> null
        } ?: return
        val semanticFact = SemanticFact(
            kind = fact,
            scope = AppSemanticRiskScope.NATIVE_CODE,
            source = AppSemanticEvidenceSource.NATIVE,
            evidence = evidence,
            value = value,
            className = libraryName,
        )
        summary.addFact(semanticFact)
        summary.addNativeLibraryFact(libraryName, semanticFact)
    }

    private fun buildDexSemanticSummaries(
        classes: List<ClassDef>,
        packageName: String,
        appClassPrefixes: Set<String>,
        cancellationCheck: () -> Unit,
    ): DexSemanticSummaries {
        val summaries = DexSemanticSummaries()
        classes.forEachIndexed { classIndex, classDef ->
            if (classIndex % CANCELLATION_CHECK_CLASS_INTERVAL == 0) {
                cancellationCheck()
            }
            classDef.methods.forEach { method ->
                val implementation = method.implementation ?: return@forEach
                if (method.name != "<init>") return@forEach
                val key = methodKey(
                    className = dexTypeName(classDef.type),
                    methodName = methodNameWithParameters(method.name, method.parameterTypes),
                )
                val parameterRegisters = parameterRegisterMap(method, implementation.registerCount)
                val registerParameterAliases = parameterRegisters.toMutableMap()
                implementation.instructions.forEach { instruction ->
                    val opcode = opcodeKey(instruction)
                    movedRegisterPair(instruction)?.let { (destination, sourceRegister) ->
                        registerParameterAliases[sourceRegister]?.let { parameterIndex ->
                            registerParameterAliases[destination] = parameterIndex
                        }
                    }
                    val reference = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                    if (!opcode.startsWith("iput") || reference == null) return@forEach
                    val registers = instruction.registerList()
                    val valueRegister = registers.firstOrNull() ?: return@forEach
                    val parameterIndex = registerParameterAliases[valueRegister] ?: return@forEach
                    summaries.constructorParameterFields
                        .getOrPut(key) { mutableMapOf() }
                        .getOrPut(parameterIndex) { mutableSetOf() }
                        .add(fieldKey(reference))
                }
            }
        }

        repeat(METHOD_SUMMARY_ITERATIONS) {
            var changed = false
            classes.forEachIndexed { classIndex, classDef ->
                if (classIndex % CANCELLATION_CHECK_CLASS_INTERVAL == 0) {
                    cancellationCheck()
                }
                val className = dexTypeName(classDef.type)
                classDef.methods.forEach { method ->
                    val implementation = method.implementation ?: return@forEach
                    val key = methodKey(
                        className = className,
                        methodName = methodNameWithParameters(method.name, method.parameterTypes),
                    )
                    val flow = summarizeMethodFlow(
                        method = method,
                        instructions = implementation.instructions,
                        packageName = packageName,
                        appClassPrefixes = appClassPrefixes,
                        summaries = summaries,
                    )
                    if (flow.returnTags.isNotEmpty()) {
                        val target = summaries.methodReturnTags.getOrPut(key) { mutableSetOf() }
                        changed = target.addAll(flow.returnTags) || changed
                    }
                    flow.fieldTags.forEach { (field, tags) ->
                        val target = summaries.fieldTags.getOrPut(field) { mutableSetOf() }
                        changed = target.addAll(tags) || changed
                    }
                }
            }
            if (!changed) return@repeat
        }
        return summaries
    }

    private fun summarizeMethodFlow(
        method: org.jf.dexlib2.iface.Method,
        instructions: Iterable<Instruction>,
        packageName: String,
        appClassPrefixes: Set<String>,
        summaries: DexSemanticSummaries,
    ): MethodFlowSummary {
        val registerStrings = mutableMapOf<Int, String>()
        val registerInts = mutableMapOf<Int, Long>()
        val registerTags = mutableMapOf<Int, MutableSet<DataTag>>()
        val returnTags = mutableSetOf<DataTag>()
        val learnedFieldTags = mutableMapOf<FieldKey, MutableSet<DataTag>>()
        var pendingResultTags = emptySet<DataTag>()
        var branchDerivedTags = emptySet<DataTag>()
        var branchDerivedCountdown = 0

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

        instructions.forEach { instruction ->
            val opcode = opcodeKey(instruction)
            if (
                opcode.startsWith("if-") &&
                instruction.registerList().any { register ->
                    registerTags[register].orEmpty().any { tag -> tag.isVpnOrProxyData() }
                }
            ) {
                branchDerivedTags = instruction.registerList()
                    .flatMap { register -> registerTags[register].orEmpty() }
                    .filter { tag -> tag.isVpnOrProxyData() }
                    .toSet()
                branchDerivedCountdown = BRANCH_DERIVED_TAG_INSTRUCTION_WINDOW
            }

            if (opcode.startsWith("move-result")) {
                val register = (instruction as? OneRegisterInstruction)?.registerA
                if (register != null && pendingResultTags.isNotEmpty()) {
                    tagRegister(register, *pendingResultTags.toTypedArray())
                }
                pendingResultTags = emptySet()
                return@forEach
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
                    if (branchDerivedCountdown > 0 && branchDerivedTags.isNotEmpty()) {
                        tagRegister(register, *branchDerivedTags.toTypedArray())
                    }
                }
            }
            if (isConstNumberInstruction(instruction)) {
                val register = (instruction as? OneRegisterInstruction)?.registerA
                val literal = (instruction as? NarrowLiteralInstruction)?.narrowLiteral?.toLong()
                    ?: (instruction as? WideLiteralInstruction)?.wideLiteral
                if (register != null && literal != null) {
                    clearRegister(register)
                    registerInts[register] = literal
                    if (branchDerivedCountdown > 0 && branchDerivedTags.isNotEmpty()) {
                        tagRegister(register, *branchDerivedTags.toTypedArray())
                    }
                }
            }

            if (reference is FieldReference) {
                val field = fieldKey(reference)
                val fieldTags = (
                    summaries.fieldTags[field].orEmpty() +
                        learnedFieldTags[field].orEmpty()
                    ).toSet()
                when {
                    opcode.startsWith("iget") || opcode.startsWith("sget") -> {
                        val register = (instruction as? OneRegisterInstruction)?.registerA
                        if (register != null) {
                            clearRegister(register)
                            if (fieldTags.isNotEmpty()) {
                                tagRegister(register, *fieldTags.toTypedArray())
                            }
                        }
                    }
                    opcode.startsWith("iput") || opcode.startsWith("sput") -> {
                        val valueRegister = instruction.registerList().firstOrNull()
                        val valueTags = valueRegister?.let { registerTags[it].orEmpty() }.orEmpty()
                        if (valueTags.any { it.isVpnOrProxyData() }) {
                            learnedFieldTags.getOrPut(field) { mutableSetOf() }.addAll(valueTags)
                        }
                    }
                }
            }

            if (opcode.startsWith("invoke") && reference is MethodReference) {
                val argumentRegisters = instruction.registerList()
                val argumentTagsByIndex = argumentRegisters.map { registerTags[it].orEmpty().toSet() }
                val argumentTags = argumentTagsByIndex.flatten().toSet()
                val argumentStrings = argumentRegisters.mapNotNull(registerStrings::get)
                val argumentInts = argumentRegisters.mapNotNull(registerInts::get)
                val targetKey = methodKey(reference)
                val resultTags = mutableSetOf<DataTag>()
                resultTags += knownInvokeResultTags(
                    method = reference,
                    packageName = packageName,
                    appClassPrefixes = appClassPrefixes,
                    argumentTags = argumentTags,
                    argumentStrings = argumentStrings,
                    argumentInts = argumentInts,
                )
                resultTags += summaries.methodReturnTags[targetKey].orEmpty()
                applyConstructorFieldSummaryToRegisters(
                    targetKey = targetKey,
                    argumentRegisters = argumentRegisters,
                    argumentTagsByIndex = argumentTagsByIndex,
                    constructorParameterFields = summaries.constructorParameterFields,
                    tagRegister = { register, tags -> tagRegister(register, *tags.toTypedArray()) },
                    addFieldTags = { field, tags ->
                        learnedFieldTags.getOrPut(field) { mutableSetOf() }.addAll(tags)
                    },
                )
                pendingResultTags = resultTags
            }

            if (opcode.startsWith("return")) {
                val register = (instruction as? OneRegisterInstruction)?.registerA
                if (register != null) {
                    returnTags += registerTags[register].orEmpty()
                }
            }

            if (branchDerivedCountdown > 0 && !opcode.startsWith("if-")) {
                branchDerivedCountdown -= 1
                if (branchDerivedCountdown == 0) {
                    branchDerivedTags = emptySet()
                }
            }

            if (!opcode.startsWith("move-result")) {
                val movedTags = movedRegisterPair(instruction)
                if (movedTags != null) {
                    val (destination, sourceRegister) = movedTags
                    clearRegister(destination)
                    registerTags[sourceRegister]?.let { tags -> tagRegister(destination, *tags.toTypedArray()) }
                    registerStrings[sourceRegister]?.let { value -> registerStrings[destination] = value }
                    registerInts[sourceRegister]?.let { value -> registerInts[destination] = value }
                } else {
                    definedRegister(instruction)?.let(::clearRegister)
                }
            }
        }

        return MethodFlowSummary(
            returnTags = returnTags.filterTo(mutableSetOf()) { tag -> tag.isSummaryTag() },
            fieldTags = learnedFieldTags
                .mapValues { (_, tags) -> tags.filterTo(mutableSetOf()) { tag -> tag.isSummaryTag() } }
                .filterValues { it.isNotEmpty() },
        )
    }

    private fun analyzeMethod(
        evidence: String,
        className: String,
        methodName: String,
        packageName: String,
        appClassPrefixes: Set<String>,
        semanticSummaries: DexSemanticSummaries,
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
        var taggedBranchCount = 0
        val methodCalls = mutableListOf<MethodCall>()
        var branchDerivedTags = emptySet<DataTag>()
        var branchDerivedCountdown = 0

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

        scanMethodContext(
            className = className,
            methodName = methodName,
            evidence = evidence,
            addFact = ::addFact,
        )

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
            if (
                opcode.startsWith("if-") &&
                instruction.registerList().any { register ->
                    registerTags[register].orEmpty().any { tag -> tag.isVpnOrProxyData() }
                }
            ) {
                taggedBranchCount += 1
                branchDerivedTags = instruction.registerList()
                    .flatMap { register -> registerTags[register].orEmpty() }
                    .filter { tag -> tag.isVpnOrProxyData() }
                    .toSet()
                branchDerivedCountdown = BRANCH_DERIVED_TAG_INSTRUCTION_WINDOW
            }

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
                    if (branchDerivedCountdown > 0 && branchDerivedTags.isNotEmpty()) {
                        tagRegister(register, *branchDerivedTags.toTypedArray())
                        dfgEdges += branchDerivedTags.size
                    }
                }
            }
            if (isConstNumberInstruction(instruction)) {
                val register = (instruction as? OneRegisterInstruction)?.registerA
                val literal = (instruction as? NarrowLiteralInstruction)?.narrowLiteral?.toLong()
                    ?: (instruction as? WideLiteralInstruction)?.wideLiteral
                if (register != null && literal != null) {
                    clearRegister(register)
                    registerInts[register] = literal
                    if (branchDerivedCountdown > 0 && branchDerivedTags.isNotEmpty()) {
                        tagRegister(register, *branchDerivedTags.toTypedArray())
                        dfgEdges += branchDerivedTags.size
                    }
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

            if (reference is FieldReference) {
                val fieldKey = fieldKey(reference)
                val fieldTags = semanticSummaries.fieldTags[fieldKey].orEmpty()
                when {
                    opcode.startsWith("iget") || opcode.startsWith("sget") -> {
                        val register = (instruction as? OneRegisterInstruction)?.registerA
                        if (register != null) {
                            clearRegister(register)
                            if (fieldTags.isNotEmpty()) {
                                tagRegister(register, *fieldTags.toTypedArray())
                                dfgEdges += fieldTags.size
                                if (fieldTags.any { it.isVpnOrProxyData() }) {
                                    addFact(
                                        SemanticFactKind.VPN_DATA_CARRIER_FIELD,
                                        "$instructionEvidence -> field ${fieldKey.displayName()} carries VPN/proxy data",
                                        fieldKey.displayName(),
                                    )
                                }
                            }
                        }
                    }
                    opcode.startsWith("iput") || opcode.startsWith("sput") -> {
                        val valueRegister = instruction.registerList().firstOrNull()
                        val valueTags = valueRegister?.let { registerTags[it].orEmpty() }.orEmpty()
                        if (valueTags.any { it.isVpnOrProxyData() }) {
                            dfgEdges += valueTags.size
                            addFact(
                                SemanticFactKind.VPN_DATA_CARRIER_FIELD,
                                "$instructionEvidence -> field ${fieldKey.displayName()} receives VPN/proxy data",
                                fieldKey.displayName(),
                            )
                        }
                    }
                }
            }

            if (opcode.startsWith("invoke")) {
                val invokedMethod = reference as? MethodReference
                val argumentRegisters = instruction.registerList()
                val argumentTags = argumentRegisters.flatMap { registerTags[it].orEmpty() }.toSet()
                val argumentStrings = argumentRegisters.mapNotNull(registerStrings::get)
                val argumentInts = argumentRegisters.mapNotNull(registerInts::get)
                if (invokedMethod != null) {
                    val targetClass = dexTypeName(invokedMethod.definingClass)
                    if (targetClass.isNotBlank() && !PLATFORM_CLASS_PREFIXES.any { prefix -> targetClass.startsWith(prefix) }) {
                        methodCalls += MethodCall(
                            callerScope = scope,
                            source = source,
                            callerClass = className,
                            callerMethod = methodName,
                            targetClass = targetClass,
                            targetMethod = methodNameWithParameters(invokedMethod.name, invokedMethod.parameterTypes),
                        )
                    }
                    val invokeSemantics = handleInvoke(
                        method = invokedMethod,
                        packageName = packageName,
                        appClassPrefixes = appClassPrefixes,
                        callerScope = scope,
                        argumentRegisters = argumentRegisters,
                        argumentTags = argumentTags,
                        argumentStrings = argumentStrings,
                        argumentInts = argumentInts,
                        evidence = instructionEvidence,
                        addFact = ::addFact,
                        tagRegister = ::tagRegister,
                    )
                    val summaryTags = semanticSummaries.methodReturnTags[methodKey(invokedMethod)].orEmpty()
                    if (summaryTags.any { it.isVpnOrProxyData() }) {
                        dfgEdges += summaryTags.size
                        addFact(
                            SemanticFactKind.VPN_DATA_METHOD_RETURN,
                            "$instructionEvidence -> ${methodSignature(invokedMethod)} returns VPN/proxy-derived data",
                            methodSignature(invokedMethod),
                        )
                    }
                    applyConstructorFieldSummary(
                        method = invokedMethod,
                        argumentRegisters = argumentRegisters,
                        registerTags = registerTags,
                        summaries = semanticSummaries,
                        evidence = instructionEvidence,
                        addFact = ::addFact,
                        tagRegister = ::tagRegister,
                    )?.let { edges -> dfgEdges += edges }
                    pendingResultTags = invokeSemantics.resultTags + summaryTags
                    dfgEdges += invokeSemantics.dfgEdges

                    if (
                        argumentStrings.any(::isPublicIpEndpoint) &&
                        invokedMethod.isNetworkTransportCall()
                    ) {
                        val invokeSignature = methodSignatureWithArguments(
                            reference = invokedMethod,
                            argumentStrings = argumentStrings,
                            argumentInts = argumentInts,
                        )
                        dfgEdges += argumentStrings.distinct().count(::isPublicIpEndpoint)
                        addFact(
                            SemanticFactKind.PUBLIC_IP_NETWORK_FLOW,
                            "$instructionEvidence -> $invokeSignature",
                            methodSignature(invokedMethod),
                        )
                    }
                }
            }

            if (branchDerivedCountdown > 0 && !opcode.startsWith("if-")) {
                branchDerivedCountdown -= 1
                if (branchDerivedCountdown == 0) {
                    branchDerivedTags = emptySet()
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
        if (facts.isNotEmpty() && taggedBranchCount > 0) {
            facts += fact(
                kind = SemanticFactKind.CONDITIONAL_BRANCH,
                scope = scope,
                source = source,
                evidence = "$evidence -> taggedBranchCount=$taggedBranchCount,totalBranchCount=${cfg.branchCount}",
                className = className,
                methodName = methodName,
            )
        }

        return MethodSemanticResult(
            cfgNodes = cfg.nodes,
            cfgEdges = cfg.edges,
            dfgEdges = dfgEdges,
            facts = facts,
            calls = methodCalls,
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
                if (isDeviceIdentifierField(reference.definingClass, reference.name)) {
                    addFact(SemanticFactKind.DEVICE_IDENTIFIER_COLLECTION, "$evidence -> ${reference.definingClass}->${reference.name}", reference.name)
                }
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
        appClassPrefixes: Set<String>,
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
        val callSignature = methodSignatureWithArguments(
            reference = method,
            argumentStrings = argumentStrings,
            argumentInts = argumentInts,
        )
        val callEvidence = "$evidence -> $callSignature"
        val name = method.name
        val resultTags = mutableSetOf<DataTag>()
        var dfgEdges = 0
        val hasVpnDataArgument = argumentTags.any { it.isVpnOrProxyData() }
        val hasPublicIpArgument = argumentStrings.any(::isPublicIpEndpoint)
        val hasTelemetryPayloadArgument = DataTag.VPN_TELEMETRY_PAYLOAD in argumentTags
        val hasVpnTelemetryKeyArgument = DataTag.VPN_TELEMETRY_VALUE in argumentTags
        val hasLocalProxyArgument = DataTag.LOCAL_PROXY_ENDPOINT in argumentTags ||
            argumentStrings.any(::isSocksOrLocalProxyText)
        val isNetworkTransportCall = (hasVpnDataArgument || hasPublicIpArgument || hasTelemetryPayloadArgument) &&
            method.isNetworkTransportCall()
        val isSdkCall = callerScope == AppSemanticRiskScope.APP_CODE &&
            isSdkBoundaryCall(
                className = className,
                packageName = packageName,
                appClassPrefixes = appClassPrefixes,
            )
        val isTelemetrySink = isTelemetrySinkCall(
            className = className,
            methodName = name,
            hasTrackedPayload = hasVpnDataArgument || hasTelemetryPayloadArgument || hasVpnTelemetryKeyArgument,
        )
        val isSerializationSink = isSerializationLikeCall(
            className = className,
            methodName = name,
            argumentInts = argumentInts,
            hasTrackedPayload = hasVpnDataArgument || hasTelemetryPayloadArgument,
            hasVpnTelemetryKey = hasVpnTelemetryKeyArgument,
        )
        val isHeaderTelemetrySink = isHttpHeaderTelemetrySinkCall(
            className = className,
            methodName = name,
            argumentStrings = argumentStrings,
            hasTrackedPayload = hasVpnDataArgument || hasTelemetryPayloadArgument,
        )

        if (hasVpnDataArgument && isValuePreservingTransformCall(className, name)) {
            resultTags += argumentTags.filter { tag -> tag.isVpnOrProxyData() }
            dfgEdges += 1
        }

        if (
            isVpnClientControlCall(className, name) ||
            (isVpnLaunchCall(className, name) && DataTag.VPN_INTENT in argumentTags)
        ) {
            addFact(SemanticFactKind.VPN_CLIENT_CONTROL_CONTEXT, callEvidence, signature)
            if (isSplitTunnelVpnBuilderCall(className, name)) {
                addFact(SemanticFactKind.SPLIT_TUNNEL_APP_SELECTION, callEvidence, signature)
            }
        }

        if (className == "java.lang.System" && name == "getProperty" && argumentStrings.any(::isSystemProxyPropertyText)) {
            addFact(SemanticFactKind.SYSTEM_PROXY_INSPECTION, callEvidence, signature)
            resultTags += DataTag.LOCAL_PROXY_ENDPOINT
            dfgEdges += 1
        }

        if (className == "java.lang.System" && name in NATIVE_LIBRARY_LOAD_METHODS) {
            argumentStrings
                .mapNotNull { value -> nativeLoadLibraryName(name, value) }
                .forEach { libraryName ->
                    addFact(SemanticFactKind.NATIVE_LIBRARY_LOAD, callEvidence, libraryName)
                }
        }

        if (isSelfProxyUseCall(className, name) || (argumentStrings.any(::isSelfProxyText) && name != "getProperty")) {
            addFact(SemanticFactKind.LOCAL_PROXY_SELF_USE_CONTEXT, callEvidence, signature)
        }

        if (
            isLocalProxyScanText(name) ||
            isLocalProxyScanText(className) ||
            (hasLocalProxyArgument && name.contains("probe", ignoreCase = true))
        ) {
            addFact(SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT, callEvidence, signature)
        }

        if (className == "android.content.Context" && name == "getPackageName") {
            resultTags += DataTag.SELF_PACKAGE_NAME
        }

        if (className == "android.content.Intent" && name == "setPackage") {
            if (DataTag.VPN_INTENT in argumentTags && DataTag.SELF_PACKAGE_NAME in argumentTags) {
                argumentRegisters.firstOrNull()?.let { register ->
                    tagRegister(register, arrayOf(DataTag.SELF_SCOPED_VPN_INTENT))
                }
                resultTags += DataTag.SELF_SCOPED_VPN_INTENT
                dfgEdges += 1
                addFact(SemanticFactKind.SELF_PACKAGE_SCOPED_VPN_QUERY, callEvidence, signature)
            }
        }

        if (className == "android.content.Intent" && (name == "<init>" || name == "setAction")) {
            if (DataTag.VPN_SERVICE_ACTION in argumentTags || argumentStrings.any(::isVpnServiceAction)) {
                argumentRegisters.firstOrNull()?.let { register ->
                    tagRegister(register, arrayOf(DataTag.VPN_INTENT))
                }
                dfgEdges += 1
                addFact(SemanticFactKind.VPN_SERVICE_INTENT, callEvidence, signature)
            }
        }

        if (className == "android.content.pm.PackageManager" && name == "queryIntentServices") {
            addFact(SemanticFactKind.PACKAGE_QUERY_API, callEvidence, signature)
            if (
                DataTag.VPN_INTENT in argumentTags ||
                DataTag.VPN_SERVICE_ACTION in argumentTags ||
                argumentStrings.any(::isVpnServiceAction)
            ) {
                if (DataTag.SELF_SCOPED_VPN_INTENT in argumentTags) {
                    addFact(SemanticFactKind.SELF_PACKAGE_SCOPED_VPN_QUERY, callEvidence, signature)
                } else {
                    addFact(SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE, callEvidence, signature)
                    resultTags += DataTag.VPN_QUERY_RESULT
                    dfgEdges += 1
                }
            }
        }

        if (className == "android.content.pm.PackageManager" && name in BROAD_PACKAGE_INVENTORY_METHODS) {
            addFact(SemanticFactKind.BROAD_PACKAGE_INVENTORY, callEvidence, signature)
            resultTags += DataTag.BROAD_PACKAGE_RESULT
            dfgEdges += 1
        }

        if (className == "android.content.pm.PackageManager" && name in PACKAGE_NAME_QUERY_METHODS) {
            addFact(SemanticFactKind.PACKAGE_QUERY_API, callEvidence, signature)
            val externalPackageNames = argumentStrings.filter { isExternalPackageNameText(it, packageName) }
            if (externalPackageNames.isNotEmpty()) {
                addFact(
                    SemanticFactKind.SINGLE_PACKAGE_INTEGRATION_CHECK,
                    callEvidence,
                    externalPackageNames.joinToString("|"),
                )
            }
            if (externalPackageNames.any(::isSensitivePackageNameText)) {
                addFact(SemanticFactKind.PACKAGE_NAME_LIST_CHECK, callEvidence, signature)
                resultTags += DataTag.PACKAGE_INVENTORY_VALUE
                dfgEdges += 1
            }
            if (DataTag.KNOWN_VPN_PACKAGE in argumentTags || argumentStrings.any { it in AppRiskRules.vpnClientPackageNames }) {
                addFact(SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK, callEvidence, signature)
                resultTags += DataTag.VPN_PACKAGE_VALUE
                dfgEdges += 1
            }
        }

        val hasVpnPackageInventoryArgument =
            DataTag.VPN_QUERY_RESULT in argumentTags ||
                DataTag.VPN_PACKAGE_VALUE in argumentTags ||
                DataTag.PACKAGE_INVENTORY_VALUE in argumentTags ||
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
                    addFact(SemanticFactKind.VPN_RESULT_COLLECTION, callEvidence, signature)
                    dfgEdges += 1
                    resultTags.addAll(argumentTags.filter { it.isVpnOrProxyData() })
                }
                isSerializationSink -> {
                    addFact(SemanticFactKind.VPN_RESULT_COLLECTION, callEvidence, signature)
                    if (hasVpnTelemetryKeyArgument || hasVpnDataArgument) {
                        addFact(SemanticFactKind.TELEMETRY_PREPARATION, callEvidence, signature)
                    }
                    addFact(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW, callEvidence, signature)
                    resultTags += DataTag.VPN_TELEMETRY_PAYLOAD
                    dfgEdges += 2
                }
                isNetworkTransportCall -> {
                    addFact(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK, callEvidence, signature)
                    addFact(SemanticFactKind.VPN_DATA_NETWORK_FLOW, callEvidence, signature)
                    dfgEdges += 2
                }
            }
        }

        if (
            isSerializationSink &&
            (hasVpnTelemetryKeyArgument || hasVpnDataArgument)
        ) {
            addFact(SemanticFactKind.TELEMETRY_PREPARATION, callEvidence, signature)
            addFact(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW, callEvidence, signature)
            resultTags += DataTag.VPN_TELEMETRY_PAYLOAD
            dfgEdges += 1
        }

        if (isHeaderTelemetrySink) {
            addFact(SemanticFactKind.TELEMETRY_PREPARATION, callEvidence, signature)
            addFact(SemanticFactKind.VPN_DATA_HTTP_HEADER_FLOW, callEvidence, signature)
            addFact(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK, callEvidence, signature)
            addFact(SemanticFactKind.VPN_DATA_NETWORK_FLOW, callEvidence, signature)
            resultTags += DataTag.VPN_TELEMETRY_PAYLOAD
            dfgEdges += 3
        }

        if (isSdkCall && hasVpnDataArgument) {
            addFact(SemanticFactKind.VPN_DATA_SDK_HANDOFF, callEvidence, signature)
            dfgEdges += 2
        }

        if (
            hasTelemetryPayloadArgument
        ) {
            when {
                isNetworkTransportCall -> {
                    addFact(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK, callEvidence, signature)
                    addFact(SemanticFactKind.VPN_DATA_NETWORK_FLOW, callEvidence, signature)
                    dfgEdges += 2
                }
                isSdkCall || isTelemetrySink -> {
                    addFact(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK, callEvidence, signature)
                    dfgEdges += 1
                    if (isSdkCall) {
                        addFact(SemanticFactKind.VPN_DATA_SDK_HANDOFF, callEvidence, signature)
                        dfgEdges += 1
                    }
                }
            }
        }

        if (isNetworkTransportCall) {
            addFact(SemanticFactKind.NETWORK_LIBRARY_CALL, callEvidence, signature)
        }
        if (isTelemetrySink && name != "<init>") {
            if (hasVpnDataArgument || hasTelemetryPayloadArgument || hasVpnTelemetryKeyArgument) {
                addFact(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK, callEvidence, signature)
                dfgEdges += 1
            }
        }

        if (
            className == "android.net.NetworkCapabilities" &&
            name == "hasTransport" &&
            (
                VPN_TRANSPORT_ID in argumentInts ||
                    argumentStrings.any { it == "TRANSPORT_VPN" || it == "NetworkCapabilities.TRANSPORT_VPN" }
                )
        ) {
            addFact(SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK, callEvidence, signature)
            resultTags += DataTag.VPN_STATE_VALUE
            dfgEdges += 1
        }
        if (
            className == "android.net.NetworkCapabilities" &&
            name == "hasCapability" &&
            (
                NOT_VPN_CAPABILITY_ID in argumentInts ||
                    argumentStrings.any { it == "NET_CAPABILITY_NOT_VPN" || it == "NetworkCapabilities.NET_CAPABILITY_NOT_VPN" }
                )
        ) {
            addFact(SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK, callEvidence, signature)
            resultTags += DataTag.VPN_STATE_VALUE
            dfgEdges += 1
        }
        if (className == "java.net.NetworkInterface" && name == "getNetworkInterfaces") {
            addFact(SemanticFactKind.TUNNEL_INTERFACE_API, callEvidence, signature)
        }
        if (className == "java.net.NetworkInterface" && name == "getMTU") {
            addFact(SemanticFactKind.MTU_PROBE, callEvidence, signature)
        }
        if (className == "android.net.LinkProperties" && name == "getDnsServers") {
            addFact(SemanticFactKind.DNS_SERVER_INSPECTION, callEvidence, signature)
        }
        if (
            (className == "android.net.LinkProperties" && name in ROUTE_INSPECTION_METHODS) ||
            (className == "android.net.ConnectivityManager" && name == "getLinkProperties")
        ) {
            addFact(SemanticFactKind.ROUTE_TABLE_INSPECTION, callEvidence, signature)
        }
        if (isSystemProxyInspectionCall(className, name)) {
            addFact(SemanticFactKind.SYSTEM_PROXY_INSPECTION, callEvidence, signature)
            if (isSystemProxyValueCall(className, name)) {
                resultTags += DataTag.LOCAL_PROXY_ENDPOINT
                dfgEdges += 1
            }
        }
        if (className == "android.net.ConnectivityManager" && name == "getAllNetworks") {
            addFact(SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION, callEvidence, signature)
        }
        if (isDeviceIdentifierCollectionCall(className, name)) {
            addFact(SemanticFactKind.DEVICE_IDENTIFIER_COLLECTION, callEvidence, signature)
            resultTags += DataTag.DEVICE_FINGERPRINT_VALUE
        }
        if (isNetworkFingerprintCollectionCall(className, name)) {
            addFact(SemanticFactKind.NETWORK_FINGERPRINT_COLLECTION, callEvidence, signature)
            resultTags += DataTag.DEVICE_FINGERPRINT_VALUE
        }
        if (isUsageStatsCollectionCall(className, name)) {
            addFact(SemanticFactKind.USAGE_STATS_COLLECTION, callEvidence, signature)
            resultTags += DataTag.DEVICE_FINGERPRINT_VALUE
        }
        if (
            (className == "android.net.ConnectivityManager" && name in CONNECTIVITY_BINDING_METHODS) ||
            (className == "android.net.Network" && name in NETWORK_BINDING_METHODS) ||
            (className == "android.system.Os" && name.startsWith("setsockopt"))
        ) {
            addFact(SemanticFactKind.NETWORK_BYPASS_BINDING, callEvidence, signature)
        }

        if (name == "exec" && argumentStrings.any { it.contains("dumpsys") || it.contains("vpn_management") }) {
            addFact(SemanticFactKind.ACTIVE_VPN_DUMPSYS, callEvidence, signature)
        }

        if (
            method.isSocketConnectCall() &&
            hasLocalProxyArgument
        ) {
            addFact(SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE, callEvidence, signature)
            dfgEdges += 1
        }

        return InvokeSemanticResult(
            resultTags = resultTags,
            dfgEdges = dfgEdges,
        )
    }

    private fun knownInvokeResultTags(
        method: MethodReference,
        packageName: String,
        appClassPrefixes: Set<String>,
        argumentTags: Set<DataTag>,
        argumentStrings: List<String>,
        argumentInts: List<Long>,
    ): Set<DataTag> {
        val className = dexTypeName(method.definingClass)
        val name = method.name
        val resultTags = mutableSetOf<DataTag>()
        val hasVpnDataArgument = argumentTags.any { it.isVpnOrProxyData() }
        val hasTelemetryPayloadArgument = DataTag.VPN_TELEMETRY_PAYLOAD in argumentTags
        val hasVpnTelemetryKeyArgument = DataTag.VPN_TELEMETRY_VALUE in argumentTags

        if (
            className == "android.net.NetworkCapabilities" &&
            name == "hasTransport" &&
            (
                VPN_TRANSPORT_ID in argumentInts ||
                    argumentStrings.any { it == "TRANSPORT_VPN" || it == "NetworkCapabilities.TRANSPORT_VPN" }
                )
        ) {
            resultTags += DataTag.VPN_STATE_VALUE
        }
        if (
            className == "android.net.NetworkCapabilities" &&
            name == "hasCapability" &&
            (
                NOT_VPN_CAPABILITY_ID in argumentInts ||
                    argumentStrings.any { it == "NET_CAPABILITY_NOT_VPN" || it == "NetworkCapabilities.NET_CAPABILITY_NOT_VPN" }
                )
        ) {
            resultTags += DataTag.VPN_STATE_VALUE
        }
        if (className == "java.lang.System" && name == "getProperty" && argumentStrings.any(::isSystemProxyPropertyText)) {
            resultTags += DataTag.LOCAL_PROXY_ENDPOINT
        }
        if (className == "android.content.Context" && name == "getPackageName") {
            resultTags += DataTag.SELF_PACKAGE_NAME
        }
        if (
            className == "android.content.pm.PackageManager" &&
            name == "queryIntentServices" &&
            (
                DataTag.VPN_INTENT in argumentTags ||
                    DataTag.VPN_SERVICE_ACTION in argumentTags ||
                    argumentStrings.any(::isVpnServiceAction)
                )
        ) {
            resultTags += DataTag.VPN_QUERY_RESULT
        }
        if (className == "android.content.pm.PackageManager" && name in BROAD_PACKAGE_INVENTORY_METHODS) {
            resultTags += DataTag.BROAD_PACKAGE_RESULT
        }
        if (
            className == "android.content.pm.PackageManager" &&
            name in PACKAGE_NAME_QUERY_METHODS &&
            (
                DataTag.KNOWN_VPN_PACKAGE in argumentTags ||
                    argumentStrings.any { it in AppRiskRules.vpnClientPackageNames } ||
                    argumentStrings.any(::isSensitivePackageNameText)
                )
        ) {
            resultTags += DataTag.PACKAGE_INVENTORY_VALUE
        }
        if (hasVpnDataArgument && isValuePreservingTransformCall(className, name)) {
            resultTags += argumentTags.filter { it.isVpnOrProxyData() }
        }
        if (
            isSerializationLikeCall(
                className = className,
                methodName = name,
                argumentInts = argumentInts,
                hasTrackedPayload = hasVpnDataArgument || hasTelemetryPayloadArgument,
                hasVpnTelemetryKey = hasVpnTelemetryKeyArgument,
            ) &&
            (hasVpnDataArgument || hasTelemetryPayloadArgument || hasVpnTelemetryKeyArgument)
        ) {
            resultTags += DataTag.VPN_TELEMETRY_PAYLOAD
        }
        return resultTags
    }

    private fun applyConstructorFieldSummary(
        method: MethodReference,
        argumentRegisters: List<Int>,
        registerTags: Map<Int, Collection<DataTag>>,
        summaries: DexSemanticSummaries,
        evidence: String,
        addFact: (SemanticFactKind, String, String) -> Unit,
        tagRegister: (Int, Array<out DataTag>) -> Unit,
    ): Int? {
        if (method.name != "<init>") return null
        var dfgEdges = 0
        applyConstructorFieldSummaryToRegisters(
            targetKey = methodKey(method),
            argumentRegisters = argumentRegisters,
            argumentTagsByIndex = argumentRegisters.map { register -> registerTags[register].orEmpty().toSet() },
            constructorParameterFields = summaries.constructorParameterFields,
            tagRegister = { register, tags -> tagRegister(register, tags.toTypedArray()) },
            addFieldTags = { field, tags ->
                if (tags.any { tag -> tag.isVpnOrProxyData() }) {
                    dfgEdges += tags.size
                    addFact(
                        SemanticFactKind.VPN_DATA_CARRIER_FIELD,
                        "$evidence -> constructor stores VPN/proxy data into ${field.displayName()}",
                        field.displayName(),
                    )
                }
            },
        )
        return dfgEdges
    }

    private fun applyConstructorFieldSummaryToRegisters(
        targetKey: MethodKey,
        argumentRegisters: List<Int>,
        argumentTagsByIndex: List<Set<DataTag>>,
        constructorParameterFields: Map<MethodKey, Map<Int, Set<FieldKey>>>,
        tagRegister: (Int, Set<DataTag>) -> Unit,
        addFieldTags: (FieldKey, Set<DataTag>) -> Unit,
    ) {
        val mappings = constructorParameterFields[targetKey].orEmpty()
        if (mappings.isEmpty()) return
        val receiverRegister = argumentRegisters.firstOrNull()
        mappings.forEach { (parameterIndex, fields) ->
            val tags = argumentTagsByIndex.getOrNull(parameterIndex)
                .orEmpty()
                .filterTo(mutableSetOf()) { tag -> tag.isSummaryTag() }
            if (tags.isEmpty()) return@forEach
            fields.forEach { field -> addFieldTags(field, tags) }
            if (receiverRegister != null && tags.any { it.isVpnOrProxyData() }) {
                tagRegister(receiverRegister, tags)
            }
        }
    }

    private fun scanMethodContext(
        className: String,
        methodName: String,
        evidence: String,
        addFact: (SemanticFactKind, String, String) -> Unit,
    ) {
        val methodToken = methodName.substringBefore("(")
        listOf(className, methodToken).forEach { value ->
            when {
                isSplitTunnelText(value) -> {
                    addFact(SemanticFactKind.SPLIT_TUNNEL_APP_SELECTION, "$evidence -> $value", value)
                }
                isLocalProxyScanText(value) -> {
                    addFact(SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT, "$evidence -> $value", value)
                }
                isSelfProxyText(value) -> {
                    addFact(SemanticFactKind.LOCAL_PROXY_SELF_USE_CONTEXT, "$evidence -> $value", value)
                }
            }
        }
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
            isDeviceIdentifierText(value) -> addFact(SemanticFactKind.DEVICE_IDENTIFIER_COLLECTION, "$evidence -> $value", value)
            isNetworkFingerprintText(value) -> addFact(SemanticFactKind.NETWORK_FINGERPRINT_COLLECTION, "$evidence -> $value", value)
            isPublicIpEndpoint(value) -> addFact(SemanticFactKind.PUBLIC_IP_PROBE, "$evidence -> $value", value)
            isVpnTelemetryText(value) -> addFact(SemanticFactKind.VPN_TELEMETRY_LABEL, "$evidence -> $value", value)
            isSplitTunnelText(value) -> addFact(SemanticFactKind.SPLIT_TUNNEL_APP_SELECTION, "$evidence -> $value", value)
            isLocalProxyScanText(value) -> addFact(SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT, "$evidence -> $value", value)
            isSelfProxyText(value) -> addFact(SemanticFactKind.LOCAL_PROXY_SELF_USE_CONTEXT, "$evidence -> $value", value)
            isSystemProxyPropertyText(value) -> {
                addFact(SemanticFactKind.SYSTEM_PROXY_INSPECTION, "$evidence -> $value", value)
            }
            isSocksOrLocalProxyText(value) -> {
                addFact(SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE, "$evidence -> $value", value)
            }
            isProcRouteText(value) -> {
                addFact(SemanticFactKind.ROUTE_TABLE_INSPECTION, "$evidence -> $value", value)
            }
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

        facts
            .filter {
                it.className.isNotBlank() &&
                    it.methodName.isNotBlank() &&
                    it.scope != AppSemanticRiskScope.NATIVE_CODE
            }
            .groupBy { MethodGroup(it.scope, it.source, it.className, it.methodName) }
            .forEach { (group, groupFacts) ->
                signals += buildGroupSignals(
                    facts = groupFacts,
                    groupLabel = listOfNotNull(group.className, group.methodName).joinToString("#"),
                    trustedVpnClient = trustedVpnClient,
                    classLevel = false,
                )
            }

        facts
            .filter { it.className.isNotBlank() && it.scope != AppSemanticRiskScope.NATIVE_CODE }
            .groupBy { ClassGroup(it.scope, it.source, it.className) }
            .forEach { (group, groupFacts) ->
                signals += buildGroupSignals(
                    facts = groupFacts,
                    groupLabel = group.className,
                    trustedVpnClient = trustedVpnClient,
                    classLevel = true,
                )
            }

        signals += buildCallGraphSignals(summary, trustedVpnClient)
        signals += buildMethodologyPatternSignals(summary, trustedVpnClient)

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
        signals += buildNativeBridgeSignals(summary, trustedVpnClient)
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

    private fun buildCallGraphSignals(
        summary: MutableSemanticSummary,
        trustedVpnClient: Boolean,
    ): List<AppSemanticSignal> {
        return buildScopedCallGraphSignals(
            summary = summary,
            trustedVpnClient = trustedVpnClient,
            scope = AppSemanticRiskScope.APP_CODE,
        ) + buildScopedCallGraphSignals(
            summary = summary,
            trustedVpnClient = trustedVpnClient,
            scope = AppSemanticRiskScope.SDK_CODE,
        )
    }

    private fun buildScopedCallGraphSignals(
        summary: MutableSemanticSummary,
        trustedVpnClient: Boolean,
        scope: AppSemanticRiskScope,
    ): List<AppSemanticSignal> {
        val scopedSource = evidenceSourceForScope(scope)
        val factsByMethod = summary.facts
            .filter {
                it.scope == scope &&
                    it.className.isNotBlank() &&
                    it.methodName.isNotBlank()
            }
            .groupBy { fact ->
                MethodGroup(
                    scope = fact.scope,
                    source = fact.source,
                    className = fact.className,
                    methodName = fact.methodName,
                )
            }
        if (factsByMethod.isEmpty()) return emptyList()

        val scopedCalls = summary.methodCalls
            .filter { call ->
                call.callerScope == scope &&
                    scopeForClass(
                        className = call.targetClass,
                        packageName = summary.packageName,
                        appClassPrefixes = summary.appClassPrefixes,
                    ) == scope
            }
            .groupBy { call ->
                MethodGroup(
                    scope = call.callerScope,
                    source = call.source,
                    className = call.callerClass,
                    methodName = call.callerMethod,
                )
            }
        if (scopedCalls.isEmpty()) return emptyList()

        val signals = mutableListOf<AppSemanticSignal>()
        val emittedFactGroups = mutableSetOf<String>()
        fun factsFor(methods: Set<MethodGroup>): List<SemanticFact> {
            return methods.flatMap { method -> factsByMethod[method].orEmpty() }.distinct()
        }
        fun factGroupKey(methods: Set<MethodGroup>): String {
            return methods
                .filter { method -> factsByMethod.containsKey(method) }
                .sortedWith(compareBy<MethodGroup> { it.className }.thenBy { it.methodName })
                .joinToString("|") { method -> "${method.className}#${method.methodName}" }
        }
        fun addSignalsFor(methods: Set<MethodGroup>) {
            val groupFacts = factsFor(methods)
            if (groupFacts.map { "${it.className}#${it.methodName}" }.distinct().size < 2) return
            val groupKey = factGroupKey(methods)
            if (groupKey.isBlank() || !emittedFactGroups.add(groupKey)) return
            signals += buildGroupSignals(
                facts = groupFacts,
                groupLabel = "app call graph",
                trustedVpnClient = trustedVpnClient,
                classLevel = false,
                allowLoosePackageApi = false,
            )
        }

        scopedCalls.forEach { (caller, calls) ->
            calls.forEach { call ->
                val target = MethodGroup(
                    scope = scope,
                    source = scopedSource,
                    className = call.targetClass,
                    methodName = call.targetMethod,
                )
                addSignalsFor(setOf(caller, target))
            }

            if (
                calls.size <= MAX_CALL_GRAPH_SIBLING_FANOUT &&
                isCallGraphCoordinatorCandidate(caller, factsByMethod)
            ) {
                val siblingMethods = buildSet {
                    add(caller)
                    calls.forEach { call ->
                        add(
                            MethodGroup(
                                scope = scope,
                                source = scopedSource,
                                className = call.targetClass,
                                methodName = call.targetMethod,
                            ),
                        )
                    }
                }
                addSignalsFor(siblingMethods)
            }
        }

        addBoundedCallChainSignals(
            appCalls = scopedCalls,
            scope = scope,
            source = scopedSource,
            factsByMethod = factsByMethod,
            addSignalsFor = ::addSignalsFor,
        )

        return signals
    }

    private fun isCallGraphCoordinatorCandidate(
        caller: MethodGroup,
        factsByMethod: Map<MethodGroup, List<SemanticFact>>,
    ): Boolean {
        val callerFacts = factsByMethod[caller].orEmpty()
        if (callerFacts.any { fact -> fact.kind in CALL_GRAPH_COORDINATOR_FACTS }) return true
        val callerName = caller.methodName.substringBefore("(")
        if (CALL_GRAPH_COORDINATOR_METHOD_TERMS.any { term -> callerName.contains(term, ignoreCase = true) }) {
            return true
        }
        return false
    }

    private fun addBoundedCallChainSignals(
        appCalls: Map<MethodGroup, List<MethodCall>>,
        scope: AppSemanticRiskScope,
        source: AppSemanticEvidenceSource,
        factsByMethod: Map<MethodGroup, List<SemanticFact>>,
        addSignalsFor: (Set<MethodGroup>) -> Unit,
    ) {
        val targetsByCaller = appCalls.mapValues { (_, calls) ->
            calls
                .map { call ->
                    MethodGroup(
                        scope = scope,
                        source = source,
                        className = call.targetClass,
                        methodName = call.targetMethod,
                    )
                }
                .distinct()
        }
        val candidateRoots = buildSet {
            addAll(factsByMethod.keys)
            targetsByCaller.keys.forEach { caller ->
                if (isCallGraphCoordinatorCandidate(caller, factsByMethod)) {
                    add(caller)
                }
            }
        }
        var exploredEdges = 0

        fun traverse(
            current: MethodGroup,
            path: List<MethodGroup>,
            depth: Int,
        ) {
            if (depth >= MAX_CALL_GRAPH_CHAIN_DEPTH || exploredEdges >= MAX_CALL_GRAPH_CHAIN_EDGES) return
            val targets = targetsByCaller[current].orEmpty()
            if (targets.isEmpty() || targets.size > MAX_CALL_GRAPH_CHAIN_FANOUT) return

            targets.forEach { target ->
                if (target in path || exploredEdges >= MAX_CALL_GRAPH_CHAIN_EDGES) return@forEach
                exploredEdges += 1
                val nextPath = path + target
                addSignalsFor(nextPath.toSet())
                traverse(
                    current = target,
                    path = nextPath,
                    depth = depth + 1,
                )
            }
        }

        candidateRoots
            .take(MAX_CALL_GRAPH_CHAIN_ROOTS)
            .forEach { root -> traverse(root, listOf(root), depth = 0) }
    }

    private fun buildMethodologyPatternSignals(
        summary: MutableSemanticSummary,
        trustedVpnClient: Boolean,
    ): List<AppSemanticSignal> {
        if (trustedVpnClient) return emptyList()
        return listOf(AppSemanticRiskScope.APP_CODE, AppSemanticRiskScope.SDK_CODE)
            .flatMap { scope ->
                buildScopeMethodologyPatternSignals(
                    facts = summary.facts.filter { it.scope == scope },
                    scope = scope,
                    packageName = summary.packageName,
                )
            }
    }

    private fun buildScopeMethodologyPatternSignals(
        facts: List<SemanticFact>,
        scope: AppSemanticRiskScope,
        packageName: String,
    ): List<AppSemanticSignal> {
        if (facts.isEmpty()) return emptyList()
        val source = evidenceSourceForScope(scope)
        val hasEnterpriseCleanContext = facts.hasEnterprisePackageManagementContext()
        val hasGenericNetworkCleanContext = scope == AppSemanticRiskScope.SDK_CODE &&
            facts.hasGenericNetworkStateSdkContext()
        val hasUserFacingProxyOrBrowserContext = facts.hasUserFacingProxyOrBrowserResolverContext()
        val packageNames = facts.externalPackageChecks()
        val nonIntegrationPackageCount = packageNames
            .filterNot { name -> isLikelyIntegrationPackageName(name, packageName) }
            .distinct()
            .size
        val hasCrossCategoryPackageEnumeration = nonIntegrationPackageCount >= MIN_PACKAGE_ENUMERATION_METHOD_COUNT
        val hasVpnSpecificInventory = facts.has(SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE) ||
            facts.has(SemanticFactKind.PACKAGE_NAME_LIST_CHECK) ||
            facts.has(SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK) ||
            facts.has(SemanticFactKind.VPN_RESULT_COLLECTION)
        val hasVpnPackageScan = facts.hasVpnPackageScan(
            hasHardcodedPackageEnumeration = hasCrossCategoryPackageEnumeration,
        )
        val hasTelemetry = facts.has(SemanticFactKind.TELEMETRY_PREPARATION) ||
            facts.has(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK) ||
            facts.has(SemanticFactKind.VPN_TELEMETRY_LABEL) ||
            facts.has(SemanticFactKind.VPN_DATA_HTTP_HEADER_FLOW) ||
            facts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW) ||
            facts.has(SemanticFactKind.VPN_DATA_NETWORK_FLOW)
        val hasNetworkSink = facts.has(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK) ||
            facts.has(SemanticFactKind.VPN_DATA_NETWORK_FLOW)
        val hasProvenVpnDataCarrier = facts.has(SemanticFactKind.VPN_DATA_METHOD_RETURN) ||
            facts.has(SemanticFactKind.VPN_DATA_CARRIER_FIELD)
        val hasHeaderTelemetry = facts.has(SemanticFactKind.VPN_DATA_HTTP_HEADER_FLOW)
        val hasVpnSemanticPayloadKey = facts.has(SemanticFactKind.VPN_TELEMETRY_LABEL) ||
            hasHeaderTelemetry
        val hasPublicIp = facts.has(SemanticFactKind.PUBLIC_IP_PROBE) ||
            facts.has(SemanticFactKind.PUBLIC_IP_NETWORK_FLOW)
        val hasActiveProxyOrTorProbe = facts.has(SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE) ||
            facts.has(SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT)
        val hasProxyOrTorProbe = hasActiveProxyOrTorProbe ||
            facts.has(SemanticFactKind.SYSTEM_PROXY_INSPECTION)
        val hasSuspiciousProxyOrTorProbe = hasActiveProxyOrTorProbe &&
            !(
                hasUserFacingProxyOrBrowserContext &&
                    !hasTelemetry &&
                    !hasPublicIp &&
                    !facts.has(SemanticFactKind.NETWORK_BYPASS_BINDING)
                )
        val hasBypass = facts.has(SemanticFactKind.NETWORK_BYPASS_BINDING)
        val hasStrongSdkMethodologyAnchor = scope != AppSemanticRiskScope.SDK_CODE ||
            hasVpnSpecificInventory ||
            hasTelemetry ||
            hasPublicIp ||
            hasBypass ||
            hasSuspiciousProxyOrTorProbe
        val hasBroadInventoryVector = facts.has(SemanticFactKind.BROAD_PACKAGE_INVENTORY) &&
            !hasEnterpriseCleanContext &&
            !(
                scope == AppSemanticRiskScope.SDK_CODE &&
                    hasGenericNetworkCleanContext &&
                    !hasStrongSdkMethodologyAnchor
                )
        val hasPackageInventoryVector = hasVpnPackageScan ||
            (
                hasBroadInventoryVector &&
                    (scope != AppSemanticRiskScope.SDK_CODE || hasStrongSdkMethodologyAnchor)
                ) ||
            (
                hasCrossCategoryPackageEnumeration &&
                    (scope != AppSemanticRiskScope.SDK_CODE || hasStrongSdkMethodologyAnchor)
                )
        val stateVectorCount = listOf(
            facts.has(SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK),
            facts.has(SemanticFactKind.TUNNEL_INTERFACE_PROBE) || facts.has(SemanticFactKind.TUNNEL_INTERFACE_API),
            facts.has(SemanticFactKind.PROC_SOCKET_TABLE),
            facts.has(SemanticFactKind.SYSTEM_PROXY_INSPECTION),
            facts.has(SemanticFactKind.DNS_SERVER_INSPECTION) || facts.has(SemanticFactKind.ROUTE_TABLE_INSPECTION),
            facts.has(SemanticFactKind.ACTIVE_VPN_DUMPSYS),
        ).count { it }
        val hasDeviceOrNetworkFingerprint =
            facts.has(SemanticFactKind.DEVICE_IDENTIFIER_COLLECTION) ||
                facts.has(SemanticFactKind.NETWORK_FINGERPRINT_COLLECTION) ||
                facts.has(SemanticFactKind.USAGE_STATS_COLLECTION)
        val cleanOnlySdkNetworkState = hasGenericNetworkCleanContext &&
            !hasPackageInventoryVector &&
            !hasTelemetry &&
            !hasPublicIp &&
            !hasProxyOrTorProbe &&
            !hasBypass
        if (cleanOnlySdkNetworkState) return emptyList()

        val patternFacts = facts
            .filter { fact -> fact.kind in METHODOLOGY_PATTERN_FACTS }
            .distinct()
        if (patternFacts.isEmpty()) return emptyList()

        return when {
            hasProvenVpnDataCarrier &&
                hasHeaderTelemetry &&
                hasTelemetry &&
                hasNetworkSink -> listOf(
                signal(
                    facts = patternFacts,
                    type = AppSemanticSignalType.DFG,
                    title = "confirmed VPN state HTTP header telemetry path",
                    description = "VPN/proxy state is propagated through method/field data-flow into an HTTP header and network request inside the same scoped evidence set.",
                    confidence = 84,
                    scope = scope,
                    source = source,
                    proofConfidence = 90,
                ),
            )
            hasProvenVpnDataCarrier &&
                hasVpnSemanticPayloadKey &&
                hasTelemetry &&
                hasNetworkSink &&
                !hasGenericNetworkCleanContext -> listOf(
                signal(
                    facts = patternFacts,
                    type = AppSemanticSignalType.DFG,
                    title = "confirmed VPN state serialized telemetry path",
                    description = "VPN/proxy state is propagated through method/field data-flow into serialization and network/telemetry handling inside the same scoped evidence set.",
                    confidence = if (scope == AppSemanticRiskScope.SDK_CODE) 82 else 84,
                    scope = scope,
                    source = source,
                    proofConfidence = 90,
                ),
            )
            hasPackageInventoryVector &&
                hasTelemetry &&
                hasStrongSdkMethodologyAnchor &&
                (scope != AppSemanticRiskScope.SDK_CODE || hasNetworkSink || hasVpnSpecificInventory) &&
                (hasBypass || hasPublicIp || hasSuspiciousProxyOrTorProbe || stateVectorCount >= 2 || hasDeviceOrNetworkFingerprint) -> listOf(
                signal(
                    facts = patternFacts,
                    type = AppSemanticSignalType.COMBINATION,
                    title = "methodology VPN surveillance call-graph bundle",
                    description = if (hasNetworkSink) {
                        "Package/VPN discovery, VPN/proxy state vectors, and telemetry/network handling appear in the same scoped call-graph evidence set."
                    } else {
                        "Package/VPN discovery and VPN/proxy state vectors reach telemetry or serialization in the same scoped call-graph evidence set. Static analysis did not confirm final network transmission."
                    },
                    confidence = when {
                        hasBypass && (hasPublicIp || hasNetworkSink) -> 92
                        hasPublicIp && hasProxyOrTorProbe -> 88
                        scope == AppSemanticRiskScope.SDK_CODE -> 76
                        else -> 82
                    },
                    scope = scope,
                    source = source,
                    proofConfidence = when {
                        hasBypass && (hasPublicIp || hasNetworkSink) -> 92
                        else -> 86
                    },
                ),
            )
            hasPackageInventoryVector &&
                scope != AppSemanticRiskScope.SDK_CODE &&
                hasDeviceOrNetworkFingerprint &&
                hasStrongSdkMethodologyAnchor &&
                (scope != AppSemanticRiskScope.SDK_CODE || hasVpnSpecificInventory || hasPublicIp || hasBypass || hasSuspiciousProxyOrTorProbe) &&
                !(
                    hasUserFacingProxyOrBrowserContext &&
                        !hasTelemetry &&
                        !hasPublicIp &&
                        !hasBypass &&
                        !hasVpnSpecificInventory
                    ) &&
                (stateVectorCount >= 1 || hasPublicIp || hasSuspiciousProxyOrTorProbe) -> listOf(
                signal(
                    facts = patternFacts,
                    type = AppSemanticSignalType.COMBINATION,
                    title = "methodology VPN inventory fingerprint bundle",
                    description = "Package/VPN discovery is combined with VPN/proxy state or network vectors and device/network fingerprint collection. Final transmission was not proven.",
                    confidence = if (scope == AppSemanticRiskScope.SDK_CODE) 72 else 76,
                    scope = scope,
                    source = source,
                    proofConfidence = 84,
                ),
            )
            hasPackageInventoryVector &&
                stateVectorCount >= 3 &&
                hasStrongSdkMethodologyAnchor &&
                !hasEnterpriseCleanContext -> listOf(
                signal(
                    facts = patternFacts,
                    type = AppSemanticSignalType.COMBINATION,
                    title = "methodology multi-vector VPN detection bundle",
                    description = "Several independent package/VPN/proxy detection vectors appear in the same scoped call-graph evidence set. No telemetry sink was confirmed, so this remains below critical.",
                    confidence = if (scope == AppSemanticRiskScope.SDK_CODE) 62 else 66,
                    scope = scope,
                    source = source,
                    proofConfidence = 72,
                ),
            )
            else -> emptyList()
        }
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

        val hasAppVpnPackageScan = appFacts.hasVpnPackageScan(
            hasHardcodedPackageEnumeration = appFacts.hasHardcodedPackageEnumeration(classLevel = false),
        )
        val hasAppTelemetryPreparation = appFacts.has(SemanticFactKind.TELEMETRY_PREPARATION) ||
            appFacts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW)
        val hasSdkTelemetryOrNetwork = relatedSdkFacts.has(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK) ||
            relatedSdkFacts.has(SemanticFactKind.VPN_DATA_NETWORK_FLOW) ||
            relatedSdkFacts.has(SemanticFactKind.TELEMETRY_PREPARATION) ||
            relatedSdkFacts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW) ||
            relatedSdkFacts.has(SemanticFactKind.VPN_TELEMETRY_LABEL)
        val hasSelfProxyContext = appFacts.has(SemanticFactKind.LOCAL_PROXY_SELF_USE_CONTEXT) ||
            relatedSdkFacts.has(SemanticFactKind.LOCAL_PROXY_SELF_USE_CONTEXT)
        val hasProxyScanContext = appFacts.has(SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT) ||
            relatedSdkFacts.has(SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT)
        val hasProxyProbe = (
            appFacts.has(SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE) ||
                relatedSdkFacts.has(SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE)
            ) && (!hasSelfProxyContext || hasProxyScanContext)
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
                    description = "Application code passes a weak VPN/proxy/public-IP diagnostic value into an SDK boundary. This stays diagnostic until VPN package discovery, telemetry/public-IP, proxy probing, or bypass is confirmed in the same graph.",
                    confidence = 22,
                    scope = AppSemanticRiskScope.CROSS_LAYER,
                    source = AppSemanticEvidenceSource.APP_TO_SDK,
                    proofConfidence = 38,
                ),
            )
        }
    }

    private fun buildNativeBridgeSignals(
        summary: MutableSemanticSummary,
        trustedVpnClient: Boolean,
    ): List<AppSemanticSignal> {
        if (summary.nativeBridgeCandidates.isEmpty() || summary.nativeLibraries.isEmpty()) return emptyList()
        val appFacts = summary.facts.filter { it.scope == AppSemanticRiskScope.APP_CODE }
        val loadFacts = appFacts.filter { it.kind == SemanticFactKind.NATIVE_LIBRARY_LOAD }
        val declarationFacts = appFacts.filter { it.kind == SemanticFactKind.NATIVE_METHOD_DECLARATION }
        if (declarationFacts.isEmpty() && loadFacts.isEmpty()) return emptyList()

        val factsByMethod = appFacts.groupBy { fact ->
            MethodGroup(
                scope = fact.scope,
                source = fact.source,
                className = fact.className,
                methodName = fact.methodName,
            )
        }
        val nativeLibraries = summary.nativeLibraries.values.toList()
        val signals = mutableListOf<AppSemanticSignal>()
        val emitted = mutableSetOf<String>()

        summary.nativeBridgeCandidates
            .asSequence()
            .filter { candidate -> candidate.scope == AppSemanticRiskScope.APP_CODE }
            .forEach { candidate ->
                val jniPrefix = jniSymbolPrefix(candidate.className, candidate.simpleMethodName)
                val callerFacts = summary.methodCalls
                    .filter { call ->
                        call.callerScope == AppSemanticRiskScope.APP_CODE &&
                            call.targetClass == candidate.className &&
                            call.targetMethod == candidate.methodName
                    }
                    .flatMap { call ->
                        factsByMethod[
                            MethodGroup(
                                scope = AppSemanticRiskScope.APP_CODE,
                                source = call.source,
                                className = call.callerClass,
                                methodName = call.callerMethod,
                            ),
                        ].orEmpty()
                    }
                val candidateFacts = appFacts.filter { fact ->
                    fact.className == candidate.className &&
                        (
                            fact.methodName == candidate.methodName ||
                                fact.kind == SemanticFactKind.NATIVE_LIBRARY_LOAD ||
                                fact.kind in NATIVE_BRIDGE_JAVA_CONTEXT_FACTS
                            )
                }

                nativeLibraries.forEach { library ->
                    val matchingLoadFacts = loadFacts.filter { fact -> library.matchesLoadName(fact.value) }
                    val hasSameClassLoad = matchingLoadFacts.any { fact -> fact.className == candidate.className }
                    val hasExactJniSymbol = library.jniSymbolTexts.any { symbol -> symbol.contains(jniPrefix) }
                    val hasRegisterNativeName = library.hasRegisterNatives &&
                        library.symbolTexts.any { text -> text == candidate.simpleMethodName }
                    if (!hasExactJniSymbol && !hasRegisterNativeName && !hasSameClassLoad && matchingLoadFacts.isEmpty()) {
                        return@forEach
                    }

                    val nativeFacts = library.facts
                        .filter { fact -> fact.kind in NATIVE_BRIDGE_NATIVE_FACTS }
                        .distinct()
                    if (nativeFacts.isEmpty()) return@forEach

                    val relatedJavaFacts = (
                        declarationFacts.filter { fact ->
                            fact.className == candidate.className && fact.methodName == candidate.methodName
                        } +
                            matchingLoadFacts +
                            candidateFacts +
                            callerFacts
                        )
                        .filter { fact ->
                            fact.kind == SemanticFactKind.NATIVE_METHOD_DECLARATION ||
                                fact.kind == SemanticFactKind.NATIVE_LIBRARY_LOAD ||
                                fact.kind in NATIVE_BRIDGE_JAVA_CONTEXT_FACTS
                        }
                        .distinct()

                    val hasJavaSuspiciousContext = relatedJavaFacts.any { fact ->
                        fact.kind in NATIVE_BRIDGE_JAVA_CONTEXT_FACTS
                    }
                    val hasExactNativeLink = hasExactJniSymbol || hasRegisterNativeName
                    val hasGenericNativeProtocolSupport = isGenericNativeProtocolLibrary(library.libraryName) &&
                        nativeFacts.none { fact ->
                            fact.kind == SemanticFactKind.NETWORK_BYPASS_BINDING ||
                                fact.kind == SemanticFactKind.PUBLIC_IP_PROBE ||
                                fact.kind == SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE
                        }
                    val hasNativePublicIp = nativeFacts.has(SemanticFactKind.PUBLIC_IP_PROBE) ||
                        nativeFacts.has(SemanticFactKind.PUBLIC_IP_NETWORK_FLOW)
                    val hasNativeProxy = nativeFacts.has(SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE) ||
                        nativeFacts.has(SemanticFactKind.SYSTEM_PROXY_INSPECTION)
                    val hasNativeBypass = nativeFacts.has(SemanticFactKind.NETWORK_BYPASS_BINDING)
                    val hasNativeVpnState = nativeFacts.any { fact ->
                        fact.kind == SemanticFactKind.TUNNEL_INTERFACE_PROBE ||
                            fact.kind == SemanticFactKind.TUNNEL_INTERFACE_API ||
                            fact.kind == SemanticFactKind.MTU_PROBE ||
                            fact.kind == SemanticFactKind.PROC_SOCKET_TABLE ||
                            fact.kind == SemanticFactKind.DNS_SERVER_INSPECTION ||
                            fact.kind == SemanticFactKind.ROUTE_TABLE_INSPECTION ||
                            fact.kind == SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK
                    }
                    val nativeContextCount = listOf(
                        hasNativePublicIp,
                        hasNativeProxy,
                        hasNativeBypass,
                        hasNativeVpnState,
                    ).count { it }
                    val hasNativeCompoundContext = nativeContextCount >= 2
                    val hasNativeActionableCompound =
                        hasNativePublicIp && (hasNativeProxy || hasNativeBypass || hasNativeVpnState) ||
                            hasNativeVpnState && (hasNativeProxy || hasNativeBypass)
                    val bridgeIsStronglyLinked = hasExactJniSymbol || hasRegisterNativeName || hasSameClassLoad
                    val confidence = when {
                        trustedVpnClient -> if (hasJavaSuspiciousContext && hasNativeCompoundContext) 20 else 12
                        hasGenericNativeProtocolSupport && !hasJavaSuspiciousContext -> 6
                        hasGenericNativeProtocolSupport -> 18
                        hasNativeBypass && hasJavaSuspiciousContext && bridgeIsStronglyLinked -> 72
                        hasJavaSuspiciousContext && (hasNativeActionableCompound || hasNativePublicIp) && bridgeIsStronglyLinked -> 58
                        hasJavaSuspiciousContext && (hasNativeActionableCompound || hasNativePublicIp) -> 52
                        hasJavaSuspiciousContext -> 45
                        hasNativeActionableCompound && hasExactNativeLink -> 38
                        hasNativeActionableCompound && bridgeIsStronglyLinked -> 28
                        hasNativePublicIp && hasExactNativeLink -> 24
                        hasNativeCompoundContext -> 18
                        else -> 18
                    }
                    val proofConfidence = when {
                        trustedVpnClient -> if (hasJavaSuspiciousContext && hasNativeCompoundContext) 25 else 15
                        hasGenericNativeProtocolSupport && !hasJavaSuspiciousContext -> 8
                        hasGenericNativeProtocolSupport -> 20
                        hasNativeBypass && hasJavaSuspiciousContext && bridgeIsStronglyLinked -> 82
                        hasJavaSuspiciousContext && (hasNativeActionableCompound || hasNativePublicIp) && bridgeIsStronglyLinked -> 65
                        hasJavaSuspiciousContext && (hasNativeActionableCompound || hasNativePublicIp) -> 58
                        hasJavaSuspiciousContext -> 52
                        hasNativeActionableCompound && hasExactNativeLink -> 35
                        hasNativeActionableCompound && bridgeIsStronglyLinked -> 25
                        hasNativePublicIp && hasExactNativeLink -> 28
                        hasNativeCompoundContext -> 18
                        else -> 18
                    }
                    val title = when {
                        hasGenericNativeProtocolSupport && !hasJavaSuspiciousContext -> "generic native SDK protocol support"
                        hasJavaSuspiciousContext -> "Java-to-native VPN/proxy semantic bridge"
                        hasNativeActionableCompound || (hasNativePublicIp && hasExactNativeLink) -> "native bridge with compound VPN/proxy indicators"
                        else -> "native bridge with unresolved native indicator"
                    }
                    val description = when {
                        hasJavaSuspiciousContext && (hasNativeActionableCompound || hasNativePublicIp) -> {
                            "Java code reaches a native method or loaded library, and the linked native library contains public-IP or VPN/proxy indicators. Native control/data-flow is not proven, so this stays below strong Java-only detections."
                        }
                        hasJavaSuspiciousContext -> {
                            "Java VPN/proxy context reaches a native method or loaded library, but native evidence is only a single unresolved indicator."
                        }
                        hasGenericNativeProtocolSupport -> {
                            "The native library looks like generic network/media protocol infrastructure and only contains weak proxy or tunnel strings. It is diagnostic until app-side VPN discovery, telemetry, public-IP probing, or bypass is proven."
                        }
                        hasNativeActionableCompound -> {
                            "A Java native bridge points to a library with public-IP or VPN-state indicators plus native proxy/bypass strings. Native control/data-flow is unresolved, so this remains a low-proof native-only suspicion."
                        }
                        else -> {
                            "A Java native bridge points to native VPN/proxy-related strings, but they look like unresolved protocol/runtime support and Java-side detection intent is not proven."
                        }
                    }
                    val key = "${candidate.className}#${candidate.methodName}:${library.libraryName}:$title"
                    if (emitted.add(key)) {
                        signals += signal(
                            facts = (relatedJavaFacts + nativeFacts).distinct(),
                            type = AppSemanticSignalType.COMBINATION,
                            title = title,
                            description = description,
                            confidence = confidence,
                            scope = AppSemanticRiskScope.CROSS_LAYER,
                            source = AppSemanticEvidenceSource.NATIVE,
                            proofConfidence = proofConfidence,
                        )
                    }
                }
            }

        return signals
            .sortedWith(
                compareByDescending<AppSemanticSignal> { it.proofConfidence }
                    .thenByDescending { it.confidence },
            )
            .take(MAX_NATIVE_BRIDGE_SIGNALS)
    }

    private fun buildGroupSignals(
        facts: List<SemanticFact>,
        groupLabel: String,
        trustedVpnClient: Boolean,
        classLevel: Boolean,
        allowLoosePackageApi: Boolean = true,
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
        val hasHardcodedPackageEnumeration = facts.hasHardcodedPackageEnumeration(classLevel = classLevel)
        val hasVpnPackageScan = facts.hasVpnPackageScan(
            allowLoosePackageApi = allowLoosePackageApi && !classLevel,
            hasHardcodedPackageEnumeration = hasHardcodedPackageEnumeration,
        )
        val hasManifestOnlyVpnVisibility = scope == AppSemanticRiskScope.MANIFEST &&
            (
                facts.has(SemanticFactKind.MANIFEST_VPN_SERVICE_QUERY) ||
                    facts.has(SemanticFactKind.MANIFEST_QUERY_ALL_PACKAGES) ||
                    facts.has(SemanticFactKind.MANIFEST_KNOWN_VPN_PACKAGE_QUERY)
                )
        val hasCollection = facts.has(SemanticFactKind.VPN_RESULT_COLLECTION) ||
            facts.has(SemanticFactKind.TELEMETRY_PREPARATION) ||
            facts.has(SemanticFactKind.VPN_DATA_CARRIER_FIELD) ||
            facts.has(SemanticFactKind.VPN_DATA_METHOD_RETURN) ||
            facts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW)
        val hasTelemetryPreparation = facts.has(SemanticFactKind.TELEMETRY_PREPARATION) ||
            facts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW) ||
            facts.has(SemanticFactKind.VPN_DATA_HTTP_HEADER_FLOW) ||
            facts.has(SemanticFactKind.VPN_TELEMETRY_LABEL)
        val hasNetworkSink = facts.has(SemanticFactKind.TELEMETRY_OR_NETWORK_SINK) ||
            facts.has(SemanticFactKind.VPN_DATA_NETWORK_FLOW)
        val hasTelemetry = hasTelemetryPreparation || hasNetworkSink
        val hasProvenVpnDataCarrier = facts.has(SemanticFactKind.VPN_DATA_METHOD_RETURN) ||
            facts.has(SemanticFactKind.VPN_DATA_CARRIER_FIELD)
        val hasHeaderTelemetry = facts.has(SemanticFactKind.VPN_DATA_HTTP_HEADER_FLOW)
        val hasVpnSemanticPayloadKey = facts.has(SemanticFactKind.VPN_TELEMETRY_LABEL) ||
            hasHeaderTelemetry
        val hasSocksProbe = facts.has(SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE)
        val hasPublicIp = facts.has(SemanticFactKind.PUBLIC_IP_PROBE) ||
            facts.has(SemanticFactKind.PUBLIC_IP_NETWORK_FLOW)
        val hasBypassBinding = facts.has(SemanticFactKind.NETWORK_BYPASS_BINDING)
        val hasUnderlyingEnum = facts.has(SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION)
        val hasVpnClientControl = facts.has(SemanticFactKind.VPN_CLIENT_CONTROL_CONTEXT)
        val hasSplitTunnelContext = facts.has(SemanticFactKind.SPLIT_TUNNEL_APP_SELECTION)
        val hasSelfProxyContext = facts.has(SemanticFactKind.LOCAL_PROXY_SELF_USE_CONTEXT)
        val hasProxyScanContext = facts.has(SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT)
        val hasSuspiciousProxyProbe = hasSocksProbe && (!hasSelfProxyContext || hasProxyScanContext)
        val hasSystemProxyInspection = facts.has(SemanticFactKind.SYSTEM_PROXY_INSPECTION)
        val hasNetworkCapabilitiesVpn = facts.has(SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK)
        val hasTunnelInterface = facts.has(SemanticFactKind.TUNNEL_INTERFACE_PROBE) ||
            facts.has(SemanticFactKind.TUNNEL_INTERFACE_API)
        val hasMtuProbe = facts.has(SemanticFactKind.MTU_PROBE)
        val hasProcSocketTable = facts.has(SemanticFactKind.PROC_SOCKET_TABLE)
        val hasDnsInspection = facts.has(SemanticFactKind.DNS_SERVER_INSPECTION)
        val hasRouteInspection = facts.has(SemanticFactKind.ROUTE_TABLE_INSPECTION)
        val hasDumpsys = facts.has(SemanticFactKind.ACTIVE_VPN_DUMPSYS)
        val hasDeviceIdentifier = facts.has(SemanticFactKind.DEVICE_IDENTIFIER_COLLECTION)
        val hasNetworkFingerprint = facts.has(SemanticFactKind.NETWORK_FINGERPRINT_COLLECTION) ||
            facts.has(SemanticFactKind.USAGE_STATS_COLLECTION)
        val hasDeviceOrNetworkFingerprint = hasDeviceIdentifier || hasNetworkFingerprint
        val hasVpnStateContext = hasNetworkCapabilitiesVpn ||
            hasTunnelInterface ||
            hasMtuProbe ||
            hasProcSocketTable ||
            hasDnsInspection ||
            hasRouteInspection ||
            hasDumpsys
        val hasStrongVpnIntentContext = hasPublicIp ||
            hasVpnPackageScan ||
            hasTelemetry ||
            hasSuspiciousProxyProbe
        val hasBranch = facts.has(SemanticFactKind.CONDITIONAL_BRANCH)
        val hasTrackerSdkContext = facts.hasTrackerSdkContext()
        val hasGenericNetworkStateSdkContext = scope == AppSemanticRiskScope.SDK_CODE &&
            facts.hasGenericNetworkStateSdkContext()
        val hasLegitimateVpnManagementContext = (hasVpnClientControl || hasSplitTunnelContext) &&
            !hasNetworkSink &&
            !facts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW) &&
            !hasPublicIp &&
            !hasProxyScanContext
        val hasRiskyVpnPackageScan = hasVpnPackageScan && !hasLegitimateVpnManagementContext

        if (
            hasLegitimateVpnManagementContext &&
            (hasVpnServiceQuery || hasBroadInventory || hasCollection)
        ) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.CALL_GRAPH,
                title = "VPN client or split-tunnel app selection context",
                description = "Package visibility is connected to local VPN client management or split-tunnel app selection, without telemetry, public-IP probing, or proxy scanning in this graph.",
                confidence = 6,
                scope = scope,
                source = source,
            )
        }

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
                title = if (hasLegitimateVpnManagementContext) {
                    "VpnService query in VPN client context"
                } else {
                    "VpnService query without result use"
                },
                description = if (hasLegitimateVpnManagementContext) {
                    "Code queries VpnService while also managing a local VPN/split-tunnel flow; this is diagnostic unless the result reaches telemetry or probing."
                } else {
                    "Code queries PackageManager for android.net.VpnService, but this graph did not prove package/label collection."
                },
                confidence = if (hasLegitimateVpnManagementContext) 4 else 10,
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

        if (hasSystemProxyInspection && !hasVpnPackageScan && !hasTelemetry) {
            result += signal(
                facts = facts.filter { it.kind == SemanticFactKind.SYSTEM_PROXY_INSPECTION },
                type = AppSemanticSignalType.CALL_GRAPH,
                title = "system proxy inspection",
                description = "Code reads Android or JVM system proxy configuration. This is low alone and becomes stronger only when the proxy value reaches telemetry, public-IP comparison, or local proxy scanning.",
                confidence = 8,
                scope = scope,
                source = source,
            )
        }

        if (hasDnsInspection && !hasVpnPackageScan && !hasTelemetry) {
            result += signal(
                facts = facts.filter { it.kind == SemanticFactKind.DNS_SERVER_INSPECTION },
                type = AppSemanticSignalType.CALL_GRAPH,
                title = "DNS server inspection",
                description = "Code reads LinkProperties DNS servers. This is low alone and becomes stronger with VPN transport, routing, or telemetry context.",
                confidence = 8,
                scope = scope,
                source = source,
            )
        }

        if (hasRouteInspection && !hasVpnPackageScan && !hasTelemetry) {
            result += signal(
                facts = facts.filter { it.kind == SemanticFactKind.ROUTE_TABLE_INSPECTION },
                type = AppSemanticSignalType.CALL_GRAPH,
                title = "route table inspection",
                description = "Code reads Android route/link properties. This is low alone and becomes stronger with VPN state or bypass context.",
                confidence = 8,
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
                title = if (hasLegitimateVpnManagementContext) {
                    "VPN split-tunnel package inventory"
                } else {
                    "VPN app inventory collection"
                },
                description = if (hasLegitimateVpnManagementContext) {
                    "VPN/package inventory is connected to local VPN client or split-tunnel app selection, without telemetry or probing in this graph."
                } else {
                    "The graph shows VPN app discovery and extraction or collection of package/service metadata."
                },
                confidence = if (hasLegitimateVpnManagementContext) 8 else 35,
                scope = scope,
                source = source,
            )
        } else if (!classLevel && hasBroadInventory && hasKnownVpnPackage && !hasTelemetry && !trustedVpnClient && !hasLegitimateVpnManagementContext) {
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

        if (!classLevel && hasRiskyVpnPackageScan && hasCollection && hasDeviceOrNetworkFingerprint && !hasTelemetry) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.DFG,
                title = "VPN app inventory with device/network fingerprint",
                description = "VPN app/service inventory is collected in the same semantic graph as device, usage, Wi-Fi, operator, or network fingerprint fields. This is high-risk even when static analysis has not proven final transmission.",
                confidence = 72,
                scope = scope,
                source = source,
                proofConfidence = 88,
            )
        }

        if (!classLevel && hasRiskyVpnPackageScan && hasTelemetry && hasBypassBinding) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.COMBINATION,
                title = "critical VPN app discovery with network bypass",
                description = "VPN app discovery is combined with telemetry/network handling and an API that can bind traffic outside the VPN path.",
                confidence = if (hasPublicIp || hasNetworkSink) 98 else 90,
                scope = scope,
                source = source,
            )
        } else if (!classLevel && hasRiskyVpnPackageScan && hasTelemetry && hasSuspiciousProxyProbe && hasPublicIp) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.COMBINATION,
                title = "critical VPN inventory with proxy and public-IP probing",
                description = "VPN app inventory is combined with telemetry, localhost/SOCKS probing, and public-IP probing.",
                confidence = 92,
                scope = scope,
                source = source,
            )
        } else if (!classLevel && hasRiskyVpnPackageScan && hasTelemetry && hasSuspiciousProxyProbe) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.COMBINATION,
                title = "VPN inventory with SOCKS/local proxy telemetry",
                description = "VPN app inventory is combined with telemetry and localhost/SOCKS proxy probing. This is high risk, but not critical without public-IP comparison or network bypass.",
                confidence = 78,
                scope = scope,
                source = source,
            )
        } else if (!classLevel && hasRiskyVpnPackageScan && hasTelemetry) {
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

        if (!classLevel && hasVpnStateContext && hasTelemetry && hasDeviceOrNetworkFingerprint && !hasLegitimateVpnManagementContext) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.COMBINATION,
                title = "VPN state embedded in device/network fingerprint",
                description = "VPN/proxy state is written into a telemetry-style payload together with device, usage, Wi-Fi, operator, or network fingerprint fields.",
                confidence = when {
                    hasNetworkSink -> 84
                    facts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW) -> 75
                    hasTrackerSdkContext -> 72
                    hasGenericNetworkStateSdkContext -> 18
                    else -> 70
                },
                scope = scope,
                source = source,
                proofConfidence = if (hasGenericNetworkStateSdkContext) 38 else 88,
            )
        }

        if (!classLevel && hasUnderlyingEnum && hasBypassBinding && hasNetworkCapabilitiesVpn && hasStrongVpnIntentContext && !hasLegitimateVpnManagementContext) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.COMBINATION,
                title = "underlying-network bypass semantic path",
                description = "The graph combines network enumeration, VPN transport context, and network binding with VPN telemetry, proxy/public-IP, or VPN-app discovery context.",
                confidence = if (hasPublicIp || hasNetworkSink || hasVpnPackageScan) 95 else 89,
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

        if (!classLevel && hasPublicIp && hasNetworkCapabilitiesVpn && (hasNetworkSink || facts.has(SemanticFactKind.PUBLIC_IP_NETWORK_FLOW))) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.COMBINATION,
                title = "public-IP and VPN-state analytics telemetry path",
                description = "The graph combines public-IP lookup, Android VPN transport state, and an analytics or telemetry sink.",
                confidence = 88,
                scope = scope,
                source = source,
            )
        }

        if (!classLevel && hasSuspiciousProxyProbe && hasPublicIp && !hasVpnPackageScan) {
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

        if (!classLevel && hasProxyScanContext && hasPublicIp && !hasVpnPackageScan) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.COMBINATION,
                title = "local proxy/control API probing with exit-IP context",
                description = "Proxy scanner/control-API terms are connected to public-IP lookup, which can expose local proxy or VPN exit behavior.",
                confidence = 70,
                scope = scope,
                source = source,
            )
        } else if (!classLevel && hasProxyScanContext && !hasSelfProxyContext) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.STRING_FLOW,
                title = "local proxy/control API scanner context",
                description = "The graph contains local proxy scanner, Xray/Clash control API, or SOCKS probing context. This is diagnostic until exit-IP, socket, or telemetry flow is confirmed.",
                confidence = 30,
                scope = scope,
                source = source,
            )
        }

        if (!classLevel && hasSystemProxyInspection && hasTelemetry && !hasSelfProxyContext) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.DFG,
                title = "system proxy telemetry path",
                description = "System proxy state is connected to telemetry or serialization in the same semantic graph. This is suspicious, but lower confidence than active localhost proxy probing.",
                confidence = if (hasNetworkSink || facts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW)) 45 else 25,
                scope = scope,
                source = source,
            )
        }

        if (!classLevel && hasBranch && (hasRiskyVpnPackageScan || hasVpnStateContext) && hasTelemetry) {
            val confidence = when {
                hasLegitimateVpnManagementContext -> 12
                hasGenericNetworkStateSdkContext &&
                    !hasVpnPackageScan &&
                    !hasHeaderTelemetry &&
                    !hasPublicIp &&
                    !hasBypassBinding -> 18
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

        if (
            !classLevel &&
            hasTrackerSdkContext &&
            hasVpnStateContext &&
            hasBranch &&
            !hasGenericNetworkStateSdkContext &&
            !hasLegitimateVpnManagementContext &&
            !hasTelemetry
        ) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.CFG,
                title = "tracker SDK VPN-state telemetry decision",
                description = "A tracker or analytics SDK branches on Android VPN/proxy state. The exact network sink was not reconstructed in this method, so this is below critical but high-risk for SDK code.",
                confidence = 62,
                scope = scope,
                source = source,
                proofConfidence = 86,
            )
        }

        if (!classLevel && hasVpnStateContext && hasProvenVpnDataCarrier && hasHeaderTelemetry && !hasLegitimateVpnManagementContext) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.DFG,
                title = "VPN state HTTP header telemetry path",
                description = "A VPN/proxy state source is propagated through a method return or DTO/model field into an HTTP request header. This is a proven source-to-sink telemetry path, not a standalone network-state check.",
                confidence = 84,
                scope = scope,
                source = source,
                proofConfidence = 90,
            )
        } else if (
            !classLevel &&
            hasVpnStateContext &&
            hasProvenVpnDataCarrier &&
            hasTelemetry &&
            hasNetworkSink &&
            (hasVpnSemanticPayloadKey || hasTrackerSdkContext) &&
            !hasGenericNetworkStateSdkContext &&
            !hasLegitimateVpnManagementContext
        ) {
            result += signal(
                facts = facts,
                type = AppSemanticSignalType.DFG,
                title = "VPN state serialized network telemetry path",
                description = "A VPN/proxy state source is propagated through a method return or DTO/model field into serialization and then a network/telemetry sink.",
                confidence = if (facts.has(SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW)) 84 else 78,
                scope = scope,
                source = source,
                proofConfidence = 90,
            )
        }

        if (
            facts.has(SemanticFactKind.SINGLE_PACKAGE_INTEGRATION_CHECK) &&
            !hasHardcodedPackageEnumeration &&
            !hasVpnPackageScan &&
            !hasTelemetry
        ) {
            result += signal(
                facts = facts.filter { it.kind == SemanticFactKind.SINGLE_PACKAGE_INTEGRATION_CHECK },
                type = AppSemanticSignalType.CALL_GRAPH,
                title = "single package integration check",
                description = "Code checks one or a few integration package names. This is diagnostic only; it is not treated as VPN app discovery unless enumeration, sensitive package context, collection, or telemetry is proven.",
                confidence = 4,
                scope = scope,
                source = source,
            )
        }

        if (hasHardcodedPackageEnumeration && !hasKnownVpnPackage && !hasTelemetry && !hasCollection) {
            result += signal(
                facts = facts.filter { it.kind == SemanticFactKind.SINGLE_PACKAGE_INTEGRATION_CHECK },
                type = AppSemanticSignalType.CALL_GRAPH,
                title = "hardcoded package enumeration diagnostic",
                description = "Code checks several external package names in one semantic graph. This is suspicious as package enumeration, but it stays diagnostic until collection, telemetry, or VPN/proxy context is confirmed.",
                confidence = 18,
                scope = scope,
                source = source,
            )
        }

        if (classLevel && scope == AppSemanticRiskScope.NATIVE_CODE) {
            val hasNativeVpnStateOrTable = hasTunnelInterface ||
                hasProcSocketTable ||
                hasRouteInspection ||
                hasDnsInspection ||
                hasDumpsys ||
                hasSystemProxyInspection
            val nativeIndicatorCount = listOf(
                hasSuspiciousProxyProbe,
                hasSystemProxyInspection,
                hasPublicIp,
                hasBypassBinding,
                hasTunnelInterface,
                hasProcSocketTable,
                hasRouteInspection,
            ).count { it }
            if (nativeIndicatorCount > 0) {
                val nativeConfidence = when {
                    hasBypassBinding && hasPublicIp -> 35
                    hasBypassBinding && hasNativeVpnStateOrTable -> 30
                    hasPublicIp && (hasSuspiciousProxyProbe || hasNativeVpnStateOrTable) -> 25
                    nativeIndicatorCount >= 2 &&
                        !(hasBypassBinding && hasSuspiciousProxyProbe && nativeIndicatorCount == 2) -> 22
                    else -> 10
                }
                result += signal(
                    facts = facts.filter { fact ->
                        fact.kind == SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE ||
                            fact.kind == SemanticFactKind.SYSTEM_PROXY_INSPECTION ||
                            fact.kind == SemanticFactKind.PUBLIC_IP_PROBE ||
                            fact.kind == SemanticFactKind.NETWORK_BYPASS_BINDING ||
                            fact.kind == SemanticFactKind.TUNNEL_INTERFACE_PROBE ||
                            fact.kind == SemanticFactKind.PROC_SOCKET_TABLE ||
                            fact.kind == SemanticFactKind.ROUTE_TABLE_INSPECTION
                    },
                    type = AppSemanticSignalType.STRING_FLOW,
                    title = "native VPN/proxy diagnostic strings",
                    description = "Native code contains VPN/proxy diagnostic strings. This is shown as evidence, but it stays low/medium until Java/JNI control flow proves active use.",
                    confidence = nativeConfidence,
                    scope = scope,
                    source = source,
                    proofConfidence = (nativeConfidence + 3).coerceAtMost(38),
                )
            }
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
        proofConfidence: Int? = null,
    ): AppSemanticSignal {
        val chain = facts
            .sortedWith(
                compareBy<SemanticFact> { fact -> evidencePriorityFor(title, fact.kind) }
                    .thenBy { it.kind.ordinal }
                    .thenBy { it.evidence },
            )
            .map { it.evidenceLine() }
            .distinct()
            .take(MAX_EVIDENCE_CHAIN)
        val riskConfidence = confidence.coerceIn(0, 100)
        val semanticProofConfidence = (
            proofConfidence ?: inferProofConfidence(
                facts = facts,
                type = type,
                riskConfidence = riskConfidence,
                scope = scope,
                source = source,
            )
            ).coerceIn(0, 100)
        return AppSemanticSignal(
            type = type,
            title = title,
            description = description,
            evidence = chain.firstOrNull().orEmpty(),
            confidence = riskConfidence,
            scope = scope,
            source = source,
            evidenceChain = chain,
            proofConfidence = semanticProofConfidence,
            proofLevel = AppSemanticProofLevel.from(semanticProofConfidence),
            proofReason = proofReasonForSignal(
                facts = facts,
                type = type,
                riskConfidence = riskConfidence,
                proofConfidence = semanticProofConfidence,
                scope = scope,
                source = source,
            ),
        )
    }

    private fun proofReasonForSignal(
        facts: List<SemanticFact>,
        type: AppSemanticSignalType,
        riskConfidence: Int,
        proofConfidence: Int,
        scope: AppSemanticRiskScope,
        source: AppSemanticEvidenceSource,
    ): String {
        if (riskConfidence <= 0 || proofConfidence <= 0) {
            return "Neutral context marker; it explains why related low-level checks were not treated as a proven threat."
        }

        val hasOnlyManifestFacts = facts.isNotEmpty() && facts.all { it.scope == AppSemanticRiskScope.MANIFEST }
        val hasOnlyNativeFacts = facts.isNotEmpty() && facts.all { it.scope == AppSemanticRiskScope.NATIVE_CODE }
        val touchesNative = scope == AppSemanticRiskScope.NATIVE_CODE ||
            source == AppSemanticEvidenceSource.NATIVE ||
            facts.any { it.scope == AppSemanticRiskScope.NATIVE_CODE }
        val semanticType = type == AppSemanticSignalType.COMBINATION ||
            type == AppSemanticSignalType.CFG ||
            type == AppSemanticSignalType.DFG
        val packageApiOnly = facts.has(SemanticFactKind.PACKAGE_QUERY_API) &&
            !facts.has(SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE) &&
            !facts.has(SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE) &&
            !facts.has(SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK) &&
            !facts.has(SemanticFactKind.MANIFEST_KNOWN_VPN_PACKAGE_QUERY)

        return when {
            hasOnlyManifestFacts || scope == AppSemanticRiskScope.MANIFEST -> {
                "Low proof: only manifest/package-visibility evidence was found; code usage was not confirmed."
            }
            hasOnlyNativeFacts || scope == AppSemanticRiskScope.NATIVE_CODE -> {
                "Low proof: native strings or symbols were found, but Java/JNI data-flow or control-flow did not prove how they are used."
            }
            touchesNative -> {
                "Medium proof: Java reaches native evidence, but native internal data-flow is unresolved, so this stays below fully proven Java chains."
            }
            packageApiOnly -> {
                "Medium proof: a generic PackageManager API appears in the graph, but it is not tightly bound to a VPN service/package match."
            }
            source == AppSemanticEvidenceSource.APP_TO_SDK -> {
                "Medium/high proof: app data crosses an SDK boundary; confidence depends on whether telemetry/proxy/public-IP evidence is also in the same graph."
            }
            semanticType && proofConfidence >= 80 -> {
                "High proof: tracked VPN/proxy facts are connected by semantic data-flow, control-flow, or one bounded call chain."
            }
            semanticType && proofConfidence >= 50 -> {
                "Medium proof: several relevant checks are in the same semantic graph, but the final harmful use is not fully proven."
            }
            else -> {
                "Low proof: standalone diagnostic signal without a proven telemetry, bypass, blocking, or exit-IP chain."
            }
        }
    }

    private fun inferProofConfidence(
        facts: List<SemanticFact>,
        type: AppSemanticSignalType,
        riskConfidence: Int,
        scope: AppSemanticRiskScope,
        source: AppSemanticEvidenceSource,
    ): Int {
        if (riskConfidence <= 0) return 0

        val hasOnlyManifestFacts = facts.isNotEmpty() && facts.all { it.scope == AppSemanticRiskScope.MANIFEST }
        val hasOnlyNativeFacts = facts.isNotEmpty() && facts.all { it.scope == AppSemanticRiskScope.NATIVE_CODE }
        val touchesNative = scope == AppSemanticRiskScope.NATIVE_CODE ||
            source == AppSemanticEvidenceSource.NATIVE ||
            facts.any { it.scope == AppSemanticRiskScope.NATIVE_CODE }
        val semanticType = type == AppSemanticSignalType.COMBINATION ||
            type == AppSemanticSignalType.CFG ||
            type == AppSemanticSignalType.DFG

        val inferred = when {
            scope == AppSemanticRiskScope.MANIFEST || hasOnlyManifestFacts -> riskConfidence.coerceAtMost(25)
            scope == AppSemanticRiskScope.NATIVE_CODE || hasOnlyNativeFacts -> when {
                riskConfidence >= 35 -> 30
                riskConfidence >= 20 -> 25
                else -> 18
            }
            touchesNative -> when {
                semanticType && riskConfidence >= 50 -> 62
                semanticType && riskConfidence >= 40 -> 50
                else -> 35
            }
            source == AppSemanticEvidenceSource.APP_TO_SDK -> when {
                riskConfidence >= 90 -> 94
                riskConfidence >= 70 -> 86
                riskConfidence >= 45 -> 65
                else -> 50
            }
            semanticType && riskConfidence >= 90 -> 98
            semanticType && riskConfidence >= 70 -> 92
            type == AppSemanticSignalType.CFG && riskConfidence >= 50 -> 88
            type == AppSemanticSignalType.DFG && riskConfidence >= 35 -> 70
            type == AppSemanticSignalType.COMBINATION && riskConfidence >= 50 -> 82
            riskConfidence >= 30 -> 55
            else -> (riskConfidence + 20).coerceAtMost(45)
        }
        val packageApiOnly = facts.has(SemanticFactKind.PACKAGE_QUERY_API) &&
            !facts.has(SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE) &&
            !facts.has(SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE) &&
            !facts.has(SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK) &&
            !facts.has(SemanticFactKind.MANIFEST_KNOWN_VPN_PACKAGE_QUERY)
        return if (packageApiOnly && inferred >= 80) {
            65
        } else {
            inferred
        }
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
        val hasOnlyDiagnosticOrInfra = scopedSignals.isNotEmpty() &&
            scopedSignals.all { signal -> signal.isDiagnosticOnly() || signal.isGenericInfrastructureSignal() }
        val hasStrongThreatProof = scopedSignals.any { signal -> signal.isStrongThreatProof() }
        val hasOnlyUnprovenNativeOrSdk = scopedSignals.isNotEmpty() &&
            scopedSignals.all { signal ->
                signal.scope == AppSemanticRiskScope.NATIVE_CODE ||
                    signal.scope == AppSemanticRiskScope.SDK_CODE ||
                    signal.source == AppSemanticEvidenceSource.NATIVE ||
                    signal.isGenericInfrastructureSignal()
            } &&
            !hasStrongThreatProof
        val hasOnlyUnresolvedNativeBridge = scope == AppSemanticRiskScope.CROSS_LAYER &&
            scopedSignals.all { signal ->
                signal.source == AppSemanticEvidenceSource.NATIVE &&
                    !signal.isStrongThreatProof()
            }
        val hasOnlyWeakCrossLayerBridge = scope == AppSemanticRiskScope.CROSS_LAYER &&
            scopedSignals.isNotEmpty() &&
            scopedSignals.all { signal ->
                signal.isWeakBridgeSignal() ||
                    signal.isDiagnosticOnly() ||
                    signal.isGenericInfrastructureSignal()
            } &&
            !hasStrongThreatProof
        val hasJavaNativeSemanticBridge = scope == AppSemanticRiskScope.CROSS_LAYER &&
            scopedSignals.any { signal ->
                signal.title == "Java-to-native VPN/proxy semantic bridge" &&
                    signal.confidence >= 50
            }
        val strongTitles = scopedSignals
            .filter { signal ->
                signal.confidence >= 50 &&
                    (
                        signal.type == AppSemanticSignalType.COMBINATION ||
                            signal.type == AppSemanticSignalType.CFG ||
                            signal.type == AppSemanticSignalType.DFG
                        )
            }
            .map(AppSemanticSignal::title)
            .toSet()
        val hasCompoundCritical = scope == AppSemanticRiskScope.APP_CODE &&
            (
                (
                    "VPN app inventory telemetry path" in strongTitles &&
                        "localhost proxy probe with exit-IP comparison" in strongTitles
                    ) ||
                    (
                        "localhost proxy probe with exit-IP comparison" in strongTitles &&
                            "branching VPN-state telemetry decision" in strongTitles
                        )
                )
        val score = when {
            hasExplicitCritical || hasCompoundCritical -> rawScore
            hasOnlyWeakDiagnostics -> rawScore.coerceAtMost(19)
            hasOnlyDiagnosticOrInfra -> rawScore.coerceAtMost(24)
            hasJavaNativeSemanticBridge -> rawScore.coerceAtMost(89)
            hasOnlyUnprovenNativeOrSdk -> rawScore.coerceAtMost(49)
            hasOnlyUnresolvedNativeBridge -> rawScore.coerceAtMost(49)
            hasOnlyWeakCrossLayerBridge -> rawScore.coerceAtMost(49)
            scope == AppSemanticRiskScope.NATIVE_CODE && !hasStrongThreatProof -> rawScore.coerceAtMost(35)
            scope == AppSemanticRiskScope.SDK_CODE && !hasStrongThreatProof -> rawScore.coerceAtMost(49)
            else -> rawScore.coerceAtMost(89)
        }
        val proofConfidence = proofConfidenceFor(scopedSignals)
        return AppSemanticRiskBucket(
            score = score,
            riskLevel = riskLevelFor(score, scopedSignals),
            signals = scopedSignals,
            proofConfidence = proofConfidence,
            proofLevel = AppSemanticProofLevel.from(proofConfidence),
        )
    }

    private fun evidencePriorityFor(
        title: String,
        kind: SemanticFactKind,
    ): Int {
        val normalizedTitle = title.lowercase()
        return when {
            "handoff" in normalizedTitle && kind == SemanticFactKind.VPN_DATA_SDK_HANDOFF -> 0
            "bypass" in normalizedTitle && kind == SemanticFactKind.NETWORK_BYPASS_BINDING -> 0
            "bypass" in normalizedTitle && kind == SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION -> 1
            "inventory" in normalizedTitle && kind == SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE -> 0
            "inventory" in normalizedTitle && kind == SemanticFactKind.BROAD_PACKAGE_INVENTORY -> 0
            "inventory" in normalizedTitle && kind == SemanticFactKind.PACKAGE_NAME_LIST_CHECK -> 0
            "telemetry" in normalizedTitle && kind == SemanticFactKind.TELEMETRY_OR_NETWORK_SINK -> 0
            "header" in normalizedTitle && kind == SemanticFactKind.VPN_DATA_HTTP_HEADER_FLOW -> 0
            "serialized" in normalizedTitle && kind == SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW -> 0
            "telemetry" in normalizedTitle && kind == SemanticFactKind.VPN_DATA_METHOD_RETURN -> 1
            "telemetry" in normalizedTitle && kind == SemanticFactKind.VPN_DATA_CARRIER_FIELD -> 1
            "telemetry" in normalizedTitle && kind == SemanticFactKind.VPN_DATA_NETWORK_FLOW -> 1
            "telemetry" in normalizedTitle && kind == SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW -> 1
            "public-ip" in normalizedTitle && kind == SemanticFactKind.PUBLIC_IP_NETWORK_FLOW -> 0
            "public-ip" in normalizedTitle && kind == SemanticFactKind.PUBLIC_IP_PROBE -> 1
            "native bridge" in normalizedTitle && kind == SemanticFactKind.NATIVE_METHOD_DECLARATION -> 0
            kind == SemanticFactKind.VPN_SERVICE_ACTION -> 2
            kind == SemanticFactKind.VPN_SERVICE_INTENT -> 2
            kind == SemanticFactKind.VPN_RESULT_COLLECTION -> 3
            kind == SemanticFactKind.VPN_TELEMETRY_LABEL -> 4
            else -> 10
        }
    }

    private fun AppSemanticSignal.isDiagnosticOnly(): Boolean {
        return title in DIAGNOSTIC_ONLY_SIGNAL_TITLES ||
            (
                confidence <= 20 &&
                    proofConfidence <= 35 &&
                    type != AppSemanticSignalType.COMBINATION &&
                    type != AppSemanticSignalType.DFG
                )
    }

    private fun AppSemanticSignal.isGenericInfrastructureSignal(): Boolean {
        return title in GENERIC_INFRASTRUCTURE_SIGNAL_TITLES ||
            description.contains("generic network", ignoreCase = true) ||
            description.contains("generic native", ignoreCase = true) ||
            description.contains("protocol infrastructure", ignoreCase = true)
    }

    private fun AppSemanticSignal.isWeakBridgeSignal(): Boolean {
        return (title == "app-to-SDK VPN data handoff" && confidence <= 25) ||
            (title == "native bridge with unresolved native indicator" && confidence <= 20) ||
            (title == "native bridge with compound VPN/proxy indicators" && proofConfidence <= 35)
    }

    private fun AppSemanticSignal.isStrongThreatProof(): Boolean {
        if (isDiagnosticOnly() || isGenericInfrastructureSignal()) return false
        if (confidence >= 90 || proofConfidence >= 85) return true
        return confidence >= 70 &&
            proofConfidence >= 70 &&
            (
                type == AppSemanticSignalType.COMBINATION ||
                    type == AppSemanticSignalType.DFG ||
                    type == AppSemanticSignalType.CFG
                )
    }

    private fun AppSemanticSignal.isCleanCompatibleSignal(): Boolean {
        if (isGenericInfrastructureSignal()) return true
        if (isDiagnosticOnly()) return true
        return confidence <= 20 && proofConfidence <= 35
    }

    private fun proofConfidenceFor(signals: List<AppSemanticSignal>): Int {
        if (signals.isEmpty()) return 0

        val maxProof = signals.maxOf(AppSemanticSignal::proofConfidence)
        val distinctPartialSemanticTitles = signals
            .filter { signal ->
                signal.scope != AppSemanticRiskScope.NATIVE_CODE &&
                    signal.scope != AppSemanticRiskScope.MANIFEST &&
                    !signal.isDiagnosticOnly() &&
                    !signal.isGenericInfrastructureSignal() &&
                    signal.proofConfidence in 35 until 80 &&
                    signal.confidence >= 25
            }
            .map { signal -> "${signal.scope}:${signal.title}" }
            .distinct()
            .size
        val independentPartialProof = if (distinctPartialSemanticTitles >= 2) 55 else 0
        val unresolvedNativeCap = if (
            signals.all { signal ->
                signal.scope == AppSemanticRiskScope.NATIVE_CODE ||
                    signal.source == AppSemanticEvidenceSource.NATIVE
            }
        ) {
            45
        } else {
            100
        }

        val unresolvedSdkCap = if (
            signals.all { signal ->
                signal.scope == AppSemanticRiskScope.SDK_CODE ||
                    signal.isGenericInfrastructureSignal()
            } &&
            signals.none { signal -> signal.isStrongThreatProof() }
        ) {
            55
        } else {
            100
        }

        return maxOf(maxProof, independentPartialProof)
            .coerceAtMost(unresolvedNativeCap)
            .coerceAtMost(unresolvedSdkCap)
    }

    private fun cleanProofConfidenceFor(
        summary: MutableSemanticSummary,
        signals: List<AppSemanticSignal>,
        score: Int,
        threatProofConfidence: Int,
    ): Int {
        if (signals.any { signal -> signal.isStrongThreatProof() }) return 0
        val cleanPatternConfidence = cleanPatternConfidenceFor(summary, signals)
        if (cleanPatternConfidence > 0 && (score < 50 || signals.all { it.isCleanCompatibleSignal() || it.isWeakBridgeSignal() })) {
            return cleanPatternConfidence
        }
        if (score >= 50 || threatProofConfidence >= 80) return 0
        val hasThreatShapedSignal = signals.any { signal ->
            !signal.isCleanCompatibleSignal() &&
            (
                (
                    signal.type == AppSemanticSignalType.COMBINATION ||
                        signal.type == AppSemanticSignalType.CFG ||
                        signal.type == AppSemanticSignalType.DFG
                    ) &&
                    signal.confidence >= 25
                ) ||
                signal.confidence >= 25 ||
                signal.proofConfidence >= 35
        }
        if (hasThreatShapedSignal) return 0

        val coverage = when {
            signals.isEmpty() && summary.facts.isNotEmpty() -> 100
            summary.methodsAnalyzed >= 500 -> 90
            summary.methodsAnalyzed >= 100 -> 80
            summary.methodsAnalyzed > 0 -> 70
            summary.nativeLibraries.isNotEmpty() -> 30
            summary.facts.any { it.scope == AppSemanticRiskScope.MANIFEST } -> 20
            else -> 0
        }
        if (coverage == 0) return 0

        val weakDiagnosticPenalty = when {
            signals.isEmpty() -> 0
            signals.all { signal ->
                signal.confidence <= 20 &&
                    signal.proofConfidence <= 32 &&
                    (
                        signal.type == AppSemanticSignalType.CALL_GRAPH ||
                            signal.type == AppSemanticSignalType.STRING_FLOW ||
                            signal.type == AppSemanticSignalType.COMBINATION
                        )
            } -> 15
            else -> 35
        }
        return (coverage - weakDiagnosticPenalty).coerceIn(0, 100)
    }

    private fun cleanPatternConfidenceFor(
        summary: MutableSemanticSummary,
        signals: List<AppSemanticSignal>,
    ): Int {
        val facts = summary.facts
        val hasThreatTelemetryOrBypass = facts.any { fact ->
            fact.kind == SemanticFactKind.TELEMETRY_OR_NETWORK_SINK ||
                fact.kind == SemanticFactKind.VPN_DATA_NETWORK_FLOW ||
                fact.kind == SemanticFactKind.VPN_DATA_HTTP_HEADER_FLOW ||
                fact.kind == SemanticFactKind.NETWORK_BYPASS_BINDING ||
                fact.kind == SemanticFactKind.PUBLIC_IP_NETWORK_FLOW
        }
        val hasVpnSpecificInventory = facts.any { fact ->
            fact.kind == SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE ||
                fact.kind == SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK ||
                fact.kind == SemanticFactKind.PACKAGE_NAME_LIST_CHECK ||
                fact.kind == SemanticFactKind.VPN_RESULT_COLLECTION
        }
        val hasEnterprisePackageManagementClean = facts.hasEnterprisePackageManagementContext() &&
            facts.has(SemanticFactKind.BROAD_PACKAGE_INVENTORY) &&
            !hasVpnSpecificInventory &&
            !hasThreatTelemetryOrBypass
        val hasGenericNetworkStateClean = facts.hasGenericNetworkStateSdkContext() &&
            signals.all { signal ->
                signal.isCleanCompatibleSignal() ||
                    signal.title == "branching VPN-state telemetry decision"
            } &&
            !hasVpnSpecificInventory &&
            !hasThreatTelemetryOrBypass
        val hasGenericNativeProtocolClean = signals.isNotEmpty() &&
            signals.all { signal -> signal.isCleanCompatibleSignal() || signal.isGenericInfrastructureSignal() } &&
            summary.nativeLibraries.keys.any(::isGenericNativeProtocolLibrary)
        val hasIntegrationPackageChecksClean = facts.externalPackageChecks().let { packageNames ->
            packageNames.isNotEmpty() &&
                packageNames.all { packageName -> isLikelyIntegrationPackageName(packageName, summary.packageName) } &&
                !hasThreatTelemetryOrBypass &&
                !hasVpnSpecificInventory
        }
        val hasUserFacingProxyOrBrowserClean = facts.hasUserFacingProxyOrBrowserResolverContext() &&
            !hasThreatTelemetryOrBypass &&
            !hasVpnSpecificInventory &&
            !facts.has(SemanticFactKind.PUBLIC_IP_PROBE) &&
            !facts.has(SemanticFactKind.DEVICE_IDENTIFIER_COLLECTION)

        return when {
            hasEnterprisePackageManagementClean -> 82
            hasGenericNetworkStateClean -> 78
            hasUserFacingProxyOrBrowserClean -> 74
            hasGenericNativeProtocolClean -> 72
            hasIntegrationPackageChecksClean -> 70
            else -> 0
        }
    }

    private fun inferAppClassPrefixes(
        classes: Iterable<ClassDef>,
        packageName: String,
    ): Set<String> {
        val ownPackage = packageName.takeIf(String::isNotBlank)
        var ownCount = 0
        val prefixCounts = mutableMapOf<String, Int>()
        classes.forEach { classDef ->
            val className = dexTypeName(classDef.type)
            if (className.isBlank()) return@forEach
            if (ownPackage != null && isOwnPackageClass(className, ownPackage)) {
                ownCount += 1
            }
            if (
                PLATFORM_CLASS_PREFIXES.any { prefix -> className.startsWith(prefix) } ||
                isKnownSdkClass(className)
            ) {
                return@forEach
            }
            val namespacePrefix = namespacePrefix(className) ?: return@forEach
            prefixCounts[namespacePrefix] = (prefixCounts[namespacePrefix] ?: 0) + 1
        }
        val maxCount = prefixCounts.values.maxOrNull() ?: 0
        val shouldInferNamespace = ownCount == 0 ||
            (
                ownCount < MIN_OWN_PACKAGE_CLASS_COUNT &&
                    maxCount >= ownCount * INFERRED_APP_NAMESPACE_DOMINANCE
                )
        val inferredPrefixes = if (shouldInferNamespace && maxCount > 0) {
            val threshold = maxOf(
                MIN_INFERRED_APP_NAMESPACE_CLASS_COUNT,
                (maxCount * INFERRED_APP_NAMESPACE_RATIO).toInt(),
            )
            prefixCounts
                .filterValues { count -> count >= threshold }
                .keys
        } else {
            emptySet()
        }
        return buildSet {
            if (ownPackage != null && ownCount > 0) add(ownPackage)
            addAll(inferredPrefixes)
        }
    }

    private fun namespacePrefix(className: String): String? {
        val parts = className.split('.')
        if (parts.size < 2) return null
        return if (parts.size >= 3 && parts[0].length > 1 && parts[1].length > 1) {
            "${parts[0]}.${parts[1]}.${parts[2]}"
        } else {
            "${parts[0]}.${parts[1]}"
        }
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

    private fun org.jf.dexlib2.iface.Method.isNativeDeclaration(): Boolean {
        return accessFlags and NATIVE_METHOD_ACCESS_FLAG != 0
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
        appClassPrefixes: Set<String>,
    ): Boolean {
        if (className.isBlank()) return false
        if (isAppCodeClass(className, packageName, appClassPrefixes)) return false
        return !PLATFORM_CLASS_PREFIXES.any { prefix -> className.startsWith(prefix) }
    }

    private fun scopeForClass(
        className: String,
        packageName: String,
        appClassPrefixes: Set<String>,
    ): AppSemanticRiskScope {
        if (isOwnPackageClass(className, packageName)) {
            return AppSemanticRiskScope.APP_CODE
        }
        if (isKnownSdkClass(className)) {
            return AppSemanticRiskScope.SDK_CODE
        }
        if (appClassPrefixes.any { prefix -> className.startsWith(prefix) }) {
            return AppSemanticRiskScope.APP_CODE
        }
        return AppSemanticRiskScope.SDK_CODE
    }

    private fun isKnownSdkClass(className: String): Boolean {
        return KNOWN_SDK_CLASS_PREFIXES.any { prefix -> className.startsWith(prefix) } ||
            TRACKER_SDK_CLASS_PREFIXES.any { prefix -> className.startsWith(prefix) } ||
            GENERIC_NETWORK_STATE_SDK_CLASS_PREFIXES.any { prefix -> className.startsWith(prefix) } ||
            SDK_INFRASTRUCTURE_CLASS_PREFIXES.any { prefix -> className.startsWith(prefix) }
    }

    private fun isAppCodeClass(
        className: String,
        packageName: String,
        appClassPrefixes: Set<String>,
    ): Boolean {
        return isOwnPackageClass(className, packageName) ||
            (!isKnownSdkClass(className) && appClassPrefixes.any { prefix -> className.startsWith(prefix) })
    }

    private fun isOwnPackageClass(
        className: String,
        packageName: String,
    ): Boolean {
        return packageName.isNotBlank() && (className == packageName || className.startsWith("$packageName."))
    }

    private fun shouldSkipSdkInfrastructureClass(className: String): Boolean {
        return SDK_INFRASTRUCTURE_CLASS_PREFIXES.any { prefix -> className.startsWith(prefix) }
    }

    private fun evidenceSourceForScope(scope: AppSemanticRiskScope): AppSemanticEvidenceSource {
        return when (scope) {
            AppSemanticRiskScope.APP_CODE -> AppSemanticEvidenceSource.DIRECT_APP_CODE
            AppSemanticRiskScope.SDK_CODE -> AppSemanticEvidenceSource.SDK
            AppSemanticRiskScope.NATIVE_CODE -> AppSemanticEvidenceSource.NATIVE
            AppSemanticRiskScope.MANIFEST -> AppSemanticEvidenceSource.MANIFEST_ONLY
            AppSemanticRiskScope.CROSS_LAYER -> AppSemanticEvidenceSource.APP_TO_SDK
        }
    }

    private fun DataTag.isVpnOrProxyData(): Boolean {
        return when (this) {
            DataTag.VPN_QUERY_RESULT,
            DataTag.PACKAGE_INVENTORY_VALUE,
            DataTag.KNOWN_VPN_PACKAGE,
            DataTag.VPN_PACKAGE_VALUE,
            DataTag.VPN_STATE_VALUE,
            DataTag.PUBLIC_IP_ENDPOINT,
            DataTag.LOCAL_PROXY_ENDPOINT,
            DataTag.VPN_TELEMETRY_PAYLOAD,
            -> true
            DataTag.VPN_SERVICE_ACTION,
            DataTag.VPN_INTENT,
            DataTag.SELF_PACKAGE_NAME,
            DataTag.SELF_SCOPED_VPN_INTENT,
            DataTag.BROAD_PACKAGE_RESULT,
            DataTag.VPN_TELEMETRY_VALUE,
            DataTag.DEVICE_FINGERPRINT_VALUE,
            -> false
        }
    }

    private fun DataTag.isSummaryTag(): Boolean {
        return this == DataTag.VPN_STATE_VALUE ||
            this == DataTag.PUBLIC_IP_ENDPOINT ||
            this == DataTag.LOCAL_PROXY_ENDPOINT ||
            this == DataTag.VPN_TELEMETRY_PAYLOAD ||
            this == DataTag.VPN_QUERY_RESULT ||
            this == DataTag.PACKAGE_INVENTORY_VALUE ||
            this == DataTag.VPN_PACKAGE_VALUE
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

    private fun isExternalPackageNameText(
        value: String,
        ownPackageName: String,
    ): Boolean {
        val trimmed = value.trim()
        if (!ANDROID_PACKAGE_NAME_PATTERN.matches(trimmed)) return false
        if (trimmed == ownPackageName || trimmed.startsWith("$ownPackageName.")) return false
        return !PLATFORM_PACKAGE_PREFIXES.any { prefix -> trimmed.startsWith(prefix) }
    }

    private fun isSensitivePackageNameText(value: String): Boolean {
        val normalized = value.trim().lowercase()
        if (!ANDROID_PACKAGE_NAME_PATTERN.matches(normalized)) return false
        return SENSITIVE_PACKAGE_NAME_TERMS.any { term -> normalized.contains(term) }
    }

    private fun isLikelyIntegrationPackageName(
        value: String,
        ownPackageName: String,
    ): Boolean {
        val normalized = value.trim().lowercase()
        if (!ANDROID_PACKAGE_NAME_PATTERN.matches(normalized)) return true
        val own = ownPackageName.lowercase()
        if (own.isNotBlank() && (normalized == own || normalized.startsWith("$own."))) return true
        return INTEGRATION_PACKAGE_PREFIXES.any { prefix -> normalized.startsWith(prefix) } ||
            INTEGRATION_PACKAGE_NAMES.any { name -> normalized == name }
    }

    private fun isGenericNativeProtocolLibrary(libraryName: String): Boolean {
        val normalized = libraryName.lowercase()
        return GENERIC_NATIVE_PROTOCOL_LIBRARY_TERMS.any { term -> normalized.contains(term) }
    }

    private fun isTelemetrySinkCall(
        className: String,
        methodName: String,
    ): Boolean {
        return isTelemetrySinkCall(
            className = className,
            methodName = methodName,
            hasTrackedPayload = false,
        )
    }

    private fun isTelemetrySinkCall(
        className: String,
        methodName: String,
        hasTrackedPayload: Boolean,
    ): Boolean {
        val telemetryClass = className in TELEMETRY_CLASS_NAMES ||
            TELEMETRY_CLASS_TERMS.any { term -> className.contains(term, ignoreCase = true) }
        return when {
            telemetryClass -> hasTrackedPayload && !isTelemetryAccessorMethod(methodName)
            hasTrackedPayload && methodName in TELEMETRY_METHOD_NAMES -> true
            else -> false
        }
    }

    private fun isSerializationLikeCall(
        className: String,
        methodName: String,
        argumentInts: List<Long>,
        hasTrackedPayload: Boolean,
        hasVpnTelemetryKey: Boolean,
    ): Boolean {
        if (!hasTrackedPayload) return false
        if (className in SERIALIZATION_CLASSES) return true
        if (methodName in SERIALIZATION_METHODS && hasVpnTelemetryKey) return true
        if (className in MAP_LIKE_CLASSES && methodName in MAP_LIKE_WRITE_METHODS) return hasVpnTelemetryKey
        if (className in EXTENDED_SERIALIZATION_CLASSES) return true
        if (EXTENDED_SERIALIZATION_CLASS_TERMS.any { term -> className.contains(term, ignoreCase = true) }) return true
        if (methodName in EXTENDED_SERIALIZATION_METHODS) return true
        val looksLikeBinaryFieldWriter = argumentInts.any { value -> value in 0L..MAX_SERIALIZATION_FIELD_NUMBER } &&
            (
                methodName in BINARY_WRITER_METHOD_NAMES ||
                    methodName.length <= 2 ||
                    className.contains("writer", ignoreCase = true) ||
                    className.contains("encoder", ignoreCase = true) ||
                    className.contains("serializer", ignoreCase = true)
                )
        return looksLikeBinaryFieldWriter
    }

    private fun isHttpHeaderTelemetrySinkCall(
        className: String,
        methodName: String,
        argumentStrings: List<String>,
        hasTrackedPayload: Boolean,
    ): Boolean {
        if (!hasTrackedPayload) return false
        val hasVpnHeaderKey = argumentStrings.any { value ->
            isVpnTelemetryText(value) ||
                value.contains("vpn", ignoreCase = true) ||
                value.contains("proxy", ignoreCase = true) ||
                value.contains("tun", ignoreCase = true)
        }
        if (!hasVpnHeaderKey) return false
        val looksLikeHeaderClass = HTTP_HEADER_CLASS_TERMS.any { term -> className.contains(term, ignoreCase = true) }
        val looksLikeHeaderMethod = methodName in HTTP_HEADER_METHOD_NAMES ||
            methodName.equals("a", ignoreCase = true) ||
            methodName.equals("c", ignoreCase = true)
        return looksLikeHeaderClass && looksLikeHeaderMethod
    }

    private fun isValuePreservingTransformCall(
        className: String,
        methodName: String,
    ): Boolean {
        return (className == "java.lang.Boolean" && methodName in VALUE_PRESERVING_BOOLEAN_METHODS) ||
            (className == "java.lang.String" && methodName == "valueOf") ||
            (className == "java.lang.Integer" && methodName == "valueOf") ||
            (className == "java.lang.Long" && methodName == "valueOf") ||
            (className == "java.lang.Object" && methodName == "toString") ||
            methodName in VALUE_PRESERVING_METHOD_NAMES
    }

    private fun isTelemetryAccessorMethod(methodName: String): Boolean {
        return methodName == "<init>" ||
            methodName.startsWith("get") ||
            methodName.startsWith("set") ||
            methodName.startsWith("component") ||
            methodName in TELEMETRY_ACCESSOR_METHOD_NAMES
    }

    private fun isSocksOrLocalProxyText(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed == "127.0.0.1" ||
            trimmed == "::1" ||
            trimmed.equals("localhost", ignoreCase = true) ||
            LOCAL_PROXY_ENDPOINT_PATTERN.containsMatchIn(value)
    }

    private fun isNativeSocksOrLocalProxyProbeText(value: String): Boolean {
        if (!isSocksOrLocalProxyText(value)) return false
        return LOCAL_PROXY_ACTIVE_CONTEXT_TERMS.any { term -> value.contains(term, ignoreCase = true) } ||
            LOCAL_PROXY_ACTIVE_ENDPOINT_PATTERN.containsMatchIn(value)
    }

    private fun isSystemProxyPropertyText(value: String): Boolean {
        return SYSTEM_PROXY_PROPERTY_TERMS.any { term -> value.contains(term, ignoreCase = true) }
    }

    private fun isDeviceIdentifierText(value: String): Boolean {
        val normalized = value.trim()
        return normalized in DEVICE_IDENTIFIER_TEXT_TERMS ||
            DEVICE_IDENTIFIER_TEXT_TERMS.any { term ->
                normalized.contains(term, ignoreCase = true) && term.length >= 6
            }
    }

    private fun isNetworkFingerprintText(value: String): Boolean {
        val normalized = value.trim()
        return normalized in NETWORK_FINGERPRINT_TEXT_TERMS
    }

    private fun isProcRouteText(value: String): Boolean {
        return value == "/proc/net/route" ||
            value == "/proc/self/net/route" ||
            value.endsWith("/proc/net/route") ||
            value.endsWith("/proc/self/net/route")
    }

    private fun isSplitTunnelText(value: String): Boolean {
        return SPLIT_TUNNEL_TERMS.any { term -> value.contains(term, ignoreCase = true) }
    }

    private fun isLocalProxyScanText(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed in LOCAL_PROXY_SCAN_EXACT_TERMS ||
            LOCAL_PROXY_SCAN_TERMS.any { term -> containsLocalProxyScanTerm(value, term) }
    }

    private fun containsLocalProxyScanTerm(
        value: String,
        term: String,
    ): Boolean {
        if (term.any { !it.isLetterOrDigit() }) {
            return value.contains(term, ignoreCase = true)
        }

        var startIndex = value.indexOf(term, ignoreCase = true)
        while (startIndex >= 0) {
            val endIndex = startIndex + term.length
            val beforeOk = startIndex == 0 || !value[startIndex - 1].isLetterOrDigit()
            val afterOk = endIndex == value.length ||
                !value[endIndex].isLetterOrDigit() ||
                value[endIndex].isUpperCase()
            if (beforeOk && afterOk) return true
            startIndex = value.indexOf(term, startIndex + 1, ignoreCase = true)
        }
        return false
    }

    private fun isSelfProxyText(value: String): Boolean {
        return SELF_PROXY_TERMS.any { term -> value.contains(term, ignoreCase = true) }
    }

    private fun isVpnClientControlCall(
        className: String,
        methodName: String,
    ): Boolean {
        return (className == "android.net.VpnService" && methodName == "prepare") ||
            className == "android.net.VpnService.Builder"
    }

    private fun isVpnLaunchCall(
        className: String,
        methodName: String,
    ): Boolean {
        return (className == "android.content.Context" && methodName in VPN_LAUNCH_METHODS) ||
            (className == "android.app.Activity" && methodName in VPN_LAUNCH_METHODS)
    }

    private fun isSplitTunnelVpnBuilderCall(
        className: String,
        methodName: String,
    ): Boolean {
        return className == "android.net.VpnService.Builder" &&
            methodName in SPLIT_TUNNEL_BUILDER_METHODS
    }

    private fun isSelfProxyUseCall(
        className: String,
        methodName: String,
    ): Boolean {
        return className == "java.net.Proxy" ||
            className == "java.net.ProxySelector" ||
            (className == "okhttp3.OkHttpClient.Builder" && methodName in SELF_PROXY_BUILDER_METHODS) ||
            (className == "java.lang.System" && methodName == "setProperty")
    }

    private fun isSystemProxyInspectionCall(
        className: String,
        methodName: String,
    ): Boolean {
        return (className == "android.net.ConnectivityManager" && methodName == "getDefaultProxy") ||
            (className == "android.net.LinkProperties" && methodName == "getHttpProxy") ||
            (className == "android.net.ProxyInfo" && methodName in PROXY_INFO_INSPECTION_METHODS) ||
            (className == "android.net.Proxy" && methodName in ANDROID_PROXY_INSPECTION_METHODS)
    }

    private fun isSystemProxyValueCall(
        className: String,
        methodName: String,
    ): Boolean {
        return (className == "android.net.ConnectivityManager" && methodName == "getDefaultProxy") ||
            (className == "android.net.LinkProperties" && methodName == "getHttpProxy") ||
            (className == "android.net.ProxyInfo" && methodName in PROXY_INFO_VALUE_METHODS) ||
            (className == "android.net.Proxy" && methodName in ANDROID_PROXY_VALUE_METHODS)
    }

    private fun isDeviceIdentifierCollectionCall(
        className: String,
        methodName: String,
    ): Boolean {
        return (className == "android.provider.Settings.Secure" && methodName == "getString") ||
            (className == "android.telephony.TelephonyManager" && methodName in TELEPHONY_IDENTIFIER_METHODS) ||
            (className == "com.google.android.gms.ads.identifier.AdvertisingIdClient" && methodName == "getAdvertisingIdInfo") ||
            className.startsWith("com.google.firebase.installations.") ||
            className.contains("AppSetId", ignoreCase = true)
    }

    private fun isNetworkFingerprintCollectionCall(
        className: String,
        methodName: String,
    ): Boolean {
        return (className == "android.net.wifi.WifiManager" && methodName in WIFI_FINGERPRINT_METHODS) ||
            (className == "android.net.wifi.WifiInfo" && methodName in WIFI_FINGERPRINT_METHODS) ||
            (className == "android.telephony.TelephonyManager" && methodName in TELEPHONY_NETWORK_FINGERPRINT_METHODS) ||
            (className == "android.net.NetworkInfo" && methodName in NETWORK_INFO_FINGERPRINT_METHODS)
    }

    private fun isUsageStatsCollectionCall(
        className: String,
        methodName: String,
    ): Boolean {
        return className == "android.app.usage.UsageStatsManager" &&
            methodName in setOf("queryUsageStats", "queryEvents", "queryAndAggregateUsageStats")
    }

    private fun isDeviceIdentifierField(
        definingClass: String,
        fieldName: String,
    ): Boolean {
        return definingClass == "Landroid/os/Build;" && fieldName in BUILD_IDENTIFIER_FIELDS
    }

    private fun nativeLoadLibraryName(
        methodName: String,
        value: String,
    ): String? {
        return when (methodName) {
            "loadLibrary" -> value.trim().takeIf(String::isNotBlank)?.removePrefix("lib")?.removeSuffix(".so")
            "load" -> nativeLibraryName(value).takeIf(String::isNotBlank)
            else -> null
        }
    }

    private fun nativeLibraryName(value: String): String {
        val fileName = value
            .substringBefore(" -> ")
            .substringAfterLast("!/")
            .substringAfterLast('/')
            .substringAfterLast('\\')
        return fileName
            .removePrefix("lib")
            .removeSuffix(".so")
            .trim()
    }

    private fun jniSymbolPrefix(
        className: String,
        methodName: String,
    ): String {
        val encodedClass = className
            .split('.')
            .joinToString("_") { part -> jniEncodeIdentifier(part) }
        return "Java_${encodedClass}_${jniEncodeIdentifier(methodName)}"
    }

    private fun jniEncodeIdentifier(value: String): String {
        return buildString {
            value.forEach { char ->
                when (char) {
                    '_' -> append("_1")
                    ';' -> append("_2")
                    '[' -> append("_3")
                    else -> append(char)
                }
            }
        }
    }

    private fun isTunnelInterfaceText(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TUNNEL_INTERFACE_TEXT_LENGTH) return false
        return TUNNEL_TOKEN_SEPARATOR
            .split(trimmed)
            .any { token -> TUNNEL_NAME_TOKEN_PATTERN.matches(token) }
    }

    private fun isNativeTunnelInterfaceText(value: String): Boolean {
        val trimmed = value.trim()
        if (!isTunnelInterfaceText(trimmed)) return false
        if (trimmed.startsWith("?") || trimmed.contains('_')) return false
        val tokens = TUNNEL_TOKEN_SEPARATOR.split(trimmed).filter(String::isNotBlank)
        if (tokens.size == 1 && TUNNEL_NAME_TOKEN_PATTERN.matches(tokens.first())) return true
        if (tokens.size in 2..4 && tokens.all(TUNNEL_NAME_TOKEN_PATTERN::matches)) return true
        return NATIVE_TUNNEL_CONTEXT_TERMS.any { term -> trimmed.contains(term, ignoreCase = true) }
    }

    private fun methodSignature(reference: MethodReference): String {
        return "${dexTypeName(reference.definingClass)}#${reference.name}"
    }

    private fun methodKey(reference: MethodReference): MethodKey {
        return methodKey(
            className = dexTypeName(reference.definingClass),
            methodName = methodNameWithParameters(reference.name, reference.parameterTypes),
        )
    }

    private fun methodKey(
        className: String,
        methodName: String,
    ): MethodKey {
        return MethodKey(className = className, methodName = methodName)
    }

    private fun fieldKey(reference: FieldReference): FieldKey {
        return FieldKey(
            className = dexTypeName(reference.definingClass),
            fieldName = reference.name,
            fieldType = dexTypeName(reference.type),
        )
    }

    private fun parameterRegisterMap(
        method: org.jf.dexlib2.iface.Method,
        registerCount: Int,
    ): Map<Int, Int> {
        val isStatic = method.accessFlags and STATIC_METHOD_ACCESS_FLAG != 0
        val parameterSlots = method.parameterTypes.sumOf { type -> registerSlotCount(type.toString()) } +
            if (isStatic) 0 else 1
        var register = registerCount - parameterSlots
        var parameterIndex = 0
        val result = mutableMapOf<Int, Int>()
        if (!isStatic) {
            result[register] = parameterIndex
            register += 1
            parameterIndex += 1
        }
        method.parameterTypes.forEach { type ->
            result[register] = parameterIndex
            register += registerSlotCount(type.toString())
            parameterIndex += 1
        }
        return result
    }

    private fun registerSlotCount(type: String): Int {
        return if (type == "J" || type == "D") 2 else 1
    }

    private fun methodSignatureWithArguments(
        reference: MethodReference,
        argumentStrings: List<String>,
        argumentInts: List<Long>,
    ): String {
        val className = dexTypeName(reference.definingClass)
        val arguments = buildList {
            argumentStrings
                .map { value -> semanticStringArgument(className, reference.name, value) }
                .forEach(::add)
            argumentInts
                .map { value -> semanticIntegerArgument(className, reference.name, value) }
                .forEach(::add)
        }
            .distinct()
            .take(MAX_FORMATTED_INVOKE_ARGUMENTS)
        return if (arguments.isEmpty()) {
            methodSignature(reference)
        } else {
            "${methodSignature(reference)}(${arguments.joinToString()})"
        }
    }

    private fun semanticIntegerArgument(
        className: String,
        methodName: String,
        value: Long,
    ): String {
        return when {
            className == "android.net.NetworkCapabilities" &&
                methodName == "hasTransport" &&
                value == VPN_TRANSPORT_ID -> "TRANSPORT_VPN=$value"
            className == "android.net.NetworkCapabilities" &&
                methodName == "hasCapability" &&
                value == NOT_VPN_CAPABILITY_ID -> "NET_CAPABILITY_NOT_VPN=$value"
            value in SOCKS_OR_TOR_PORTS -> "proxy_port=$value"
            else -> value.toString()
        }
    }

    private fun semanticStringArgument(
        className: String,
        methodName: String,
        value: String,
    ): String {
        val trimmed = value.trim()
        val labeled = when {
            isVpnServiceAction(trimmed) -> "VpnService.SERVICE_INTERFACE=$trimmed"
            isSystemProxyPropertyText(trimmed) -> "property=$trimmed"
            isPublicIpEndpoint(trimmed) -> "url=$trimmed"
            isSocksOrLocalProxyText(trimmed) -> "endpoint=$trimmed"
            isExternalPackageNameText(trimmed, ownPackageName = "") ||
                trimmed in AppRiskRules.vpnClientPackageNames -> "package=$trimmed"
            className == "java.lang.System" && methodName in NATIVE_LIBRARY_LOAD_METHODS -> "library=$trimmed"
            else -> trimmed
        }
        return labeled.take(MAX_FORMATTED_INVOKE_ARGUMENT_LENGTH)
    }

    private fun methodNameWithParameters(
        name: String,
        parameterTypes: Iterable<CharSequence>,
    ): String {
        return "$name${parameterTypes.joinToString(prefix = "(", postfix = ")")}"
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

    private fun List<SemanticFact>.externalPackageChecks(): List<String> {
        return filter { fact -> fact.kind == SemanticFactKind.SINGLE_PACKAGE_INTEGRATION_CHECK }
            .flatMap { fact -> fact.value.split('|') }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun List<SemanticFact>.hasVpnPackageScan(
        allowLoosePackageApi: Boolean = true,
        hasHardcodedPackageEnumeration: Boolean = false,
    ): Boolean {
        val hasVpnServiceQuery = has(SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE)
        val hasPackageQueryApi = has(SemanticFactKind.PACKAGE_QUERY_API)
        val hasBroadInventory = has(SemanticFactKind.BROAD_PACKAGE_INVENTORY)
        val hasPackageNameListCheck = has(SemanticFactKind.PACKAGE_NAME_LIST_CHECK)
        val hasKnownVpnPackage = has(SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE) ||
            has(SemanticFactKind.MANIFEST_KNOWN_VPN_PACKAGE_QUERY) ||
            has(SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK)
        return hasVpnServiceQuery ||
            has(SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK) ||
            (allowLoosePackageApi && hasPackageNameListCheck) ||
            (allowLoosePackageApi && hasHardcodedPackageEnumeration) ||
            (allowLoosePackageApi && hasPackageQueryApi && hasKnownVpnPackage) ||
            (allowLoosePackageApi && hasBroadInventory && hasKnownVpnPackage)
    }

    private fun List<SemanticFact>.hasHardcodedPackageEnumeration(classLevel: Boolean): Boolean {
        val packageNames = externalPackageChecks()
        if (packageNames.isEmpty()) return false
        val threshold = if (classLevel) MIN_PACKAGE_ENUMERATION_CLASS_COUNT else MIN_PACKAGE_ENUMERATION_METHOD_COUNT
        return packageNames.size >= threshold
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

    private fun List<SemanticFact>.hasEnterprisePackageManagementContext(): Boolean {
        return any { fact ->
            ENTERPRISE_PACKAGE_MANAGEMENT_TERMS.any { term ->
                fact.className.contains(term, ignoreCase = true) ||
                    fact.methodName.contains(term, ignoreCase = true) ||
                    fact.evidence.contains(term, ignoreCase = true) ||
                    fact.value.contains(term, ignoreCase = true)
            }
        }
    }

    private fun List<SemanticFact>.hasUserFacingProxyOrBrowserResolverContext(): Boolean {
        return any { fact ->
            USER_FACING_PROXY_BROWSER_TERMS.any { term ->
                fact.className.contains(term, ignoreCase = true) ||
                    fact.methodName.contains(term, ignoreCase = true) ||
                    fact.evidence.contains(term, ignoreCase = true) ||
                    fact.value.contains(term, ignoreCase = true)
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
            SemanticFactKind.SELF_PACKAGE_SCOPED_VPN_QUERY -> "self-package scoped VpnService query"
            SemanticFactKind.VPN_CLIENT_CONTROL_CONTEXT -> "local VPN client control context"
            SemanticFactKind.SPLIT_TUNNEL_APP_SELECTION -> "split-tunnel app selection context"
            SemanticFactKind.PACKAGE_QUERY_API -> "PackageManager query API"
            SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE -> "queryIntentServices(android.net.VpnService)"
            SemanticFactKind.BROAD_PACKAGE_INVENTORY -> "broad installed package inventory"
            SemanticFactKind.SINGLE_PACKAGE_INTEGRATION_CHECK -> "single external package integration check"
            SemanticFactKind.PACKAGE_NAME_LIST_CHECK -> "hardcoded package-name existence check"
            SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE -> "known VPN package reference"
            SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK -> "known VPN package check"
            SemanticFactKind.VPN_RESULT_COLLECTION -> "VPN result collection/use"
            SemanticFactKind.TELEMETRY_PREPARATION -> "VPN data serialization/telemetry preparation"
            SemanticFactKind.TELEMETRY_OR_NETWORK_SINK -> "telemetry or network sink receiving VPN data"
            SemanticFactKind.VPN_TELEMETRY_LABEL -> "VPN telemetry label"
            SemanticFactKind.VPN_DATA_METHOD_RETURN -> "VPN/proxy value returned from method"
            SemanticFactKind.VPN_DATA_CARRIER_FIELD -> "VPN/proxy value carried by DTO or model field"
            SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW -> "VPN data flows into serialized payload"
            SemanticFactKind.VPN_DATA_NETWORK_FLOW -> "VPN/proxy data flows into network call"
            SemanticFactKind.VPN_DATA_HTTP_HEADER_FLOW -> "VPN/proxy data flows into HTTP header"
            SemanticFactKind.VPN_DATA_SDK_HANDOFF -> "VPN/proxy data handed from app code to SDK boundary"
            SemanticFactKind.NETWORK_LIBRARY_CALL -> "network library/API call"
            SemanticFactKind.NATIVE_METHOD_DECLARATION -> "Java/Kotlin native method declaration"
            SemanticFactKind.NATIVE_LIBRARY_LOAD -> "native library load"
            SemanticFactKind.SYSTEM_PROXY_INSPECTION -> "system proxy inspection"
            SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE -> "SOCKS or localhost proxy probe"
            SemanticFactKind.LOCAL_PROXY_SELF_USE_CONTEXT -> "local proxy self-use context"
            SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT -> "local proxy scanner/prober context"
            SemanticFactKind.PUBLIC_IP_PROBE -> "public-IP endpoint reference"
            SemanticFactKind.PUBLIC_IP_NETWORK_FLOW -> "public-IP endpoint flows into network call"
            SemanticFactKind.NETWORK_BYPASS_BINDING -> "underlying network/socket binding API"
            SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION -> "underlying network enumeration"
            SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK -> "NetworkCapabilities VPN transport check"
            SemanticFactKind.TUNNEL_INTERFACE_API -> "NetworkInterface tunnel API"
            SemanticFactKind.TUNNEL_INTERFACE_PROBE -> "tunnel interface name probe"
            SemanticFactKind.MTU_PROBE -> "MTU heuristic probe"
            SemanticFactKind.PROC_SOCKET_TABLE -> "proc socket table inspection"
            SemanticFactKind.DNS_SERVER_INSPECTION -> "DNS server inspection"
            SemanticFactKind.ROUTE_TABLE_INSPECTION -> "route table inspection"
            SemanticFactKind.ACTIVE_VPN_DUMPSYS -> "active dumpsys VPN probe"
            SemanticFactKind.DEVICE_IDENTIFIER_COLLECTION -> "device identifier collection"
            SemanticFactKind.NETWORK_FINGERPRINT_COLLECTION -> "network or Wi-Fi fingerprint collection"
            SemanticFactKind.USAGE_STATS_COLLECTION -> "usage statistics collection"
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
        val calls: List<MethodCall>,
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

    private data class DexSemanticSummaries(
        val methodReturnTags: MutableMap<MethodKey, MutableSet<DataTag>> = mutableMapOf(),
        val fieldTags: MutableMap<FieldKey, MutableSet<DataTag>> = mutableMapOf(),
        val constructorParameterFields: MutableMap<MethodKey, MutableMap<Int, MutableSet<FieldKey>>> = mutableMapOf(),
    )

    private data class MethodFlowSummary(
        val returnTags: Set<DataTag>,
        val fieldTags: Map<FieldKey, Set<DataTag>>,
    )

    private data class MethodKey(
        val className: String,
        val methodName: String,
    )

    private data class FieldKey(
        val className: String,
        val fieldName: String,
        val fieldType: String,
    ) {
        fun displayName(): String {
            return "$className#$fieldName"
        }
    }

    private class MutableSemanticSummary(
        val packageName: String,
    ) {
        var methodsAnalyzed: Int = 0
        var cfgNodeCount: Int = 0
        var cfgEdgeCount: Int = 0
        var dfgEdgeCount: Int = 0
        val facts = mutableListOf<SemanticFact>()
        val methodCalls = mutableListOf<MethodCall>()
        val nativeBridgeCandidates = mutableListOf<NativeBridgeCandidate>()
        val nativeLibraries = linkedMapOf<String, NativeLibrarySummary>()
        val appClassPrefixes = mutableSetOf<String>()

        fun addFact(fact: SemanticFact) {
            facts += fact
        }

        fun addMethodCall(call: MethodCall) {
            methodCalls += call
        }

        fun addNativeBridgeCandidate(candidate: NativeBridgeCandidate) {
            nativeBridgeCandidates += candidate
        }

        fun addNativeLibraryText(
            libraryName: String,
            evidence: String,
            value: String,
        ) {
            nativeLibraries
                .getOrPut(libraryName) { NativeLibrarySummary(libraryName = libraryName, evidence = evidence) }
                .addText(value)
        }

        fun addNativeLibraryFact(
            libraryName: String,
            fact: SemanticFact,
        ) {
            nativeLibraries
                .getOrPut(libraryName) { NativeLibrarySummary(libraryName = libraryName, evidence = fact.evidence) }
                .facts += fact
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

    private data class MethodCall(
        val callerScope: AppSemanticRiskScope,
        val source: AppSemanticEvidenceSource,
        val callerClass: String,
        val callerMethod: String,
        val targetClass: String,
        val targetMethod: String,
    )

    private data class NativeBridgeCandidate(
        val scope: AppSemanticRiskScope,
        val source: AppSemanticEvidenceSource,
        val className: String,
        val methodName: String,
        val simpleMethodName: String,
        val evidence: String,
    )

    private data class NativeLibrarySummary(
        val libraryName: String,
        val evidence: String,
        val facts: MutableList<SemanticFact> = mutableListOf(),
        val symbolTexts: MutableSet<String> = linkedSetOf(),
        val jniSymbolTexts: MutableSet<String> = linkedSetOf(),
        var hasJniOnLoad: Boolean = false,
        var hasRegisterNatives: Boolean = false,
    ) {
        fun addText(value: String) {
            if (value.contains("JNI_OnLoad")) hasJniOnLoad = true
            if (value.contains("RegisterNatives")) hasRegisterNatives = true
            if (value.contains("Java_")) {
                jniSymbolTexts += value
            }
            if (
                symbolTexts.size < MAX_NATIVE_LIBRARY_SYMBOL_TEXTS &&
                value.length in 2..128 &&
                value.all { char -> char.isLetterOrDigit() || char == '_' || char == '$' }
            ) {
                symbolTexts += value
            }
        }

        fun matchesLoadName(value: String): Boolean {
            val normalized = value
                .substringBefore(" -> ")
                .substringAfterLast("!/")
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .removePrefix("lib")
                .removeSuffix(".so")
                .trim()
            return value == libraryName ||
                value == "lib$libraryName.so" ||
                normalized == libraryName
        }
    }

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
        SELF_PACKAGE_SCOPED_VPN_QUERY,
        VPN_CLIENT_CONTROL_CONTEXT,
        SPLIT_TUNNEL_APP_SELECTION,
        PACKAGE_QUERY_API,
        PACKAGE_QUERY_VPN_SERVICE,
        BROAD_PACKAGE_INVENTORY,
        SINGLE_PACKAGE_INTEGRATION_CHECK,
        PACKAGE_NAME_LIST_CHECK,
        KNOWN_VPN_PACKAGE_REFERENCE,
        KNOWN_VPN_PACKAGE_CHECK,
        VPN_RESULT_COLLECTION,
        TELEMETRY_PREPARATION,
        TELEMETRY_OR_NETWORK_SINK,
        VPN_TELEMETRY_LABEL,
        VPN_DATA_METHOD_RETURN,
        VPN_DATA_CARRIER_FIELD,
        VPN_DATA_SERIALIZATION_FLOW,
        VPN_DATA_NETWORK_FLOW,
        VPN_DATA_HTTP_HEADER_FLOW,
        VPN_DATA_SDK_HANDOFF,
        NETWORK_LIBRARY_CALL,
        NATIVE_METHOD_DECLARATION,
        NATIVE_LIBRARY_LOAD,
        SYSTEM_PROXY_INSPECTION,
        SOCKS_OR_LOCAL_PROXY_PROBE,
        LOCAL_PROXY_SELF_USE_CONTEXT,
        LOCAL_PROXY_SCAN_CONTEXT,
        PUBLIC_IP_PROBE,
        PUBLIC_IP_NETWORK_FLOW,
        NETWORK_BYPASS_BINDING,
        UNDERLYING_NETWORK_ENUMERATION,
        NETWORK_CAPABILITIES_VPN_CHECK,
        TUNNEL_INTERFACE_API,
        TUNNEL_INTERFACE_PROBE,
        MTU_PROBE,
        PROC_SOCKET_TABLE,
        DNS_SERVER_INSPECTION,
        ROUTE_TABLE_INSPECTION,
        ACTIVE_VPN_DUMPSYS,
        DEVICE_IDENTIFIER_COLLECTION,
        NETWORK_FINGERPRINT_COLLECTION,
        USAGE_STATS_COLLECTION,
        CONDITIONAL_BRANCH,
    }

    private enum class DataTag {
        VPN_SERVICE_ACTION,
        VPN_INTENT,
        SELF_PACKAGE_NAME,
        SELF_SCOPED_VPN_INTENT,
        VPN_QUERY_RESULT,
        BROAD_PACKAGE_RESULT,
        PACKAGE_INVENTORY_VALUE,
        KNOWN_VPN_PACKAGE,
        VPN_PACKAGE_VALUE,
        VPN_STATE_VALUE,
        PUBLIC_IP_ENDPOINT,
        LOCAL_PROXY_ENDPOINT,
        VPN_TELEMETRY_VALUE,
        VPN_TELEMETRY_PAYLOAD,
        DEVICE_FINGERPRINT_VALUE,
    }

    companion object {
        const val ANALYZER_VERSION = 14
        private const val NATIVE_METHOD_ACCESS_FLAG = 0x0100
        private const val STATIC_METHOD_ACCESS_FLAG = 0x0008
        private const val VPN_TRANSPORT_ID = 4L
        private const val NOT_VPN_CAPABILITY_ID = 15L
        private const val METHOD_SUMMARY_ITERATIONS = 4
        private const val BRANCH_DERIVED_TAG_INSTRUCTION_WINDOW = 16
        private const val MAX_SERIALIZATION_FIELD_NUMBER = 128L
        private const val MIN_OWN_PACKAGE_CLASS_COUNT = 8
        private const val MIN_INFERRED_APP_NAMESPACE_CLASS_COUNT = 2
        private const val INFERRED_APP_NAMESPACE_DOMINANCE = 3
        private const val INFERRED_APP_NAMESPACE_RATIO = 0.35
        private const val MAX_SIGNALS = 100
        private const val MAX_NATIVE_BRIDGE_SIGNALS = 24
        private const val MAX_EVIDENCE_CHAIN = 8
        private const val CANCELLATION_CHECK_CLASS_INTERVAL = 8
        private const val CANCELLATION_CHECK_METHOD_INTERVAL = 16
        private const val CANCELLATION_CHECK_INSTRUCTION_INTERVAL = 128
        private const val CANCELLATION_CHECK_NATIVE_STRING_INTERVAL = 128
        private const val MAX_CALL_GRAPH_SIBLING_FANOUT = 12
        private const val MAX_CALL_GRAPH_CHAIN_DEPTH = 4
        private const val MAX_CALL_GRAPH_CHAIN_FANOUT = 12
        private const val MAX_CALL_GRAPH_CHAIN_ROOTS = 512
        private const val MAX_CALL_GRAPH_CHAIN_EDGES = 4096
        private const val MAX_NATIVE_LIBRARY_SYMBOL_TEXTS = 4096
        private const val MIN_NATIVE_STRING_LENGTH = 4
        private const val MAX_NATIVE_REGEX_TEXT_LENGTH = 256
        private const val MAX_FORMATTED_INVOKE_ARGUMENTS = 6
        private const val MAX_FORMATTED_INVOKE_ARGUMENT_LENGTH = 96
        private const val MIN_PACKAGE_ENUMERATION_METHOD_COUNT = 3
        private const val MIN_PACKAGE_ENUMERATION_CLASS_COUNT = 5
        private val DEX_ENTRY_PATTERN = Regex("""classes(?:\d*)\.dex""")
        private const val MAX_TUNNEL_INTERFACE_TEXT_LENGTH = 64
        private val TUNNEL_NAME_TOKEN_PATTERN = Regex("""^(?:(?:tun|ppp|tap|pptp|wg)\d+|ipsec\d+)$""")
        private val TUNNEL_TOKEN_SEPARATOR = Regex("""[^A-Za-z0-9_]+""")
        private val ANDROID_PACKAGE_NAME_PATTERN = Regex("""^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*){1,}$""")
        private val LOCAL_PROXY_ENDPOINT_PATTERN = Regex(
            """(?i)(?:socks5?://|https?://(?:localhost|127\.0\.0\.1|\[?::1\]?)|(?:localhost|127\.0\.0\.1|::1):\d{2,5}|\bSOCKS5?\b)""",
        )
        private val LOCAL_PROXY_ACTIVE_ENDPOINT_PATTERN = Regex(
            """(?i)(?:socks5?://|https?://(?:localhost|127\.0\.0\.1|\[?::1\]?)|(?:localhost|127\.0\.0\.1|::1):\d{2,5})""",
        )
        private val SOCKS_OR_TOR_PORTS = setOf(9050L, 9150L, 1080L)
        private val PLATFORM_PACKAGE_PREFIXES = listOf(
            "android.",
            "androidx.",
            "com.android.",
            "com.google.android.",
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "org.jetbrains.",
        )
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
            "vpn=",
            "&vpn=",
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
        private val SPLIT_TUNNEL_TERMS = listOf(
            "splitTunnel",
            "split_tunnel",
            "split-tunnel",
            "SplitTunnel",
            "perAppVpn",
            "per_app_vpn",
            "allowedApplications",
            "disallowedApplications",
            "allowApps",
            "disallowApps",
            "excludedApplications",
            "includedApplications",
            "bypassVpn",
            "bypass_vpn",
        )
        private val SELF_PROXY_TERMS = listOf(
            "ProxyConfig",
            "ProxySettings",
            "proxySettings",
            "MtProtoProxy",
            "MTProxy",
            "localProxyPort",
            "setProxy",
            "useProxy",
            "use_proxy",
        )
        private val USER_FACING_PROXY_BROWSER_TERMS = listOf(
            "tg:socks",
            "tg://socks",
            "browser",
            "Browser",
            "LaunchActivity",
            "ProxySettings",
            "proxySettings",
            "ProxyListActivity",
            "ProxySettingsActivity",
            "MtProtoProxy",
            "MTProxy",
        )
        private val SYSTEM_PROXY_PROPERTY_TERMS = listOf(
            "http.proxyHost",
            "http.proxyPort",
            "https.proxyHost",
            "https.proxyPort",
            "socksProxyHost",
            "socksProxyPort",
        )
        private val LOCAL_PROXY_SCAN_TERMS = listOf(
            "PortScanner",
            "ProxyScanner",
            "ProxyProber",
            "scanKnownPorts",
            "scanFullRange",
            "scanListeningPorts",
            "probePort",
            "probeSocks",
            "probeSocks5",
            "probeHTTP",
            "probeGrpc",
            "Socks5Probe",
            "AuthProbe",
            "MtProtoProber",
            "MtProtoProbe",
            "probeMtProto",
            "supportsNoAuth",
            "bruteForceCredentials",
            "probeCredentials",
            "tryProxyCredentials",
            "fetchIpViaProxy",
            "fetchDirectIp",
            "LocalProxyCheckResult",
            "ExitIPResolver",
            "ExitIPInfo",
            "CONNECT ifconfig.me",
            "UDP ASSOCIATE",
            "HandlerServiceGrpc",
            "ListOutboundsRequest",
            "XrayApiScanner",
            "XrayAPIProbe",
            "XrayAPIInfo",
            "ClashAPIProbe",
            "ClashAPIResult",
            "mihomo",
            "sing-box Clash API",
        )
        private val LOCAL_PROXY_SCAN_EXACT_TERMS = setOf(
            "/connections",
            "/proxies",
            "/configs",
        )
        private val LOCAL_PROXY_ACTIVE_CONTEXT_TERMS = listOf(
            "connect",
            "socket",
            "probe",
            "scan",
            "port",
            "outbound",
            "bind",
            "listen",
        )
        private val NATIVE_TUNNEL_CONTEXT_TERMS = listOf(
            "NetworkInterface",
            "/sys/class/net",
            "/proc/net",
            "/proc/self/net",
            "getifaddrs",
            "if_nametoindex",
            "SIOCGIF",
            "interface",
            "route",
        )
        private val SENSITIVE_PACKAGE_NAME_TERMS = listOf(
            "vpn",
            "proxy",
            "socks",
            "clash",
            "xray",
            "v2ray",
            "shadowsocks",
            "wireguard",
            "openvpn",
            "outline",
            "root",
            "magisk",
            "superuser",
            "tor",
            "orbot",
        )
        private val VPN_LAUNCH_METHODS = setOf(
            "startActivity",
            "startActivityForResult",
            "startService",
            "startForegroundService",
            "bindService",
        )
        private val SPLIT_TUNNEL_BUILDER_METHODS = setOf(
            "addAllowedApplication",
            "addDisallowedApplication",
            "allowFamily",
        )
        private val SELF_PROXY_BUILDER_METHODS = setOf(
            "proxy",
            "proxySelector",
        )
        private val NATIVE_LIBRARY_LOAD_METHODS = setOf(
            "load",
            "loadLibrary",
        )
        private val PROXY_INFO_INSPECTION_METHODS = setOf(
            "getHost",
            "getPort",
            "getPacFileUrl",
            "getExclusionList",
            "isValid",
        )
        private val PROXY_INFO_VALUE_METHODS = setOf(
            "getHost",
            "getPort",
            "getPacFileUrl",
            "getExclusionList",
        )
        private val ANDROID_PROXY_INSPECTION_METHODS = setOf(
            "getDefaultHost",
            "getDefaultPort",
        )
        private val ANDROID_PROXY_VALUE_METHODS = setOf(
            "getDefaultHost",
            "getDefaultPort",
        )
        private val BROAD_PACKAGE_INVENTORY_METHODS = setOf(
            "getInstalledPackages",
            "getInstalledApplications",
        )
        private val PACKAGE_NAME_QUERY_METHODS = setOf(
            "getPackageInfo",
            "getApplicationInfo",
            "getLaunchIntentForPackage",
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
            "addProperty",
            "toJson",
            "encodeToString",
            "writeString",
        )
        private val SERIALIZATION_CLASSES = setOf(
            "org.json.JSONObject",
            "org.json.JSONArray",
            "android.os.Bundle",
            "com.google.gson.JsonObject",
            "com.google.gson.Gson",
        )
        private val MAP_LIKE_CLASSES = setOf(
            "java.util.Map",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "kotlin.collections.Map",
            "kotlin.collections.MutableMap",
        )
        private val MAP_LIKE_WRITE_METHODS = setOf(
            "put",
            "putAll",
            "set",
        )
        private val EXTENDED_SERIALIZATION_CLASSES = setOf(
            "android.util.Base64",
            "okhttp3.RequestBody",
            "okio.BufferedSink",
            "java.io.OutputStream",
            "java.util.zip.GZIPOutputStream",
            "kotlinx.serialization.json.Json",
        )
        private val EXTENDED_SERIALIZATION_CLASS_TERMS = listOf(
            "serializer",
            "serialization",
            "protobuf",
            "proto",
            "encoder",
            "writer",
            "requestbody",
            "formbody",
        )
        private val EXTENDED_SERIALIZATION_METHODS = setOf(
            "encodeToString",
            "encodeToByteArray",
            "serialize",
            "serializer",
            "write",
            "writeTo",
            "writeBoolean",
            "writeInt",
            "writeInt32",
            "writeString",
            "writeBytes",
            "create",
            "toRequestBody",
            "body",
            "field",
            "addEncoded",
        )
        private val BINARY_WRITER_METHOD_NAMES = setOf(
            "write",
            "writeBool",
            "writeBoolean",
            "writeInt",
            "writeInt32",
            "writeUInt32",
            "writeSInt32",
            "writeString",
            "writeBytes",
        )
        private val HTTP_HEADER_CLASS_TERMS = listOf(
            "Request.Builder",
            "Headers.Builder",
            "HttpURLConnection",
            "RequestHeader",
            "Headers",
            "Interceptor",
        )
        private val HTTP_HEADER_METHOD_NAMES = setOf(
            "header",
            "addHeader",
            "removeHeader",
            "setHeader",
            "setRequestProperty",
            "addRequestProperty",
            "add",
            "set",
        )
        private val VALUE_PRESERVING_BOOLEAN_METHODS = setOf(
            "valueOf",
            "booleanValue",
            "equals",
        )
        private val VALUE_PRESERVING_METHOD_NAMES = setOf(
            "copy",
            "component1",
            "component2",
            "component3",
            "component4",
            "component5",
            "component6",
            "getValue",
            "getFirst",
            "getSecond",
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
        private val TELEMETRY_ACCESSOR_METHOD_NAMES = setOf(
            "toString",
            "hashCode",
            "equals",
            "copy",
            "describeContents",
            "writeToParcel",
        )
        private val TELEMETRY_CLASS_NAMES = setOf(
            "com.yandex.metrica.YandexMetrica",
            "com.google.firebase.analytics.FirebaseAnalytics",
        )
        private val TELEMETRY_CLASS_TERMS = listOf(
            ".analytics.",
            ".telemetry.",
        )
        private val TRACKER_SDK_CLASS_PREFIXES = listOf(
            "com.facebook.",
            "com.meta.",
            "com.instagram.",
            "com.my.tracker.",
            "com.yandex.metrica.",
            "io.appmetrica.",
        )
        private val TRACKER_CONTEXT_TERMS = listOf(
            "my.tracker",
            "mytracker",
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
            "livekit.org.webrtc.",
        )
        private val KNOWN_SDK_CLASS_PREFIXES = listOf(
            "com.appsflyer.",
            "com.facebook.",
            "com.google.android.gms.",
            "com.google.android.datatransport.",
            "com.google.firebase.",
            "com.google.android.recaptcha.",
            "com.huawei.hms.",
            "com.meta.",
            "com.yandex.metrica.",
            "io.appmetrica.",
            "io.sentry.",
            "org.webrtc.",
            "livekit.org.webrtc.",
            "ru.rtln.tds.sdk.",
        )
        private val GENERIC_NETWORK_STATE_SDK_TERMS = listOf(
            "NetworkBreadcrumbsIntegration",
            "ConnectivityReceiver",
            "NetworkCallbackConnectivityReceiver",
            "NetworkMonitorAutoDetect",
            "ConnectivityUtility",
            "AndroidNetworkLibrary",
        )
        private val GENERIC_NATIVE_PROTOCOL_LIBRARY_TERMS = listOf(
            "webrtc",
            "jingle",
            "livekit",
            "chromium",
            "cronet",
            "curl",
            "boringssl",
            "openssl",
            "conscrypt",
            "grpc",
            "okhttp",
            "ffmpeg",
            "avcodec",
            "avformat",
            "avutil",
            "exo",
            "sqlcipher",
            "mapkit",
            "maps-mobile",
            "yandexmaps",
            "yandex.map",
            "dgis",
            "2gis",
            "zoom",
            "discord",
            "telegram",
            "sentry",
        )
        private val ENTERPRISE_PACKAGE_MANAGEMENT_TERMS = listOf(
            "intune",
            "mam",
            "mdm",
            "enterprise",
            "managedprofile",
            "packageManagerCompat",
            "OfflinePackageManagementBehavior",
        )
        private val INTEGRATION_PACKAGE_PREFIXES = listOf(
            "android.",
            "androidx.",
            "com.android.",
            "com.google.android.",
            "com.google.firebase.",
            "com.google.android.gms.",
            "com.huawei.",
            "com.miui.",
            "com.xiaomi.",
            "com.facebook.",
            "com.meta.",
            "io.sentry.",
        )
        private val INTEGRATION_PACKAGE_NAMES = setOf(
            "com.facebook.katana",
            "com.google.firebase.messaging",
            "com.huawei.hwid",
            "com.huawei.works",
            "com.huawei.android.pushagent",
            "com.miui.home",
            "com.huawei.android.launcher",
        )
        private val DIAGNOSTIC_ONLY_SIGNAL_TITLES = setOf(
            "VPN transport state check",
            "tunnel interface inspection",
            "MTU VPN heuristic",
            "proc socket table inspection",
            "system proxy inspection",
            "DNS server inspection",
            "route table inspection",
            "active VPN dumpsys probe",
            "single package integration check",
            "hardcoded package enumeration diagnostic",
            "native VPN/proxy diagnostic strings",
            "native bridge with unresolved native indicator",
        )
        private val GENERIC_INFRASTRUCTURE_SIGNAL_TITLES = setOf(
            "generic native SDK protocol support",
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
        private val ROUTE_INSPECTION_METHODS = setOf(
            "getRoutes",
            "getLinkProperties",
            "getInterfaceName",
        )
        private val NETWORK_BINDING_METHODS = setOf(
            "bindSocket",
            "getSocketFactory",
        )
        private val DEVICE_IDENTIFIER_TEXT_TERMS = setOf(
            "android_id",
            "ANDROID_ID",
            "advertising_id",
            "adid",
            "gaid",
            "app_set_id",
            "appsetid",
            "firebase_installation_id",
            "installation_id",
            "device_id",
            "imei",
            "meid",
            "imsi",
            "iccid",
        )
        private val NETWORK_FINGERPRINT_TEXT_TERMS = setOf(
            "ssid",
            "bssid",
            "wifiSecurity",
            "networkOperator",
            "networkCountry",
            "simOperator",
            "networkType",
            "subtype",
            "roaming",
            "extraInfo",
            "networksCount",
        )
        private val BUILD_IDENTIFIER_FIELDS = setOf(
            "SERIAL",
            "FINGERPRINT",
            "MODEL",
            "MANUFACTURER",
            "BRAND",
            "DEVICE",
            "PRODUCT",
            "HARDWARE",
            "BOARD",
        )
        private val TELEPHONY_IDENTIFIER_METHODS = setOf(
            "getDeviceId",
            "getImei",
            "getMeid",
            "getSubscriberId",
            "getSimSerialNumber",
            "getLine1Number",
        )
        private val TELEPHONY_NETWORK_FINGERPRINT_METHODS = setOf(
            "getNetworkOperator",
            "getNetworkOperatorName",
            "getNetworkCountryIso",
            "getSimOperator",
            "getSimOperatorName",
            "getSimCountryIso",
            "getDataState",
            "getDataActivity",
            "isNetworkRoaming",
        )
        private val WIFI_FINGERPRINT_METHODS = setOf(
            "getConnectionInfo",
            "getScanResults",
            "getSSID",
            "getBSSID",
            "getMacAddress",
            "getIpAddress",
            "getLinkSpeed",
            "getNetworkId",
            "isP2pSupported",
            "isScanAlwaysAvailable",
        )
        private val NETWORK_INFO_FINGERPRINT_METHODS = setOf(
            "getExtraInfo",
            "getSubtypeName",
            "getTypeName",
            "getType",
            "getSubtype",
            "isRoaming",
        )
        private val NATIVE_BRIDGE_JAVA_CONTEXT_FACTS = setOf(
            SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE,
            SemanticFactKind.BROAD_PACKAGE_INVENTORY,
            SemanticFactKind.PACKAGE_NAME_LIST_CHECK,
            SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE,
            SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK,
            SemanticFactKind.VPN_RESULT_COLLECTION,
            SemanticFactKind.TELEMETRY_PREPARATION,
            SemanticFactKind.TELEMETRY_OR_NETWORK_SINK,
            SemanticFactKind.VPN_TELEMETRY_LABEL,
            SemanticFactKind.VPN_DATA_METHOD_RETURN,
            SemanticFactKind.VPN_DATA_CARRIER_FIELD,
            SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW,
            SemanticFactKind.VPN_DATA_NETWORK_FLOW,
            SemanticFactKind.VPN_DATA_HTTP_HEADER_FLOW,
            SemanticFactKind.SYSTEM_PROXY_INSPECTION,
            SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE,
            SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT,
            SemanticFactKind.PUBLIC_IP_PROBE,
            SemanticFactKind.PUBLIC_IP_NETWORK_FLOW,
            SemanticFactKind.NETWORK_BYPASS_BINDING,
            SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION,
            SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK,
            SemanticFactKind.TUNNEL_INTERFACE_PROBE,
            SemanticFactKind.MTU_PROBE,
            SemanticFactKind.PROC_SOCKET_TABLE,
            SemanticFactKind.DNS_SERVER_INSPECTION,
            SemanticFactKind.ROUTE_TABLE_INSPECTION,
            SemanticFactKind.ACTIVE_VPN_DUMPSYS,
        )
        private val NATIVE_BRIDGE_NATIVE_FACTS = setOf(
            SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE,
            SemanticFactKind.SYSTEM_PROXY_INSPECTION,
            SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE,
            SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT,
            SemanticFactKind.PUBLIC_IP_PROBE,
            SemanticFactKind.NETWORK_BYPASS_BINDING,
            SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK,
            SemanticFactKind.TUNNEL_INTERFACE_API,
            SemanticFactKind.TUNNEL_INTERFACE_PROBE,
            SemanticFactKind.MTU_PROBE,
            SemanticFactKind.PROC_SOCKET_TABLE,
            SemanticFactKind.DNS_SERVER_INSPECTION,
            SemanticFactKind.ROUTE_TABLE_INSPECTION,
            SemanticFactKind.ACTIVE_VPN_DUMPSYS,
        )
        private val CROSS_LAYER_APP_FACTS = setOf(
            SemanticFactKind.VPN_CLIENT_CONTROL_CONTEXT,
            SemanticFactKind.SPLIT_TUNNEL_APP_SELECTION,
            SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE,
            SemanticFactKind.BROAD_PACKAGE_INVENTORY,
            SemanticFactKind.PACKAGE_NAME_LIST_CHECK,
            SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE,
            SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK,
            SemanticFactKind.VPN_RESULT_COLLECTION,
            SemanticFactKind.TELEMETRY_PREPARATION,
            SemanticFactKind.VPN_TELEMETRY_LABEL,
            SemanticFactKind.VPN_DATA_METHOD_RETURN,
            SemanticFactKind.VPN_DATA_CARRIER_FIELD,
            SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW,
            SemanticFactKind.VPN_DATA_HTTP_HEADER_FLOW,
            SemanticFactKind.VPN_DATA_SDK_HANDOFF,
            SemanticFactKind.NATIVE_METHOD_DECLARATION,
            SemanticFactKind.NATIVE_LIBRARY_LOAD,
            SemanticFactKind.SYSTEM_PROXY_INSPECTION,
            SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE,
            SemanticFactKind.LOCAL_PROXY_SELF_USE_CONTEXT,
            SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT,
            SemanticFactKind.PUBLIC_IP_PROBE,
            SemanticFactKind.PUBLIC_IP_NETWORK_FLOW,
            SemanticFactKind.NETWORK_BYPASS_BINDING,
            SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION,
            SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK,
            SemanticFactKind.TUNNEL_INTERFACE_PROBE,
            SemanticFactKind.MTU_PROBE,
            SemanticFactKind.PROC_SOCKET_TABLE,
            SemanticFactKind.DNS_SERVER_INSPECTION,
            SemanticFactKind.ROUTE_TABLE_INSPECTION,
            SemanticFactKind.ACTIVE_VPN_DUMPSYS,
        )
        private val CROSS_LAYER_SDK_FACTS = setOf(
            SemanticFactKind.TELEMETRY_PREPARATION,
            SemanticFactKind.TELEMETRY_OR_NETWORK_SINK,
            SemanticFactKind.VPN_TELEMETRY_LABEL,
            SemanticFactKind.VPN_DATA_METHOD_RETURN,
            SemanticFactKind.VPN_DATA_CARRIER_FIELD,
            SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW,
            SemanticFactKind.VPN_DATA_NETWORK_FLOW,
            SemanticFactKind.VPN_DATA_HTTP_HEADER_FLOW,
            SemanticFactKind.NETWORK_LIBRARY_CALL,
            SemanticFactKind.SYSTEM_PROXY_INSPECTION,
            SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE,
            SemanticFactKind.LOCAL_PROXY_SELF_USE_CONTEXT,
            SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT,
            SemanticFactKind.PUBLIC_IP_PROBE,
            SemanticFactKind.PUBLIC_IP_NETWORK_FLOW,
            SemanticFactKind.NETWORK_BYPASS_BINDING,
            SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION,
            SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK,
            SemanticFactKind.TUNNEL_INTERFACE_PROBE,
            SemanticFactKind.MTU_PROBE,
            SemanticFactKind.PROC_SOCKET_TABLE,
            SemanticFactKind.DNS_SERVER_INSPECTION,
            SemanticFactKind.ROUTE_TABLE_INSPECTION,
            SemanticFactKind.ACTIVE_VPN_DUMPSYS,
        )
        private val CALL_GRAPH_COORDINATOR_FACTS = setOf(
            SemanticFactKind.VPN_CLIENT_CONTROL_CONTEXT,
            SemanticFactKind.SPLIT_TUNNEL_APP_SELECTION,
            SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE,
            SemanticFactKind.PACKAGE_NAME_LIST_CHECK,
            SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK,
            SemanticFactKind.VPN_RESULT_COLLECTION,
            SemanticFactKind.TELEMETRY_PREPARATION,
            SemanticFactKind.TELEMETRY_OR_NETWORK_SINK,
            SemanticFactKind.VPN_TELEMETRY_LABEL,
            SemanticFactKind.VPN_DATA_METHOD_RETURN,
            SemanticFactKind.VPN_DATA_CARRIER_FIELD,
            SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW,
            SemanticFactKind.VPN_DATA_HTTP_HEADER_FLOW,
            SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK,
            SemanticFactKind.SYSTEM_PROXY_INSPECTION,
            SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE,
            SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT,
            SemanticFactKind.PUBLIC_IP_PROBE,
            SemanticFactKind.NETWORK_BYPASS_BINDING,
        )
        private val METHODOLOGY_PATTERN_FACTS = setOf(
            SemanticFactKind.PACKAGE_QUERY_VPN_SERVICE,
            SemanticFactKind.BROAD_PACKAGE_INVENTORY,
            SemanticFactKind.SINGLE_PACKAGE_INTEGRATION_CHECK,
            SemanticFactKind.PACKAGE_NAME_LIST_CHECK,
            SemanticFactKind.KNOWN_VPN_PACKAGE_REFERENCE,
            SemanticFactKind.KNOWN_VPN_PACKAGE_CHECK,
            SemanticFactKind.VPN_RESULT_COLLECTION,
            SemanticFactKind.TELEMETRY_PREPARATION,
            SemanticFactKind.TELEMETRY_OR_NETWORK_SINK,
            SemanticFactKind.VPN_TELEMETRY_LABEL,
            SemanticFactKind.VPN_DATA_METHOD_RETURN,
            SemanticFactKind.VPN_DATA_CARRIER_FIELD,
            SemanticFactKind.VPN_DATA_SERIALIZATION_FLOW,
            SemanticFactKind.VPN_DATA_NETWORK_FLOW,
            SemanticFactKind.VPN_DATA_HTTP_HEADER_FLOW,
            SemanticFactKind.PUBLIC_IP_PROBE,
            SemanticFactKind.PUBLIC_IP_NETWORK_FLOW,
            SemanticFactKind.NETWORK_BYPASS_BINDING,
            SemanticFactKind.UNDERLYING_NETWORK_ENUMERATION,
            SemanticFactKind.NETWORK_CAPABILITIES_VPN_CHECK,
            SemanticFactKind.TUNNEL_INTERFACE_PROBE,
            SemanticFactKind.PROC_SOCKET_TABLE,
            SemanticFactKind.SYSTEM_PROXY_INSPECTION,
            SemanticFactKind.SOCKS_OR_LOCAL_PROXY_PROBE,
            SemanticFactKind.LOCAL_PROXY_SCAN_CONTEXT,
            SemanticFactKind.DNS_SERVER_INSPECTION,
            SemanticFactKind.ROUTE_TABLE_INSPECTION,
            SemanticFactKind.ACTIVE_VPN_DUMPSYS,
            SemanticFactKind.DEVICE_IDENTIFIER_COLLECTION,
            SemanticFactKind.NETWORK_FINGERPRINT_COLLECTION,
            SemanticFactKind.USAGE_STATS_COLLECTION,
            SemanticFactKind.CONDITIONAL_BRANCH,
        )
        private val CALL_GRAPH_COORDINATOR_METHOD_TERMS = listOf(
            "vpn",
            "proxy",
            "scan",
            "detect",
            "check",
            "probe",
            "collect",
            "report",
            "telemetry",
            "risk",
            "security",
            "inspect",
            "verify",
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
            "io.ktor.",
            "com.google.gson.",
            "com.google.protobuf.",
            "com.google.common.",
            "com.squareup.",
            "org.intellij.",
            "org.jetbrains.",
        )
    }
}
