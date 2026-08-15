// JNI bridge for the second display's decoder.
//
// A second, independent set of decoder callbacks, so the second video stream
// renders into a different Surface from the first. That is the entire point of
// the feature: without it both streams would submit to one renderer and the
// second display would overwrite the first.
//
// Kept beside callbacks.c rather than inside it because the first stream's
// bridge is the code path every existing single-display session runs through,
// and none of what follows may reach it.

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include <Limelight.h>
#include "SecondStream.h"

// Owned by callbacks.c, which initialises the JNI plumbing for both bridges.
extern JavaVM* JVM;
extern jclass GlobalBridgeClass;
JNIEnv* GetThreadEnv(void);

static jmethodID BridgeDr2SetupMethod;
static jmethodID BridgeDr2StartMethod;
static jmethodID BridgeDr2StopMethod;
static jmethodID BridgeDr2CleanupMethod;
static jmethodID BridgeDr2SubmitDecodeUnitMethod;
static jmethodID BridgeClSecondDisplayStatusChangedMethod;

// This stream's own frame buffer.
//
// Separate from the first stream's DecodedFrameBuffer, and that is not an
// optimisation. Both decoder threads run concurrently, and a shared buffer
// would have one stream's frame copied into it while the other was reading it
// out -- which produces frames built from two different pictures rather than a
// clean failure.
static jbyteArray DecodedFrameBuffer2;

void initializeSecondDisplayBridge(JNIEnv* env, jclass clazz) {
    BridgeDr2SetupMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeDr2Setup", "(IIII)I");
    BridgeDr2StartMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeDr2Start", "()V");
    BridgeDr2StopMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeDr2Stop", "()V");
    BridgeDr2CleanupMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeDr2Cleanup", "()V");
    BridgeDr2SubmitDecodeUnitMethod = (*env)->GetStaticMethodID(env, clazz, "bridgeDr2SubmitDecodeUnit", "([BIIIICJJ)I");
    BridgeClSecondDisplayStatusChangedMethod = (*env)->GetStaticMethodID(
        env, clazz, "bridgeClSecondDisplayStatusChanged", "(ZI)V");
}

/**
 * @brief Clear a Java exception before returning to a long-lived native thread.
 *
 * @param env JNI environment for the current thread.
 * @return True when an exception was pending.
 */
static bool clearPendingException(JNIEnv* env) {
    if (!(*env)->ExceptionCheck(env)) {
        return false;
    }

    (*env)->ExceptionClear(env);
    return true;
}

/**
 * @brief Forward a second-stream lifecycle transition to the connection listener.
 *
 * @param active Whether stream one is usable.
 * @param errorCode Zero for intentional teardown, otherwise the stream failure.
 */
static void BridgeClSecondDisplayStatusChanged(bool active, int errorCode) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass,
                                 BridgeClSecondDisplayStatusChangedMethod,
                                 active ? JNI_TRUE : JNI_FALSE, errorCode);
    clearPendingException(env);
}

static int BridgeDr2Setup(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) {
    JNIEnv* env = GetThreadEnv();
    int err;

    err = (*env)->CallStaticIntMethod(env, GlobalBridgeClass, BridgeDr2SetupMethod,
                                      videoFormat, width, height, redrawRate);
    if (clearPendingException(env)) {
        return -1;
    }
    else if (err != 0) {
        return err;
    }

    jbyteArray localBuffer = (*env)->NewByteArray(env, 32768);
    if (localBuffer == NULL || clearPendingException(env)) {
        (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeDr2CleanupMethod);
        clearPendingException(env);
        return -1;
    }

    DecodedFrameBuffer2 = (*env)->NewGlobalRef(env, localBuffer);
    (*env)->DeleteLocalRef(env, localBuffer);
    if (DecodedFrameBuffer2 == NULL || clearPendingException(env)) {
        (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeDr2CleanupMethod);
        clearPendingException(env);
        return -1;
    }

    return 0;
}

static void BridgeDr2Start(void) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeDr2StartMethod);
    clearPendingException(env);
}

static void BridgeDr2Stop(void) {
    JNIEnv* env = GetThreadEnv();

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeDr2StopMethod);
    clearPendingException(env);
}

static void BridgeDr2Cleanup(void) {
    JNIEnv* env = GetThreadEnv();

    if (DecodedFrameBuffer2 != NULL) {
        (*env)->DeleteGlobalRef(env, DecodedFrameBuffer2);
        DecodedFrameBuffer2 = NULL;
    }

    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, BridgeDr2CleanupMethod);
    clearPendingException(env);
}

