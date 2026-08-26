package com.sahidcode404.camx.core.imaging.graph

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

class GraphExecutionStep internal constructor(
    val nodeId: GraphNodeId,
    val algorithmId: AlgorithmId,
    val algorithmVersion: Int,
    val backend: GraphBackend,
    inputs: List<GraphValueId>,
    outputs: List<GraphValueId>,
    val workspaceBytes: Long,
    releaseAfter: List<GraphValueId>,
) {
    val inputs: List<GraphValueId> = immutableList(inputs)
    val outputs: List<GraphValueId> = immutableList(outputs)
    val releaseAfter: List<GraphValueId> = immutableList(releaseAfter.sortedBy { it.value })
}

class ResourceStepProof internal constructor(
    val nodeId: GraphNodeId,
    val liveBeforeBytes: Long,
    val workspaceBytes: Long,
    val outputAllocationBytes: Long,
    val peakDuringNodeBytes: Long,
    val liveAfterReleaseBytes: Long,
)

class ResourceProof internal constructor(
    val sourceBytes: Long,
    val finalOutputBytes: Long,
    val peakLiveAndOutputBytes: Long,
    val peakWorkspaceBytes: Long,
    val safetyMarginBytes: Long,
    val reservedBytes: Long,
    steps: List<ResourceStepProof>,
) {
    val steps: List<ResourceStepProof> = immutableList(steps)
}

data class AlgorithmManifestEntry(
    val nodeId: GraphNodeId,
    val algorithmId: AlgorithmId,
    val algorithmVersion: Int,
    val parameterSchemaVersion: Int,
    val backend: GraphBackend,
    val determinismClass: DeterminismClass,
    val latencyClass: GraphLatencyClass,
    val mutationFlags: MutationFlags,
)

class DeterminismPlan internal constructor(
    val graphSha256: GraphSha256,
    val planSha256: GraphSha256,
    val randomnessPolicy: String,
    algorithms: List<AlgorithmManifestEntry>,
) {
    val algorithms: List<AlgorithmManifestEntry> = immutableList(algorithms.sortedBy { it.nodeId.value })
}

class M3ProcessingManifestPlan internal constructor(
    sourceBindings: List<GraphSourceBinding>,
    finalOutputs: List<GraphValueId>,
    val graphSha256: GraphSha256,
    val planSha256: GraphSha256,
    val changesSamples: Boolean,
    val changesGeometry: Boolean,
    val changesRepresentation: Boolean,
    val changesProvenance: Boolean,
) {
    val sourceBindings: List<GraphSourceBinding> = immutableList(sourceBindings.sortedBy { it.sourceId.value })
    val finalOutputs: List<GraphValueId> = immutableList(finalOutputs.sortedBy { it.value })
}

class CompiledGraphPlan internal constructor(
    values: List<GraphValue>,
    steps: List<GraphExecutionStep>,
    finalOutputs: List<GraphValueId>,
    val resourceProof: ResourceProof,
    val determinismPlan: DeterminismPlan,
    val manifestPlan: M3ProcessingManifestPlan,
) {
    val values: List<GraphValue> = immutableList(values.sortedBy { it.id.value })
    val steps: List<GraphExecutionStep> = immutableList(steps)
    val finalOutputs: List<GraphValueId> = immutableList(finalOutputs.sortedBy { it.value })
}

