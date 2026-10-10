package dev.slimevr.config

/**
 * Online-learning controls for the neural Stay Aligned controller.
 *
 * Sampling/training and correction application are deliberately separate so the
 * model can learn passively before being allowed to influence live yaw.
 */
class NeuralStayAlignedConfig {
	var enabled: Boolean = false
	var learnFromYawResets: Boolean = true
	var applyCorrections: Boolean = false

	/**
	 * Blend applied after the neural model predicts a yaw correction rate.
	 */
	var correctionStrength: Float = 0.5f

	/**
	 * Hard non-neural safety limit on applied learned yaw correction.
	 */
	var maxCorrectionRateDegPerSec: Float = 0.35f

	/**
	 * Minimum amount of learned confidence required before correction is applied.
	 */
	var confidenceThreshold: Float = 0.65f

	/**
	 * Suppresses learned correction during fast intentional tracker motion.
	 * 0 = disabled, 1 = full motion gating.
	 */
	var motionProtection: Float = 0.85f

	/**
	 * Compact temporal samples retained per physical sensor.
	 */
	var historySamples: Int = 1500

	/**
	 * Target feature sampling frequency. Stay Aligned itself can run much faster.
	 */
	var sampleRateHz: Float = 20f

	/**
	 * Learning rate used for reset-supervised GRU updates.
	 */
	var learningRate: Float = 0.0005f

	/**
	 * Very short reset intervals are poor drift labels and are ignored for training.
	 */
	var minimumResetIntervalSeconds: Float = 15f

	/**
	 * Reject implausibly large reset supervision events.
	 */
	var maxResetSupervisionDeg: Float = 45f

	/**
	 * Independently train one tiny GRU per hardware sensor from its OWN rotation,
	 * acceleration, and fresh temperature only. No skeleton context is used.
	 */
	var hardwareLearningEnabled: Boolean = false

	/**
	 * Optional convex fusion with the existing cross-skeleton neural model.
	 * Never adds the two full drift rates on top of one another.
	 */
	var hardwareFusionEnabled: Boolean = false

	/** Maximum local-hardware contribution to the blended requested yaw rate. */
	var hardwareBlend: Float = 0.25f

	/** Temperature older than this is masked as unavailable. */
	var hardwareTemperatureMaxAgeSeconds: Float = 90f
}
