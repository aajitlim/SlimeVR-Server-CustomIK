package dev.slimevr.config

/**
 * Controls bounded axial compliance in the physical torso solve.
 *
 * This runs before computed trackers are emitted and is intentionally separate
 * from export-only Spring Bones.
 */
class BoneComplianceConfig {
	var enabled: Boolean = false

	/**
	 * Global blend from the rigid SlimeVR rest lengths to the full compliant
	 * solution. 0 = rigid, 1 = use the complete bounded compliant solve.
	 */
	var overallCompliance: Float = 0.5f

	/**
	 * When true, local torso strain is redistributed so the combined length of
	 * the compliant torso segments remains approximately equal to calibration.
	 */
	var preserveTorsoLength: Boolean = true

	/**
	 * 0 = smoother/less reactive strain estimate, 1 = quicker response.
	 */
	var response: Float = 0.5f

	val segments: MutableMap<String, BoneComplianceSegmentConfig> = mutableMapOf(
		UPPER_CHEST_TO_CHEST to BoneComplianceSegmentConfig(
			enabled = true,
			compliance = 0.45f,
			compressionLimit = 0.025f,
			extensionLimit = 0.015f,
			sensorInfluence = 0.35f,
		),
		CHEST_TO_WAIST to BoneComplianceSegmentConfig(
			enabled = true,
			compliance = 0.65f,
			compressionLimit = 0.04f,
			extensionLimit = 0.025f,
			sensorInfluence = 0.55f,
		),
		WAIST_TO_HIP to BoneComplianceSegmentConfig(
			enabled = true,
			compliance = 0.55f,
			compressionLimit = 0.035f,
			extensionLimit = 0.02f,
			sensorInfluence = 0.50f,
		),
	)

	fun getSegment(key: String): BoneComplianceSegmentConfig =
		segments.getOrPut(key) { BoneComplianceSegmentConfig() }

	companion object {
		const val UPPER_CHEST_TO_CHEST = "upper_chest_to_chest"
		const val CHEST_TO_WAIST = "chest_to_waist"
		const val WAIST_TO_HIP = "waist_to_hip"

		val TORSO_SEGMENTS = arrayOf(
			UPPER_CHEST_TO_CHEST,
			CHEST_TO_WAIST,
			WAIST_TO_HIP,
		)
	}
}