object GraphCompiler {
    fun compile(
        definition: GraphDefinition,
        budget: GraphResourceBudget,
    ): CompiledGraphPlan {
        validateLimits(definition, budget)

        val valuesById = uniqueValues(definition.values)
        val nodesById = uniqueNodes(definition.nodes)
        val finalOutputs = validateFinalOutputs(definition.finalOutputs, valuesById)
        validateValueLifetimes(valuesById, finalOutputs)

        val producerByValue = LinkedHashMap<GraphValueId, GraphNodeId>()
        val consumersByValue = LinkedHashMap<GraphValueId, MutableList<GraphNodeId>>()
        valuesById.keys.forEach { consumersByValue[it] = ArrayList() }

        nodesById.values.forEach { node ->
            validateNodeShape(node)
            node.outputs.forEach { outputId ->
                val output = valuesById[outputId] ?: compilationFailure(
                    GraphCompileFailureReason.MISSING_VALUE,
                    "Node ${node.id.value} references missing output ${outputId.value}",
                )
                if (output.sourceBinding != null) {
                    compilationFailure(
                        GraphCompileFailureReason.SOURCE_REWRITE,
                        "Node ${node.id.value} cannot produce an immutable source value",
                    )
                }
                if (producerByValue.put(outputId, node.id) != null) {
                    compilationFailure(
                        GraphCompileFailureReason.DUPLICATE_PRODUCER,
                        "Graph value ${outputId.value} has more than one producer",
                    )
                }
            }
            node.inputs.forEach { inputId ->
                if (inputId !in valuesById) {
                    compilationFailure(
                        GraphCompileFailureReason.MISSING_VALUE,
                        "Node ${node.id.value} references missing input ${inputId.value}",
                    )
                }
                consumersByValue.getValue(inputId).add(node.id)
            }
        }

        valuesById.values.forEach { value ->
            if (value.sourceBinding == null && value.id !in producerByValue) {
                compilationFailure(
                    GraphCompileFailureReason.UNPRODUCED_VALUE,
                    "Non-source graph value ${value.id.value} has no producer",
                )
            }
            if (value.sourceBinding != null && value.id in producerByValue) {
                compilationFailure(
                    GraphCompileFailureReason.SOURCE_REWRITE,
                    "Source graph value ${value.id.value} cannot have a producer",
                )
            }
            if (consumersByValue.getValue(value.id).isEmpty() && value.id !in finalOutputs) {
                compilationFailure(
                    GraphCompileFailureReason.DANGLING_VALUE,
                    "Graph value ${value.id.value} is neither consumed nor a final output",
                )
            }
        }

        val orderedNodes = topologicalOrder(nodesById, producerByValue)
        val contractsByNode = LinkedHashMap<GraphNodeId, ReferenceNodeContract>()
        orderedNodes.forEach { node ->
            val contract = ReferenceNodeCatalog.resolve(node.algorithmId, node.algorithmVersion)
            ReferenceNodeCatalog.validate(
                contract,
                node,
                node.inputs.map(valuesById::getValue),
                node.outputs.map(valuesById::getValue),
            )
            contractsByNode[node.id] = contract
        }

        val resourceCompilation = compileResourcePlan(
            orderedNodes = orderedNodes,
            contractsByNode = contractsByNode,
            valuesById = valuesById,
            consumersByValue = consumersByValue,
            finalOutputs = finalOutputs,
            budget = budget,
        )

        val graphHash = GraphCanonicalHasher.graphHash(
            values = valuesById.values.toList(),
            nodes = nodesById.values.toList(),
            finalOutputs = finalOutputs,
        )
        val planHash = GraphCanonicalHasher.planHash(
            graphHash = graphHash,
            steps = resourceCompilation.steps,
            proof = resourceCompilation.proof,
            budget = budget,
        )

        val algorithmManifest = orderedNodes.map { node ->
            val contract = contractsByNode.getValue(node.id)
            AlgorithmManifestEntry(
                nodeId = node.id,
                algorithmId = contract.algorithmId,
                algorithmVersion = contract.algorithmVersion,
                parameterSchemaVersion = contract.parameterSchemaVersion,
                backend = GraphBackend.SCALAR_REFERENCE,
                determinismClass = contract.determinismClass,
                latencyClass = contract.latencyClass,
                mutationFlags = contract.mutationFlags,
            )
        }
        val mutationSummary = algorithmManifest.fold(
            MutationFlags(false, false, false, false),
        ) { accumulated, entry ->
            MutationFlags(
                changesSamples = accumulated.changesSamples || entry.mutationFlags.changesSamples,
                changesGeometry = accumulated.changesGeometry || entry.mutationFlags.changesGeometry,
                changesRepresentation = accumulated.changesRepresentation || entry.mutationFlags.changesRepresentation,
                changesProvenance = accumulated.changesProvenance || entry.mutationFlags.changesProvenance,
            )
        }

        return CompiledGraphPlan(
            values = valuesById.values.toList(),
            steps = resourceCompilation.steps,
            finalOutputs = finalOutputs,
            resourceProof = resourceCompilation.proof,
            determinismPlan = DeterminismPlan(
                graphSha256 = graphHash,
                planSha256 = planHash,
                randomnessPolicy = M3ReferenceAlgorithms.NO_RANDOMNESS_POLICY,
                algorithms = algorithmManifest,
            ),
            manifestPlan = M3ProcessingManifestPlan(
                sourceBindings = valuesById.values.mapNotNull(GraphValue::sourceBinding),
                finalOutputs = finalOutputs,
                graphSha256 = graphHash,
                planSha256 = planHash,
                changesSamples = mutationSummary.changesSamples,
                changesGeometry = mutationSummary.changesGeometry,
                changesRepresentation = mutationSummary.changesRepresentation,
                changesProvenance = mutationSummary.changesProvenance,
            ),
        )
    }

