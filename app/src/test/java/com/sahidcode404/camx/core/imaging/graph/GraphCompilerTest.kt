package com.sahidcode404.camx.core.imaging.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphCompilerTest {
    @Test
    fun declarationOrderDoesNotChangeCompiledPlan() {
        val source = m3SourceEvidence()
        val type = source.sourceValue.type
        val values = listOf(
            source.sourceValue,
            GraphValue.intermediate(GraphValueId(1), type),
            GraphValue.intermediate(GraphValueId(2), type),
            GraphValue.intermediate(GraphValueId(3), type, GraphValueLifetime.FINAL_OUTPUT),
            GraphValue.intermediate(GraphValueId(4), type, GraphValueLifetime.FINAL_OUTPUT),
        )
        val nodes = listOf(
            m3ForkNode(0, 0, listOf(1, 2)),
            m3CopyNode(1, 1, 3),
            m3CopyNode(2, 2, 4),
        )
        val first = GraphCompiler.compile(
            GraphDefinition(values, nodes, listOf(GraphValueId(3), GraphValueId(4))),
            m3Budget(),
        )
        val second = GraphCompiler.compile(
            GraphDefinition(values.reversed(), nodes.reversed(), listOf(GraphValueId(4), GraphValueId(3))),
            m3Budget(),
        )

        assertEquals(first.determinismPlan.graphSha256, second.determinismPlan.graphSha256)
        assertEquals(first.determinismPlan.planSha256, second.determinismPlan.planSha256)
        assertEquals(listOf(0, 1, 2), first.steps.map { it.nodeId.value })
        assertEquals(listOf(0, 1, 2), second.steps.map { it.nodeId.value })
        assertEquals(M3ReferenceAlgorithms.NO_RANDOMNESS_POLICY, first.determinismPlan.randomnessPolicy)
        assertFalse(first.manifestPlan.changesSamples)
        assertFalse(first.manifestPlan.changesGeometry)
        assertFalse(first.manifestPlan.changesRepresentation)
        assertFalse(first.manifestPlan.changesProvenance)
    }

    @Test
    fun cycleIsRejectedBeforeExecution() {
        val source = m3SourceEvidence()
        val type = source.sourceValue.type
        val values = listOf(
            GraphValue.intermediate(GraphValueId(1), type),
            GraphValue.intermediate(GraphValueId(2), type, GraphValueLifetime.FINAL_OUTPUT),
        )
        val definition = GraphDefinition(
            values = values,
            nodes = listOf(
                m3CopyNode(1, 2, 1),
                m3CopyNode(2, 1, 2),
            ),
            finalOutputs = listOf(GraphValueId(2)),
        )

        val error = assertThrows(GraphCompilationException::class.java) {
            GraphCompiler.compile(definition, m3Budget())
        }
        assertEquals(GraphCompileFailureReason.CYCLE, error.reason)
    }

    @Test
    fun typeMismatchIsRejected() {
        val source = m3SourceEvidence()
        val mismatchedType = source.sourceValue.type.copy(effectiveBits = 10)
        val output = GraphValue.intermediate(
            GraphValueId(1),
            mismatchedType,
            GraphValueLifetime.FINAL_OUTPUT,
        )
        val definition = GraphDefinition(
            values = listOf(source.sourceValue, output),
            nodes = listOf(m3CopyNode(0, 0, 1)),
            finalOutputs = listOf(GraphValueId(1)),
        )

        val error = assertThrows(GraphCompilationException::class.java) {
            GraphCompiler.compile(definition, m3Budget())
        }
        assertEquals(GraphCompileFailureReason.TYPE_MISMATCH, error.reason)
    }

    @Test
    fun resourceProofRejectsOverBudgetPlan() {
        val source = m3SourceEvidence()
        val output = GraphValue.intermediate(
            GraphValueId(1),
            source.sourceValue.type,
            GraphValueLifetime.FINAL_OUTPUT,
        )
        val definition = GraphDefinition(
            values = listOf(source.sourceValue, output),
            nodes = listOf(m3CopyNode(0, 0, 1)),
            finalOutputs = listOf(GraphValueId(1)),
        )

        val error = assertThrows(GraphCompilationException::class.java) {
            GraphCompiler.compile(
                definition,
                m3Budget(maxResidentBytes = 15L, maxWorkspaceBytes = 0L, safetyMarginBytes = 0L),
            )
        }
        assertEquals(GraphCompileFailureReason.RESOURCE_BUDGET, error.reason)
    }

    @Test
    fun resourceProofAccountsForLivenessAndSafetyMargin() {
        val source = m3SourceEvidence()
        val type = source.sourceValue.type
        val branchA = GraphValue.intermediate(GraphValueId(1), type)
        val branchB = GraphValue.intermediate(GraphValueId(2), type)
        val finalA = GraphValue.intermediate(GraphValueId(3), type, GraphValueLifetime.FINAL_OUTPUT)
        val finalB = GraphValue.intermediate(GraphValueId(4), type, GraphValueLifetime.FINAL_OUTPUT)
        val plan = GraphCompiler.compile(
            GraphDefinition(
                values = listOf(source.sourceValue, branchA, branchB, finalA, finalB),
                nodes = listOf(
                    m3ForkNode(0, 0, listOf(1, 2)),
                    m3CopyNode(1, 1, 3),
                    m3CopyNode(2, 2, 4),
                ),
                finalOutputs = listOf(GraphValueId(3), GraphValueId(4)),
            ),
            m3Budget(safetyMarginBytes = 128L),
        )

        assertEquals(8L, plan.resourceProof.sourceBytes)
        assertEquals(16L, plan.resourceProof.finalOutputBytes)
        assertEquals(24L, plan.resourceProof.peakLiveAndOutputBytes)
        assertEquals(0L, plan.resourceProof.peakWorkspaceBytes)
        assertEquals(152L, plan.resourceProof.reservedBytes)
        assertEquals(listOf(GraphValueId(0)), plan.steps[0].releaseAfter)
        assertEquals(listOf(GraphValueId(1)), plan.steps[1].releaseAfter)
        assertEquals(listOf(GraphValueId(2)), plan.steps[2].releaseAfter)
    }

    @Test
    fun malformedNodeMetadataIsRejected() {
        val source = m3SourceEvidence()
        val output = GraphValue.intermediate(
            GraphValueId(1),
            source.sourceValue.type,
            GraphValueLifetime.FINAL_OUTPUT,
        )
        val definition = GraphDefinition(
            values = listOf(source.sourceValue, output),
            nodes = listOf(
                m3CopyNode(
                    nodeId = 0,
                    inputId = 0,
                    outputId = 1,
                    parameters = listOf(NodeParameter("unexpected", "1")),
                ),
            ),
            finalOutputs = listOf(GraphValueId(1)),
        )

        val error = assertThrows(GraphCompilationException::class.java) {
            GraphCompiler.compile(definition, m3Budget())
        }
        assertEquals(GraphCompileFailureReason.MALFORMED_METADATA, error.reason)
    }

    @Test
    fun insufficientCalibrationIsRejected() {
        val source = m3SourceEvidence(calibrationConfidence = 0.0)
        val output = GraphValue.intermediate(
            GraphValueId(1),
            source.sourceValue.type,
            GraphValueLifetime.FINAL_OUTPUT,
        )
        val node = m3CopyNode(
            nodeId = 0,
            inputId = 0,
            outputId = 1,
            algorithmId = M3ReferenceAlgorithms.CALIBRATION_GATE,
        )
        val error = assertThrows(GraphCompilationException::class.java) {
            GraphCompiler.compile(
                GraphDefinition(
                    values = listOf(source.sourceValue, output),
                    nodes = listOf(node),
                    finalOutputs = listOf(GraphValueId(1)),
                ),
                m3Budget(),
            )
        }
        assertEquals(GraphCompileFailureReason.CALIBRATION_INSUFFICIENT, error.reason)
    }

    @Test
    fun duplicateProducerIsRejected() {
        val source = m3SourceEvidence()
        val output = GraphValue.intermediate(
            GraphValueId(1),
            source.sourceValue.type,
            GraphValueLifetime.FINAL_OUTPUT,
        )
        val error = assertThrows(GraphCompilationException::class.java) {
            GraphCompiler.compile(
                GraphDefinition(
                    values = listOf(source.sourceValue, output),
                    nodes = listOf(m3CopyNode(0, 0, 1), m3CopyNode(1, 0, 1)),
                    finalOutputs = listOf(GraphValueId(1)),
                ),
                m3Budget(),
            )
        }
        assertEquals(GraphCompileFailureReason.DUPLICATE_PRODUCER, error.reason)
    }

    @Test
    fun unknownAlgorithmIsRejected() {
        val source = m3SourceEvidence()
        val output = GraphValue.intermediate(
            GraphValueId(1),
            source.sourceValue.type,
            GraphValueLifetime.FINAL_OUTPUT,
        )
        val error = assertThrows(GraphCompilationException::class.java) {
            GraphCompiler.compile(
                GraphDefinition(
                    values = listOf(source.sourceValue, output),
                    nodes = listOf(m3CopyNode(0, 0, 1, AlgorithmId("reference.unknown"))),
                    finalOutputs = listOf(GraphValueId(1)),
                ),
                m3Budget(),
            )
        }
        assertEquals(GraphCompileFailureReason.UNKNOWN_ALGORITHM, error.reason)
    }

    @Test
    fun manifestPlanBindsExactSourceEvidenceAndAlgorithms() {
        val source = m3SourceEvidence()
        val output = GraphValue.intermediate(
            GraphValueId(1),
            source.sourceValue.type,
            GraphValueLifetime.FINAL_OUTPUT,
        )
        val plan = GraphCompiler.compile(
            GraphDefinition(
                values = listOf(source.sourceValue, output),
                nodes = listOf(m3CopyNode(0, 0, 1)),
                finalOutputs = listOf(GraphValueId(1)),
            ),
            m3Budget(),
        )

        assertEquals(1, plan.manifestPlan.sourceBindings.size)
        assertEquals(source.record.sourceId, plan.manifestPlan.sourceBindings.single().sourceId)
        assertEquals(source.record.canonicalRaster.sha256, plan.manifestPlan.sourceBindings.single().canonicalRasterSha256)
        assertEquals(source.record.descriptorSha256, plan.manifestPlan.sourceBindings.single().representationDescriptorSha256)
        assertEquals(M3ReferenceAlgorithms.EXACT_COPY, plan.determinismPlan.algorithms.single().algorithmId)
        assertEquals(GraphBackend.SCALAR_REFERENCE, plan.determinismPlan.algorithms.single().backend)
        assertTrue(plan.determinismPlan.graphSha256.value.length == 64)
        assertTrue(plan.determinismPlan.planSha256.value.length == 64)
    }
}
