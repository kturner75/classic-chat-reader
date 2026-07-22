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
    isFeatureAvailable,
    isForwardPosition,
    escapeHtml,
    createController
} = require('../../main/resources/static/js/reading-buddy.js');

function fakeEl(initial = {}) {
    const classSet = new Set(
        String(initial.className || '')
            .split(/\s+/)
            .filter(Boolean)
    );
    if (initial.hidden !== false) {
        classSet.add('hidden');
    }
    return {
        classList: {
            add: (...names) => names.forEach(n => classSet.add(n)),
            remove: (...names) => names.forEach(n => classSet.delete(n)),
            contains: (name) => classSet.has(name),
            toggle: (name, force) => {
                if (force === true) classSet.add(name);
                else if (force === false) classSet.delete(name);
                else if (classSet.has(name)) classSet.delete(name);
                else classSet.add(name);
            }
        },
        textContent: '',
        src: '',
        alt: '',
        value: '',
        disabled: false,
        checked: false,
        innerHTML: '',
        scrollTop: 0,
        focus: () => {},
        querySelector: () => null,
        appendChild: () => {},
        addEventListener: () => {}
    };
}

function createFakeHost(overrides = {}) {
    let bookId = overrides.bookId || 'book-a';
    let pos = overrides.position || { chapterIndex: 0, paragraphIndex: 0 };
    let focused = overrides.focusedModal === true;
    let speed = overrides.speedReadingActive === true;
    const fetchImpl = overrides.fetch || (async () => ({
        ok: true,
        status: 200,
        headers: { get: () => null },
        json: async () => ({})
    }));

    const elements = {
        settingsSection: fakeEl({ hidden: true }),
        toggle: fakeEl(),
        frequency: Object.assign(fakeEl(), { value: 'rare' }),
        personaList: fakeEl(),
        talkBtn: fakeEl(),
        toast: fakeEl(),
        toastImage: fakeEl(),
        toastName: fakeEl(),
        toastPreview: fakeEl(),
        toastOpen: fakeEl(),
        toastDismiss: fakeEl(),
        toastQuiet: fakeEl(),
        chatModal: fakeEl(),
        chatPortrait: fakeEl(),
        chatName: fakeEl(),
        chatClose: fakeEl(),
        clearHistoryBtn: fakeEl(),
        chatError: fakeEl(),
        chatErrorMessage: fakeEl(),
        chatErrorRetry: fakeEl(),
        chatMessages: fakeEl(),
        chatInput: fakeEl(),
        chatSend: fakeEl()
    };

    return {
        elements,
        fetch: fetchImpl,
        getBookId: () => bookId,
        setBookId: (id) => { bookId = id; },
        getPosition: () => ({ ...pos }),
        setPosition: (next) => { pos = { ...next }; },
        getCurrentParagraphHtml: () => overrides.paragraphHtml || ('x'.repeat(80)),
        isFocusedModal: () => focused,
        setFocusedModal: (v) => { focused = v; },
        isSpeedReadingActive: () => speed,
        getClassroomContext: () => ({ enrolled: false }),
        isClassroomAllowed: () => true,
        onDiagnostic: overrides.onDiagnostic || (() => {}),
        mapChatError: () => ({ message: 'err', retryable: true }),
        escapeHtml,
        ttsPauseForModal: () => {},
        ttsResumeAfterModal: () => {},
        closeReaderSettingsPanel: () => {},
        confirm: () => true
    };
}

