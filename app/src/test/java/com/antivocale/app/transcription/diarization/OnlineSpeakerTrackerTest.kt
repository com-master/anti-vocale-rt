package com.antivocale.app.transcription.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineSpeakerTrackerTest {

    private val voiceA = floatArrayOf(1f, 0.1f, 0f, 0f)
    private val voiceA2 = floatArrayOf(0.9f, 0.2f, 0.05f, 0f)
    private val voiceB = floatArrayOf(0f, 0.1f, 1f, 0.2f)
    private val voiceC = floatArrayOf(0f, 0f, 0.1f, 1f)

    @Test
    fun `same voice keeps its speaker id, a new voice opens the next id`() {
        val tracker = OnlineSpeakerTracker()
        val a = tracker.assign(voiceA)
        val b = tracker.assign(voiceB)
        val a2 = tracker.assign(voiceA2)
        assertEquals(0, a.speaker)
        assertEquals(1, b.speaker)
        assertEquals(0, a2.speaker)
        assertEquals(2, tracker.speakerCount)
    }

    @Test
    fun `past the speaker cap a new voice joins the closest cluster`() {
        val tracker = OnlineSpeakerTracker(maxSpeakers = 2)
        tracker.assign(voiceA)
        tracker.assign(voiceB)
        val c = tracker.assign(voiceC)
        assertEquals(2, tracker.speakerCount)
        assertEquals(1, c.speaker)
    }

    @Test
    fun `an enrolled voiceprint names its cluster, others stay generic`() {
        val alice = SpeakerIdentity(id = "alice", name = "Alice", embedding = voiceA, sampleSeconds = 5f)
        val tracker = OnlineSpeakerTracker(identities = listOf(alice))
        assertEquals("Alice", tracker.assign(voiceA2).name)
        val other = tracker.assign(voiceB)
        assertNull(other.name)
        assertNotEquals(0, other.speaker)
    }
}