    private fun validateLimits(definition: GraphDefinition, budget: GraphResourceBudget) {
        if (definition.nodes.isEmpty() || definition.nodes.size > budget.maxNodes) {
            compilationFailure(
                GraphCompileFailureReason.LIMIT_EXCEEDED,
                "Graph node count is empty or exceeds the admitted bound",
            )
        }
        if (definition.values.isEmpty() || definition.values.size > budget.maxValues) {
            compilationFailure(
                GraphCompileFailureReason.LIMIT_EXCEEDED,
                "Graph value count is empty or exceeds the admitted bound",
            )
        }
        if (definition.nodes.size > M3GraphLimits.MAX_NODES || definition.values.size > M3GraphLimits.MAX_VALUES) {
            compilationFailure(GraphCompileFailureReason.LIMIT_EXCEEDED, "Graph exceeds absolute M3 limits")
        }
    }

    private fun uniqueValues(values: List<GraphValue>): LinkedHashMap<GraphValueId, GraphValue> {
        val result = LinkedHashMap<GraphValueId, GraphValue>()
        values.sortedBy { it.id.value }.forEach { value ->
            if (result.put(value.id, value) != null) {
                compilationFailure(
                    GraphCompileFailureReason.DUPLICATE_VALUE,
                    "Duplicate graph value ID ${value.id.value}",
                )
            }
        }
        return result
    }

    private fun uniqueNodes(nodes: List<GraphNodeInvocation>): LinkedHashMap<GraphNodeId, GraphNodeInvocation> {
        val result = LinkedHashMap<GraphNodeId, GraphNodeInvocation>()
        nodes.sortedBy { it.id.value }.forEach { node ->
            if (result.put(node.id, node) != null) {
                compilationFailure(
                    GraphCompileFailureReason.DUPLICATE_NODE,
                    "Duplicate graph node ID ${node.id.value}",
                )
            }
        }
        return result
    }

    private fun validateFinalOutputs(
        finalOutputs: List<GraphValueId>,
        valuesById: Map<GraphValueId, GraphValue>,
    ): List<GraphValueId> {
        if (finalOutputs.isEmpty() || finalOutputs.distinct().size != finalOutputs.size) {
            compilationFailure(
                GraphCompileFailureReason.MALFORMED_METADATA,
                "Graph must declare a unique non-empty final-output set",
            )
        }
        finalOutputs.forEach { outputId ->
            if (outputId !in valuesById) {
                compilationFailure(
                    GraphCompileFailureReason.MISSING_VALUE,
                    "Final output ${outputId.value} does not exist",
                )
            }
        }
        return finalOutputs.sortedBy { it.value }
    }

