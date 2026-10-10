package dev.slimevr.unit

import dev.slimevr.tracking.processor.stayaligned.neural.NeuralYawGruModel
import dev.slimevr.tracking.processor.stayaligned.neural.NeuralYawSequenceSample
import kotlin.math.abs
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NeuralYawGruModelTests {
	private fun integratedPrediction(
		model: NeuralYawGruModel,
		samples: List<NeuralYawSequenceSample>,
		deviceBias: Float = 0f,
	): Float {
		var hidden = model.newHidden()
		var integrated = 0f
		for (sample in samples) {
			val prediction = model.predict(sample.features, hidden, deviceBias)
			hidden = prediction.hidden
			integrated += prediction.rateDegPerSec * sample.dtSeconds
		}
		return integrated
	}

	@Test
	fun predictionShapeAndRateAreBounded() {
		val model = NeuralYawGruModel(seed = 1234L)
		val features = FloatArray(NeuralYawGruModel.FEATURE_COUNT)
		val hidden = model.newHidden()

		val prediction = model.predict(features, hidden)

		assertEquals(NeuralYawGruModel.HIDDEN_SIZE, prediction.hidden.size)
		assertTrue(prediction.rateDegPerSec.isFinite())
		assertTrue(
			abs(prediction.rateDegPerSec) <=
				NeuralYawGruModel.MODEL_MAX_RATE_DEG_PER_SEC + 1e-5f,
		)
	}

	@Test
	fun resetSupervisionMovesIntegratedPredictionTowardTarget() {
		val model = NeuralYawGruModel(seed = 42L)
		val samples =
			List(80) {
				NeuralYawSequenceSample(
					features = FloatArray(NeuralYawGruModel.FEATURE_COUNT),
					dtSeconds = 0.05f,
				)
			}

		val target = 1.5f
		val before = integratedPrediction(model, samples)
		var deviceBias = 0f
		var lastLoss = Float.POSITIVE_INFINITY

		repeat(40) {
			val result = model.trainSequence(
				samples = samples,
				targetCorrectionDeg = target,
				learningRate = 0.001f,
				deviceBias = deviceBias,
			)
			deviceBias = result.deviceBias
			lastLoss = result.loss
		}

		val after = integratedPrediction(model, samples, deviceBias)

		assertTrue(abs(target - after) < abs(target - before))
		assertTrue(lastLoss.isFinite())
	}

	@Test
	fun checkpointedChunksProduceEquivalentUpdates() {
		val samples = List(192) { index ->
			NeuralYawSequenceSample(
				FloatArray(NeuralYawGruModel.FEATURE_COUNT) { axis ->
					((index % 11) - 5).toFloat() * (axis + 1) * 0.003f
				},
				0.05f,
			)
		}
		val model32 = NeuralYawGruModel(seed = 887L)
		val model64 = NeuralYawGruModel(seed = 887L)

		val result32 = model32.trainSequence(
			samples = samples,
			targetCorrectionDeg = 0.7f,
			learningRate = 0.0005f,
			deviceBias = 0f,
			chunkSize = 32,
		)
		val result64 = model64.trainSequence(
			samples = samples,
			targetCorrectionDeg = 0.7f,
			learningRate = 0.0005f,
			deviceBias = 0f,
			chunkSize = 64,
		)

		assertTrue(abs(result32.loss - result64.loss) < 1e-3f)
		assertTrue(abs(result32.deviceBias - result64.deviceBias) < 1e-3f)
		assertTrue(
			abs(integratedPrediction(model32, samples, result32.deviceBias) -
				integratedPrediction(model64, samples, result64.deviceBias)) < 1e-3f,
		)
	}

	@Test
	fun oppositeResetLabelsMovePredictionInOppositeDirections() {
		val positiveModel = NeuralYawGruModel(seed = 77L)
		val negativeModel = NeuralYawGruModel(seed = 77L)
		val samples =
			List(60) {
				NeuralYawSequenceSample(
					features = FloatArray(NeuralYawGruModel.FEATURE_COUNT).apply {
						this[0] = 0.2f
						this[7] = 0.1f
						this[8] = 0.99f
					},
					dtSeconds = 0.05f,
				)
			}

		var positiveBias = 0f
		var negativeBias = 0f
		repeat(25) {
			positiveBias = positiveModel.trainSequence(
				samples,
				1f,
				0.001f,
				positiveBias,
			).deviceBias
			negativeBias = negativeModel.trainSequence(
				samples,
				-1f,
				0.001f,
				negativeBias,
			).deviceBias
		}

		val positive = integratedPrediction(positiveModel, samples, positiveBias)
		val negative = integratedPrediction(negativeModel, samples, negativeBias)

		assertTrue(positive > negative)
		assertTrue(positive > 0f)
		assertTrue(negative < 0f)
	}
}
