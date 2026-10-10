package dev.slimevr.websocketapi

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.slimevr.VRServer
import dev.slimevr.VRServer.Companion.getNextLocalTrackerId
import dev.slimevr.VRServer.Companion.instance
import dev.slimevr.bridge.Bridge
import dev.slimevr.config.BoneComplianceConfig
import dev.slimevr.config.TrackerPositionAdjustmentConfig
import dev.slimevr.config.TrackerSpringBoneConfig
import dev.slimevr.tracking.processor.stayaligned.neural.NeuralStayAlignedController
import dev.slimevr.tracking.trackers.DeviceOrigin
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerRole
import dev.slimevr.tracking.trackers.TrackerStatus
import io.eiren.util.collections.FastList
import io.eiren.util.logging.LogManager
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class WebSocketVRBridge(
	computedTrackers: List<Tracker>,
	server: VRServer,
) : WebsocketAPI(server, server.protocolAPI),
	Bridge {
	private val computedTrackers: List<Tracker> = FastList(computedTrackers)
	private val internalTrackers: MutableList<Tracker> = FastList(computedTrackers.size)
	private val newHMDData = AtomicBoolean(false)
	private val mapper = ObjectMapper()
	private val internalHMDTracker = Tracker(
		null,
		0,
		"internal://HMD",
		"internal://HMD",
		TrackerPosition.HEAD,
		hasPosition = true,
		hasRotation = true,
		isInternal = true,
		isComputed = true,
	)
	private var hmdTracker: Tracker? = null

	init {
		for (t in computedTrackers) {
			val ct = Tracker(
				null,
				t.id,
				"internal://${t.name}",
				"internal://${t.name}",
				t.trackerPosition,
				hasPosition = true,
				hasRotation = true,
				userEditable = true,
				isInternal = true,
			)
			ct.status = TrackerStatus.OK
			internalTrackers.add(ct)
		}
	}

	override fun dataRead() {
		if (newHMDData.compareAndSet(true, false)) {
			if (hmdTracker == null) {
				// Create HMD for websocket
				val hmdDevice = server.deviceManager
					.createDevice(DeviceOrigin.WEBSOCKET, "WebSocketVRBridge", null, null)
				hmdTracker = Tracker(
					null,
					getNextLocalTrackerId(),
					"WebSocketHMD",
					"WebSocketHMD",
					TrackerPosition.HEAD,
					hasPosition = true,
					hasRotation = true,
					userEditable = true,
					isComputed = true,
				)
				hmdTracker!!.status = TrackerStatus.OK
				hmdDevice.trackers[0] = hmdTracker!!
				server.registerTracker(hmdTracker!!)
			}

			hmdTracker!!.position = internalHMDTracker.position
			hmdTracker!!.setRotation(internalHMDTracker.getRotation())
			hmdTracker!!.dataTick()
		}
	}

	override fun dataWrite() {
		for (i in computedTrackers.indices) {
			val t = computedTrackers[i]
			val it = internalTrackers[i]
			if (t.hasPosition) it.position = t.position
			if (t.hasRotation) it.setRotation(t.getRotation())
		}
	}

	override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
		super.onOpen(conn, handshake)
		// Register trackers
		for (i in internalTrackers.indices) {
			val message = mapper.nodeFactory.objectNode()
			message.put("type", "config")
			message.put("tracker_id", "SlimeVR Tracker " + (i + 1))
			message
				.put(
					"location",
					computedTrackers[i]
						.trackerPosition
						?.trackerRole
						?.name
						?.lowercase(Locale.getDefault()),
				)
			message.put("tracker_type", message["location"].asText())
			conn.send(message.toString())
		}
	}

	override fun onMessage(conn: WebSocket, message: String) {
		// LogManager.info(message);
		try {
			val json = mapper.readTree(message) as ObjectNode
			if (json.has("type")) {
				when (json["type"].asText()) {
					"pos" -> {
						parsePosition(json, conn)
						return
					}

					"action" -> {
						parseAction(json, conn)
						return
					}

					"retarget_get" -> {
						sendRetargetConfig(conn)
						return
					}

					"retarget_set" -> {
						parseRetargetConfig(json)
						sendRetargetConfig(conn)
						return
					}

					"neural_stay_aligned_clear" -> {
						NeuralStayAlignedController.clearLearning()
						sendRetargetConfig(conn)
						return
					}

					// TODO Ignore it for now, it should only register HMD in our test case with id 0
					"config" -> {
						LogManager.info("[WebSocket] Config received: $json")
						return
					}
				}
			}
			LogManager
				.warning(
					"[WebSocket] Unrecognized message from " +
						conn.remoteSocketAddress.address.hostAddress +
						": " +
						message,
				)
		} catch (e: Exception) {
			LogManager
				.severe(
					"[WebSocket] Exception parsing message from " +
						conn.remoteSocketAddress.address.hostAddress +
						". Message: " +
						message,
					e,
				)
		}
	}

	private fun parseRetargetConfig(json: ObjectNode) {
		val bridgeConfig = server.configManager.vrConfig.getBridge("steamvr")
		if (json.has("enabled")) {
			bridgeConfig.positionRetargetingEnabled = json["enabled"].asBoolean()
		}

		val trackers = json["trackers"] as? ObjectNode
		trackers?.fields()?.forEach { (roleKey, value) ->
			val role = try {
				TrackerRole.valueOf(roleKey.uppercase(Locale.ROOT))
			} catch (_: IllegalArgumentException) {
				null
			} ?: return@forEach

			val adjustmentNode = value as? ObjectNode ?: return@forEach
			val current = bridgeConfig.getTrackerPositionOffset(role)
				?: TrackerPositionAdjustmentConfig()

			if (adjustmentNode.has("enabled")) current.enabled = adjustmentNode["enabled"].asBoolean()
			if (adjustmentNode.has("x")) current.x = adjustmentNode["x"].asDouble().toFloat().coerceIn(-2f, 2f)
			if (adjustmentNode.has("y")) current.y = adjustmentNode["y"].asDouble().toFloat().coerceIn(-2f, 2f)
			if (adjustmentNode.has("z")) current.z = adjustmentNode["z"].asDouble().toFloat().coerceIn(-2f, 2f)
			if (adjustmentNode.has("space")) {
				current.space = if (adjustmentNode["space"].asText().equals("world", true)) {
					"world"
				} else {
					"body_yaw"
				}
			}

			bridgeConfig.setTrackerPositionOffset(role, current)
		}

		if (json.has("springBonesEnabled")) {
			bridgeConfig.springBonesEnabled = json["springBonesEnabled"].asBoolean()
		}
		if (json.has("springBonesUseAcceleration")) {
			bridgeConfig.springBonesUseAcceleration =
				json["springBonesUseAcceleration"].asBoolean()
		}

		val springBones = json["springBones"] as? ObjectNode
		springBones?.fields()?.forEach { (roleKey, value) ->
			val role = try {
				TrackerRole.valueOf(roleKey.uppercase(Locale.ROOT))
			} catch (_: IllegalArgumentException) {
				null
			} ?: return@forEach

			val springNode = value as? ObjectNode ?: return@forEach
			val current = bridgeConfig.getTrackerSpringBone(role)
				?: TrackerSpringBoneConfig()

			if (springNode.has("enabled")) current.enabled = springNode["enabled"].asBoolean()
			if (springNode.has("distance")) {
				current.distance = springNode["distance"].asDouble().toFloat().coerceIn(0f, 0.25f)
			}
			if (springNode.has("strength")) {
				current.strength = springNode["strength"].asDouble().toFloat().coerceIn(1f, 30f)
			}
			if (springNode.has("pull")) {
				current.pull = springNode["pull"].asDouble().toFloat().coerceIn(0f, 2f)
			}
			if (springNode.has("derivativeDriverEnabled")) {
				current.derivativeDriverEnabled =
					springNode["derivativeDriverEnabled"].asBoolean()
			}
			if (springNode.has("accelerationWeight")) {
				current.accelerationWeight =
					springNode["accelerationWeight"].asDouble().toFloat().coerceIn(0f, 2f)
			}
			if (springNode.has("jerkWeight")) {
				current.jerkWeight =
					springNode["jerkWeight"].asDouble().toFloat().coerceIn(0f, 2f)
			}
			if (springNode.has("snapWeight")) {
				current.snapWeight =
					springNode["snapWeight"].asDouble().toFloat().coerceIn(0f, 2f)
			}
			if (springNode.has("derivativeResponse")) {
				current.derivativeResponse =
					springNode["derivativeResponse"].asDouble().toFloat().coerceIn(0f, 1f)
			}

			bridgeConfig.setTrackerSpringBone(role, current)
		}

		val neuralStayAligned =
			server.configManager.vrConfig.neuralStayAligned
		if (json.has("neuralStayAlignedEnabled")) {
			neuralStayAligned.enabled =
				json["neuralStayAlignedEnabled"].asBoolean()
		}
		if (json.has("neuralStayAlignedLearnFromYawResets")) {
			neuralStayAligned.learnFromYawResets =
				json["neuralStayAlignedLearnFromYawResets"].asBoolean()
		}
		if (json.has("neuralStayAlignedApplyCorrections")) {
			neuralStayAligned.applyCorrections =
				json["neuralStayAlignedApplyCorrections"].asBoolean()
		}
		if (json.has("neuralStayAlignedCorrectionStrength")) {
			neuralStayAligned.correctionStrength =
				json["neuralStayAlignedCorrectionStrength"].asDouble().toFloat().coerceIn(0f, 1f)
		}
		if (json.has("neuralStayAlignedMaxCorrectionRateDegPerSec")) {
			neuralStayAligned.maxCorrectionRateDegPerSec =
				json["neuralStayAlignedMaxCorrectionRateDegPerSec"].asDouble().toFloat().coerceIn(0f, 3f)
		}
		if (json.has("neuralStayAlignedConfidenceThreshold")) {
			neuralStayAligned.confidenceThreshold =
				json["neuralStayAlignedConfidenceThreshold"].asDouble().toFloat().coerceIn(0f, 1f)
		}
		if (json.has("neuralStayAlignedMotionProtection")) {
			neuralStayAligned.motionProtection =
				json["neuralStayAlignedMotionProtection"].asDouble().toFloat().coerceIn(0f, 1f)
		}
		if (json.has("neuralStayAlignedHistorySamples")) {
			neuralStayAligned.historySamples =
				json["neuralStayAlignedHistorySamples"].asInt().coerceIn(100, 50_000)
		}
		if (json.has("neuralRecentDetailedSamples")) {
			neuralStayAligned.recentDetailedSamples =
				json["neuralRecentDetailedSamples"].asInt().coerceIn(32, 4096)
		}
		if (json.has("neuralHistoryChunkSize")) {
			neuralStayAligned.historyChunkSize =
				if (json["neuralHistoryChunkSize"].asInt() <= 32) 32 else 64
		}
		if (json.has("neuralStayAlignedSampleRateHz")) {
			neuralStayAligned.sampleRateHz =
				json["neuralStayAlignedSampleRateHz"].asDouble().toFloat().coerceIn(5f, 60f)
		}
		if (json.has("neuralStayAlignedLearningRate")) {
			neuralStayAligned.learningRate =
				json["neuralStayAlignedLearningRate"].asDouble().toFloat().coerceIn(0.000001f, 0.01f)
		}
		if (json.has("neuralStayAlignedMinimumResetIntervalSeconds")) {
			neuralStayAligned.minimumResetIntervalSeconds =
				json["neuralStayAlignedMinimumResetIntervalSeconds"].asDouble().toFloat().coerceIn(1f, 600f)
		}
		if (json.has("neuralStayAlignedMaxResetSupervisionDeg")) {
			neuralStayAligned.maxResetSupervisionDeg =
				json["neuralStayAlignedMaxResetSupervisionDeg"].asDouble().toFloat().coerceIn(1f, 180f)
		}
		if (json.has("neuralHardwareLearningEnabled")) {
			neuralStayAligned.hardwareLearningEnabled =
				json["neuralHardwareLearningEnabled"].asBoolean()
		}
		if (json.has("neuralHardwareFusionEnabled")) {
			neuralStayAligned.hardwareFusionEnabled =
				json["neuralHardwareFusionEnabled"].asBoolean()
		}
		if (json.has("neuralHardwareBlend")) {
			neuralStayAligned.hardwareBlend =
				json["neuralHardwareBlend"].asDouble().toFloat().coerceIn(0f, 1f)
		}
		if (json.has("neuralHardwareTemperatureMaxAgeSeconds")) {
			neuralStayAligned.hardwareTemperatureMaxAgeSeconds =
				json["neuralHardwareTemperatureMaxAgeSeconds"]
					.asDouble().toFloat().coerceIn(1f, 600f)
		}

		if (json.has("boneComplianceEnabled")) {
			server.configManager.vrConfig.boneCompliance.enabled =
				json["boneComplianceEnabled"].asBoolean()
		}
		if (json.has("boneComplianceOverall")) {
			server.configManager.vrConfig.boneCompliance.overallCompliance =
				json["boneComplianceOverall"].asDouble().toFloat().coerceIn(0f, 1f)
		}
		if (json.has("boneCompliancePreserveTorsoLength")) {
			server.configManager.vrConfig.boneCompliance.preserveTorsoLength =
				json["boneCompliancePreserveTorsoLength"].asBoolean()
		}
		if (json.has("boneComplianceResponse")) {
			server.configManager.vrConfig.boneCompliance.response =
				json["boneComplianceResponse"].asDouble().toFloat().coerceIn(0f, 1f)
		}
		if (json.has("boneComplianceGroundClosureEnabled")) {
			server.configManager.vrConfig.boneCompliance.groundClosureEnabled =
				json["boneComplianceGroundClosureEnabled"].asBoolean()
		}
		if (json.has("boneComplianceGroundClosureStrength")) {
			server.configManager.vrConfig.boneCompliance.groundClosureStrength =
				json["boneComplianceGroundClosureStrength"].asDouble().toFloat().coerceIn(0f, 1f)
		}
		if (json.has("boneComplianceGroundClosureMaxCorrectionMeters")) {
			server.configManager.vrConfig.boneCompliance.groundClosureMaxCorrectionMeters =
				json["boneComplianceGroundClosureMaxCorrectionMeters"].asDouble().toFloat().coerceIn(0f, 0.15f)
		}
		if (json.has("boneComplianceGroundClosureBilateralToleranceMeters")) {
			server.configManager.vrConfig.boneCompliance.groundClosureBilateralToleranceMeters =
				json["boneComplianceGroundClosureBilateralToleranceMeters"].asDouble().toFloat().coerceIn(0.001f, 0.20f)
		}
		if (json.has("boneComplianceGroundClosureRequireBothFeet")) {
			server.configManager.vrConfig.boneCompliance.groundClosureRequireBothFeet =
				json["boneComplianceGroundClosureRequireBothFeet"].asBoolean()
		}

		val boneComplianceSegments = json["boneComplianceSegments"] as? ObjectNode
		boneComplianceSegments?.fields()?.forEach { (segmentKey, value) ->
			if (!BONE_COMPLIANCE_SEGMENTS.contains(segmentKey)) return@forEach
			val segmentNode = value as? ObjectNode ?: return@forEach
			val current =
				server.configManager.vrConfig.boneCompliance.getSegment(segmentKey)

			if (segmentNode.has("enabled")) {
				current.enabled = segmentNode["enabled"].asBoolean()
			}
			if (segmentNode.has("compliance")) {
				current.compliance =
					segmentNode["compliance"].asDouble().toFloat().coerceIn(0f, 1f)
			}
			if (segmentNode.has("compressionLimit")) {
				current.compressionLimit =
					segmentNode["compressionLimit"].asDouble().toFloat().coerceIn(0f, 0.12f)
			}
			if (segmentNode.has("extensionLimit")) {
				current.extensionLimit =
					segmentNode["extensionLimit"].asDouble().toFloat().coerceIn(0f, 0.12f)
			}
			if (segmentNode.has("sensorInfluence")) {
				current.sensorInfluence =
					segmentNode["sensorInfluence"].asDouble().toFloat().coerceIn(0f, 1f)
			}
		}

		if (json.has("hipFloorLiftWeight")) {
			server.configManager.vrConfig.legTweaks.hipFloorLiftWeight =
				json["hipFloorLiftWeight"].asDouble().toFloat().coerceIn(0f, 1f)
			server.humanPoseManager.updateLegTweaksConfig()
		}

		if (json.has("spineArticulationEnabled")) {
			server.configManager.vrConfig.spineArticulation.enabled =
				json["spineArticulationEnabled"].asBoolean()
		}
		if (json.has("spineCurvePower")) {
			server.configManager.vrConfig.spineArticulation.curvePower =
				json["spineCurvePower"].asDouble().toFloat().coerceIn(0.25f, 4f)
		}

		server.configManager.saveConfig()
	}

	private fun sendRetargetConfig(conn: WebSocket) {
		val bridgeConfig = server.configManager.vrConfig.getBridge("steamvr")
		val response = mapper.nodeFactory.objectNode()
		response.put("type", "retarget_config")
		response.put("enabled", bridgeConfig.positionRetargetingEnabled)
		response.put("springBonesEnabled", bridgeConfig.springBonesEnabled)
		response.put(
			"springBonesUseAcceleration",
			bridgeConfig.springBonesUseAcceleration,
		)
		val neuralStayAligned =
			server.configManager.vrConfig.neuralStayAligned
		response.put("neuralStayAlignedEnabled", neuralStayAligned.enabled)
		response.put(
			"neuralStayAlignedLearnFromYawResets",
			neuralStayAligned.learnFromYawResets,
		)
		response.put(
			"neuralStayAlignedApplyCorrections",
			neuralStayAligned.applyCorrections,
		)
		response.put(
			"neuralStayAlignedCorrectionStrength",
			neuralStayAligned.correctionStrength,
		)
		response.put(
			"neuralStayAlignedMaxCorrectionRateDegPerSec",
			neuralStayAligned.maxCorrectionRateDegPerSec,
		)
		response.put(
			"neuralStayAlignedConfidenceThreshold",
			neuralStayAligned.confidenceThreshold,
		)
		response.put(
			"neuralStayAlignedMotionProtection",
			neuralStayAligned.motionProtection,
		)
		response.put(
			"neuralStayAlignedHistorySamples",
			neuralStayAligned.historySamples,
		)
		response.put(
			"neuralStayAlignedSampleRateHz",
			neuralStayAligned.sampleRateHz,
		)
		response.put("neuralRecentDetailedSamples", neuralStayAligned.recentDetailedSamples)
		response.put("neuralHistoryChunkSize", neuralStayAligned.historyChunkSize)
		response.put(
			"neuralStayAlignedLearningRate",
			neuralStayAligned.learningRate,
		)
		response.put(
			"neuralStayAlignedMinimumResetIntervalSeconds",
			neuralStayAligned.minimumResetIntervalSeconds,
		)
		response.put(
			"neuralStayAlignedMaxResetSupervisionDeg",
			neuralStayAligned.maxResetSupervisionDeg,
		)
		response.put("neuralHardwareLearningEnabled", neuralStayAligned.hardwareLearningEnabled)
		response.put("neuralHardwareFusionEnabled", neuralStayAligned.hardwareFusionEnabled)
		response.put("neuralHardwareBlend", neuralStayAligned.hardwareBlend)
		response.put(
			"neuralHardwareTemperatureMaxAgeSeconds",
			neuralStayAligned.hardwareTemperatureMaxAgeSeconds,
		)

		response.put(
			"boneComplianceEnabled",
			server.configManager.vrConfig.boneCompliance.enabled,
		)
		response.put(
			"boneComplianceOverall",
			server.configManager.vrConfig.boneCompliance.overallCompliance,
		)
		response.put(
			"boneCompliancePreserveTorsoLength",
			server.configManager.vrConfig.boneCompliance.preserveTorsoLength,
		)
		response.put(
			"boneComplianceResponse",
			server.configManager.vrConfig.boneCompliance.response,
		)
		response.put(
			"boneComplianceGroundClosureEnabled",
			server.configManager.vrConfig.boneCompliance.groundClosureEnabled,
		)
		response.put(
			"boneComplianceGroundClosureStrength",
			server.configManager.vrConfig.boneCompliance.groundClosureStrength,
		)
		response.put(
			"boneComplianceGroundClosureMaxCorrectionMeters",
			server.configManager.vrConfig.boneCompliance.groundClosureMaxCorrectionMeters,
		)
		response.put(
			"boneComplianceGroundClosureBilateralToleranceMeters",
			server.configManager.vrConfig.boneCompliance.groundClosureBilateralToleranceMeters,
		)
		response.put(
			"boneComplianceGroundClosureRequireBothFeet",
			server.configManager.vrConfig.boneCompliance.groundClosureRequireBothFeet,
		)
		response.put(
			"hipFloorLiftWeight",
			server.configManager.vrConfig.legTweaks.hipFloorLiftWeight,
		)
		response.put(
			"spineArticulationEnabled",
			server.configManager.vrConfig.spineArticulation.enabled,
		)
		response.put(
			"spineCurvePower",
			server.configManager.vrConfig.spineArticulation.curvePower,
		)

		val trackers = mapper.nodeFactory.objectNode()
		for (role in RETARGET_ROLES) {
			val adjustment = bridgeConfig.getTrackerPositionOffset(role)
				?: TrackerPositionAdjustmentConfig()
			val tracker = mapper.nodeFactory.objectNode()
			tracker.put("enabled", adjustment.enabled)
			tracker.put("x", adjustment.x)
			tracker.put("y", adjustment.y)
			tracker.put("z", adjustment.z)
			tracker.put("space", adjustment.space)
			trackers.set<ObjectNode>(role.name.lowercase(Locale.ROOT), tracker)
		}
		response.set<ObjectNode>("trackers", trackers)

		val springBones = mapper.nodeFactory.objectNode()
		for (role in RETARGET_ROLES) {
			val spring = bridgeConfig.getTrackerSpringBone(role)
				?: TrackerSpringBoneConfig()
			val springNode = mapper.nodeFactory.objectNode()
			springNode.put("enabled", spring.enabled)
			springNode.put("distance", spring.distance)
			springNode.put("strength", spring.strength)
			springNode.put("pull", spring.pull)
			springNode.put("derivativeDriverEnabled", spring.derivativeDriverEnabled)
			springNode.put("accelerationWeight", spring.accelerationWeight)
			springNode.put("jerkWeight", spring.jerkWeight)
			springNode.put("snapWeight", spring.snapWeight)
			springNode.put("derivativeResponse", spring.derivativeResponse)
			springBones.set<ObjectNode>(role.name.lowercase(Locale.ROOT), springNode)
		}
		response.set<ObjectNode>("springBones", springBones)

		val boneComplianceSegments = mapper.nodeFactory.objectNode()
		for (segmentKey in BONE_COMPLIANCE_SEGMENTS) {
			val segment =
				server.configManager.vrConfig.boneCompliance.getSegment(segmentKey)
			val segmentNode = mapper.nodeFactory.objectNode()
			segmentNode.put("enabled", segment.enabled)
			segmentNode.put("compliance", segment.compliance)
			segmentNode.put("compressionLimit", segment.compressionLimit)
			segmentNode.put("extensionLimit", segment.extensionLimit)
			segmentNode.put("sensorInfluence", segment.sensorInfluence)
			boneComplianceSegments.set<ObjectNode>(segmentKey, segmentNode)
		}
		response.set<ObjectNode>("boneComplianceSegments", boneComplianceSegments)

		val neuralStatus = NeuralStayAlignedController.status()
		val neuralStatusNode = mapper.nodeFactory.objectNode()
		neuralStatusNode.put("deviceCount", neuralStatus.deviceCount)
		neuralStatusNode.put("totalSamples", neuralStatus.totalSamples)
		neuralStatusNode.put(
			"totalSupervisionEvents",
			neuralStatus.totalSupervisionEvents,
		)
		val neuralDevices = mapper.nodeFactory.arrayNode()
		for (device in neuralStatus.devices) {
			val deviceNode = mapper.nodeFactory.objectNode()
			deviceNode.put("deviceKey", device.deviceKey)
			deviceNode.put("trackerName", device.trackerName)
			deviceNode.put("bodyPosition", device.bodyPosition)
			deviceNode.put("samplesSeen", device.samplesSeen)
			deviceNode.put("historySize", device.historySize)
			deviceNode.put("historyRawEquivalentSamples", device.historyRawEquivalentSamples)
			deviceNode.put("historyCompressedCapsules", device.historyCompressedCapsules)
			deviceNode.put("historyReplayTokens", device.historyReplayTokens)
			deviceNode.put("historySeconds", device.historySeconds)
			deviceNode.put("supervisionEvents", device.supervisionEvents)
			deviceNode.put(
				"predictedRateDegPerSec",
				device.predictedRateDegPerSec,
			)
			deviceNode.put(
				"appliedRateDegPerSec",
				device.appliedRateDegPerSec,
			)
			deviceNode.put("confidence", device.confidence)
			deviceNode.put(
				"lastResetCorrectionDeg",
				device.lastResetCorrectionDeg,
			)
			deviceNode.put(
				"lastTrainingTargetDeg",
				device.lastTrainingTargetDeg,
			)
			deviceNode.put("lastLoss", device.lastLoss)
			deviceNode.put("hardwareSamplesSeen", device.hardwareSamplesSeen)
			deviceNode.put("hardwareHistorySize", device.hardwareHistorySize)
			deviceNode.put("hardwareRawEquivalentSamples", device.hardwareRawEquivalentSamples)
			deviceNode.put("hardwareCompressedCapsules", device.hardwareCompressedCapsules)
			deviceNode.put("hardwareReplayTokens", device.hardwareReplayTokens)
			deviceNode.put("hardwareHistorySeconds", device.hardwareHistorySeconds)
			deviceNode.put("hardwareResetLabels", device.hardwareResetLabels)
			deviceNode.put(
				"hardwarePredictedRateDegPerSec",
				device.hardwarePredictedRateDegPerSec,
			)
			deviceNode.put("hardwareLastLoss", device.hardwareLastLoss)
			deviceNode.put("hardwareLastErrorDeg", device.hardwareLastErrorDeg)
			deviceNode.put("hardwareConfidence", device.hardwareConfidence)
			if (device.hardwareTemperatureCelsius != null) {
				deviceNode.put("hardwareTemperatureCelsius", device.hardwareTemperatureCelsius)
			} else {
				deviceNode.putNull("hardwareTemperatureCelsius")
			}
			deviceNode.put(
				"hardwareTemperatureRateCelsiusPerSec",
				device.hardwareTemperatureRateCelsiusPerSec,
			)
			deviceNode.put("hardwareTemperatureFresh", device.hardwareTemperatureFresh)
			neuralDevices.add(deviceNode)
		}
		neuralStatusNode.replace(
			"devices",
			neuralDevices,
		)
		response.set<ObjectNode>("neuralStayAlignedStatus", neuralStatusNode)

		conn.send(response.toString())
	}

	private fun parsePosition(json: ObjectNode, conn: WebSocket) {
		if (json["tracker_id"].asInt() == 0) {
			// Read HMD information
			internalHMDTracker
				.position = Vector3(
				json["x"].asDouble().toFloat(),
				json["y"].asDouble().toFloat() + 0.2f,
				json["z"].asDouble().toFloat(),
			)
			// TODO Wtf is this hack? VRWorkout issue?
			internalHMDTracker
				.setRotation(
					Quaternion(
						json["qw"].asDouble().toFloat(),
						json["qx"].asDouble().toFloat(),
						json["qy"].asDouble().toFloat(),
						json["qz"].asDouble().toFloat(),
					),
				)
			internalHMDTracker.dataTick()
			newHMDData.set(true)

			// Send tracker info in reply
			for (i in internalTrackers.indices) {
				val message = mapper.nodeFactory.objectNode()
				message.put("type", "pos")
				message.put("src", "full")
				message.put("tracker_id", "SlimeVR Tracker ${i + 1}")

				val t = internalTrackers[i]
				message.put("x", t.position.x)
				message.put("y", t.position.y)
				message.put("z", t.position.z)
				message.put("qx", t.getRotation().x)
				message.put("qy", t.getRotation().y)
				message.put("qz", t.getRotation().z)
				message.put("qw", t.getRotation().w)

				conn.send(message.toString())
			}
		}
	}

	private fun parseAction(json: ObjectNode, conn: WebSocket) {
		when (json["name"].asText()) {
			"calibrate" -> instance.resetTrackersYaw(RESET_SOURCE_NAME)
			"full_calibrate" -> instance.resetTrackersFull(RESET_SOURCE_NAME)
			"mounting_calibrate" -> instance.resetTrackersMounting(RESET_SOURCE_NAME)
			"mounting_clear" -> instance.clearTrackersMounting(RESET_SOURCE_NAME)
			"toggle_pause_tracking" -> instance.togglePauseTracking(RESET_SOURCE_NAME)
		}
	}

	override fun onStart() {
		LogManager.info("[WebSocket] Web Socket VR Bridge started on port $port")
		connectionLostTimeout = 0
		connectionLostTimeout = 1
		// This has to be removed for Android
		// (keepalive did not work for me @mgschwan)
	}

	override fun addSharedTracker(tracker: Tracker?) {
		// TODO Auto-generated method stub
	}

	override fun removeSharedTracker(tracker: Tracker?) {
		// TODO Auto-generated method stub
	}

	override fun startBridge() {
		start()
	}

	override fun stopBridge() {
		stop()
	}

	override fun isConnected(): Boolean = super.getConnections().isNotEmpty()

	companion object {
		private const val RESET_SOURCE_NAME = "WebSocketVRBridge"
		private val BONE_COMPLIANCE_SEGMENTS = arrayOf(
			BoneComplianceConfig.UPPER_CHEST_TO_CHEST,
			BoneComplianceConfig.CHEST_TO_WAIST,
			BoneComplianceConfig.WAIST_TO_HIP,
		)

		private val RETARGET_ROLES = arrayOf(
			TrackerRole.WAIST,
			TrackerRole.CHEST,
			TrackerRole.LEFT_KNEE,
			TrackerRole.RIGHT_KNEE,
			TrackerRole.LEFT_FOOT,
			TrackerRole.RIGHT_FOOT,
			TrackerRole.LEFT_ELBOW,
			TrackerRole.RIGHT_ELBOW,
			TrackerRole.LEFT_HAND,
			TrackerRole.RIGHT_HAND,
		)
	}
}
