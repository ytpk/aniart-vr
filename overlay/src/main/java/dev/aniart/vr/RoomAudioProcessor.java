package dev.aniart.vr;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;

final class RoomAudioProcessor extends BaseAudioProcessor {
    private final DoubleSupplier yaw;
    private final IntConsumer reportInput;
    private RoomAudio room;
    private int roomRate;

    RoomAudioProcessor(DoubleSupplier yaw, IntConsumer reportInput) {
        this.yaw = yaw;
        this.reportInput = reportInput;
    }

    @Override protected AudioFormat onConfigure(AudioFormat format) {
        int count = format.channelCount;
        boolean supported = format.encoding == C.ENCODING_PCM_16BIT
                && (count == 1 || count == 2 || count == 6 || count == 8)
                && format.sampleRate >= 8000 && format.sampleRate <= 192000;
        reportInput.accept(supported ? count : -count);
        return supported ? new AudioFormat(format.sampleRate, 2, C.ENCODING_PCM_16BIT) : AudioFormat.NOT_SET;
    }

    @Override protected void onFlush() {
        if (inputAudioFormat.sampleRate > 0 && outputAudioFormat.channelCount == 2) {
            int rate = inputAudioFormat.sampleRate;
            if (room != null && roomRate == rate) room.reset();
            else {
                room = new RoomAudio(rate);
                roomRate = rate;
            }
        } else {
            room = null;
        }
    }

    @Override protected void onReset() { room = null; }

    @Override public void queueInput(ByteBuffer input) {
        int channels = inputAudioFormat.channelCount;
        int frames = input.remaining() / inputAudioFormat.bytesPerFrame;
        var output = replaceOutputBuffer(frames * 4);
        input.order(ByteOrder.LITTLE_ENDIAN);
        output.order(ByteOrder.LITTLE_ENDIAN);
        room.updatePose((float) yaw.getAsDouble());
        for (int i = 0; i < frames; ++i) room.renderFrame(input, output, channels);
        output.flip();
    }
}
