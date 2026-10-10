package dev.slimevr.config;

/**
 * Y-axis-only secondary spring motion for a computed tracker exported to a game
 * bridge. This is intentionally independent from tracker/skeleton rotation.
 */
public class TrackerSpringBoneConfig {
	public boolean enabled = false;

	/**
	 * Maximum vertical displacement from the solved tracker position, in metres.
	 */
	public float distance = 0.03f;

	/**
	 * Return stiffness in inverse seconds. Higher values restore toward the
	 * solved position faster and produce a tighter, faster spring.
	 */
	public float strength = 12.0f;

	/**
	 * Coupling from changes in solved vertical velocity into spring velocity.
	 * 0 disables motion injection; larger values make body motion pull the
	 * spring farther before the distance clamp is reached.
	 */
	public float pull = 0.65f;

	/**
	 * When true and IMU acceleration is available, derive jerk and snap from the
	 * filtered acceleration signal and use a weighted normalized combination as
	 * the transient spring driver.
	 */
	public boolean derivativeDriverEnabled = false;

	/**
	 * Relative contribution of filtered dynamic acceleration.
	 */
	public float accelerationWeight = 0.20f;

	/**
	 * Relative contribution of the first acceleration derivative (jerk).
	 */
	public float jerkWeight = 0.70f;

	/**
	 * Relative contribution of the second acceleration derivative (snap).
	 */
	public float snapWeight = 0.10f;

	/**
	 * 0 = smoother / slower derivative response, 1 = faster / more reactive.
	 * This changes filter bandwidth and derivative normalization time scale.
	 */
	public float derivativeResponse = 0.55f;

	public TrackerSpringBoneConfig() {
	}

	public TrackerSpringBoneConfig(boolean enabled, float distance, float strength, float pull) {
		this.enabled = enabled;
		this.distance = distance;
		this.strength = strength;
		this.pull = pull;
	}
}
