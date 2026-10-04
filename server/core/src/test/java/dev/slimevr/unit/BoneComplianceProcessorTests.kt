package dev.slimevr.unit

import dev.slimevr.config.BoneComplianceConfig
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
