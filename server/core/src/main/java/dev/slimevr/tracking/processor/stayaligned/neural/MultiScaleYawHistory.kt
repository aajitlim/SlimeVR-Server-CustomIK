package dev.slimevr.tracking.processor.stayaligned.neural

import java.util.ArrayDeque

/**
 * Retains the entire motion interval between resets in bounded space.
 *
 * The newest samples stay at original resolution. Older samples are collapsed
 * into time-weighted, ordered FIRST / MEAN / LAST capsules. When the archive
 * reaches its configured capacity, adjacent oldest capsules are merged rather
 * than discarded. This deliberately preserves duration and long-term trends,
 * but it is NOT mathematically lossless recurrent history.
 *
 * Crucially, the reset target is never silently rescaled to the newest window.
 * Older evidence remains visible to the approximate reset replay as capsules.
 */
class MultiScaleYawHistory(private val featureCount: Int) {
    private data class Capsule(
        val first: FloatArray,
        val last: FloatArray,
        val weightedSum: FloatArray,
        val duration: Float,
        val count: Long,
    ) {
        fun mean(): FloatArray = FloatArray(weightedSum.size) {
            weightedSum[it] / duration.coerceAtLeast(1e-8f)
        }

        fun merge(other: Capsule): Capsule {
            val sum = FloatArray(weightedSum.size) {
                weightedSum[it] + other.weightedSum[it]
            }
            return Capsule(first, other.last, sum, duration + other.duration, count + other.count)
        }

        companion object {
            fun single(s: NeuralYawSequenceSample): Capsule {
                val dt = s.dtSeconds
                return Capsule(
                    first = s.features.copyOf(),
                    last = s.features.copyOf(),
                    weightedSum = FloatArray(s.features.size) { s.features[it] * dt },
                    duration = dt,
                    count = 1L,
                )
            }
        }
    }

    private val recent = ArrayDeque<NeuralYawSequenceSample>()
    private val archive = ArrayDeque<Capsule>()
    private var pending: Capsule? = null
    private var groupSize = 64
    private var recentLimit = 1024
    private var archiveLimit = 768

    var durationSeconds: Float = 0f
        private set
    var rawEquivalentSamples: Long = 0L
        private set

    val detailedSamples: Int get() = recent.size
    val archivedCapsules: Int get() = archive.size + if (pending == null) 0 else 1
    val replayTokens: Int get() = recent.size + archivedCapsules * 3
    val isEmpty: Boolean get() = rawEquivalentSamples == 0L

    /**
     * capacity is a resolution budget (not a timer that drops old history);
     * every complete reset interval is retained at increasingly coarse levels.
     */
    fun add(
        sample: NeuralYawSequenceSample,
        capacity: Int,
        chunkSize: Int = 64,
        recentSamples: Int = 1024,
    ) {
        require(sample.features.size == featureCount)
        require(sample.dtSeconds.isFinite() && sample.dtSeconds > 0f)
        val newGroup = if (chunkSize <= 32) 32 else 64
        if (groupSize != newGroup) {
            flushPending()
            groupSize = newGroup
        }
        val limit = capacity.coerceIn(100, 50_000)
        recentLimit = recentSamples.coerceIn(32, minOf(4096, limit))
        archiveLimit = ((limit - recentLimit) / groupSize).coerceIn(4, 768)

        recent.addLast(sample)
        rawEquivalentSamples++
        durationSeconds += sample.dtSeconds

        while (recent.size > recentLimit) {
            val oldest = recent.removeFirst()
            val capsule = Capsule.single(oldest)
            pending = pending?.merge(capsule) ?: capsule
            if ((pending?.count ?: 0L) >= groupSize.toLong()) flushPending()
        }
        trimArchive()
    }

    /** Old samples first, latest exact samples last. No time is discarded. */
    fun trainingSequence(): List<NeuralYawSequenceSample> {
        val result = ArrayList<NeuralYawSequenceSample>(replayTokens)
        for (capsule in archive) appendCapsule(result, capsule)
        pending?.let { appendCapsule(result, it) }
        result.addAll(recent)
        return result
    }

    /** Removes the previous reset interval but keeps network weights elsewhere. */
    fun clear() {
        recent.clear()
        archive.clear()
        pending = null
        durationSeconds = 0f
        rawEquivalentSamples = 0L
    }

    private fun flushPending() {
        pending?.let { archive.addLast(it) }
        pending = null
        trimArchive()
    }

    private fun trimArchive() {
        while (archive.size > archiveLimit) {
            val first = archive.removeFirst()
            val second = archive.removeFirst()
            archive.addFirst(first.merge(second))
        }
    }

    private fun appendCapsule(
        output: MutableList<NeuralYawSequenceSample>,
        capsule: Capsule,
    ) {
        // Three ordered representatives keep boundary directions while the
        // weighted average captures the full block. Time is preserved exactly.
        val dt = capsule.duration / 3f
        output.add(NeuralYawSequenceSample(capsule.first.copyOf(), dt))
        output.add(NeuralYawSequenceSample(capsule.mean(), dt))
        output.add(NeuralYawSequenceSample(capsule.last.copyOf(), capsule.duration - 2f * dt))
    }
}
