package com.sahidcode404.camx.core.imaging.graph

import com.sahidcode404.camx.core.camera.acquisition.ManifestSourceId
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

internal enum class ReferenceAlgorithmKind {
    EXACT_COPY,
    EXACT_FORK,
    CALIBRATION_GATE,
}

internal data class ReferenceNodeContract(
    val kind: ReferenceAlgorithmKind,
    val algorithmId: AlgorithmId,
    val algorithmVersion: Int,
    val parameterSchemaVersion: Int,
    val minInputs: Int,
    val maxInputs: Int,
    val minOutputs: Int,
    val maxOutputs: Int,
    val minimumCalibrationConfidence: Double?,
    val determinismClass: DeterminismClass,
    val supportedBackends: Set<GraphBackend>,
    val latencyClass: GraphLatencyClass,
    val mutationFlags: MutationFlags,
)

object M3ReferenceAlgorithms {
    val EXACT_COPY = AlgorithmId("reference.exact-copy")
    val EXACT_FORK = AlgorithmId("reference.exact-fork")
    val CALIBRATION_GATE = AlgorithmId("reference.calibration-gate")
    const val VERSION = 1
    const val PARAMETER_SCHEMA_VERSION = 1
    const val CALIBRATION_GATE_MIN_CONFIDENCE = 0.5
    const val NO_RANDOMNESS_POLICY = "NO_RANDOMNESS_ALLOWED_IN_M3_REFERENCE_PLAN"
}

internal object ReferenceNodeCatalog {
    private val noMutation = MutationFlags(
        changesSamples = false,
        changesGeometry = false,
        changesRepresentation = false,
        changesProvenance = false,
    )

    private val contracts = listOf(
        ReferenceNodeContract(
            kind = ReferenceAlgorithmKind.EXACT_COPY,
            algorithmId = M3ReferenceAlgorithms.EXACT_COPY,
            algorithmVersion = M3ReferenceAlgorithms.VERSION,
            parameterSchemaVersion = M3ReferenceAlgorithms.PARAMETER_SCHEMA_VERSION,
            minInputs = 1,
            maxInputs = 1,
            minOutputs = 1,
            maxOutputs = 1,
            minimumCalibrationConfidence = null,
            determinismClass = DeterminismClass.BIT_EXACT,
            supportedBackends = setOf(GraphBackend.SCALAR_REFERENCE),
            latencyClass = GraphLatencyClass.DEFERRED_REFERENCE,
            mutationFlags = noMutation,
        ),
        ReferenceNodeContract(
            kind = ReferenceAlgorithmKind.EXACT_FORK,
            algorithmId = M3ReferenceAlgorithms.EXACT_FORK,
            algorithmVersion = M3ReferenceAlgorithms.VERSION,
            parameterSchemaVersion = M3ReferenceAlgorithms.PARAMETER_SCHEMA_VERSION,
            minInputs = 1,
            maxInputs = 1,
            minOutputs = 2,
            maxOutputs = M3GraphLimits.MAX_NODE_OUTPUTS,
            minimumCalibrationConfidence = null,
            determinismClass = DeterminismClass.BIT_EXACT,
            supportedBackends = setOf(GraphBackend.SCALAR_REFERENCE),
            latencyClass = GraphLatencyClass.DEFERRED_REFERENCE,
            mutationFlags = noMutation,
        ),
        ReferenceNodeContract(
            kind = ReferenceAlgorithmKind.CALIBRATION_GATE,
            algorithmId = M3ReferenceAlgorithms.CALIBRATION_GATE,
            algorithmVersion = M3ReferenceAlgorithms.VERSION,
            parameterSchemaVersion = M3ReferenceAlgorithms.PARAMETER_SCHEMA_VERSION,
            minInputs = 1,
            maxInputs = 1,
            minOutputs = 1,
            maxOutputs = 1,
            minimumCalibrationConfidence = M3ReferenceAlgorithms.CALIBRATION_GATE_MIN_CONFIDENCE,
            determinismClass = DeterminismClass.BIT_EXACT,
            supportedBackends = setOf(GraphBackend.SCALAR_REFERENCE),
            latencyClass = GraphLatencyClass.DEFERRED_REFERENCE,
            mutationFlags = noMutation,
        ),
    ).associateBy { it.algorithmId to it.algorithmVersion }

    fun resolve(algorithmId: AlgorithmId, algorithmVersion: Int): ReferenceNodeContract =
        contracts[algorithmId to algorithmVersion]
            ?: throw GraphCompilationException(
                GraphCompileFailureReason.UNKNOWN_ALGORITHM,
                "No M3 reference semantics exist for ${algorithmId.value}@$algorithmVersion",
            )

