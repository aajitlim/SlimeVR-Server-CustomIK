package dev.slimevr.tracking.processor.stayaligned.neural

import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

data class NeuralYawSequenceSample(
	val features: FloatArray,
	val dtSeconds: Float,
)

data class NeuralYawPrediction(
	val rateDegPerSec: Float,
	val hidden: FloatArray,
)

/**
 * Tiny dependency-free GRU used by Neural Stay Aligned.
 *
 * The model predicts signed yaw correction rate rather than an absolute
 * quaternion. A soft yaw reset supervises the integral of those predicted rates
 * over the retained temporal window.
 */
class NeuralYawGruModel(
	private val inputSize: Int = FEATURE_COUNT,
	private val hiddenSize: Int = HIDDEN_SIZE,
	seed: Long = 0x534C494D45564CL,
) {
	private var wz = matrix(hiddenSize, inputSize)
	private var uz = matrix(hiddenSize, hiddenSize)
	private var bz = FloatArray(hiddenSize)

	private var wr = matrix(hiddenSize, inputSize)
	private var ur = matrix(hiddenSize, hiddenSize)
	private var br = FloatArray(hiddenSize)

	private var wn = matrix(hiddenSize, inputSize)
	private var un = matrix(hiddenSize, hiddenSize)
	private var bn = FloatArray(hiddenSize)

	private var outputW = FloatArray(hiddenSize)
	private var outputBias = 0f

	private val random = Random(seed)

	init {
		randomize(wz)
		randomize(uz)
		randomize(wr)
		randomize(ur)
		randomize(wn)
		randomize(un)
		for (i in outputW.indices) {
			outputW[i] = (random.nextGaussian() * INITIAL_WEIGHT_SCALE).toFloat()
		}
	}

	fun newHidden(): FloatArray = FloatArray(hiddenSize)

	fun resetWeights() {
		wz = matrix(hiddenSize, inputSize)
		uz = matrix(hiddenSize, hiddenSize)
		bz = FloatArray(hiddenSize)
		wr = matrix(hiddenSize, inputSize)
		ur = matrix(hiddenSize, hiddenSize)
		br = FloatArray(hiddenSize)
		wn = matrix(hiddenSize, inputSize)
		un = matrix(hiddenSize, hiddenSize)
		bn = FloatArray(hiddenSize)
		outputW = FloatArray(hiddenSize)
		outputBias = 0f

		randomize(wz)
		randomize(uz)
		randomize(wr)
		randomize(ur)
		randomize(wn)
		randomize(un)
		for (i in outputW.indices) {
			outputW[i] = (random.nextGaussian() * INITIAL_WEIGHT_SCALE).toFloat()
		}
	}

	fun predict(
		features: FloatArray,
		hidden: FloatArray,
		deviceBias: Float = 0f,
	): NeuralYawPrediction {
		require(features.size == inputSize)
		require(hidden.size == hiddenSize)

		val step = forwardStep(features, hidden, deviceBias)
		return NeuralYawPrediction(step.rateDegPerSec, step.hidden)
	}

	/**
	 * One reset-supervised BPTT update.
	 *
	 * The target is the signed yaw correction attributable to this retained
	 * history window in degrees.
	 */
	fun trainSequence(
		samples: List<NeuralYawSequenceSample>,
		targetCorrectionDeg: Float,
		learningRate: Float,
		deviceBias: Float,
		chunkSize: Int = 64,
	): TrainingResult {
		if (samples.isEmpty()) {
			return TrainingResult(
				loss = 0f,
				predictedCorrectionDeg = 0f,
				targetCorrectionDeg = targetCorrectionDeg,
				deviceBias = deviceBias,
			)
		}

		// Streaming forward pass: store only recurrent states at chunk boundaries.
		// This avoids retaining 50k full GRU steps at once. During reverse BPTT
		// we recompute each chunk from its saved boundary state, then propagate
		// hidden-state gradients across chunk boundaries exactly.
		val valid = samples.filter {
			it.features.size == inputSize && it.dtSeconds.isFinite() && it.dtSeconds > 0f
		}
		if (valid.isEmpty()) {
			return TrainingResult(0f, 0f, targetCorrectionDeg, deviceBias)
		}
		val chunk = chunkSize.coerceIn(8, 256)
		val checkpoints = ArrayList<FloatArray>((valid.size + chunk - 1) / chunk)
		var hidden = newHidden()
		var predictedCorrection = 0f
		for (index in valid.indices) {
			if (index % chunk == 0) checkpoints.add(hidden.copyOf())
			val step = forwardStep(valid[index].features, hidden, deviceBias)
			hidden = step.hidden
			predictedCorrection += step.rateDegPerSec * valid[index].dtSeconds
		}

		val error = predictedCorrection - targetCorrectionDeg
		val loss = huberLoss(error, HUBER_DELTA_DEG)
		val dLossDIntegrated = huberGradient(error, HUBER_DELTA_DEG)

		val gwz = matrix(hiddenSize, inputSize)
		val guz = matrix(hiddenSize, hiddenSize)
		val gbz = FloatArray(hiddenSize)
		val gwr = matrix(hiddenSize, inputSize)
		val gur = matrix(hiddenSize, hiddenSize)
		val gbr = FloatArray(hiddenSize)
		val gwn = matrix(hiddenSize, inputSize)
		val gun = matrix(hiddenSize, hiddenSize)
		val gbn = FloatArray(hiddenSize)
		val gout = FloatArray(hiddenSize)
		var goutBias = 0f
		var gDeviceBias = 0f

		var dhNext = FloatArray(hiddenSize)

		for (chunkIndex in checkpoints.indices.reversed()) {
			val start = chunkIndex * chunk
			val end = minOf(start + chunk, valid.size)
			var chunkHidden = checkpoints[chunkIndex]
			val steps = ArrayList<ForwardStep>(end - start)
			for (i in start until end) {
				val sample = valid[i]
				val step = forwardStep(sample.features, chunkHidden, deviceBias)
				steps.add(step.copy(dtSeconds = sample.dtSeconds))
				chunkHidden = step.hidden
			}

			for (stepIndex in steps.indices.reversed()) {
				val step = steps[stepIndex]
			val normalizedRate =
				(step.rateDegPerSec / MODEL_MAX_RATE_DEG_PER_SEC)
					.coerceIn(-0.999999f, 0.999999f)
			val dPre =
				dLossDIntegrated *
					step.dtSeconds *
					MODEL_MAX_RATE_DEG_PER_SEC *
					(1f - normalizedRate * normalizedRate)

			val dh = FloatArray(hiddenSize)
			for (i in 0 until hiddenSize) {
				gout[i] += dPre * step.hidden[i]
				dh[i] = dhNext[i] + dPre * outputW[i]
			}
			goutBias += dPre
			gDeviceBias += dPre

			val dn = FloatArray(hiddenSize)
			val dz = FloatArray(hiddenSize)
			val dhPrev = FloatArray(hiddenSize)

			for (i in 0 until hiddenSize) {
				dn[i] = dh[i] * (1f - step.updateGate[i])
				dz[i] = dh[i] * (step.hiddenPrev[i] - step.candidate[i])
				dhPrev[i] += dh[i] * step.updateGate[i]
			}

			val dCandidatePre = FloatArray(hiddenSize)
			for (i in 0 until hiddenSize) {
				dCandidatePre[i] =
					dn[i] * (1f - step.candidate[i] * step.candidate[i])
				gbn[i] += dCandidatePre[i]
				for (j in 0 until inputSize) {
					gwn[i][j] += dCandidatePre[i] * step.features[j]
				}
				for (j in 0 until hiddenSize) {
					gun[i][j] +=
						dCandidatePre[i] *
							(step.resetGate[j] * step.hiddenPrev[j])
				}
			}

			val dResetHidden = FloatArray(hiddenSize)
			for (j in 0 until hiddenSize) {
				var sum = 0f
				for (i in 0 until hiddenSize) {
					sum += un[i][j] * dCandidatePre[i]
				}
				dResetHidden[j] = sum
			}

			val dReset = FloatArray(hiddenSize)
			for (i in 0 until hiddenSize) {
				dReset[i] = dResetHidden[i] * step.hiddenPrev[i]
				dhPrev[i] += dResetHidden[i] * step.resetGate[i]
			}

			val dResetPre = FloatArray(hiddenSize)
			for (i in 0 until hiddenSize) {
				dResetPre[i] =
					dReset[i] *
						step.resetGate[i] *
						(1f - step.resetGate[i])
				gbr[i] += dResetPre[i]
				for (j in 0 until inputSize) {
					gwr[i][j] += dResetPre[i] * step.features[j]
				}
				for (j in 0 until hiddenSize) {
					gur[i][j] += dResetPre[i] * step.hiddenPrev[j]
					dhPrev[j] += ur[i][j] * dResetPre[i]
				}
			}

			val dUpdatePre = FloatArray(hiddenSize)
			for (i in 0 until hiddenSize) {
				dUpdatePre[i] =
					dz[i] *
						step.updateGate[i] *
						(1f - step.updateGate[i])
				gbz[i] += dUpdatePre[i]
				for (j in 0 until inputSize) {
					gwz[i][j] += dUpdatePre[i] * step.features[j]
				}
				for (j in 0 until hiddenSize) {
					guz[i][j] += dUpdatePre[i] * step.hiddenPrev[j]
					dhPrev[j] += uz[i][j] * dUpdatePre[i]
				}
			}

				dhNext = dhPrev
			}
		}

		val lr = learningRate.coerceIn(1e-6f, 0.01f)
		applyGradient(wz, gwz, lr)
		applyGradient(uz, guz, lr)
		applyGradient(bz, gbz, lr)
		applyGradient(wr, gwr, lr)
		applyGradient(ur, gur, lr)
		applyGradient(br, gbr, lr)
		applyGradient(wn, gwn, lr)
		applyGradient(un, gun, lr)
		applyGradient(bn, gbn, lr)
		applyGradient(outputW, gout, lr)
		outputBias -= lr * clipGradient(goutBias)

		val newDeviceBias =
			(deviceBias - lr * clipGradient(gDeviceBias))
				.coerceIn(-DEVICE_BIAS_LIMIT, DEVICE_BIAS_LIMIT)

		return TrainingResult(
			loss = loss,
			predictedCorrectionDeg = predictedCorrection,
			targetCorrectionDeg = targetCorrectionDeg,
			deviceBias = newDeviceBias,
		)
	}

	private fun forwardStep(
		features: FloatArray,
		hiddenPrev: FloatArray,
		deviceBias: Float,
	): ForwardStep {
		val z = FloatArray(hiddenSize)
		val r = FloatArray(hiddenSize)
		val n = FloatArray(hiddenSize)

		for (i in 0 until hiddenSize) {
			z[i] = sigmoid(
				dot(wz[i], features) +
					dot(uz[i], hiddenPrev) +
					bz[i],
			)
			r[i] = sigmoid(
				dot(wr[i], features) +
					dot(ur[i], hiddenPrev) +
					br[i],
			)
		}

		val resetHidden = FloatArray(hiddenSize)
		for (i in 0 until hiddenSize) {
			resetHidden[i] = r[i] * hiddenPrev[i]
		}

		for (i in 0 until hiddenSize) {
			n[i] = tanh(
				(
					dot(wn[i], features) +
						dot(un[i], resetHidden) +
						bn[i]
				).toDouble(),
			).toFloat()
		}

		val hidden = FloatArray(hiddenSize)
		for (i in 0 until hiddenSize) {
			hidden[i] =
				(1f - z[i]) * n[i] +
					z[i] * hiddenPrev[i]
		}

		val outputPre =
			dot(outputW, hidden) +
				outputBias +
				deviceBias
		val rate =
			tanh(outputPre.toDouble()).toFloat() *
				MODEL_MAX_RATE_DEG_PER_SEC

		return ForwardStep(
			features = features.copyOf(),
			hiddenPrev = hiddenPrev.copyOf(),
			updateGate = z,
			resetGate = r,
			candidate = n,
			hidden = hidden,
			rateDegPerSec = rate,
			dtSeconds = 0f,
		)
	}

	private fun randomize(matrix: Array<FloatArray>) {
		for (row in matrix) {
			for (i in row.indices) {
				row[i] =
					(random.nextGaussian() * INITIAL_WEIGHT_SCALE).toFloat()
			}
		}
	}

	private fun applyGradient(
		weights: Array<FloatArray>,
		gradient: Array<FloatArray>,
		learningRate: Float,
	) {
		for (i in weights.indices) {
			for (j in weights[i].indices) {
				weights[i][j] -=
					learningRate * clipGradient(gradient[i][j])
			}
		}
	}

	private fun applyGradient(
		weights: FloatArray,
		gradient: FloatArray,
		learningRate: Float,
	) {
		for (i in weights.indices) {
			weights[i] -= learningRate * clipGradient(gradient[i])
		}
	}

	private fun clipGradient(value: Float): Float =
		value.coerceIn(-GRADIENT_CLIP, GRADIENT_CLIP)

	private fun dot(a: FloatArray, b: FloatArray): Float {
		var sum = 0f
		val size = minOf(a.size, b.size)
		for (i in 0 until size) sum += a[i] * b[i]
		return sum
	}

	private fun sigmoid(value: Float): Float =
		(1.0 / (1.0 + exp(-value.toDouble()))).toFloat()

	private fun matrix(rows: Int, cols: Int): Array<FloatArray> =
		Array(rows) { FloatArray(cols) }

	private fun huberLoss(error: Float, delta: Float): Float =
		if (abs(error) <= delta) {
			0.5f * error * error
		} else {
			delta * (abs(error) - 0.5f * delta)
		}

	private fun huberGradient(error: Float, delta: Float): Float =
		error.coerceIn(-delta, delta)

	private data class ForwardStep(
		val features: FloatArray,
		val hiddenPrev: FloatArray,
		val updateGate: FloatArray,
		val resetGate: FloatArray,
		val candidate: FloatArray,
		val hidden: FloatArray,
		val rateDegPerSec: Float,
		val dtSeconds: Float,
	)

	data class TrainingResult(
		val loss: Float,
		val predictedCorrectionDeg: Float,
		val targetCorrectionDeg: Float,
		val deviceBias: Float,
	)

	companion object {
		const val FEATURE_COUNT = 32
		const val HIDDEN_SIZE = 16
		const val MODEL_MAX_RATE_DEG_PER_SEC = 3f

		private const val INITIAL_WEIGHT_SCALE = 0.035
		private const val HUBER_DELTA_DEG = 5f
		private const val GRADIENT_CLIP = 1f
		private const val DEVICE_BIAS_LIMIT = 1.5f
	}
}
