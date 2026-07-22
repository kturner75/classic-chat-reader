const test = require('node:test');
const assert = require('node:assert/strict');

const {
    buildListRequestUrl,
    buildFilterOptions,
    createInitialListState,
    getListViewModel,
    reduceListState,
    safeResumeUrl
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
    const options = buildFilterOptions([
        chat({
            sessionId: 'session-2',
            character: { id: 'character-2', name: 'Zaphod', portraitUrl: null },
            book: { id: 'book-2', title: 'The Hitchhiker’s Guide', author: 'Douglas Adams' }
        }),
        chat()
    ], 'book-1');

    assert.deepEqual(options.books.map(option => option.label), ['Pride and Prejudice', 'The Hitchhiker’s Guide']);
    assert.deepEqual(options.characters.map(option => option.label), ['Elizabeth Bennet']);
});

test('unsafe, missing, and unavailable resume targets are rejected', () => {
    assert.equal(safeResumeUrl(chat({ resume: { available: false, url: '/my-chats?session=session-1' } })), null);
    assert.equal(safeResumeUrl(chat({ resume: { available: true, url: 'https://example.com/steal' } })), null);
    assert.equal(safeResumeUrl(chat({ resume: { available: true, url: '/my-chats?session=server-owned-id' } })), '/my-chats?session=server-owned-id');
});
