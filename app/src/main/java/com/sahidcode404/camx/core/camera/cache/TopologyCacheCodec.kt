package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraProfile
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLens
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.PreviewTrust
import com.sahidcode404.camx.core.camera.model.RawTrust
import com.sahidcode404.camx.core.camera.model.frozenCopy

internal object TopologyCacheCodec {
    fun encode(snapshot: CameraTopologySnapshot): ByteArray {
        require(snapshot.schema == CameraSchemaVersions.TOPOLOGY) { "Unsupported topology-cache schema" }
        validateTopology(snapshot)
        val writer = CacheBinaryWriter()
        writer.writeString(snapshot.environment.value, CacheBounds.ENVIRONMENT_BYTES, "environment")
        writer.writeLong(snapshot.generatedAtElapsedRealtimeNs)
        writer.writeInt(snapshot.routes.size)
        snapshot.routes.forEach { writeRoute(writer, it) }
        writer.writeInt(snapshot.canonicalLenses.size)
        snapshot.canonicalLenses.forEach { writeLens(writer, it) }
        writer.writeInt(snapshot.evidence.size)
        snapshot.evidence.forEach { writeEvidence(writer, it) }
        return CacheEnvelope.encode(
            CacheEnvelope.TOPOLOGY_MAGIC,
            CameraSchemaVersions.TOPOLOGY,
            CacheBounds.TOPOLOGY_PAYLOAD_BYTES,
            writer.toByteArray(),
        )
    }

    fun decode(
        bytes: ByteArray,
        expectedEnvironment: CameraEnvironmentFingerprint,
    ): CacheRead<CameraTopologySnapshot> = when (
        val envelope = CacheEnvelope.decode(
            bytes,
            CacheEnvelope.TOPOLOGY_MAGIC,
            CameraSchemaVersions.TOPOLOGY,
            CacheBounds.TOPOLOGY_PAYLOAD_BYTES,
        )
    ) {
        CacheEnvelope.Decoded.Unsupported -> CacheRead.Miss
        is CacheEnvelope.Decoded.Corrupt -> CacheRead.Corrupt(envelope.reason)
        is CacheEnvelope.Decoded.Payload -> decodePayload(envelope.bytes, expectedEnvironment)
    }

    private fun decodePayload(
        payload: ByteArray,
        expectedEnvironment: CameraEnvironmentFingerprint,
    ): CacheRead<CameraTopologySnapshot> {
        return try {
            val reader = CacheBinaryReader(payload)
            val environment = CameraEnvironmentFingerprint(
                reader.readString(CacheBounds.ENVIRONMENT_BYTES, "environment"),
            )
            if (environment != expectedEnvironment) return CacheRead.Miss
            val generatedAt = reader.readLong("topology timestamp")
            val routeCount = reader.readCount(CacheBounds.ROUTES, "route count")
            val routes = ArrayList<CameraRoute>(routeCount)
            val routesById = LinkedHashMap<CameraRouteId, CameraRoute>(routeCount)
            repeat(routeCount) {
                val route = readRoute(reader)
                if (routesById.put(route.id, route) != null) throw CacheFormatException("Duplicate topology route")
                routes += route
            }

            val lensCount = reader.readCount(CacheBounds.CANONICAL_LENSES, "canonical lens count")
            val lenses = ArrayList<CanonicalLens>(lensCount)
            var profileTotal = 0
            repeat(lensCount) {
                val fingerprint = CanonicalLensFingerprint(
                    reader.readString(CacheBounds.IDENTIFIER_BYTES, "canonical lens fingerprint"),
                )
                val facing = reader.readEnum(LensFacing.values(), "canonical lens facing")
                val profileCount = reader.readCount(CacheBounds.PROFILES_PER_LENS, "profile count")
                if (profileCount == 0) throw CacheFormatException("Canonical lens has no profiles")
                profileTotal += profileCount
                if (profileTotal > CacheBounds.TOTAL_PROFILES) throw CacheFormatException("Total profile count exceeds bound")
                val profiles = ArrayList<CameraProfile>(profileCount)
                repeat(profileCount) {
                    val profileFingerprint = CameraProfileFingerprint(
                        reader.readString(CacheBounds.IDENTIFIER_BYTES, "profile fingerprint"),
                    )
                    val canonicalFingerprint = CanonicalLensFingerprint(
                        reader.readString(CacheBounds.IDENTIFIER_BYTES, "profile canonical fingerprint"),
                    )
                    val routeId = CameraRouteId(
                        reader.readString(CacheBounds.IDENTIFIER_BYTES, "profile route ID"),
                    )
                    val route = routesById[routeId] ?: throw CacheFormatException("Profile references absent route")
                    profiles += CameraProfile(profileFingerprint, canonicalFingerprint, route)
                }
                lenses += CanonicalLens(fingerprint, facing, profiles)
            }

            val evidenceCount = reader.readCount(CacheBounds.EVIDENCE, "evidence count")
            val evidence = ArrayList<CameraMetadataEvidence>(evidenceCount)
            repeat(evidenceCount) { evidence += readEvidence(reader) }
            reader.requireExhausted()
            CacheRead.Hit(
                CameraTopologySnapshot(
                    schema = CameraSchemaVersions.TOPOLOGY,
                    environment = environment,
                    routes = routes,
                    canonicalLenses = lenses,
                    generatedAtElapsedRealtimeNs = generatedAt,
                    evidence = evidence,
                ).frozenCopy(),
            )
        } catch (error: Exception) {
            CacheRead.Corrupt(error.message ?: "Malformed topology cache")
        }
    }

