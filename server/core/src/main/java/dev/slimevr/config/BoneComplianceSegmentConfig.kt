package dev.slimevr.config

/**
 * Per-torso-segment settings for the physical compliant skeleton solver.
 *
 * Strain limits are fractions of the calibrated/rest segment length:
 * 0.04 = 4 percent.
 */
class BoneComplianceSegmentConfig(
	var enabled: Boolean = true,
	var compliance: Float = 0.5f,
	var compressionLimit: Float = 0.03f,
	var extensionLimit: Float = 0.02f,
	var sensorInfluence: Float = 0.5f,
)
