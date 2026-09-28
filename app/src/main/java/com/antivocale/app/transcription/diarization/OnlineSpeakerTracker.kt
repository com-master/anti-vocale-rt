package com.antivocale.app.transcription.diarization

/**
 * Live mode: incremental speaker clustering, one embedding per phrase.
 *
 * The offline diarizer ([SpeakerDiarizer]) needs the whole recording; live
 * dictation labels each phrase as soon as it is decoded, so clustering here
 * is online: a phrase joins the most similar existing speaker (cosine against
 * the running centroid) when the similarity reaches [threshold], otherwise it
 * opens a new speaker, up to [maxSpeakers] (past the cap it joins the closest).
 *
 * Embeddings come from the same titanet model the file path and enrollment
 * use, so an enrolled voiceprint can name a live speaker through
 * [SpeakerNamer.bestMatch] with the shared threshold.
 *
 * Pure Kotlin: tests drive it with synthetic vectors.
 */
class OnlineSpeakerTracker(
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val maxSpeakers: Int = DEFAULT_MAX_SPEAKERS,
    private val identities: List<SpeakerIdentity> = emptyList(),
) {
    private class Cluster(val id: Int, var centroid: FloatArray, var weight: Float, var name: String?)

    private val clusters = ArrayList<Cluster>()

    val speakerCount: Int get() = clusters.size

    /** Result of labeling one phrase. [name] is the enrolled person's name when matched. */
    data class Assignment(val speaker: Int, val name: String?)

    /**
     * Assigns one phrase embedding to a speaker.
     * @param weight how much this phrase moves the centroid (its speech seconds).
     */
    fun assign(embedding: FloatArray, weight: Float = 1f): Assignment {
        val unit = normalized(embedding)
        var best: Cluster? = null
        var bestSim = Float.NEGATIVE_INFINITY
        for (c in clusters) {
            val sim = SpeakerNamer.cosineSimilarity(unit, c.centroid) ?: continue
            if (sim > bestSim) {
                bestSim = sim
                best = c
            }
        }
        val target = when {
            best != null && bestSim >= threshold -> best
            clusters.size < maxSpeakers -> Cluster(clusters.size, unit, 0f, null).also { clusters.add(it) }
            else -> best ?: Cluster(clusters.size, unit, 0f, null).also { clusters.add(it) }
        }
        merge(target, unit, weight.coerceAtLeast(MIN_WEIGHT))
        // Re-match every time: a centroid built from more speech is a better voiceprint.
        if (identities.isNotEmpty()) {
            target.name = SpeakerNamer.bestMatch(target.centroid, identities)?.name ?: target.name
        }
        return Assignment(target.id, target.name)
    }

    private fun merge(c: Cluster, unit: FloatArray, weight: Float) {
        if (c.weight == 0f) {
            c.centroid = unit
            c.weight = weight
            return
        }
        val total = c.weight + weight
        val merged = FloatArray(unit.size) { i -> (c.centroid[i] * c.weight + unit[i] * weight) / total }
        c.centroid = normalized(merged)
        c.weight = total
    }

    private fun normalized(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        if (norm == 0f) return v.copyOf()
        val inv = 1f / kotlin.math.sqrt(norm)
        return FloatArray(v.size) { v[it] * inv }
    }

    companion object {
        /**
         * Same-speaker cutoff for titanet-small phrase embeddings. Lower than
         * [SpeakerNamer.MATCH_THRESHOLD]: a new cluster per mis-split phrase is
         * worse in a live transcript than an occasional merge, and naming keeps
         * its own stricter line. Field-tunable.
         */
        const val DEFAULT_THRESHOLD = 0.5f
        const val DEFAULT_MAX_SPEAKERS = 8
        private const val MIN_WEIGHT = 0.1f
    }
}