    fun validate(
        contract: ReferenceNodeContract,
        invocation: GraphNodeInvocation,
        inputs: List<GraphValue>,
        outputs: List<GraphValue>,
    ) {
        if (invocation.parameterSchemaVersion != contract.parameterSchemaVersion) {
            throw GraphCompilationException(
                GraphCompileFailureReason.MALFORMED_METADATA,
                "Node ${invocation.id.value} uses an unsupported parameter schema",
            )
        }
        if (invocation.parameters.size > M3GraphLimits.MAX_PARAMETERS_PER_NODE ||
            invocation.parameters.map(NodeParameter::key).distinct().size != invocation.parameters.size
        ) {
            throw GraphCompilationException(
                GraphCompileFailureReason.MALFORMED_METADATA,
                "Node ${invocation.id.value} contains duplicate or excessive parameters",
            )
        }
        if (invocation.parameters.isNotEmpty()) {
            throw GraphCompilationException(
                GraphCompileFailureReason.MALFORMED_METADATA,
                "M3 reference algorithms do not accept free-form parameters",
            )
        }
        if (inputs.size !in contract.minInputs..contract.maxInputs ||
            outputs.size !in contract.minOutputs..contract.maxOutputs
        ) {
            throw GraphCompilationException(
                GraphCompileFailureReason.TYPE_MISMATCH,
                "Node ${invocation.id.value} has an illegal arity",
            )
        }
        val input = inputs.single()
        outputs.forEach { output ->
            if (output.type != input.type || output.canonicalBytes != input.canonicalBytes) {
                throw GraphCompilationException(
                    GraphCompileFailureReason.TYPE_MISMATCH,
                    "M3 exact reference nodes require byte- and type-identical outputs",
                )
            }
            if (output.memoryDomain != GraphMemoryDomain.HOST_JVM) {
                throw GraphCompilationException(
                    GraphCompileFailureReason.UNSUPPORTED_BACKEND,
                    "M3 reference outputs must remain in HOST_JVM memory",
                )
            }
        }
        contract.minimumCalibrationConfidence?.let { minimum ->
            val calibration = input.type.calibration
            if (calibration.identity == null || calibration.confidence < minimum) {
                throw GraphCompilationException(
                    GraphCompileFailureReason.CALIBRATION_INSUFFICIENT,
                    "Node ${invocation.id.value} requires identified calibration confidence >= $minimum",
                )
            }
        }
        if (GraphBackend.SCALAR_REFERENCE !in contract.supportedBackends) {
            throw GraphCompilationException(
                GraphCompileFailureReason.UNSUPPORTED_BACKEND,
                "M3 requires a scalar reference backend",
            )
        }
    }

    fun workspaceBytes(
        contract: ReferenceNodeContract,
        inputs: List<GraphValue>,
        outputs: List<GraphValue>,
    ): Long {
        require(inputs.isNotEmpty() && outputs.isNotEmpty())
        return 0L
    }
}

class ReferenceExecutionOutput internal constructor(
    val valueId: GraphValueId,
    val sha256: String,
    bytes: ByteArray,
) {
    private val frozenBytes = bytes.copyOf()

    val byteCount: Long
        get() = frozenBytes.size.toLong()

    fun bytes(): ByteArray = frozenBytes.copyOf()
}

class ReferenceExecutionResult internal constructor(outputs: List<ReferenceExecutionOutput>) {
    val outputs: List<ReferenceExecutionOutput> = Collections.unmodifiableList(
        ArrayList(outputs.sortedBy { it.valueId.value }),
    )
}

/**
 * Executes only the M3 scalar reference algorithms already selected by [GraphCompiler]. It never owns
 * camera resources and never changes source samples, geometry, representation, or provenance.
 */
object ReferenceGraphExecutor {
    fun execute(
        plan: CompiledGraphPlan,
        sourcePayloads: Map<ManifestSourceId, ByteArray>,
    ): ReferenceExecutionResult {
        val valuesById = plan.values.associateBy(GraphValue::id)
        val live = LinkedHashMap<GraphValueId, ByteArray>()

        plan.values.filter { it.sourceBinding != null }.forEach { value ->
            val binding = requireNotNull(value.sourceBinding)
            val source = sourcePayloads[binding.sourceId]
                ?: throw IllegalArgumentException("Missing source payload ${binding.sourceId.value}")
            require(source.size.toLong() == value.canonicalBytes) {
                "Source payload byte count does not match the compiled graph"
            }
            require(sha256(source) == binding.canonicalRasterSha256) {
                "Source payload digest does not match immutable corpus evidence"
            }
            live[value.id] = source.copyOf()
        }

        plan.steps.forEach { step ->
            val contract = ReferenceNodeCatalog.resolve(step.algorithmId, step.algorithmVersion)
            val input = live[step.inputs.single()]
                ?: throw IllegalStateException("Compiled input ${step.inputs.single().value} is not live")
            when (contract.kind) {
                ReferenceAlgorithmKind.EXACT_COPY,
                ReferenceAlgorithmKind.CALIBRATION_GATE,
                -> {
                    val outputId = step.outputs.single()
                    live[outputId] = exactCopy(input, valuesById.getValue(outputId).canonicalBytes)
                }

                ReferenceAlgorithmKind.EXACT_FORK -> {
                    step.outputs.forEach { outputId ->
                        live[outputId] = exactCopy(input, valuesById.getValue(outputId).canonicalBytes)
                    }
                }
            }
            step.releaseAfter.forEach(live::remove)
        }

        val outputs = plan.finalOutputs.map { outputId ->
            val bytes = live[outputId]
                ?: throw IllegalStateException("Compiled final output ${outputId.value} is not live")
            ReferenceExecutionOutput(
                valueId = outputId,
                sha256 = sha256(bytes),
                bytes = bytes,
            )
        }
        return ReferenceExecutionResult(outputs)
    }

    private fun exactCopy(input: ByteArray, expectedBytes: Long): ByteArray {
        require(expectedBytes == input.size.toLong()) { "Reference copy extent mismatch" }
        val output = ByteArray(input.size)
        var offset = 0
        while (offset < input.size) {
            val count = minOf(64 * 1024, input.size - offset)
            System.arraycopy(input, offset, output, offset, count)
            offset += count
        }
        return output
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
