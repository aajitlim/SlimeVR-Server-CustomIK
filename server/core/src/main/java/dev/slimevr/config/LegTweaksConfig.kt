package dev.slimevr.config

class LegTweaksConfig {
	var correctionStrength = 0.3f
	var alwaysUseFloorclip = false

	/**
	 * How much floor clipping is allowed to lift the computed hip tracker.
	 *
	 * Upstream effectively used 0.2. This custom IK branch defaults to 0 so
	 * keeping the feet above the floor cannot lift the pelvis and distort a
	 * forward bend. Feet and knees still receive floor correction.
	 */
	var hipFloorLiftWeight = 0.0f
}