    private fun validateValueLifetimes(
        valuesById: Map<GraphValueId, GraphValue>,
        finalOutputs: List<GraphValueId>,
    ) {
        valuesById.values.forEach { value ->
            val isFinal = value.id in finalOutputs
            if (isFinal && value.lifetime != GraphValueLifetime.FINAL_OUTPUT) {
                compilationFailure(
                    GraphCompileFailureReason.MALFORMED_METADATA,
                    "Final output ${value.id.value} must declare FINAL_OUTPUT lifetime",
                )
            }
            if (!isFinal && value.lifetime == GraphValueLifetime.FINAL_OUTPUT) {
                compilationFailure(
                    GraphCompileFailureReason.MALFORMED_METADATA,
                    "Non-final value ${value.id.value} cannot declare FINAL_OUTPUT lifetime",
                )
            }
            if (value.sourceBinding != null && value.memoryDomain != GraphMemoryDomain.HOST_JVM) {
                compilationFailure(
                    GraphCompileFailureReason.UNSUPPORTED_BACKEND,
                    "M3 source values must use HOST_JVM memory",
                )
            }
        }
    }

    private fun validateNodeShape(node: GraphNodeInvocation) {
        if (node.inputs.isEmpty() || node.inputs.size > M3GraphLimits.MAX_NODE_INPUTS ||
            node.outputs.isEmpty() || node.outputs.size > M3GraphLimits.MAX_NODE_OUTPUTS
        ) {
            compilationFailure(
                GraphCompileFailureReason.LIMIT_EXCEEDED,
                "Node ${node.id.value} input/output arity exceeds M3 limits",
            )
        }
        if (node.inputs.distinct().size != node.inputs.size || node.outputs.distinct().size != node.outputs.size) {
            compilationFailure(
                GraphCompileFailureReason.MALFORMED_METADATA,
                "Node ${node.id.value} repeats an input or output value",
            )
        }
        if (node.inputs.any { it in node.outputs }) {
            compilationFailure(
                GraphCompileFailureReason.MALFORMED_METADATA,
                "Node ${node.id.value} cannot overwrite an input in place",
            )
        }
    }

    private fun topologicalOrder(
        nodesById: Map<GraphNodeId, GraphNodeInvocation>,
        producerByValue: Map<GraphValueId, GraphNodeId>,
    ): List<GraphNodeInvocation> {
        val dependencies = LinkedHashMap<GraphNodeId, MutableSet<GraphNodeId>>()
        val dependents = LinkedHashMap<GraphNodeId, MutableSet<GraphNodeId>>()
        nodesById.keys.forEach { nodeId ->
            dependencies[nodeId] = linkedSetOf()
            dependents[nodeId] = linkedSetOf()
        }
        nodesById.values.forEach { node ->
            node.inputs.forEach { inputId ->
                producerByValue[inputId]?.let { producer ->
                    if (producer != node.id && dependencies.getValue(node.id).add(producer)) {
                        dependents.getValue(producer).add(node.id)
                    }
                }
            }
        }

        val indegree = dependencies.mapValuesTo(LinkedHashMap()) { it.value.size }
        val ready = ArrayList<GraphNodeId>()
        indegree.filterValues { it == 0 }.keys.sortedBy { it.value }.forEach(ready::add)
        val ordered = ArrayList<GraphNodeInvocation>(nodesById.size)
        while (ready.isNotEmpty()) {
            val nodeId = ready.removeAt(0)
            ordered.add(nodesById.getValue(nodeId))
            dependents.getValue(nodeId).sortedBy { it.value }.forEach { dependent ->
                val next = indegree.getValue(dependent) - 1
                indegree[dependent] = next
                if (next == 0) {
                    insertReadyByNodeId(ready, dependent)
                }
            }
        }
        if (ordered.size != nodesById.size) {
            compilationFailure(GraphCompileFailureReason.CYCLE, "Graph contains a dependency cycle")
        }
        return ordered
    }

