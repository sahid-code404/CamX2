package com.sahidcode404.camx.core.camera.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProcessingBoundaryTest {
    @Test
    fun graphFreezesCallerOwnedProcessorNames() {
        val names = mutableListOf("demosaic")
        val graph = ProcessingGraph(names)
        names += "tone-map"
        assertEquals(listOf("demosaic"), graph.processorNames)
        assertThrows(UnsupportedOperationException::class.java) {
            (graph.processorNames as MutableList).add("mutable")
        }
    }
}
