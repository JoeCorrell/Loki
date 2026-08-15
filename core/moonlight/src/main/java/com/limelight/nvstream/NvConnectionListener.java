package com.limelight.nvstream;

public interface NvConnectionListener {
    void stageStarting(String stage);
    void stageComplete(String stage);
    void stageFailed(String stage, int portFlags, int errorCode);
    
    void connectionStarted();
    void connectionTerminated(int errorCode);
    void connectionStatusUpdate(int connectionStatus);

    /**
     * Reports a lifecycle change for the optional second video stream.
     *
     * <p>This callback is independent of {@link #connectionTerminated(int)}. A
     * second-display failure leaves the primary video, audio, input, and control
     * streams running. Implementations compiled before dual-display support do
     * not need to implement it.</p>
     *
     * @param active {@code true} once stream one is usable, or {@code false}
     *               after it is detached or fails
     * @param errorCode zero for an intentional transition, otherwise the native
     *                  stream error
     */
    default void secondDisplayStatusChanged(boolean active, int errorCode) {
    }
    
    void displayMessage(String message);
    void displayTransientMessage(String message);

    void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor);
    void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger);

    void setHdrMode(boolean enabled, byte[] hdrMetadata);

    void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz);

    void setControllerLED(short controllerNumber, byte r, byte g, byte b);
}
