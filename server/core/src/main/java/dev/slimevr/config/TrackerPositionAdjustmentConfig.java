package dev.slimevr.config;

/**
 * Position-only adjustment for a computed tracker exported to a game bridge.
 *
 * Offsets are in metres. Rotation is intentionally not represented here so
 * avatar alignment cannot alter the physical tracker/skeleton quaternion.
 */
public class TrackerPositionAdjustmentConfig {
	public boolean enabled = false;
	public float x = 0.0f;
	public float y = 0.0f;
	public float z = 0.0f;

	/**
	 * Supported values: "world" and "body_yaw".
	 */
	public String space = "body_yaw";

	public TrackerPositionAdjustmentConfig() {
	}

	public TrackerPositionAdjustmentConfig(boolean enabled, float x, float y, float z, String space) {
		this.enabled = enabled;
		this.x = x;
		this.y = y;
		this.z = z;
		this.space = space;
	}
}
