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

	public TrackerSpringBoneConfig() {
	}

	public TrackerSpringBoneConfig(boolean enabled, float distance, float strength, float pull) {
		this.enabled = enabled;
		this.distance = distance;
		this.strength = strength;
		this.pull = pull;
	}
}