static int BridgeDr2SubmitDecodeUnit(PDECODE_UNIT decodeUnit) {
    JNIEnv* env = GetThreadEnv();
    jint ret;

    if (DecodedFrameBuffer2 == NULL) {
        return DR_NEED_IDR;
    }

    // Grow the buffer to fit the frame if needed. The initial 32K covers an
    // ordinary desktop frame; a full-screen change on the second display can
    // exceed it.
    jsize currentLength = (*env)->GetArrayLength(env, DecodedFrameBuffer2);
    if (currentLength < decodeUnit->fullLength) {
        jbyteArray localBuffer;

        (*env)->DeleteGlobalRef(env, DecodedFrameBuffer2);
        DecodedFrameBuffer2 = NULL;
        localBuffer = (*env)->NewByteArray(env, decodeUnit->fullLength);
        if (localBuffer == NULL || clearPendingException(env)) {
            return DR_NEED_IDR;
        }

        DecodedFrameBuffer2 = (*env)->NewGlobalRef(env, localBuffer);
        (*env)->DeleteLocalRef(env, localBuffer);
        if (DecodedFrameBuffer2 == NULL || clearPendingException(env)) {
            return DR_NEED_IDR;
        }
    }

    // Parameter sets go in on their own, picture data is accumulated.
    //
    // Identical to the first bridge's handling, and it has to be: SPS and PPS
    // NALUs are configuration rather than a frame, and MediaCodec must receive
    // them as separate buffers. Flattening everything into one submission is the
    // obvious simplification and produces a decoder that never starts.
    PLENTRY currentEntry = decodeUnit->bufferList;
    int offset = 0;
    while (currentEntry != NULL) {
        if (currentEntry->bufferType != BUFFER_TYPE_PICDATA) {
            // From the start of the buffer each time, since this is a separate
            // invocation of the decoder.
            (*env)->SetByteArrayRegion(env, DecodedFrameBuffer2, 0, currentEntry->length, (jbyte*)currentEntry->data);
            if (clearPendingException(env)) {
                return DR_NEED_IDR;
            }

            ret = (*env)->CallStaticIntMethod(env, GlobalBridgeClass, BridgeDr2SubmitDecodeUnitMethod,
                                              DecodedFrameBuffer2, currentEntry->length, currentEntry->bufferType,
                                              decodeUnit->frameNumber, decodeUnit->frameType,
                                              (jchar)decodeUnit->frameHostProcessingLatency,
                                              (jlong)decodeUnit->receiveTimeMs, (jlong)decodeUnit->enqueueTimeMs);
            if (clearPendingException(env)) {
                return DR_NEED_IDR;
            }
            else if (ret != DR_OK) {
                return ret;
            }
        }
        else {
            (*env)->SetByteArrayRegion(env, DecodedFrameBuffer2, offset, currentEntry->length, (jbyte*)currentEntry->data);
            if (clearPendingException(env)) {
                return DR_NEED_IDR;
            }
            offset += currentEntry->length;
        }

        currentEntry = currentEntry->next;
    }

    ret = (*env)->CallStaticIntMethod(env, GlobalBridgeClass, BridgeDr2SubmitDecodeUnitMethod,
                                      DecodedFrameBuffer2, offset, BUFFER_TYPE_PICDATA,
                                      decodeUnit->frameNumber, decodeUnit->frameType,
                                      (jchar)decodeUnit->frameHostProcessingLatency,
                                      (jlong)decodeUnit->receiveTimeMs, (jlong)decodeUnit->enqueueTimeMs);
    if (clearPendingException(env)) {
        return DR_NEED_IDR;
    }

    return ret;
}

static DECODER_RENDERER_CALLBACKS BridgeVideoRendererCallbacks2 = {
        .setup = BridgeDr2Setup,
        .start = BridgeDr2Start,
        .stop = BridgeDr2Stop,
        .cleanup = BridgeDr2Cleanup,
        .submitDecodeUnit = BridgeDr2SubmitDecodeUnit,
};

// Arms the second display before the connection is started.
//
// Called from Java when the client has two panels and the host advertised
// MaxVideoStreams >= 2. Whether a second stream actually runs is decided later,
// during RTSP -- see the SETUP handling in RtspConnection.c.
JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_enableSecondDisplay(JNIEnv *env, jclass clazz,
                                                               jint width, jint height,
                                                               jint fps, jint bitrateKbps,
                                                               jint videoCapabilities) {
    BridgeVideoRendererCallbacks2.capabilities = videoCapabilities;
    LiInitializeSecondDisplay(width, height, fps, bitrateKbps,
                              &BridgeVideoRendererCallbacks2,
                              BridgeClSecondDisplayStatusChanged);
}

/** Stops stream one without disturbing the primary connection. */
JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_detachSecondDisplay(JNIEnv *env, jclass clazz) {
    LiStopSecondDisplayStream();
}

// Whether a second stream was negotiated and is still running.
//
// The client asks after the connection is up: the host may have declined, and
// the panel has to show the trackpad instead of a black surface waiting for
// frames that will never arrive.
JNIEXPORT jboolean JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_isSecondDisplayActive(JNIEnv *env, jclass clazz) {
    return LiIsSecondDisplayActive() ? JNI_TRUE : JNI_FALSE;
}
