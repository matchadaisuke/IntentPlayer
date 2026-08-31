package com.intentplayer.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferencesManagerTest {

    @Test
    fun playbackSpeed_clampsToSupportedRange() {
        assertEquals(0.5f, PreferencesManager.normalizePlaybackSpeed(-10f), 0.0001f)
        assertEquals(0.5f, PreferencesManager.normalizePlaybackSpeed(0.49f), 0.0001f)
        assertEquals(5.0f, PreferencesManager.normalizePlaybackSpeed(5.01f), 0.0001f)
        assertEquals(5.0f, PreferencesManager.normalizePlaybackSpeed(100f), 0.0001f)
    }

    @Test
    fun playbackSpeed_roundsToQuarterSteps() {
        assertEquals(0.5f, PreferencesManager.normalizePlaybackSpeed(0.51f), 0.0001f)
        assertEquals(0.75f, PreferencesManager.normalizePlaybackSpeed(0.74f), 0.0001f)
        assertEquals(1.0f, PreferencesManager.normalizePlaybackSpeed(1.12f), 0.0001f)
        assertEquals(1.25f, PreferencesManager.normalizePlaybackSpeed(1.13f), 0.0001f)
        assertEquals(2.5f, PreferencesManager.normalizePlaybackSpeed(2.49f), 0.0001f)
        assertEquals(5.0f, PreferencesManager.normalizePlaybackSpeed(4.99f), 0.0001f)
    }

    @Test
    fun playbackSpeed_nonFiniteFallsBackToNormalSpeed() {
        assertEquals(1.0f, PreferencesManager.normalizePlaybackSpeed(Float.NaN), 0.0001f)
        assertEquals(1.0f, PreferencesManager.normalizePlaybackSpeed(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(1.0f, PreferencesManager.normalizePlaybackSpeed(Float.NEGATIVE_INFINITY), 0.0001f)
    }
}
