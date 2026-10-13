/* Voice gateway Java binding profile for PJSIP 2.17.
 * Keep upstream pjsua2.i and exclude feature groups rather than maintaining an
 * app-method whitelist. Restore a group here if the app starts using it.
 * Insert after upstream director features and before PJSUA2 headers/templates.
 */
#ifdef SWIGJAVA

/* Endpoint is used directly; restore its director if Java/Kotlin subclasses it.
 * Keep Account/Call/AudioMediaPort/LogWriter directors and their default callbacks,
 * including callbacks the app does not override.
 */
%feature("director", "0") pj::Endpoint;

/* Configuration is populated in Kotlin; no Java JSON/persistence API is used.
 * PersistentObject remains as the base of configuration classes. C++ virtual
 * serialization methods still exist; these directives only remove JNI wrappers.
 */
%ignore readObject;
%ignore writeObject;
%ignore pj::ContainerNode;
%ignore pj::PersistentDocument;
%ignore pj::JsonDocument;

/* Buddy management is unused. Retain Account's registration/call callbacks. */
%ignore pj::Buddy;
%ignore pj::BuddyConfig;
%ignore pj::BuddyInfo;
%ignore pj::OnBuddyEvSubStateParam;
%ignore pj::FindBuddyMatch;
%ignore std::vector<pj::Buddy*>;
%ignore std::vector<pj::Buddy>;
%ignore pj::Account::enumBuddies;
%ignore pj::Account::enumBuddies2;
%ignore pj::Account::findBuddy;
%ignore pj::Account::findBuddy2;

/* Audio goes through AudioMediaPort. Keep AudDevManager (setNullDev), codec
 * controls, AudioMedia, MediaFrame and the bulk ByteVector copy extensions.
 * ToneGenerator is unrelated to Call::sendDtmf, which remains available.
 */
%ignore pj::AudioMediaPlayer;
%ignore pj::AudioMediaPlayerInfo;
%ignore pj::AudioMediaRecorder;
%ignore pj::AudioMediaAiPort;
%ignore pj::AiMediaEvent;
%ignore pj::AiMediaPortParam;
%ignore pj::ToneGenerator;
%ignore pj::ToneDesc;
%ignore pj::ToneDigit;
%ignore pj::ToneDigitMapDigit;
%ignore std::vector<pj::ToneDesc>;
%ignore std::vector<pj::ToneDigit>;
%ignore std::vector<pj::ToneDigitMapDigit>;

/* Video is disabled in the native configuration too. Remove its Java entry
 * points, configuration and containers, leaving audio call/stream statistics.
 */
%ignore pj::MediaFormatVideo;
%ignore pj::MediaCoordinate;
%ignore pj::MediaSize;
%ignore pj::VidConfPortInfo;
%ignore pj::VideoMediaTransmitParam;
%ignore pj::VideoMedia;
%ignore pj::WindowHandle;
%ignore pj::VideoWindowHandle;
%ignore pj::VideoWindowInfo;
%ignore pj::VideoWindow;
%ignore pj::VideoPreviewOpParam;
%ignore pj::VideoPreview;
%ignore pj::VideoDevInfo;
%ignore pj::VideoSwitchParam;
%ignore pj::VidDevManager;
%ignore pj::VideoPlayer;
%ignore pj::VideoRecorder;
%ignore pj::VidCodecParam;
%ignore pj::AccountVideoConfig;
%ignore pj::AccountConfig::videoConfig;
%ignore pj::CallMediaInfo::videoWindow;
%ignore pj::StreamInfo::vidCodecParam;
%ignore pj::CallVidSetStreamParam;
%ignore pj::Call::getEncodingVideoMedia;
%ignore pj::Call::getDecodingVideoMedia;
%ignore pj::Call::vidGetStreamIdx;
%ignore pj::Call::vidStreamIsRunning;
%ignore pj::Call::vidSetStream;
%ignore pj::Call::vidStreamModifyCodecParam;
%ignore pj::Endpoint::mediaEnumVidPorts;
%ignore pj::Endpoint::vidDevManager;
%ignore pj::Endpoint::videoCodecEnum;
%ignore pj::Endpoint::videoCodecEnum2;
%ignore pj::Endpoint::videoCodecSetPriority;
%ignore pj::Endpoint::getVideoCodecParam;
%ignore pj::Endpoint::setVideoCodecParam;
%ignore pj::Endpoint::resetVideoCodecParam;
%ignore pj::Endpoint::onVideoMediaOpCompleted;
%ignore pj::VideoMediaAddInfo;
%ignore pj::VideoMediaRemoveInfo;
%ignore pj::VideoMediaConnectInfo;
%ignore pj::VideoMediaDisconnectInfo;
%ignore pj::VideoMediaUpdateInfo;
%ignore pj::VideoMediaOpParam;
%ignore pj::OnVideoMediaOpCompletedParam;
%ignore std::vector<pj::VideoMedia>;
%ignore std::vector<pj::VideoDevInfo*>;
%ignore std::vector<pj::VideoDevInfo>;
%ignore std::vector<pj::MediaFormatVideo>;

#endif
