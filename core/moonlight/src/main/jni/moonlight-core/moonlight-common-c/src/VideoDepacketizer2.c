// The second display's depacketizer.
//
// This file is VideoDepacketizer.c compiled a second time with its externally
// visible symbols renamed. See SecondStream.h for why.
//
// Everything the depacketizer keeps between packets -- the NAL chain, the frame
// numbers, the IDR wait state, the decode unit queue, all 39 file statics -- is
// per translation unit, so this copy has its own and cannot disturb stream 0's.
// Only the names below cross the linker, and only those need to differ.
//
// Nothing may be added here except renames. Any actual change belongs in
// VideoDepacketizer.c, where it applies to both streams; a fix made here alone
// would silently apply to the second display and not the first.

// --- Externally visible functions ---------------------------------------
// Must list every non-static function in VideoDepacketizer.c. Missing one is a
// duplicate symbol at link time, which is at least a loud failure.
#define initializeVideoDepacketizer   initializeVideoDepacketizer2
#define stopVideoDepacketizer         stopVideoDepacketizer2
#define destroyVideoDepacketizer      destroyVideoDepacketizer2
#define validateDecodeUnitForPlayback validateDecodeUnitForPlayback2
#define requestDecoderRefresh         requestDecoderRefresh2
#define notifyFrameLost               notifyFrameLost2
#define queueRtpPacket                queueRtpPacket2
#define notifyKeyFrameReceived        notifyKeyFrameReceived2

// The public frame-pull API. Renamed for the same reason, though only the
// pipeline's own decoder thread uses these for the second stream -- a client
// pulling frames itself does so for stream 0.
#define LiWaitForNextVideoFrame       LiWaitForNextVideoFrame2
#define LiPollNextVideoFrame          LiPollNextVideoFrame2
#define LiPeekNextVideoFrame          LiPeekNextVideoFrame2
#define LiWakeWaitForVideoFrame       LiWakeWaitForVideoFrame2
#define LiCompleteVideoFrame          LiCompleteVideoFrame2
#define LiGetPendingVideoFrames       LiGetPendingVideoFrames2

// --- Shared globals replaced by per-stream ones --------------------------
// The second display has its own dimensions and its own decoder.
#define StreamConfig                  SecondStreamConfig
#define VideoCallbacks                VideoCallbacks2

// Loss and refresh must be reported against this stream's index, or a dropped
// packet on one screen forces an IDR on the other.
#define LiRequestIdrFrame             LiRequestIdrFrameForStream2
#define connectionDetectedFrameLoss   connectionDetectedFrameLoss2
#define connectionReceivedCompleteFrame connectionReceivedCompleteFrame2
#define isReferenceFrameInvalidationEnabled isReferenceFrameInvalidationEnabledForStream2

#include "SecondStream.h"

#include "VideoDepacketizer.c"
