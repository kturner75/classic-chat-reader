const test = require('node:test');
const assert = require('node:assert/strict');

const {
    floatTo16BitPcmBase64,
    base64PcmToFloat32,
    resampleLinear,
    createTranscriptTracker
} = require('../../main/resources/static/js/voice-call-utils.js');

test('floatTo16BitPcmBase64 encodes known samples as little-endian PCM16', () => {
    const base64 = floatTo16BitPcmBase64(new Float32Array([0, 1, -1]));
    const bytes = Buffer.from(base64, 'base64');
    assert.equal(bytes.length, 6);
    assert.equal(bytes.readInt16LE(0), 0);
    assert.equal(bytes.readInt16LE(2), 0x7FFF);
    assert.equal(bytes.readInt16LE(4), -0x8000);
});

test('floatTo16BitPcmBase64 clamps out-of-range samples', () => {
    const base64 = floatTo16BitPcmBase64(new Float32Array([2.5, -3.7]));
    const bytes = Buffer.from(base64, 'base64');
    assert.equal(bytes.readInt16LE(0), 0x7FFF);
    assert.equal(bytes.readInt16LE(2), -0x8000);
});

test('PCM16 round-trip preserves samples within quantization error', () => {
    const original = new Float32Array([0, 0.25, -0.25, 0.9, -0.9, 1, -1]);
    const decoded = base64PcmToFloat32(floatTo16BitPcmBase64(original));
    assert.equal(decoded.length, original.length);
    for (let i = 0; i < original.length; i++) {
        assert.ok(Math.abs(decoded[i] - original[i]) < 1 / 0x7FFF,
            `sample ${i}: ${decoded[i]} vs ${original[i]}`);
    }
});

test('resampleLinear halves length when downsampling 48k to 24k', () => {
    const input = new Float32Array(480).fill(0.5);
    const output = resampleLinear(input, 48000, 24000);
    assert.equal(output.length, 240);
    assert.ok(Math.abs(output[100] - 0.5) < 1e-6);
});

test('resampleLinear returns input unchanged for equal rates', () => {
    const input = new Float32Array([0.1, 0.2]);
    assert.equal(resampleLinear(input, 24000, 24000), input);
});

test('transcript tracker: cumulative user updates replace the caption', () => {
    const tracker = createTranscriptTracker({ now: () => 111 });
    tracker.consume({ type: 'conversation.item.input_audio_transcription.updated', transcript: 'Tell me' });
    tracker.consume({ type: 'conversation.item.input_audio_transcription.updated', transcript: 'Tell me about the moor' });
    assert.equal(tracker.getUserPartial(), 'Tell me about the moor');

    const turns = tracker.consume({ type: 'response.created' });
    assert.deepEqual(turns, [{ role: 'user', content: 'Tell me about the moor', timestamp: 111 }]);
    assert.equal(tracker.getUserPartial(), '');
});

test('transcript tracker: assistant deltas append and finalize on done', () => {
    const tracker = createTranscriptTracker({ now: () => 222 });
    tracker.consume({ type: 'response.output_audio_transcript.delta', delta: 'It is a bleak ' });
    tracker.consume({ type: 'response.output_audio_transcript.delta', delta: 'and wondrous place.' });
    assert.equal(tracker.getAssistantPartial(), 'It is a bleak and wondrous place.');

    const turns = tracker.consume({ type: 'response.done' });
    assert.deepEqual(turns, [{ role: 'character', content: 'It is a bleak and wondrous place.', timestamp: 222 }]);
});

test('transcript tracker: transcript.done full text overrides accumulated deltas', () => {
    const tracker = createTranscriptTracker({ now: () => 1 });
    tracker.consume({ type: 'response.output_audio_transcript.delta', delta: 'Partial gar' });
    const turns = tracker.consume({
        type: 'response.output_audio_transcript.done',
        transcript: 'Corrected full sentence.'
    });
    assert.equal(turns[0].content, 'Corrected full sentence.');
});

test('transcript tracker: response start finalizes a dangling user caption', () => {
    const tracker = createTranscriptTracker({ now: () => 5 });
    tracker.consume({ type: 'conversation.item.input_audio_transcription.updated', transcript: 'Hello there' });
    const turns = tracker.consume({ type: 'response.created' });
    assert.equal(turns.length, 1);
    assert.equal(turns[0].role, 'user');
});

test('transcript tracker: flush persists both dangling partials in order', () => {
    const tracker = createTranscriptTracker({ now: () => 9 });
    tracker.consume({ type: 'conversation.item.input_audio_transcription.updated', transcript: 'Wait, what about' });
    tracker.consume({ type: 'response.output_audio_transcript.delta', delta: 'As I was saying' });
    const turns = tracker.flush();
    assert.deepEqual(turns.map(t => t.role), ['user', 'character']);
    assert.equal(tracker.getUserPartial(), '');
    assert.equal(tracker.getAssistantPartial(), '');
});

test('transcript tracker: empty or whitespace captions are not finalized', () => {
    const tracker = createTranscriptTracker();
    assert.deepEqual(tracker.consume({ type: 'input_audio_buffer.committed' }), []);
    tracker.consume({ type: 'conversation.item.input_audio_transcription.updated', transcript: '   ' });
    assert.deepEqual(tracker.consume({ type: 'response.created' }), []);
    assert.deepEqual(tracker.flush(), []);
});

test('transcript tracker: unknown events are ignored', () => {
    const tracker = createTranscriptTracker();
    assert.deepEqual(tracker.consume({ type: 'rate_limits.updated' }), []);
    assert.deepEqual(tracker.consume(null), []);
});

test('transcript tracker: input_audio_buffer.committed alone never finalizes', () => {
    const tracker = createTranscriptTracker({ now: () => 1 });
    tracker.consume({ type: 'conversation.item.input_audio_transcription.updated', transcript: 'Tell me about the moor' });

    // Committing the audio buffer is not a transcription-complete signal - xAI
    // transcribes asynchronously, so the caption may still be behind here.
    const committedTurns = tracker.consume({ type: 'input_audio_buffer.committed' });
    assert.deepEqual(committedTurns, []);
    assert.equal(tracker.getUserPartial(), 'Tell me about the moor');
    assert.equal(tracker.getFinalized().length, 0);
});

test('transcript tracker: a correction arriving after committed but before response.created is captured', () => {
    const tracker = createTranscriptTracker({ now: () => 1 });
    tracker.consume({ type: 'conversation.item.input_audio_transcription.updated', transcript: 'Tell me about the mo' });
    tracker.consume({ type: 'input_audio_buffer.committed' });
    // Transcription catches up with the full, corrected text after commit.
    tracker.consume({ type: 'conversation.item.input_audio_transcription.updated', transcript: 'Tell me about the moor' });

    const turns = tracker.consume({ type: 'response.created' });
    assert.equal(turns.length, 1);
    assert.equal(turns[0].content, 'Tell me about the moor');
    assert.equal(tracker.getFinalized().length, 1);
});

test('transcript tracker: transcription.completed events only update the caption, never finalize', () => {
    const tracker = createTranscriptTracker({ now: () => 1 });
    tracker.consume({ type: 'conversation.item.input_audio_transcription.updated', transcript: 'Hello there' });
    const completedTurns = tracker.consume({
        type: 'conversation.item.input_audio_transcription.completed',
        transcript: 'Hello there, friend'
    });
    assert.deepEqual(completedTurns, []);
    assert.equal(tracker.getUserPartial(), 'Hello there, friend');

    const turns = tracker.consume({ type: 'response.created' });
    assert.equal(turns.length, 1);
    assert.equal(turns[0].content, 'Hello there, friend');
});
