package dev.slimevr.tracking.processor.skeleton

import io.github.axisangles.ktmath.Quaternion
import kotlin.math.pow

data class SpineRotationAnchor(
	val position: Float,
	val rotation: Quaternion,
)

object SpineRotationDistributor {
	fun sample(
		anchors: List<SpineRotationAnchor>,
		position: Float,
		curvePower: Float,
	): Quaternion {
		if (anchors.isEmpty()) return Quaternion.IDENTITY

		val sortedAnchors = anchors.sortedBy { it.position }
		if (position <= sortedAnchors.first().position) return sortedAnchors.first().rotation
		if (position >= sortedAnchors.last().position) return sortedAnchors.last().rotation

		for (index in 0 until sortedAnchors.lastIndex) {
			val lower = sortedAnchors[index]
			val upper = sortedAnchors[index + 1]
			if (position < lower.position || position > upper.position) continue

			val span = upper.position - lower.position
			if (span <= 1e-6f) return upper.rotation

			val linearT = ((position - lower.position) / span).coerceIn(0f, 1f)
			val shapedT = linearT
				.toDouble()
				.pow(curvePower.coerceIn(0.25f, 4f).toDouble())
				.toFloat()

			return lower.rotation.interpQ(upper.rotation, shapedT).unit()
		}

		return sortedAnchors.last().rotation
	}
}
