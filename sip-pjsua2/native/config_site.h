/* pjproject build profile for the cellular <-> SIP gateway (see build-pjsua2.sh). */

/* Audio is exchanged with the call tap through an AudioMediaPort; no sound device backend at all,
 * so pjsua never opens Android audio or changes the audio mode. */
#define PJMEDIA_AUDIO_DEV_HAS_ANDROID_JNI   0
#define PJMEDIA_AUDIO_DEV_HAS_OPENSL        0
#define PJMEDIA_AUDIO_DEV_HAS_OBOE          0
#define PJMEDIA_AUDIO_DEV_HAS_PORTAUDIO     0
#define PJMEDIA_HAS_VIDEO                   0

#define PJ_HAS_IPV6                         1
#define PJMEDIA_HAS_SRTP                    1

/* Opus keeps its mandatory 48 kHz RTP clock, but encodes/decodes 16 kHz mono PCM.
 * Keep resampling available between the 16 kHz cellular bridge and G.711. */
#define PJMEDIA_CODEC_OPUS_DEFAULT_SAMPLE_RATE 16000
#define PJMEDIA_CODEC_OPUS_DEFAULT_BIT_RATE    20000

/* Gateway: few simultaneous calls, small footprint. */
#define PJSUA_MAX_CALLS                     4
#define PJSUA_MAX_ACC                       4
#define PJSUA_MAX_PLAYERS                   0
#define PJSUA_MAX_RECORDERS                 0
#define PJMEDIA_CONF_USE_SWITCH_BOARD       0
