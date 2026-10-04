package dev.slimevr.websocketapi

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.slimevr.VRServer
import dev.slimevr.VRServer.Companion.getNextLocalTrackerId
import dev.slimevr.VRServer.Companion.instance
import dev.slimevr.bridge.Bridge
import dev.slimevr.config.TrackerPositionAdjustmentConfig
import dev.slimevr.config.TrackerSpringBoneConfig
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

			bridgeConfig.setTrackerSpringBone(role, current)
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
			springBones.set<ObjectNode>(role.name.lowercase(Locale.ROOT), springNode)
		}
		response.set<ObjectNode>("springBones", springBones)
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
