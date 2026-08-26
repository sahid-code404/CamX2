package com.sahidcode404.camx.core.camera.trace

import com.sahidcode404.camx.core.camera.model.CameraStartupMilestone
import com.sahidcode404.camx.core.camera.model.CameraStartupTrace
import com.sahidcode404.camx.core.camera.model.CameraStartupTraceEvent
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration

class BoundedCameraStartupTrace(capacity: Int = 64) {
    private val milestones: IntArray
    private val timestamps: LongArray
    private val selectionGenerations: LongArray
    private val sessionGenerations: LongArray
    private var size = 0
    private var next = 0

    init {
        require(capacity in 1..MAX_CAPACITY) { "Trace capacity must be between 1 and $MAX_CAPACITY" }
        milestones = IntArray(capacity)
        timestamps = LongArray(capacity)
        selectionGenerations = LongArray(capacity)
        sessionGenerations = LongArray(capacity)
    }

    @Synchronized
    fun mark(
        milestone: CameraStartupMilestone,
        elapsedRealtimeNs: Long,
        selection: SelectionGeneration = SelectionGeneration(0L),
        session: SessionGeneration = SessionGeneration(0L),
    ) {
        require(elapsedRealtimeNs >= 0L) { "Trace time cannot be negative" }
        milestones[next] = milestone.ordinal
        timestamps[next] = elapsedRealtimeNs
        selectionGenerations[next] = selection.value
        sessionGenerations[next] = session.value
        next = (next + 1) % milestones.size
        if (size < milestones.size) size += 1
    }

    @Synchronized
    fun snapshot(): CameraStartupTrace {
        val start = if (size == milestones.size) next else 0
        return CameraStartupTrace(
            events = List(size) { offset ->
                val index = (start + offset) % milestones.size
                CameraStartupTraceEvent(
                    milestone = CameraStartupMilestone.entries[milestones[index]],
                    elapsedRealtimeNs = timestamps[index],
                    selectionGeneration = SelectionGeneration(selectionGenerations[index]),
                    sessionGeneration = SessionGeneration(sessionGenerations[index]),
                )
            },
        )
    }

    private companion object {
        const val MAX_CAPACITY = 1_024
    }
}
