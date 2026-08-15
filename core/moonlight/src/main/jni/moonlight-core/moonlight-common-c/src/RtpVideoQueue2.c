/**
 * @file RtpVideoQueue2.c
 * @brief Independent RTP/FEC queue implementation for video stream two.
 *
 * RtpVideoQueue.c calls the depacketizer by global symbol name rather than by a
 * callback stored in RTP_VIDEO_QUEUE. Reusing the primary implementation would
 * therefore reconstruct stream-two frames and submit them to stream zero. This
 * translation unit renames the queue entry points and every outbound callback
 * before compiling the upstream implementation a second time.
 */

#define RtpvInitializeQueue RtpvInitializeQueue2
#define RtpvCleanupQueue RtpvCleanupQueue2
#define RtpvGetCurrentFrameNumber RtpvGetCurrentFrameNumber2
#define RtpvAddPacket RtpvAddPacket2
#define RtpvSubmitQueuedPackets RtpvSubmitQueuedPackets2

#define StreamConfig SecondStreamConfig
#define queueRtpPacket queueRtpPacket2
#define notifyFrameLost notifyFrameLost2
#define connectionReceivedCompleteFrame connectionReceivedCompleteFrame2
#define connectionSawFrame connectionSawFrame2
#define connectionSendFrameFecStatus connectionSendFrameFecStatus2

#include "SecondStream.h"

#include "RtpVideoQueue.c"