function delay(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

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

test('controller diagnostics explain unavailable status without logging reader text', async () => {
    const diagnostics = [];
    const host = createFakeHost({
        onDiagnostic: (event, details) => diagnostics.push({ event, details }),
        fetch: async () => ({
            ok: true,
            status: 200,
            headers: { get: () => null },
            json: async () => ({
                available: false,
                enabled: false,
                providerAvailable: true,
                chatEnabled: true
            })
        })
    });
    const controller = createController(host);

    await controller.checkAvailability();
    controller.onPageRendered();

    assert.deepEqual(diagnostics.map(item => item.event), [
        'availability',
        'proactive_skipped'
    ]);
    assert.equal(diagnostics[0].details.serverEnabled, false);
    assert.equal(diagnostics[1].details.reason, 'status_unavailable');
    assert.equal(JSON.stringify(diagnostics).includes('x'.repeat(40)), false);
});

test('quiet default is 45 minutes', () => {
    assert.equal(QUIET_MINUTES, 45);
});

test('escapeHtml fallback encodes single quotes', () => {
    assert.equal(escapeHtml("it's"), 'it&#39;s');
});

test('isForwardPosition is lexicographic on chapter then paragraph', () => {
    assert.equal(isForwardPosition({ chapterIndex: 0, paragraphIndex: 1 }, { chapterIndex: 0, paragraphIndex: 2 }), true);
    assert.equal(isForwardPosition({ chapterIndex: 0, paragraphIndex: 5 }, { chapterIndex: 1, paragraphIndex: 0 }), true);
    assert.equal(isForwardPosition({ chapterIndex: 1, paragraphIndex: 0 }, { chapterIndex: 0, paragraphIndex: 9 }), false);
    assert.equal(isForwardPosition(null, { chapterIndex: 0, paragraphIndex: 1 }), false);
});

test('page-turn position deltas count as advances (not only nextParagraph)', () => {
    const host = createFakeHost({ position: { chapterIndex: 0, paragraphIndex: 0 } });
    const controller = createController(host);
    const state = controller.getState();
    state.statusAvailable = true;
    state.prefs.enabled = true;
    state.prefs.frequency = 'chatty';
    state.prefs.personaId = 'humorist';

    // Baseline position (first render does not count as advance)
    controller.onPageRendered();
    assert.equal(state.advancesSinceSample, 0);

    // Page-turn style forward move
    host.setPosition({ chapterIndex: 0, paragraphIndex: 3 });
    controller.onPageRendered();
    assert.equal(state.advancesSinceSample, 1);

    // Same position again (renderPage + onParagraphAdvanced) does not double-count
    controller.onParagraphAdvanced();
    assert.equal(state.advancesSinceSample, 1);

    // Another page jump forward
    host.setPosition({ chapterIndex: 0, paragraphIndex: 8 });
    controller.onPageRendered();
    assert.equal(state.advancesSinceSample, 2);

    // Backward move does not count
    host.setPosition({ chapterIndex: 0, paragraphIndex: 2 });
    controller.onPageRendered();
    assert.equal(state.advancesSinceSample, 2);
});

test('stale checkRequestId after book switch does not show toast', async () => {
    let resolveFetch;
    const host = createFakeHost({
        bookId: 'book-a',
        position: { chapterIndex: 0, paragraphIndex: 0 },
        fetch: () => new Promise(resolve => {
            resolveFetch = resolve;
        })
    });
    const controller = createController(host);
    const state = controller.getState();
    state.statusAvailable = true;
    state.prefs.enabled = true;
    state.prefs.frequency = 'chatty';
    state.prefs.personaId = 'humorist';
    state.prefs.suppressUntilEpochMs = null;

    controller.onPageRendered();
    host.setPosition({ chapterIndex: 0, paragraphIndex: 1 });
    controller.onPageRendered();
    assert.equal(state.advancesSinceSample, 1);

    // Fire dwell check
    await delay(DWELL_MS + 50);
    assert.equal(typeof resolveFetch, 'function', 'check-comment fetch should have started');

    // Book switch invalidates sequence token
    host.setBookId('book-b');
    controller.onBookSwitch();
    const tokenAfterSwitch = state.checkRequestId;

    resolveFetch({
        ok: true,
        status: 200,
        headers: { get: () => null },
        json: async () => ({
            action: 'COMMENT',
            messageId: 'm1',
            text: 'Comment for previous book',
            personaId: 'humorist',
            chapterIndex: 0,
            paragraphIndex: 1
        })
    });
    await delay(30);

    assert.ok(state.checkRequestId >= tokenAfterSwitch);
    assert.equal(host.elements.toast.classList.contains('hidden'), true,
        'toast must stay hidden for stale book response');
    assert.equal(state.pendingComment, null);
});

test('disable during in-flight check prevents toast', async () => {
    let resolveFetch;
    const host = createFakeHost({
        bookId: 'book-a',
        position: { chapterIndex: 0, paragraphIndex: 0 },
        fetch: async (url, options = {}) => {
            if (options.method === 'PUT') {
                return {
                    ok: true,
                    status: 200,
                    headers: { get: () => null },
                    json: async () => ({
                        enabled: false,
                        frequency: 'chatty',
                        defaultPersonaId: 'humorist',
                        personaId: 'humorist',
                        personaSource: 'global',
                        suppressUntilEpochMs: null,
                        bookId: null
                    })
                };
            }
            return new Promise(resolve => {
                resolveFetch = resolve;
            });
        }
    });
    const controller = createController(host);
    const state = controller.getState();
    state.statusAvailable = true;
    state.prefs.enabled = true;
    state.prefs.frequency = 'chatty';
    state.prefs.personaId = 'humorist';

    controller.onPageRendered();
    host.setPosition({ chapterIndex: 0, paragraphIndex: 1 });
    controller.onPageRendered();
    await delay(DWELL_MS + 50);
    assert.equal(typeof resolveFetch, 'function');

    await controller.setEnabled(false);

    resolveFetch({
        ok: true,
        status: 200,
        headers: { get: () => null },
        json: async () => ({
            action: 'COMMENT',
            text: 'Should not toast after disable',
            personaId: 'humorist',
            chapterIndex: 0,
            paragraphIndex: 1
        })
    });
    await delay(30);

    assert.equal(host.elements.toast.classList.contains('hidden'), true);
    assert.equal(state.prefs.enabled, false);
});

test('quiet for a while sends quietMinutes 45 and suppresses immediately', async () => {
    const putBodies = [];
    let resolveCheck;
    const host = createFakeHost({
        bookId: 'book-a',
        position: { chapterIndex: 0, paragraphIndex: 0 },
        fetch: async (url, options = {}) => {
            if (options.method === 'PUT' && String(url).includes('/preferences')) {
                putBodies.push(JSON.parse(options.body || '{}'));
                return {
                    ok: true,
                    status: 200,
                    headers: { get: () => null },
                    json: async () => ({
                        enabled: true,
                        frequency: 'chatty',
                        defaultPersonaId: 'humorist',
                        personaId: 'humorist',
                        personaSource: 'global',
                        suppressUntilEpochMs: Date.now() + (QUIET_MINUTES * 60 * 1000),
                        bookId: null
                    })
                };
            }
            if (options.method === 'POST' && String(url).includes('check-comment')) {
                return new Promise(resolve => {
                    resolveCheck = resolve;
                });
            }
            return {
                ok: true,
                status: 200,
                headers: { get: () => null },
                json: async () => ({})
            };
        }
    });
    const controller = createController(host);
    const state = controller.getState();
    state.statusAvailable = true;
    state.prefs.enabled = true;
    state.prefs.frequency = 'chatty';
    state.prefs.personaId = 'humorist';

    controller.onPageRendered();
    host.setPosition({ chapterIndex: 0, paragraphIndex: 1 });
    controller.onPageRendered();
    await delay(DWELL_MS + 50);
    assert.equal(typeof resolveCheck, 'function');

    await controller.quietForAWhile();

    assert.equal(putBodies.length, 1);
    assert.deepEqual(putBodies[0], { quietMinutes: 45 });
    assert.ok((state.prefs.suppressUntilEpochMs || 0) > Date.now());
    assert.equal(controller.canPresentProactiveToast(Date.now()), false);

    resolveCheck({
        ok: true,
        status: 200,
        headers: { get: () => null },
        json: async () => ({
            action: 'COMMENT',
            text: 'Should not toast after quiet',
            personaId: 'humorist',
            chapterIndex: 0,
            paragraphIndex: 1
        })
    });
    await delay(30);
    assert.equal(host.elements.toast.classList.contains('hidden'), true);
});

test('toast open path never auto-opens modal; openChat is explicit', async () => {
    const host = createFakeHost();
    const controller = createController(host);
    const state = controller.getState();
    state.statusAvailable = true;
    state.prefs.enabled = true;
    state.prefs.personaId = 'humorist';

    // COMMENT path only uses showToast via canPresent — modal stays closed unless openChat.
    assert.equal(controller.isModalVisible(), false);
    assert.equal(state.chatOpen, false);

    // Explicit open is the only path that shows the modal (history fetch empty ok)
    await controller.openChat();
    assert.equal(state.chatOpen, true);
    assert.equal(controller.isModalVisible(), true);
});
