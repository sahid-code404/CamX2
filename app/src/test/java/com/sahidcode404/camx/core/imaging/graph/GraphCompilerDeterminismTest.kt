package com.sahidcode404.camx.core.imaging.graph

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GraphCompilerDeterminismTest {
    @Test
    fun deterministicHundredPermutationCorpusProducesOnePlan() {
        val source = m3SourceEvidence()
        val type = source.sourceValue.type
        val values = listOf(
            source.sourceValue,
            GraphValue.intermediate(GraphValueId(1), type),
            GraphValue.intermediate(GraphValueId(2), type),
            GraphValue.intermediate(GraphValueId(3), type),
            GraphValue.intermediate(GraphValueId(4), type, GraphValueLifetime.FINAL_OUTPUT),
            GraphValue.intermediate(GraphValueId(5), type, GraphValueLifetime.FINAL_OUTPUT),
        )
        val nodes = listOf(
            m3ForkNode(10, 0, listOf(1, 2)),
            m3CopyNode(20, 1, 3),
            m3CopyNode(30, 3, 4),
            m3CopyNode(40, 2, 5),
        )
        val outputs = listOf(GraphValueId(4), GraphValueId(5))
        val baseline = GraphCompiler.compile(GraphDefinition(values, nodes, outputs), m3Budget())

        repeat(100) { iteration ->
            val random = Random(0xC0FFEE + iteration)
            val candidate = GraphCompiler.compile(
                GraphDefinition(
                    values = values.shuffled(random),
                    nodes = nodes.shuffled(random),
                    finalOutputs = outputs.shuffled(random),
                ),
                m3Budget(),
            )
            assertEquals(baseline.determinismPlan.graphSha256, candidate.determinismPlan.graphSha256)
            assertEquals(baseline.determinismPlan.planSha256, candidate.determinismPlan.planSha256)
            assertEquals(
                baseline.steps.map { it.nodeId },
                candidate.steps.map { it.nodeId },
            )
        }
    }

    @Test
    fun resourceBudgetChangesPlanHashButNotSemanticGraphHash() {
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
        val first = GraphCompiler.compile(definition, m3Budget(safetyMarginBytes = 64L))
        val second = GraphCompiler.compile(definition, m3Budget(safetyMarginBytes = 128L))

        assertEquals(first.determinismPlan.graphSha256, second.determinismPlan.graphSha256)
        assertNotEquals(first.determinismPlan.planSha256, second.determinismPlan.planSha256)
    }
}
