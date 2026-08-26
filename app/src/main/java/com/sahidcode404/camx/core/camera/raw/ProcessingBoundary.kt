package com.sahidcode404.camx.core.camera.raw

import java.util.Collections

interface RawFrame : AutoCloseable

/** Non-copyable owner of RAW frames until a one-time [takeFrames] transfer. */
class RawFrameSet(frames: List<RawFrame>) : AutoCloseable {
    private var ownedFrames: List<RawFrame>? = frames.toList()

    init {
        require(frames.isNotEmpty()) { "A frame set cannot be empty" }
        require(frames.indices.all { left ->
            (left + 1 until frames.size).all { right -> frames[left] !== frames[right] }
        }) {
            "A frame set cannot contain the same owning frame twice"
        }
    }

    @Synchronized
    fun takeFrames(): List<RawFrame> = checkNotNull(ownedFrames) {
        "RAW frame ownership already transferred"
    }.also { ownedFrames = null }

    @Synchronized
    override fun close() {
        val framesToClose = ownedFrames ?: return
        ownedFrames = null
        framesToClose.forEach { frame -> runCatching { frame.close() } }
    }
}

fun interface ImageProcessor<I, O> {
    suspend fun process(input: I): O
}

class ProcessingGraph(processorNames: List<String>) {
    val processorNames: List<String> = Collections.unmodifiableList(ArrayList(processorNames))

    init {
        require(this.processorNames.isNotEmpty()) { "A processing graph must have at least one node" }
        require(this.processorNames.all(String::isNotBlank)) { "Processor names cannot be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is ProcessingGraph && processorNames == other.processorNames

    override fun hashCode(): Int = processorNames.hashCode()

    override fun toString(): String = "ProcessingGraph(processorNames=$processorNames)"
}
