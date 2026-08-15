// The second display's video stream.
//
// This file is VideoStream.c compiled a second time with its externally visible
// symbols renamed. See SecondStream.h for why, and VideoDepacketizer2.c for the
// same treatment of the depacketizer.
//
// Nothing may be added here except renames. Any actual change belongs in
// VideoStream.c, where it applies to both streams.

// --- Externally visible functions ---------------------------------------
#define initializeVideoStream   initializeVideoStream2
#define destroyVideoStream      destroyVideoStream2
#define startVideoStream        startVideoStream2
#define stopVideoStream         stopVideoStream2
#define notifyKeyFrameReceived  notifyKeyFrameReceived2
#define readFirstFrame          readFirstFrame2

// --- The depacketizer this copy drives -----------------------------------
// VideoStream.c calls into the depacketizer by name, so without these the
// second stream's receive thread would feed the *first* stream's depacketizer
// and both displays would show the same picture, interleaved and broken.
#define initializeVideoDepacketizer   initializeVideoDepacketizer2
#define stopVideoDepacketizer         stopVideoDepacketizer2
#define destroyVideoDepacketizer      destroyVideoDepacketizer2
#define LiWaitForNextVideoFrame       LiWaitForNextVideoFrame2
#define LiCompleteVideoFrame          LiCompleteVideoFrame2

// --- The RTP/FEC queue this copy drives -------------------------------
// RtpVideoQueue.c dispatches completed frames through global depacketizer
// symbols, so sharing its implementation would feed stream two into stream zero.
#define RtpvInitializeQueue           RtpvInitializeQueue2
#define RtpvCleanupQueue              RtpvCleanupQueue2
#define RtpvGetCurrentFrameNumber     RtpvGetCurrentFrameNumber2
#define RtpvAddPacket                 RtpvAddPacket2
#define RtpvSubmitQueuedPackets       RtpvSubmitQueuedPackets2

// --- Shared globals replaced by per-stream ones --------------------------
#define StreamConfig       SecondStreamConfig
#define VideoCallbacks     VideoCallbacks2
#define VideoPortNumber    VideoPortNumber2
#define VideoPingPayload   VideoPingPayload2

// Errors here must not end the session; see SecondStreamListenerCallbacks.
#define ListenerCallbacks  SecondStreamListenerCallbacks

#include "SecondStream.h"

#include "VideoStream.c"
