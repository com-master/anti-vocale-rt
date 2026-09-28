package com.antivocale.app.ui.live

import com.antivocale.app.transcription.TimedSegment
import com.antivocale.app.util.SubtitleFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Live mode text renderings. Speaker labels come from the app's one prefix
 * rule ([SubtitleFormatter]: "SPEAKER N" or the enrolled name), so a live
 * copy reads exactly like the History row the session is saved as.
 */
object LiveTranscriptFormat {

    /** The phrases joined into the stored transcript (the History row's `result`). */
    fun joined(segments: List<TimedSegment>): String = segments.joinToString(" ") { it.text }

    /** Plain text: speaker turns on their own lines when labeled, else one paragraph. */
    fun plain(segments: List<TimedSegment>): String =
        SubtitleFormatter.annotatedOrStored(joined(segments), segments)

    /**
     * One line per phrase with the WALL-CLOCK time it started
     * (`[14:03:12] SPEAKER 1: text`): a live session happens at a real time of
     * day, unlike a file, so that is the useful stamp. The speaker is repeated
     * on every line so any single line pasted elsewhere stays attributable.
     */
    fun timed(
        sessionStartWallMs: Long,
        segments: List<TimedSegment>,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US).apply { this.timeZone = timeZone }
        return segments.joinToString("\n") { s ->
            val stamp = clock.format(Date(sessionStartWallMs + s.startMs))
            val who = speakerLabel(s)?.let { "$it: " }.orEmpty()
            "[$stamp] $who${s.text}"
        }
    }

    /** The label [SubtitleFormatter] would print for this cue, or null when unlabeled. */
    fun speakerLabel(s: TimedSegment): String? =
        s.speaker?.let { s.speakerName ?: "SPEAKER ${it + 1}" }
}