    private fun insertReadyByNodeId(ready: MutableList<GraphNodeId>, nodeId: GraphNodeId) {
        val insertionIndex = ready.indexOfFirst { it.value > nodeId.value }
        if (insertionIndex < 0) {
            ready.add(nodeId)
        } else {
            ready.add(insertionIndex, nodeId)
        }
    }

    private fun compileResourcePlan(
        orderedNodes: List<GraphNodeInvocation>,
        contractsByNode: Map<GraphNodeId, ReferenceNodeContract>,
        valuesById: Map<GraphValueId, GraphValue>,
        consumersByValue: Map<GraphValueId, List<GraphNodeId>>,
        finalOutputs: List<GraphValueId>,
        budget: GraphResourceBudget,
    ): ResourceCompilation {
        val remainingUses = LinkedHashMap<GraphValueId, Int>()
        valuesById.keys.forEach { valueId -> remainingUses[valueId] = consumersByValue.getValue(valueId).size }

        var liveBytes = 0L
        val liveValues = linkedSetOf<GraphValueId>()
        valuesById.values.filter { it.sourceBinding != null }.forEach { source ->
            liveBytes = checkedAdd(liveBytes, source.canonicalBytes, "Source live-byte total overflow")
            liveValues.add(source.id)
        }
        val sourceBytes = liveBytes
        var peak = liveBytes
        var peakWorkspace = 0L
        val steps = ArrayList<GraphExecutionStep>(orderedNodes.size)
        val proofSteps = ArrayList<ResourceStepProof>(orderedNodes.size)

        orderedNodes.forEach { node ->
            val contract = contractsByNode.getValue(node.id)
            val inputs = node.inputs.map(valuesById::getValue)
            val outputs = node.outputs.map(valuesById::getValue)
            node.inputs.forEach { inputId ->
                if (inputId !in liveValues) {
                    throw IllegalStateException("Compiler liveness bug: input ${inputId.value} is not live")
                }
            }
            val workspace = ReferenceNodeCatalog.workspaceBytes(contract, inputs, outputs)
            if (workspace < 0L || workspace > budget.maxWorkspaceBytes) {
                compilationFailure(
                    GraphCompileFailureReason.RESOURCE_BUDGET,
                    "Node ${node.id.value} workspace exceeds the admitted budget",
                )
            }
            peakWorkspace = maxOf(peakWorkspace, workspace)
            var outputBytes = 0L
            outputs.forEach { output ->
                outputBytes = checkedAdd(outputBytes, output.canonicalBytes, "Node output-byte total overflow")
            }
            val liveBefore = liveBytes
            val during = checkedAdd(
                checkedAdd(liveBytes, workspace, "Node resident-byte total overflow"),
                outputBytes,
                "Node resident-byte total overflow",
            )
            peak = maxOf(peak, during)

            outputs.forEach { output ->
                if (!liveValues.add(output.id)) {
                    throw IllegalStateException("Compiler liveness bug: output ${output.id.value} is already live")
                }
                liveBytes = checkedAdd(liveBytes, output.canonicalBytes, "Live-byte total overflow")
            }

            val releases = ArrayList<GraphValueId>()
            node.inputs.forEach { inputId ->
                val nextUses = remainingUses.getValue(inputId) - 1
                if (nextUses < 0) {
                    throw IllegalStateException("Compiler liveness bug: negative use count")
                }
                remainingUses[inputId] = nextUses
                if (nextUses == 0 && inputId !in finalOutputs && liveValues.remove(inputId)) {
                    liveBytes = checkedSubtract(
                        liveBytes,
                        valuesById.getValue(inputId).canonicalBytes,
                        "Live-byte total underflow",
                    )
                    releases.add(inputId)
                }
            }

            steps.add(
                GraphExecutionStep(
                    nodeId = node.id,
                    algorithmId = contract.algorithmId,
                    algorithmVersion = contract.algorithmVersion,
                    backend = GraphBackend.SCALAR_REFERENCE,
                    inputs = node.inputs,
                    outputs = node.outputs,
                    workspaceBytes = workspace,
                    releaseAfter = releases,
                ),
            )
            proofSteps.add(
                ResourceStepProof(
                    nodeId = node.id,
                    liveBeforeBytes = liveBefore,
                    workspaceBytes = workspace,
                    outputAllocationBytes = outputBytes,
                    peakDuringNodeBytes = during,
                    liveAfterReleaseBytes = liveBytes,
                ),
            )
        }

        val expectedFinalLive = finalOutputs.toSet()
        if (liveValues != expectedFinalLive) {
            throw IllegalStateException("Compiler liveness bug: terminal live set differs from final outputs")
        }
        val finalOutputBytes = finalOutputs.fold(0L) { total, outputId ->
            checkedAdd(total, valuesById.getValue(outputId).canonicalBytes, "Final output-byte total overflow")
        }
        val reserved = checkedAdd(peak, budget.safetyMarginBytes, "Plan reservation overflow")
        if (reserved > budget.maxResidentBytes) {
            compilationFailure(
                GraphCompileFailureReason.RESOURCE_BUDGET,
                "Graph requires $reserved resident bytes but budget is ${budget.maxResidentBytes}",
            )
        }
        return ResourceCompilation(
            steps = immutableList(steps),
            proof = ResourceProof(
                sourceBytes = sourceBytes,
                finalOutputBytes = finalOutputBytes,
                peakLiveAndOutputBytes = peak,
                peakWorkspaceBytes = peakWorkspace,
                safetyMarginBytes = budget.safetyMarginBytes,
                reservedBytes = reserved,
                steps = proofSteps,
            ),
        )
    }
}