    private fun writeRoute(writer: CacheBinaryWriter, route: CameraRoute) {
        writer.writeString(route.id.value, CacheBounds.IDENTIFIER_BYTES, "route ID")
        writer.writeInt(route.source.ordinal)
        writer.writeString(route.openCameraId.value, CacheBounds.IDENTIFIER_BYTES, "open camera ID")
        writer.writeNullable(route.physicalCameraId) {
            writer.writeString(it.value, CacheBounds.IDENTIFIER_BYTES, "physical camera ID")
        }
        writeCapabilities(writer, route.capabilities)
        writer.writeInt(route.metadataTrust.ordinal)
        writer.writeInt(route.previewTrust.ordinal)
        writer.writeInt(route.rawTrust.ordinal)
        val sources = route.sources.sortedBy { it.ordinal }
        writer.writeInt(sources.size)
        sources.forEach { writer.writeInt(it.ordinal) }
    }

    private fun readRoute(reader: CacheBinaryReader): CameraRoute {
        val id = CameraRouteId(reader.readString(CacheBounds.IDENTIFIER_BYTES, "route ID"))
        val source = reader.readEnum(CameraRouteSource.values(), "route source")
        val openCameraId = CameraTransportId(
            reader.readString(CacheBounds.IDENTIFIER_BYTES, "open camera ID"),
        )
        val physical = reader.readNullable("physical camera ID") {
            PhysicalCameraId(reader.readString(CacheBounds.IDENTIFIER_BYTES, "physical camera ID"))
        }
        val capabilities = readCapabilities(reader)
        val metadataTrust = reader.readEnum(CameraTrust.values(), "metadata trust")
        val previewTrust = reader.readEnum(PreviewTrust.values(), "preview trust")
        val rawTrust = reader.readEnum(RawTrust.values(), "raw trust")
        val sourceCount = reader.readCount(CacheBounds.ROUTE_SOURCES, "route source provenance count")
        if (sourceCount == 0) throw CacheFormatException("Route provenance is empty")
        val sources = LinkedHashSet<CameraRouteSource>(sourceCount)
        repeat(sourceCount) {
            val value = reader.readEnum(CameraRouteSource.values(), "route provenance source")
            if (!sources.add(value)) throw CacheFormatException("Duplicate route provenance source")
        }
        return CameraRoute(
            id = id,
            source = source,
            openCameraId = openCameraId,
            physicalCameraId = physical,
            capabilities = capabilities,
            metadataTrust = metadataTrust,
            previewTrust = previewTrust,
            rawTrust = rawTrust,
            sources = sources,
        )
    }

    private fun writeLens(writer: CacheBinaryWriter, lens: CanonicalLens) {
        writer.writeString(lens.fingerprint.value, CacheBounds.IDENTIFIER_BYTES, "canonical lens fingerprint")
        writer.writeInt(lens.facing.ordinal)
        writer.writeInt(lens.profiles.size)
        lens.profiles.forEach { profile ->
            writer.writeString(profile.fingerprint.value, CacheBounds.IDENTIFIER_BYTES, "profile fingerprint")
            writer.writeString(
                profile.canonicalFingerprint.value,
                CacheBounds.IDENTIFIER_BYTES,
                "profile canonical fingerprint",
            )
            writer.writeString(profile.route.id.value, CacheBounds.IDENTIFIER_BYTES, "profile route ID")
        }
    }

