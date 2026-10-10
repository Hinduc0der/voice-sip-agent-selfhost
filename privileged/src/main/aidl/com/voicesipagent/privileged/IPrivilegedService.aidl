package com.voicesipagent.privileged;

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

    /** Idempotent: appops, Telecom companion override, Doze allowlist, runtime grants. Returns health(). */
    Bundle setup(String packageName) = 2;

    /** Keys: see HealthKeys. */
    Bundle health(String packageName) = 3;

    /**
     * Opens the call-audio tap (only valid while a call is ACTIVE) and returns the app end of a
     * SOCK_SEQPACKET pair: each packet is one 20 ms frame of 8 kHz mono PCM16LE. Reads deliver
     * downlink (far end), writes are injected into the uplink. Closing either end stops the tap.
     * method: 0 = auto, 1 = official interception API, 2 = legacy VOICE_DOWNLINK/TYPE_TELEPHONY.
     */
    ParcelFileDescriptor openCallAudio(int method) = 4;

    void closeCallAudio() = 5;

    /** One-line counters for the current/last tap. */
    String audioStats() = 6;

    /** Turns the local earpiece/speaker voice-call volume down (true) or restores it. */
    String setLocalOutputMuted(boolean muted) = 7;
}
