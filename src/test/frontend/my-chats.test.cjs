const test = require('node:test');
const assert = require('node:assert/strict');

const {
    buildListRequestUrl,
    buildFilterOptions,
    buildOpenBookUrl,
    canStartVoiceCall,
    canContinueChat,
    createInitialListState,
    getListViewModel,
    reduceListState,
    renderConversationPortrait,
    safeResumeUrl,
    safeSessionUrl,
    toVoiceCallHistory,
    toVoiceCallPersistenceTurns,
    toReaderParagraphParam
} = require('../../main/resources/static/js/my-chats.js');

function chat(overrides = {}) {
    return {
        sessionId: 'session-1',
        character: { id: 'character-1', name: 'Elizabeth Bennet', portraitUrl: null },
        book: { id: 'book-1', title: 'Pride and Prejudice', author: 'Jane Austen' },
        previewText: 'First impressions can be misleading.',
        lastMessageAt: '2026-07-21T23:14:35Z',
        resume: { available: true, url: '/my-chats?session=session-1', unavailableReason: null },
        ...overrides
    };
}

test('initial state exposes an accessible loading view without an empty-state flash', () => {
    const view = getListViewModel(createInitialListState());

    assert.equal(view.kind, 'loading');
    assert.equal(view.skeletonCount, 6);
    assert.equal(view.announcement, 'Loading My Chats.');
});

test('loaded chats produce the primary results view and preserve the server resume URL', () => {
    const item = chat();
    const state = reduceListState(createInitialListState(), {
        type: 'LOAD_SUCCEEDED',
        payload: { items: [item], page: { hasMore: true, nextCursor: 'next-page' } }
    });
    const view = getListViewModel(state);

    assert.equal(view.kind, 'results');
    assert.equal(view.items.length, 1);
    assert.equal(view.hasMore, true);
    assert.equal(safeResumeUrl(item), '/my-chats?session=session-1');
});

test('an account with no sessions uses the empty-account state', () => {
    const state = reduceListState(createInitialListState(), {
        type: 'LOAD_SUCCEEDED',
        payload: { items: [], page: { hasMore: false, nextCursor: null } }
    });

    assert.equal(getListViewModel(state).kind, 'empty');
});

test('an initial request failure produces a retryable error state', () => {
    const state = reduceListState(createInitialListState(), {
        type: 'LOAD_FAILED',
        message: 'My Chats couldn’t load.'
    });
    const view = getListViewModel(state);

    assert.equal(view.kind, 'error');
    assert.equal(view.canRetry, true);
    assert.match(view.announcement, /couldn’t load/);
});

test('empty filtered results are distinguished from an empty account', () => {
    let state = reduceListState(createInitialListState(), {
        type: 'FILTERS_CHANGED',
        filters: { q: 'darcy' }
    });
    state = reduceListState(state, {
        type: 'LOAD_SUCCEEDED',
        payload: { items: [], page: { hasMore: false, nextCursor: null } }
    });

    assert.equal(getListViewModel(state).kind, 'no-results');
});

test('load-more failure keeps existing rows and exposes inline retry', () => {
    let state = reduceListState(createInitialListState(), {
        type: 'LOAD_SUCCEEDED',
        payload: { items: [chat()], page: { hasMore: true, nextCursor: 'next-page' } }
    });
    state = reduceListState(state, { type: 'LOAD_MORE_STARTED' });
    state = reduceListState(state, { type: 'LOAD_FAILED', message: 'Could not load more.' });
    const view = getListViewModel(state);

    assert.equal(view.kind, 'results');
    assert.equal(view.items.length, 1);
    assert.equal(view.loadMoreError, 'Could not load more.');
});

test('request URL includes normalized filters and resets cursor when filters change', () => {
    const url = buildListRequestUrl({
        filters: {
            q: '  elizabeth  bennet ',
            bookId: 'book-1',
            characterId: 'character-1',
            activeAfter: '2026-07-01',
            activeBefore: '2026-08-01'
        },
        cursor: null,
        limit: 20
    });

    const parsed = new URL(url, 'https://classicchatreader.test');
    assert.equal(parsed.pathname, '/api/account/chats');
    assert.equal(parsed.searchParams.get('limit'), '20');
    assert.equal(parsed.searchParams.get('sort'), 'recent');
    assert.equal(parsed.searchParams.get('q'), 'elizabeth bennet');
    assert.equal(parsed.searchParams.get('bookId'), 'book-1');
    assert.equal(parsed.searchParams.get('characterId'), 'character-1');
    assert.equal(parsed.searchParams.has('cursor'), false);

    const after = new Date(parsed.searchParams.get('activeAfter'));
    const before = new Date(parsed.searchParams.get('activeBefore'));
    assert.deepEqual(
        [after.getFullYear(), after.getMonth(), after.getDate(), after.getHours()],
        [2026, 6, 1, 0]
    );
    assert.deepEqual(
        [before.getFullYear(), before.getMonth(), before.getDate(), before.getHours()],
        [2026, 7, 2, 0]
    );
});

test('filter choices are alphabetized and characters are constrained by book', () => {
    const options = buildFilterOptions({
        books: [
            { id: 'book-2', label: 'The Hitchhiker’s Guide' },
            { id: 'book-1', label: 'Pride and Prejudice' }
        ],
        characters: [
            { id: 'character-2', label: 'Zaphod', bookId: 'book-2' },
            { id: 'character-1', label: 'Elizabeth Bennet', bookId: 'book-1' }
        ]
    }, 'book-1');

    assert.deepEqual(options.books.map(option => option.label), ['Pride and Prejudice', 'The Hitchhiker’s Guide']);
    assert.deepEqual(options.characters.map(option => option.label), ['Elizabeth Bennet']);
});

