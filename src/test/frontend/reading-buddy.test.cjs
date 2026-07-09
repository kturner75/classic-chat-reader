const test = require('node:test');
const assert = require('node:assert/strict');

const {
    MIN_PARAGRAPH_CHARS,
    DWELL_MS,
    QUIET_MINUTES,
    FREQUENCY_SAMPLE_INTERVAL,
    normalizeFrequency,
    sampleIntervalFor,
    clientFrequencyAllows,
    stripHtml,
    paragraphTextLength,
    previewText,
    formatHiddenPlaceholder,
    renderHistoryMessagesHtml,
    evaluateClientGates,
    isFeatureAvailable
} = require('../../main/resources/static/js/reading-buddy.js');

test('normalizeFrequency defaults to rare', () => {
    assert.equal(normalizeFrequency('CHATTY'), 'chatty');
    assert.equal(normalizeFrequency('nope'), 'rare');
    assert.equal(normalizeFrequency(null), 'rare');
});

test('sample intervals match design (rare 5 / occasional 3 / chatty 1)', () => {
    assert.equal(sampleIntervalFor('rare'), 5);
    assert.equal(sampleIntervalFor('occasional'), 3);
    assert.equal(sampleIntervalFor('chatty'), 1);
    assert.equal(FREQUENCY_SAMPLE_INTERVAL.rare, 5);
});

test('clientFrequencyAllows samples every N advances', () => {
    assert.equal(clientFrequencyAllows('rare', 5), true);
    assert.equal(clientFrequencyAllows('rare', 4), false);
    assert.equal(clientFrequencyAllows('rare', 10), true);
    assert.equal(clientFrequencyAllows('chatty', 1), true);
    assert.equal(clientFrequencyAllows('chatty', 0), false);
});

test('stripHtml and paragraph length threshold', () => {
    const html = '<p>Hello <em>world</em> &amp; friends</p>';
    assert.equal(stripHtml(html), 'Hello world & friends');
    assert.ok(paragraphTextLength(html) < MIN_PARAGRAPH_CHARS);
    assert.ok(paragraphTextLength('x'.repeat(40)) >= MIN_PARAGRAPH_CHARS);
});

test('previewText truncates with ellipsis', () => {
    const long = 'a'.repeat(200);
    const preview = previewText(long, 40);
    assert.ok(preview.length <= 40);
    assert.ok(preview.endsWith('…'));
});

test('formatHiddenPlaceholder is 1-based chapter label', () => {
    assert.equal(formatHiddenPlaceholder(2, 5), 'Hidden until you re-read past Ch. 3');
});

test('renderHistoryMessagesHtml collapses future-relative messages', () => {
    const html = renderHistoryMessagesHtml([
        { role: 'user', content: 'Hi', visibleAtPosition: true },
        { role: 'buddy', content: 'Spoiler!', visibleAtPosition: false, chapterIndex: 9, paragraphIndex: 0 },
        { role: 'buddy', content: 'Ok', visibleAtPosition: true }
    ]);
    assert.match(html, /chat-message user/);
    assert.match(html, /chat-message buddy-hidden/);
    assert.match(html, /Hidden until you re-read past Ch\. 10/);
    assert.doesNotMatch(html, /Spoiler!/);
    assert.match(html, />Ok</);
});

test('evaluateClientGates requires all gates', () => {
    const base = {
        statusAvailable: true,
        prefsEnabled: true,
        personaId: 'humorist',
        focusedModal: false,
        speedReadingActive: false,
        cooldownActive: false,
        frequency: 'chatty',
        advancesSinceSample: 1,
        paragraphLength: 80,
        dwellMs: DWELL_MS,
        suppressUntilEpochMs: null,
        nowMs: 1_000_000,
        renderSettled: true,
        classroomAllowed: true
    };
    assert.equal(evaluateClientGates(base).ok, true);
    assert.equal(evaluateClientGates({ ...base, statusAvailable: false }).reason, 'status_unavailable');
    assert.equal(evaluateClientGates({ ...base, prefsEnabled: false }).reason, 'prefs_disabled');
    assert.equal(evaluateClientGates({ ...base, focusedModal: true }).reason, 'focused_modal');
    assert.equal(evaluateClientGates({ ...base, speedReadingActive: true }).reason, 'speed_reading');
    assert.equal(evaluateClientGates({ ...base, dwellMs: 100 }).reason, 'dwell');
    assert.equal(evaluateClientGates({ ...base, paragraphLength: 10 }).reason, 'paragraph_short');
    assert.equal(evaluateClientGates({ ...base, suppressUntilEpochMs: 2_000_000 }).reason, 'suppressed');
    assert.equal(evaluateClientGates({ ...base, classroomAllowed: false }).reason, 'classroom');
    assert.equal(evaluateClientGates({ ...base, frequency: 'rare', advancesSinceSample: 2 }).reason, 'frequency_sample');
});

test('isFeatureAvailable honors status and classroom kill-switch', () => {
    assert.equal(isFeatureAvailable({ available: true }, { enrolled: false }), true);
    assert.equal(isFeatureAvailable({ available: false }, { enrolled: false }), false);
    assert.equal(isFeatureAvailable(
        { available: true },
        { enrolled: true, features: { chatEnabled: true, readingBuddyEnabled: true } }
    ), true);
    assert.equal(isFeatureAvailable(
        { available: true },
        { enrolled: true, features: { chatEnabled: false, readingBuddyEnabled: true } }
    ), false);
    assert.equal(isFeatureAvailable(
        { available: true },
        { enrolled: true, features: { chatEnabled: true, readingBuddyEnabled: false } }
    ), false);
});

test('quiet default is 45 minutes', () => {
    assert.equal(QUIET_MINUTES, 45);
});
