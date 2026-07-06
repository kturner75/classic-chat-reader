(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
        return;
    }
    root.VoiceCallUtils = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    // Converts Float32 [-1,1] samples to base64-encoded little-endian PCM16,
    // the wire format for xAI realtime input_audio_buffer.append events.
    function floatTo16BitPcmBase64(float32Array) {
        const bytes = new Uint8Array(float32Array.length * 2);
        const view = new DataView(bytes.buffer);
        for (let i = 0; i < float32Array.length; i++) {
            const clamped = Math.max(-1, Math.min(1, float32Array[i]));
            view.setInt16(i * 2, clamped < 0 ? clamped * 0x8000 : clamped * 0x7FFF, true);
        }
        return bytesToBase64(bytes);
    }

    // Decodes base64 little-endian PCM16 (response.output_audio.delta payload)
    // into Float32 samples for Web Audio playback.
    function base64PcmToFloat32(base64) {
        const bytes = base64ToBytes(base64);
        const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        const sampleCount = Math.floor(bytes.byteLength / 2);
        const floats = new Float32Array(sampleCount);
        for (let i = 0; i < sampleCount; i++) {
            const sample = view.getInt16(i * 2, true);
            floats[i] = sample < 0 ? sample / 0x8000 : sample / 0x7FFF;
        }
        return floats;
    }

    function bytesToBase64(bytes) {
        let binary = '';
        const chunkSize = 0x8000;
        for (let i = 0; i < bytes.length; i += chunkSize) {
            binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
        }
        return (typeof btoa === 'function' ? btoa : b => Buffer.from(b, 'binary').toString('base64'))(binary);
    }

    function base64ToBytes(base64) {
        if (typeof atob === 'function') {
            const binary = atob(base64);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
            }
            return bytes;
        }
        return new Uint8Array(Buffer.from(base64, 'base64'));
    }

    // Simple linear-interpolation resampler for browsers whose AudioContext
    // refuses to run at the xAI wire rate (24 kHz).
    function resampleLinear(float32Array, fromRate, toRate) {
        if (fromRate === toRate || float32Array.length === 0) {
            return float32Array;
        }
        const ratio = fromRate / toRate;
        const outLength = Math.max(1, Math.round(float32Array.length / ratio));
        const out = new Float32Array(outLength);
        for (let i = 0; i < outLength; i++) {
            const pos = i * ratio;
            const left = Math.floor(pos);
            const right = Math.min(left + 1, float32Array.length - 1);
            const frac = pos - left;
            out[i] = float32Array[left] * (1 - frac) + float32Array[right] * frac;
        }
        return out;
    }

    // Tracks live transcription events from the realtime session and emits
    // finalized {role, content, timestamp} turns compatible with the text-chat
    // history ('user' / 'character' roles).
    //
    // xAI semantics: user transcript updates are CUMULATIVE (each event replaces
    // the whole user caption, possibly correcting earlier text); assistant
    // transcript deltas are appended.
    function createTranscriptTracker(options = {}) {
        const now = options.now || (() => Date.now());
        let userPartial = '';
        let assistantPartial = '';
        const finalized = [];

        function finalizeUser() {
            const content = userPartial.trim();
            userPartial = '';
            if (content) {
                finalized.push({ role: 'user', content, timestamp: now() });
                return finalized[finalized.length - 1];
            }
            return null;
        }

        function finalizeAssistant() {
            const content = assistantPartial.trim();
            assistantPartial = '';
            if (content) {
                finalized.push({ role: 'character', content, timestamp: now() });
                return finalized[finalized.length - 1];
            }
            return null;
        }

        // Consumes one realtime event; returns an array of turns finalized by it.
        function consume(event) {
            const type = event && event.type;
            if (!type) {
                return [];
            }

            // Only updates the running caption here; finalization is anchored solely
            // to input_audio_buffer.committed / response.created below so a turn is
            // never finalized twice if both a transcription-completed and a
            // buffer-committed event arrive for the same utterance.
            if (type === 'conversation.item.input_audio_transcription.updated'
                    || type === 'conversation.item.input_audio_transcription.completed') {
                const transcript = event.transcript
                    ?? event.item?.content?.[0]?.transcript
                    ?? '';
                if (transcript) {
                    userPartial = transcript;
                }
                return [];
            }

            if (type === 'response.output_audio_transcript.delta'
                    || type === 'response.audio_transcript.delta') {
                assistantPartial += event.delta || '';
                return [];
            }

            if (type === 'input_audio_buffer.committed') {
                // The user's turn ended (server VAD); their caption is final.
                const turn = finalizeUser();
                return turn ? [turn] : [];
            }

            if (type === 'response.created') {
                // A response is starting: any dangling user caption is final.
                const turn = finalizeUser();
                return turn ? [turn] : [];
            }

            if (type === 'response.output_audio_transcript.done'
                    || type === 'response.audio_transcript.done') {
                if (typeof event.transcript === 'string' && event.transcript.trim()) {
                    assistantPartial = event.transcript;
                }
                const turn = finalizeAssistant();
                return turn ? [turn] : [];
            }

            if (type === 'response.done') {
                const turn = finalizeAssistant();
                return turn ? [turn] : [];
            }

            return [];
        }

        // Flushes both partial captions (e.g. when the call ends mid-turn).
        function flush() {
            const turns = [];
            const user = finalizeUser();
            if (user) turns.push(user);
            const assistant = finalizeAssistant();
            if (assistant) turns.push(assistant);
            return turns;
        }

        return {
            consume,
            flush,
            getUserPartial: () => userPartial,
            getAssistantPartial: () => assistantPartial,
            getFinalized: () => finalized.slice()
        };
    }

    return {
        floatTo16BitPcmBase64,
        base64PcmToFloat32,
        resampleLinear,
        createTranscriptTracker
    };
});
