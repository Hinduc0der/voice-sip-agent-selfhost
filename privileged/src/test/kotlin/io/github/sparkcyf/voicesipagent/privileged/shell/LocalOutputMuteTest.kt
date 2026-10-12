package io.github.sparkcyf.voicesipagent.privileged.shell

import org.junit.Assert.*
import org.junit.Test

class LocalOutputMuteTest {
    private class Audio : LocalOutputMute.AudioControl {
        override var mode = 2
        override var voiceCallMuted = false
        val modes = mutableListOf<Int>()
        val streamRequests = mutableListOf<Boolean>()
        var acceptMute = true
        var acceptUnmute = true
        var acceptMode = true
        var failMuteAcknowledgement = false
        var failUnmute = false
        var failMode = false

        override fun requestMode(mode: Int) {
            modes += mode
            if (failMode) throw SecurityException("mode denied")
            if (acceptMode) this.mode = mode
        }
        override fun requestVoiceCallMute(muted: Boolean) {
            streamRequests += muted
            if (!muted && failUnmute) throw IllegalStateException("audio service unavailable")
            if (if (muted) acceptMute else acceptUnmute) voiceCallMuted = muted
        }
        override fun awaitMode(wanted: Int) = mode
        override fun awaitVoiceCallMute(wanted: Boolean): Boolean {
            if (wanted && failMuteAcknowledgement) throw IllegalStateException("mute acknowledgement lost")
            return voiceCallMuted
        }
    }

    @Test fun directCallMutesOutputWithoutChangingModeAndRestoresOnce() {
        val audio = Audio()
        val mute = LocalOutputMute(audio)
        assertEquals("muted: voice-call stream", mute.mute(false))
        assertTrue(audio.voiceCallMuted)
        assertEquals(2, audio.mode)
        assertTrue(audio.modes.isEmpty())
        assertEquals("restored voice-call stream (tap closed)", mute.restore("tap closed"))
        assertFalse(audio.voiceCallMuted)
        assertEquals("not muted", mute.restore("close again"))
        assertEquals(listOf(true, false), audio.streamRequests)
    }

    @Test fun redirectionWithdrawsOurModeRequestRatherThanSettingTelecomsMode() {
        val audio = Audio()
        val mute = LocalOutputMute(audio)
        assertEquals("muted: mode 2->5", mute.mute(true))
        assertEquals(5, audio.mode)
        assertTrue(audio.streamRequests.isEmpty())
        assertEquals("restored redirection mode (tap closed), mode=0", mute.restore("tap closed"))
        assertEquals(listOf(5, 0), audio.modes)
    }

    @Test fun initiallyMutedStreamIsNeverUnmutedByUs() {
        val audio = Audio().apply { voiceCallMuted = true }
        val mute = LocalOutputMute(audio)
        assertEquals("already muted: voice-call stream (preserved)", mute.mute(false))
        mute.mute(false)
        assertEquals("preserved existing voice-call stream mute (tap closed)", mute.restore("tap closed"))
        assertTrue(audio.voiceCallMuted)
        assertTrue(audio.streamRequests.isEmpty())
    }

    @Test fun repeatedMuteDoesNotOverwriteTheOriginalUnmutedState() {
        val audio = Audio()
        val mute = LocalOutputMute(audio)
        mute.mute(false)
        assertEquals("already muted: voice-call stream", mute.mute(false))
        mute.restore("tap closed")
        assertEquals(listOf(true, false), audio.streamRequests)
        assertFalse(audio.voiceCallMuted)
    }

    @Test fun noGlobalChangeIsMadeOutsideACellularCall() {
        val audio = Audio().apply { mode = 0 }
        val mute = LocalOutputMute(audio)
        assertEquals("skipped: mode=0", mute.mute(false))
        assertEquals("skipped: mode=0", mute.mute(true))
        assertEquals("not muted", mute.restore("tap closed"))
        assertTrue(audio.modes.isEmpty())
        assertTrue(audio.streamRequests.isEmpty())
    }

    @Test fun ignoredMuteIsReportedAndItsPossiblyDelayedRequestIsUndone() {
        val audio = Audio().apply { acceptMute = false }
        val mute = LocalOutputMute(audio)
        assertTrue(mute.mute(false).startsWith("failed: voice-call stream mute not applied"))
        assertEquals("restored voice-call stream (tap closed)", mute.restore("tap closed"))
        assertEquals(listOf(true, false), audio.streamRequests)
    }

    @Test fun appliedMuteWithLostAcknowledgementStillRestores() {
        val audio = Audio().apply { failMuteAcknowledgement = true }
        val mute = LocalOutputMute(audio)
        assertTrue(mute.mute(false).contains("IllegalStateException: mute acknowledgement lost"))
        assertTrue(audio.voiceCallMuted)
        assertEquals("restored voice-call stream (tap closed)", mute.restore("tap closed"))
        assertFalse(audio.voiceCallMuted)
    }

    @Test fun failedRestoreKeepsOwnershipUntilARetrySucceeds() {
        val audio = Audio()
        val mute = LocalOutputMute(audio)
        mute.mute(false)
        audio.failUnmute = true
        assertTrue(mute.restore("tap closed").contains("restoration pending"))
        assertTrue(audio.voiceCallMuted)
        audio.failUnmute = false
        assertEquals("restored voice-call stream (retry)", mute.restore("retry"))
        assertFalse(audio.voiceCallMuted)
        assertEquals(listOf(true, false, false), audio.streamRequests)
    }

    @Test fun ignoredUnmuteDoesNotReportSuccessOrForgetOwnership() {
        val audio = Audio()
        val mute = LocalOutputMute(audio)
        mute.mute(false)
        audio.acceptUnmute = false
        assertTrue(mute.restore("tap closed").startsWith("failed: voice-call stream still muted"))
        audio.acceptUnmute = true
        assertEquals("restored voice-call stream (retry)", mute.restore("retry"))
    }

    @Test fun userUnmutingDoesNotCauseAnotherMuteOrFalseSuccessReport() {
        val audio = Audio()
        val mute = LocalOutputMute(audio)
        mute.mute(false)
        audio.voiceCallMuted = false
        assertTrue(mute.mute(false).contains("no longer muted"))
        assertEquals(listOf(true), audio.streamRequests)
        mute.restore("tap closed")
        assertFalse(audio.voiceCallMuted)
    }

    @Test fun failedModeRequestStillWithdrawsAPossiblyAppliedRequest() {
        val audio = Audio().apply { failMode = true }
        val mute = LocalOutputMute(audio)
        assertTrue(mute.mute(true).contains("SecurityException: mode denied"))
        audio.failMode = false
        assertEquals("restored redirection mode (tap closed), mode=0", mute.restore("tap closed"))
        assertEquals(listOf(5, 0), audio.modes)
    }

    @Test fun ignoredModeRestoreRetainsThePendingRequest() {
        val audio = Audio()
        val mute = LocalOutputMute(audio)
        mute.mute(true)
        audio.acceptMode = false
        assertTrue(mute.restore("tap closed").contains("restoration pending"))
        audio.acceptMode = true
        assertEquals("restored redirection mode (retry), mode=0", mute.restore("retry"))
    }
}