    private fun writeEvidence(writer: CacheBinaryWriter, evidence: CameraMetadataEvidence) {
        writer.writeInt(evidence.source.ordinal)
        writer.writeString(evidence.transportId.value, CacheBounds.IDENTIFIER_BYTES, "evidence transport ID")
        writer.writeNullable(evidence.physicalId) {
            writer.writeString(it.value, CacheBounds.IDENTIFIER_BYTES, "evidence physical ID")
        }
        writer.writeNullable(evidence.logicalParentId) {
            writer.writeString(it.value, CacheBounds.IDENTIFIER_BYTES, "logical parent ID")
        }
        writer.writeInt(evidence.facing.ordinal)
        writer.writeInt(evidence.focalLengthsMillimetres.size)
        evidence.focalLengthsMillimetres.forEach(writer::writeFloat)
        writer.writeNullable(evidence.sensorPhysicalWidthMillimetres, writer::writeFloat)
        writer.writeNullable(evidence.sensorPhysicalHeightMillimetres, writer::writeFloat)
        writer.writeNullable(evidence.activeArray) { writeSize(writer, it) }
        writer.writeNullable(evidence.pixelArray) { writeSize(writer, it) }
        writer.writeNullable(evidence.sensorOrientationDegrees, writer::writeInt)
        writer.writeInt(evidence.apertureValues.size)
        evidence.apertureValues.forEach(writer::writeFloat)
        writer.writeNullable(evidence.colorFilterArrangement, writer::writeInt)
        writeCapabilities(writer, evidence.capabilities)
    }

    private fun readEvidence(reader: CacheBinaryReader): CameraMetadataEvidence {
        val source = reader.readEnum(CameraRouteSource.values(), "evidence source")
        val transport = CameraTransportId(
            reader.readString(CacheBounds.IDENTIFIER_BYTES, "evidence transport ID"),
        )
        val physical = reader.readNullable("evidence physical ID") {
            PhysicalCameraId(reader.readString(CacheBounds.IDENTIFIER_BYTES, "evidence physical ID"))
        }
        val parent = reader.readNullable("logical parent ID") {
            CameraTransportId(reader.readString(CacheBounds.IDENTIFIER_BYTES, "logical parent ID"))
        }
        val facing = reader.readEnum(LensFacing.values(), "evidence facing")
        val focalCount = reader.readCount(CacheBounds.FOCAL_LENGTHS, "focal length count")
        val focals = ArrayList<Float>(focalCount)
        repeat(focalCount) { focals += reader.readFloat("focal length") }
        val sensorWidth = reader.readNullable("sensor physical width") { reader.readFloat("sensor physical width") }
        val sensorHeight = reader.readNullable("sensor physical height") { reader.readFloat("sensor physical height") }
        val active = reader.readNullable("active array") { readSize(reader, "active array") }
        val pixel = reader.readNullable("pixel array") { readSize(reader, "pixel array") }
        val orientation = reader.readNullable("sensor orientation") { reader.readInt("sensor orientation") }
        val apertureCount = reader.readCount(CacheBounds.APERTURES, "aperture count")
        val apertures = ArrayList<Float>(apertureCount)
        repeat(apertureCount) { apertures += reader.readFloat("aperture") }
        val colorFilter = reader.readNullable("color filter arrangement") {
            reader.readInt("color filter arrangement")
        }
        return CameraMetadataEvidence(
            source = source,
            transportId = transport,
            physicalId = physical,
            logicalParentId = parent,
            facing = facing,
            focalLengthsMillimetres = focals,
            sensorPhysicalWidthMillimetres = sensorWidth,
            sensorPhysicalHeightMillimetres = sensorHeight,
            activeArray = active,
            pixelArray = pixel,
            sensorOrientationDegrees = orientation,
            apertureValues = apertures,
            colorFilterArrangement = colorFilter,
            capabilities = readCapabilities(reader),
        )
    }

    private fun writeCapabilities(writer: CacheBinaryWriter, value: CameraCapabilities) {
        writer.writeInt(value.previewStreams.size)
        value.previewStreams.forEach { stream ->
            writer.writeInt(stream.type.ordinal)
            writeSize(writer, stream.size)
            writer.writeNullable(stream.minimumFrameDurationNs, writer::writeLong)
        }
        writer.writeInt(value.fpsRanges.size)
        value.fpsRanges.forEach { range ->
            writer.writeInt(range.minimum)
            writer.writeInt(range.maximum)
        }
        writer.writeInt(value.rawSizes.size)
        value.rawSizes.forEach { writeSize(writer, it) }
    }

