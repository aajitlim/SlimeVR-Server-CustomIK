package dev.slimevr.config

/**
 * Controls how missing torso rotations are distributed across the existing
 * upper-chest -> chest -> waist -> hip chain.
 *
 * Directly tracked bones stay authoritative. Only missing bones between
 * available anchors are interpolated.
 */
class SpineArticulationConfig {
	var enabled = true

	/**
	 * Shapes interpolation between neighboring tracked anchors.
	 *
	 * 1.0 = length-linear distribution.
	 * < 1.0 moves bend earlier toward the upper anchor.
	 * > 1.0 holds the upper segment longer and moves bend toward the lower anchor.
	 */
	var curvePower = 1.0f
}
