package io.github.sparkcyf.voicesipagent.privileged;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/**
 * Runs as the shell user (uid 2000) via Shizuku. Deliberately narrow: no generic command
 * execution is exposed to the app process.
 */
interface IPrivilegedService {
    /** Shizuku's UserService teardown hook; the code is fixed by Shizuku. */
    void destroy() = 16777114;

    int getUid() = 1;

    /**
     * Idempotent: appops, Doze allowlist, runtime grants, and the Telecom companion override, which
     * is added when [takeover] is true and removed otherwise. Returns health().
     */
    Bundle setup(String packageName, boolean takeover) = 2;

    /** Keys: see HealthKeys. */
    Bundle health(String packageName) = 3;

    /**
     * Opens the call-audio tap (only valid while a call is ACTIVE) and returns the app end of a
     * SOCK_SEQPACKET pair: each packet is one 20 ms frame of mono PCM16LE at sampleRate. Reads deliver
     * downlink (far end), writes are injected into the uplink. Closing either end stops the tap.
     * method: 0 = auto, 1 = official interception API, 2 = legacy VOICE_DOWNLINK/TYPE_TELEPHONY.
     * sampleRate: 8000 or 16000; failures are reported without retrying at a different rate.
     */
    ParcelFileDescriptor openCallAudio(int method, int sampleRate) = 4;

    void closeCallAudio() = 5;

    /** One-line counters for the current/last tap. */
    String audioStats() = 6;

    /**
     * Silences the handset's own earpiece/mic path for the current call (true) or restores it.
     * Only valid while the tap is open; closing the tap always restores. Returns a short report.
     */
    String setLocalOutputMuted(boolean muted) = 7;

    /** Selected call access: 0 automatic, 1 companion, 2 default dialer. Never requests a role. */
    Bundle setupCallAccess(String packageName, boolean takeover, int callAccess) = 8;
}