test('filter catalog can include options beyond the currently loaded page items', () => {
    const catalog = {
        books: [
            { id: 'book-1', label: 'Pride and Prejudice' },
            { id: 'book-old', label: 'Moby-Dick' }
        ],
        characters: [
            { id: 'character-1', label: 'Elizabeth Bennet', bookId: 'book-1' },
            { id: 'character-old', label: 'Ahab', bookId: 'book-old' }
        ]
    };
    const options = buildFilterOptions(catalog, '');

    assert.deepEqual(options.books.map(option => option.id), ['book-old', 'book-1']);
    assert.deepEqual(options.characters.map(option => option.id), ['character-old', 'character-1']);
});

test('unsafe and missing session targets are rejected, but read-only chats keep a view URL', () => {
    assert.equal(safeResumeUrl(chat({ resume: { available: false, url: '/my-chats?session=session-1' } })), null);
    assert.equal(safeSessionUrl(chat({ resume: { available: false, url: '/my-chats?session=session-1' } })), '/my-chats?session=session-1');
    assert.equal(canContinueChat(chat({ resume: { available: false, url: '/my-chats?session=session-1' } })), false);
    // Reject an explicitly unsafe server URL instead of hiding it with a generated fallback.
    assert.equal(safeResumeUrl(chat({ resume: { available: true, url: 'https://example.com/steal' } })), null);
    assert.equal(safeSessionUrl(chat({ resume: { available: true, url: 'https://example.com/steal' } })), null);
    assert.equal(safeSessionUrl({ resume: { available: true, url: 'https://example.com/steal' } }), null);
    assert.equal(safeSessionUrl(chat({ resume: { available: true } })), '/my-chats?session=session-1');
    assert.equal(safeResumeUrl(chat({ resume: { available: true, url: '/my-chats?session=server-owned-id' } })), '/my-chats?session=server-owned-id');
    assert.equal(canContinueChat(chat({ resume: { available: true, url: '/my-chats?session=server-owned-id' } })), true);
});

test('open-book URL converts zero-based paragraph indexes to the one-based reader route', () => {
    assert.equal(toReaderParagraphParam(0), '1');
    assert.equal(toReaderParagraphParam(4), '5');
    assert.equal(toReaderParagraphParam(undefined), '1');

    const url = buildOpenBookUrl({
        book: { id: 'book-1' },
        context: { chapterId: 'chapter-1', paragraphIndex: 4 }
    });
    const parsed = new URL(url, 'https://classicchatreader.test');
    assert.equal(parsed.searchParams.get('book'), 'book-1');
    assert.equal(parsed.searchParams.get('chapter'), 'chapter-1');
    assert.equal(parsed.searchParams.get('paragraph'), '5');
});

test('conversation portrait remains optional when cached HTML lacks the new elements', () => {
    assert.doesNotThrow(() => renderConversationPortrait(
        null,
        null,
        'Mr. Bingley',
        '/api/characters/character-1/portrait'
    ));
});

test('voice call action uses the reader availability, policy, and browser gates', () => {
    const detail = {
        session: {
            character: { id: 'character-1' },
            resume: { available: true, voiceCallAvailable: true }
        }
    };
    const status = {
        enabled: true,
        chatEnabled: true,
        chatProviderAvailable: true,
        voiceCallEnabled: true,
        voiceCallAvailable: true
    };
    const browser = {
        navigator: { mediaDevices: { getUserMedia() {} } },
        AudioWorkletNode: function AudioWorkletNode() {}
    };

    assert.equal(canStartVoiceCall(detail, status, browser), true);
    assert.equal(canStartVoiceCall({
        session: { ...detail.session, resume: { available: true, voiceCallAvailable: false } }
    }, status, browser), false);
    assert.equal(canStartVoiceCall(detail, { ...status, voiceCallAvailable: false }, browser), false);
    assert.equal(canStartVoiceCall(detail, status, {
        ...browser,
        navigator: { mediaDevices: {} }
    }), false);
});

test('voice call history normalizes account messages to the shared call payload', () => {
    assert.deepEqual(toVoiceCallHistory([
        {
            role: 'USER',
            content: ' Hello ',
            createdAt: '2026-07-23T16:07:54.805411Z'
        },
        {
            role: 'CHARACTER',
            content: 'Good day.',
            createdAt: '2026-07-23T16:08:00Z'
        },
        { role: 'SYSTEM', content: 'ignored', createdAt: '2026-07-23T16:08:01Z' }
    ]), [
        {
            role: 'user',
            content: 'Hello',
            timestamp: Date.parse('2026-07-23T16:07:54.805411Z')
        },
        {
            role: 'character',
            content: 'Good day.',
            timestamp: Date.parse('2026-07-23T16:08:00Z')
        }
    ]);
});

test('finalized voice call turns receive stable persistence payload fields', () => {
    const ids = ['turn-1', 'turn-2'];
    assert.deepEqual(toVoiceCallPersistenceTurns([
        { role: 'user', content: ' Hello aloud ' },
        { role: 'character', content: 'Good day.' },
        { role: 'system', content: 'ignored' }
    ], () => ids.shift()), [
        { turnId: 'turn-1', role: 'USER', content: 'Hello aloud' },
        { turnId: 'turn-2', role: 'CHARACTER', content: 'Good day.' }
    ]);
});