    private fun readCapabilities(reader: CacheBinaryReader): CameraCapabilities {
        val streamCount = reader.readCount(CacheBounds.PREVIEW_STREAMS, "preview stream count")
        val streams = ArrayList<CameraStreamCapability>(streamCount)
        repeat(streamCount) {
            streams += CameraStreamCapability(
                type = reader.readEnum(PreviewStreamType.values(), "preview stream type"),
                size = readSize(reader, "preview stream size"),
                minimumFrameDurationNs = reader.readNullable("minimum frame duration") {
                    reader.readLong("minimum frame duration")
                },
            )
        }
        val fpsCount = reader.readCount(CacheBounds.FPS_RANGES, "FPS range count")
        val fpsRanges = ArrayList<CameraFpsCapability>(fpsCount)
        repeat(fpsCount) {
            fpsRanges += CameraFpsCapability(
                reader.readInt("minimum FPS"),
                reader.readInt("maximum FPS"),
            )
        }
        val rawCount = reader.readCount(CacheBounds.RAW_SIZES, "RAW size count")
        val rawSizes = ArrayList<IntSize>(rawCount)
        repeat(rawCount) { rawSizes += readSize(reader, "RAW size") }
        return CameraCapabilities(streams, fpsRanges, rawSizes)
    }

    private fun writeSize(writer: CacheBinaryWriter, value: IntSize) {
        writer.writeInt(value.width)
        writer.writeInt(value.height)
    }

    private fun readSize(reader: CacheBinaryReader, label: String): IntSize = IntSize(
        reader.readInt("$label width"),
        reader.readInt("$label height"),
    )

    private fun validateTopology(snapshot: CameraTopologySnapshot) {
        validateString(snapshot.environment.value, CacheBounds.ENVIRONMENT_BYTES, "environment")
        require(snapshot.routes.size <= CacheBounds.ROUTES) { "Route count exceeds cache bound" }
        require(snapshot.canonicalLenses.size <= CacheBounds.CANONICAL_LENSES) {
            "Canonical lens count exceeds cache bound"
        }
        require(snapshot.evidence.size <= CacheBounds.EVIDENCE) { "Evidence count exceeds cache bound" }
        var totalProfiles = 0
        snapshot.routes.forEach { route ->
            validateString(route.id.value, CacheBounds.IDENTIFIER_BYTES, "route ID")
            validateString(route.openCameraId.value, CacheBounds.IDENTIFIER_BYTES, "open camera ID")
            route.physicalCameraId?.let { validateString(it.value, CacheBounds.IDENTIFIER_BYTES, "physical camera ID") }
            require(route.sources.size in 1..CacheBounds.ROUTE_SOURCES) { "Route provenance count exceeds bound" }
            validateCapabilities(route.capabilities)
        }
        snapshot.canonicalLenses.forEach { lens ->
            validateString(lens.fingerprint.value, CacheBounds.IDENTIFIER_BYTES, "canonical lens fingerprint")
            require(lens.profiles.size in 1..CacheBounds.PROFILES_PER_LENS) { "Profile count exceeds cache bound" }
            totalProfiles += lens.profiles.size
            require(totalProfiles <= CacheBounds.TOTAL_PROFILES) { "Total profile count exceeds cache bound" }
            lens.profiles.forEach { profile ->
                validateString(profile.fingerprint.value, CacheBounds.IDENTIFIER_BYTES, "profile fingerprint")
                validateString(
                    profile.canonicalFingerprint.value,
                    CacheBounds.IDENTIFIER_BYTES,
                    "profile canonical fingerprint",
                )
                validateString(profile.route.id.value, CacheBounds.IDENTIFIER_BYTES, "profile route ID")
            }
        }
        snapshot.evidence.forEach { evidence ->
            validateString(evidence.transportId.value, CacheBounds.IDENTIFIER_BYTES, "evidence transport ID")
            evidence.physicalId?.let { validateString(it.value, CacheBounds.IDENTIFIER_BYTES, "evidence physical ID") }
            evidence.logicalParentId?.let { validateString(it.value, CacheBounds.IDENTIFIER_BYTES, "logical parent ID") }
            require(evidence.focalLengthsMillimetres.size <= CacheBounds.FOCAL_LENGTHS) {
                "Focal length count exceeds cache bound"
            }
            require(evidence.apertureValues.size <= CacheBounds.APERTURES) { "Aperture count exceeds cache bound" }
            validateCapabilities(evidence.capabilities)
        }
    }

    private fun validateCapabilities(value: CameraCapabilities) {
        require(value.previewStreams.size <= CacheBounds.PREVIEW_STREAMS) { "Preview stream count exceeds cache bound" }
        require(value.fpsRanges.size <= CacheBounds.FPS_RANGES) { "FPS range count exceeds cache bound" }
        require(value.rawSizes.size <= CacheBounds.RAW_SIZES) { "RAW size count exceeds cache bound" }
    }

    private fun validateString(value: String, maximumBytes: Int, label: String) {
        require(value.isNotBlank()) { "$label cannot be blank" }
        require(value.toByteArray(Charsets.UTF_8).size <= maximumBytes) { "$label exceeds cache bound" }
    }
}
