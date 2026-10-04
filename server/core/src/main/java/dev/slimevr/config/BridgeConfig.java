package dev.slimevr.config;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdKeySerializers;
import dev.slimevr.config.serializers.BooleanMapDeserializer;
import dev.slimevr.tracking.trackers.TrackerRole;
import java.util.HashMap;
import java.util.Map;


public class BridgeConfig {

	@JsonDeserialize(using = BooleanMapDeserializer.class)
	@JsonSerialize(keyUsing = StdKeySerializers.StringKeySerializer.class)
	public Map<String, Boolean> trackers = new HashMap<>();
	public boolean automaticSharedTrackersToggling = true;

	/**
	 * Enables position-only retargeting for trackers exported through this bridge.
	 * This never changes the computed tracker rotation or feeds offsets back into IK.
	 */
	public boolean positionRetargetingEnabled = false;

	/**
	 * Per-role positional offsets used only at the bridge output boundary.
	 */
	public Map<String, TrackerPositionAdjustmentConfig> trackerPositionOffsets = new HashMap<>();

	/**
	 * Enables bounded Y-axis-only secondary spring motion on exported trackers.
	 */
	public boolean springBonesEnabled = false;

	/**
	 * Per-role spring settings. Rotation is never modified by this layer.
	 */
	public Map<String, TrackerSpringBoneConfig> trackerSpringBones = new HashMap<>();

	public BridgeConfig() {
	}

	public boolean getBridgeTrackerRole(TrackerRole role, boolean def) {
		return trackers.getOrDefault(role.name().toLowerCase(), def);
	}

	public void setBridgeTrackerRole(TrackerRole role, boolean val) {
		this.trackers.put(role.name().toLowerCase(), val);
	}

	public TrackerPositionAdjustmentConfig getTrackerPositionOffset(TrackerRole role) {
		if (role == null) return null;
		return trackerPositionOffsets.get(role.name().toLowerCase());
	}

	public void setTrackerPositionOffset(TrackerRole role, TrackerPositionAdjustmentConfig config) {
		if (role == null) return;
		if (config == null) {
			trackerPositionOffsets.remove(role.name().toLowerCase());
		} else {
			trackerPositionOffsets.put(role.name().toLowerCase(), config);
		}
	}

	public Map<String, TrackerPositionAdjustmentConfig> getTrackerPositionOffsets() {
		return trackerPositionOffsets;
	}

	public TrackerSpringBoneConfig getTrackerSpringBone(TrackerRole role) {
		if (role == null) return null;
		return trackerSpringBones.get(role.name().toLowerCase());
	}

	public void setTrackerSpringBone(TrackerRole role, TrackerSpringBoneConfig config) {
		if (role == null) return;
		if (config == null) {
			trackerSpringBones.remove(role.name().toLowerCase());
		} else {
			trackerSpringBones.put(role.name().toLowerCase(), config);
		}
	}

	public Map<String, TrackerSpringBoneConfig> getTrackerSpringBones() {
		return trackerSpringBones;
	}

	public Map<String, Boolean> getTrackers() {
		return trackers;
	}
}