private data class ResourceCompilation(
    val steps: List<GraphExecutionStep>,
    val proof: ResourceProof,
)

private object GraphCanonicalHasher {
    fun graphHash(
        values: List<GraphValue>,
        nodes: List<GraphNodeInvocation>,
        finalOutputs: List<GraphValueId>,
    ): GraphSha256 {
        val tokens = ArrayList<String>()
        tokens.add("camx2-m3-graph-v1")
        values.sortedBy { it.id.value }.forEach { value ->
            tokens.add("value")
            tokens.add(value.id.value.toString())
            tokens.add(value.canonicalBytes.toString())
            tokens.add(value.memoryDomain.name)
            tokens.add(value.lifetime.name)
            tokens.add(value.type.representation.name)
            tokens.add(value.type.encoding.name)
            tokens.add(value.type.photometricDomain.name)
            tokens.add(value.type.size.width.toString())
            tokens.add(value.type.size.height.toString())
            tokens.add(value.type.validAreaLeft.toString())
            tokens.add(value.type.validAreaTop.toString())
            tokens.add(value.type.validAreaWidth.toString())
            tokens.add(value.type.validAreaHeight.toString())
            tokens.add(value.type.storedBits.toString())
            tokens.add(value.type.effectiveBits.toString())
            tokens.add(value.type.cfaPattern?.name.orEmpty())
            tokens.add(value.type.sensorPixelMode.name)
            tokens.add(value.type.calibration.identity.orEmpty())
            tokens.add(value.type.calibration.version.orEmpty())
            tokens.add(java.lang.Double.toHexString(value.type.calibration.confidence))
            tokens.add(value.type.calibration.colorIdentity.orEmpty())
            tokens.add(value.type.lineage.temporalScope.name)
            value.type.lineage.sourceIds.forEach { tokens.add(it.value) }
            tokens.add(value.type.uncertaintySemantics.name)
            value.type.layout.planes.forEach { plane ->
                tokens.add("plane")
                tokens.add(plane.planeIndex.toString())
                tokens.add(plane.offsetBytes.toString())
                tokens.add(plane.rowStrideBytes.toString())
                tokens.add(plane.meaningfulRowBytes.toString())
                tokens.add(plane.rowCount.toString())
                tokens.add(plane.pixelStrideBytes.toString())
            }
            value.sourceBinding?.let { source ->
                tokens.add("source")
                tokens.add(source.sourceId.value)
                tokens.add(source.canonicalRasterSha256)
                tokens.add(source.representationDescriptorSha256)
            }
        }
        nodes.sortedBy { it.id.value }.forEach { node ->
            tokens.add("node")
            tokens.add(node.id.value.toString())
            tokens.add(node.algorithmId.value)
            tokens.add(node.algorithmVersion.toString())
            tokens.add(node.parameterSchemaVersion.toString())
            node.inputs.forEach { tokens.add("i:${it.value}") }
            node.outputs.forEach { tokens.add("o:${it.value}") }
            node.parameters.sortedWith(compareBy(NodeParameter::key, NodeParameter::value)).forEach { parameter ->
                tokens.add("p:${parameter.key}")
                tokens.add(parameter.value)
            }
        }
        finalOutputs.sortedBy { it.value }.forEach { tokens.add("final:${it.value}") }
        return GraphSha256(hashTokens(tokens))
    }

