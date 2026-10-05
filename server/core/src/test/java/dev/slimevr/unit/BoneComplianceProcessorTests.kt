package dev.slimevr.unit

import dev.slimevr.config.BoneComplianceConfig
import dev.slimevr.tracking.processor.skeleton.BoneComplianceGroundClosureInput
import dev.slimevr.tracking.processor.skeleton.BoneComplianceProcessor
import dev.slimevr.tracking.processor.skeleton.BoneComplianceSegmentInput
import kotlin.math.abs
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoneComplianceProcessorTests {
	private fun config(): BoneComplianceConfig =
		BoneComplianceConfig().apply {
			enabled = true
			overallCompliance = 1f
			preserveTorsoLength = false
			response = 1f
		}

	@Test
	fun disabledComplianceReturnsRigidStrain() {
		val processor = BoneComplianceProcessor()
		val config = config().apply { enabled = false }
		val inputs = listOf(
			BoneComplianceSegmentInput(
				BoneComplianceConfig.CHEST_TO_WAIST,
				0.16f,
				0.8f,
				1.5f,
			),
		)

		val solved = processor.solve(inputs, config, 1_000_000_000L)

		assertEquals(0f, solved[BoneComplianceConfig.CHEST_TO_WAIST])
	}

	@Test
	fun bendingProducesBoundedCompression() {
		val processor = BoneComplianceProcessor()
		val config = config()
		val segment = config.getSegment(BoneComplianceConfig.CHEST_TO_WAIST).apply {
			compliance = 1f
			compressionLimit = 0.04f
			extensionLimit = 0.02f
			sensorInfluence = 0f
		}
		val key = BoneComplianceConfig.CHEST_TO_WAIST

		var time = 1_000_000_000L
		processor.solve(
			listOf(BoneComplianceSegmentInput(key, 0.16f, 0f, null)),
			config,
			time,
		)

		var strain = 0f
		repeat(60) {
			time += 16_666_667L
			strain = processor.solve(
				listOf(BoneComplianceSegmentInput(key, 0.16f, 1.15f, null)),
				config,
				time,
			)[key] ?: 0f
		}

		assertTrue(strain < 0f)
		assertTrue(strain >= -segment.compressionLimit - 1e-5f)
	}

	@Test
	fun relativeAccelerationCanExtendWithoutExceedingLimit() {
		val processor = BoneComplianceProcessor()
		val config = config()
		val key = BoneComplianceConfig.WAIST_TO_HIP
		val segment = config.getSegment(key).apply {
			compliance = 1f
			compressionLimit = 0.04f
			extensionLimit = 0.02f
			sensorInfluence = 1f
		}

		var time = 1_000_000_000L
		processor.solve(
			listOf(BoneComplianceSegmentInput(key, 0.20f, 0f, 1f)),
			config,
			time,
		)

		time += 16_666_667L
		processor.solve(
			listOf(BoneComplianceSegmentInput(key, 0.20f, 0f, 1f)),
			config,
			time,
		)

		var strain = 0f
		repeat(20) {
			time += 16_666_667L
			strain = processor.solve(
				listOf(BoneComplianceSegmentInput(key, 0.20f, 0f, 2.2f)),
				config,
				time,
			)[key] ?: 0f
		}

		assertTrue(strain > 0f)
		assertTrue(strain <= segment.extensionLimit + 1e-5f)
	}

	@Test
	fun preserveTorsoLengthProjectsNetLengthChangeTowardZero() {
		val processor = BoneComplianceProcessor()
		val config = config().apply {
			preserveTorsoLength = true
		}

		for (key in BoneComplianceConfig.TORSO_SEGMENTS) {
			config.getSegment(key).apply {
				enabled = true
				compliance = 1f
				compressionLimit = 0.05f
				extensionLimit = 0.05f
				sensorInfluence = 0f
			}
		}

		val inputs = listOf(
			BoneComplianceSegmentInput(
				BoneComplianceConfig.UPPER_CHEST_TO_CHEST,
				0.16f,
				0.8f,
				null,
			),
			BoneComplianceSegmentInput(
				BoneComplianceConfig.CHEST_TO_WAIST,
				0.16f,
				0.5f,
				null,
			),
			BoneComplianceSegmentInput(
				BoneComplianceConfig.WAIST_TO_HIP,
				0.20f,
				0.2f,
				null,
			),
		)

		var time = 1_000_000_000L
		processor.solve(inputs, config, time)

		var solved = emptyMap<String, Float>()
		repeat(60) {
			time += 16_666_667L
			solved = processor.solve(inputs, config, time)
		}

		val netDelta = inputs.sumOf {
			(it.restLength * (solved[it.key] ?: 0f)).toDouble()
		}.toFloat()

		assertTrue(abs(netDelta) < 1e-4f)
	}

	@Test
	fun groundClosureLowersCommonPlantedFootResidual() {
		val processor = BoneComplianceProcessor()
		val config = config().apply {
			groundClosureEnabled = true
			groundClosureStrength = 1f
			groundClosureMaxCorrectionMeters = 0.05f
			preserveTorsoLength = false
		}
		val key = BoneComplianceConfig.WAIST_TO_HIP
		config.getSegment(key).apply {
			compliance = 1f
			compressionLimit = 0.05f
			extensionLimit = 0.05f
			sensorInfluence = 0f
		}

		val solved = processor.solve(
			inputs = listOf(
				BoneComplianceSegmentInput(
					key = key,
					restLength = 0.20f,
					relativeRotationRadians = 0f,
					relativeAccelerationY = null,
					verticalLengthSensitivity = -1f,
				),
			),
			config = config,
			nowNanos = 1_000_000_000L,
			groundClosure = BoneComplianceGroundClosureInput(
				commonFootResidualMeters = 0.02f,
				contactConfidence = 1f,
				bilateralDisagreementMeters = 0f,
			),
		)

		val strain = solved[key] ?: 0f
		assertTrue(strain > 0f)

		val predictedFootDelta = 0.20f * -1f * strain
		assertTrue(predictedFootDelta < 0f)
	}

	@Test
	fun groundClosureRejectsAsymmetricFeet() {
		val processor = BoneComplianceProcessor()
		val config = config().apply {
			groundClosureEnabled = true
			groundClosureStrength = 1f
			groundClosureBilateralToleranceMeters = 0.02f
			preserveTorsoLength = false
		}
		val key = BoneComplianceConfig.WAIST_TO_HIP
		config.getSegment(key).apply {
			compliance = 1f
			compressionLimit = 0.05f
			extensionLimit = 0.05f
			sensorInfluence = 0f
		}

		val solved = processor.solve(
			inputs = listOf(
				BoneComplianceSegmentInput(
					key = key,
					restLength = 0.20f,
					relativeRotationRadians = 0f,
					relativeAccelerationY = null,
					verticalLengthSensitivity = -1f,
				),
			),
			config = config,
			nowNanos = 1_000_000_000L,
			groundClosure = BoneComplianceGroundClosureInput(
				commonFootResidualMeters = 0.02f,
				contactConfidence = 1f,
				bilateralDisagreementMeters = 0.03f,
			),
		)

		assertEquals(0f, solved[key])
	}

	@Test
	fun groundClosureRespectsExtensionLimit() {
		val processor = BoneComplianceProcessor()
		val config = config().apply {
			groundClosureEnabled = true
			groundClosureStrength = 1f
			groundClosureMaxCorrectionMeters = 0.10f
			preserveTorsoLength = false
		}
		val key = BoneComplianceConfig.CHEST_TO_WAIST
		val segment = config.getSegment(key).apply {
			compliance = 1f
			compressionLimit = 0.01f
			extensionLimit = 0.015f
			sensorInfluence = 0f
		}

		val solved = processor.solve(
			inputs = listOf(
				BoneComplianceSegmentInput(
					key = key,
					restLength = 0.15f,
					relativeRotationRadians = 0f,
					relativeAccelerationY = null,
					verticalLengthSensitivity = -0.8f,
				),
			),
			config = config,
			nowNanos = 1_000_000_000L,
			groundClosure = BoneComplianceGroundClosureInput(
				commonFootResidualMeters = 0.10f,
				contactConfidence = 1f,
			),
		)

		assertTrue((solved[key] ?: 0f) <= segment.extensionLimit + 1e-5f)
	}

	@Test
	fun groundClosureCanRedistributeWhilePreservingTorsoLength() {
		val processor = BoneComplianceProcessor()
		val config = config().apply {
			groundClosureEnabled = true
			groundClosureStrength = 1f
			preserveTorsoLength = true
		}
		for (key in BoneComplianceConfig.TORSO_SEGMENTS) {
			config.getSegment(key).apply {
				compliance = 1f
				compressionLimit = 0.05f
				extensionLimit = 0.05f
				sensorInfluence = 0f
			}
		}

		val inputs = listOf(
			BoneComplianceSegmentInput(
				BoneComplianceConfig.UPPER_CHEST_TO_CHEST,
				0.15f,
				0f,
				null,
				verticalLengthSensitivity = -0.10f,
			),
			BoneComplianceSegmentInput(
				BoneComplianceConfig.CHEST_TO_WAIST,
				0.16f,
				0f,
				null,
				verticalLengthSensitivity = -0.45f,
			),
			BoneComplianceSegmentInput(
				BoneComplianceConfig.WAIST_TO_HIP,
				0.20f,
				0f,
				null,
				verticalLengthSensitivity = -0.90f,
			),
		)

		val solved = processor.solve(
			inputs = inputs,
			config = config,
			nowNanos = 1_000_000_000L,
			groundClosure = BoneComplianceGroundClosureInput(
				commonFootResidualMeters = 0.02f,
				contactConfidence = 1f,
			),
		)

		val netLengthDelta = inputs.sumOf {
			(it.restLength * (solved[it.key] ?: 0f)).toDouble()
		}.toFloat()

		assertTrue(abs(netLengthDelta) < 1e-4f)
		assertTrue(solved.values.any { abs(it) > 1e-5f })
	}

	@Test
	fun eachSegmentKeepsIndependentState() {
		val processor = BoneComplianceProcessor()
		val config = config().apply { preserveTorsoLength = false }

		for (key in BoneComplianceConfig.TORSO_SEGMENTS) {
			config.getSegment(key).apply {
				compliance = 1f
				sensorInfluence = 0f
			}
		}

		var time = 1_000_000_000L
		val restInputs = listOf(
			BoneComplianceSegmentInput(
				BoneComplianceConfig.UPPER_CHEST_TO_CHEST,
				0.16f,
				0f,
				null,
			),
			BoneComplianceSegmentInput(
				BoneComplianceConfig.CHEST_TO_WAIST,
				0.16f,
				0f,
				null,
			),
			BoneComplianceSegmentInput(
				BoneComplianceConfig.WAIST_TO_HIP,
				0.20f,
				0f,
				null,
			),
		)
		processor.solve(restInputs, config, time)

		time += 16_666_667L
		val solved = processor.solve(
			listOf(
				restInputs[0],
				restInputs[1].copy(relativeRotationRadians = 1.15f),
				restInputs[2],
			),
			config,
			time,
		)

		assertEquals(0f, solved[BoneComplianceConfig.UPPER_CHEST_TO_CHEST])
		assertTrue((solved[BoneComplianceConfig.CHEST_TO_WAIST] ?: 0f) < 0f)
		assertEquals(0f, solved[BoneComplianceConfig.WAIST_TO_HIP])
	}
}
