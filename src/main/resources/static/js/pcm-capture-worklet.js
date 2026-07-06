// AudioWorklet processor that batches mic input into ~100ms Float32 chunks and
// posts them to the main thread, where they are PCM16/base64-encoded and sent
// to the xAI realtime WebSocket. Kept dependency-free: worklet scope cannot
// load the VoiceCallUtils UMD module.
class PcmCaptureProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.chunkSize = 2400; // 100ms at 24kHz
        this.buffer = new Float32Array(this.chunkSize);
        this.offset = 0;
    }

    process(inputs) {
        const channel = inputs[0] && inputs[0][0];
        if (!channel) {
            return true;
        }

        let read = 0;
        while (read < channel.length) {
            const toCopy = Math.min(channel.length - read, this.chunkSize - this.offset);
            this.buffer.set(channel.subarray(read, read + toCopy), this.offset);
            this.offset += toCopy;
            read += toCopy;

            if (this.offset === this.chunkSize) {
                const chunk = this.buffer;
                this.buffer = new Float32Array(this.chunkSize);
                this.offset = 0;
                this.port.postMessage(chunk, [chunk.buffer]);
            }
        }
        return true;
    }
}

registerProcessor('pcm-capture', PcmCaptureProcessor);