    fun planHash(
        graphHash: GraphSha256,
        steps: List<GraphExecutionStep>,
        proof: ResourceProof,
        budget: GraphResourceBudget,
    ): GraphSha256 {
        val tokens = ArrayList<String>()
        tokens.add("camx2-m3-plan-v1")
        tokens.add(graphHash.value)
        tokens.add(M3ReferenceAlgorithms.NO_RANDOMNESS_POLICY)
        tokens.add(budget.maxResidentBytes.toString())
        tokens.add(budget.maxWorkspaceBytes.toString())
        tokens.add(budget.safetyMarginBytes.toString())
        tokens.add(budget.maxNodes.toString())
        tokens.add(budget.maxValues.toString())
        steps.forEach { step ->
            tokens.add(step.nodeId.value.toString())
            tokens.add(step.algorithmId.value)
            tokens.add(step.algorithmVersion.toString())
            tokens.add(step.backend.name)
            tokens.add(step.workspaceBytes.toString())
            step.inputs.forEach { tokens.add("i:${it.value}") }
            step.outputs.forEach { tokens.add("o:${it.value}") }
            step.releaseAfter.forEach { tokens.add("r:${it.value}") }
        }
        tokens.add(proof.sourceBytes.toString())
        tokens.add(proof.finalOutputBytes.toString())
        tokens.add(proof.peakLiveAndOutputBytes.toString())
        tokens.add(proof.peakWorkspaceBytes.toString())
        tokens.add(proof.safetyMarginBytes.toString())
        tokens.add(proof.reservedBytes.toString())
        proof.steps.forEach { step ->
            tokens.add(step.nodeId.value.toString())
            tokens.add(step.liveBeforeBytes.toString())
            tokens.add(step.workspaceBytes.toString())
            tokens.add(step.outputAllocationBytes.toString())
            tokens.add(step.peakDuringNodeBytes.toString())
            tokens.add(step.liveAfterReleaseBytes.toString())
        }
        return GraphSha256(hashTokens(tokens))
    }

    private fun hashTokens(tokens: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        tokens.forEach { token ->
            val bytes = token.toByteArray(StandardCharsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

private fun compilationFailure(reason: GraphCompileFailureReason, message: String): Nothing =
    throw GraphCompilationException(reason, message)

private fun checkedAdd(left: Long, right: Long, message: String): Long = try {
    Math.addExact(left, right)
} catch (error: ArithmeticException) {
    throw GraphCompilationException(GraphCompileFailureReason.RESOURCE_BUDGET, message)
}

private fun checkedSubtract(left: Long, right: Long, message: String): Long = try {
    Math.subtractExact(left, right)
} catch (error: ArithmeticException) {
    throw IllegalStateException(message, error)
}

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
