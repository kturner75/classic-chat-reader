const test = require('node:test');
const assert = require('node:assert/strict');

const {
    createCharacterChatClient,
    createPendingUserMessage,
    discardLegacyCharacterChatCache,
    mergeServerExchange,
    normalizeMessages
} = require('../../main/resources/static/js/character-chat-sync.js');

function response(body, overrides = {}) {
    return {
        ok: true,
        status: 200,
        json: async () => body,
        ...overrides
    };
}

test('load requests the authenticated character transcript and preserves server order', async () => {
    const requests = [];
    const client = createCharacterChatClient({
        fetchImpl: async (...args) => {
            requests.push(args);
            return response({
                session: { sessionId: 'session-1' },
                messages: [
                    { messageId: 'm-1', role: 'USER', content: 'Hello', createdAt: '2026-07-22T12:00:00Z' },
                    { messageId: 'm-2', role: 'CHARACTER', content: 'Good day.', createdAt: '2026-07-22T12:00:01Z' }
                ]
            });
        }
    });

    const loaded = await client.load('character/one');

    assert.equal(requests[0][0], '/api/account/chats/characters/character%2Fone');
    assert.equal(requests[0][1].credentials, 'same-origin');
    assert.deepEqual(loaded.messages.map(message => [message.messageId, message.role]), [
        ['m-1', 'user'],
        ['m-2', 'character']
    ]);
    assert.deepEqual(loaded.messages.map(message => message.timestamp), [
        Date.parse('2026-07-22T12:00:00Z'),
        Date.parse('2026-07-22T12:00:01Z')
    ]);
});

test('send uses a stable idempotency key and server-owned reader context', async () => {
    const requests = [];
    const client = createCharacterChatClient({
        fetchImpl: async (...args) => {
            requests.push(args);
            return response({
                sessionId: 'session-1',
                userMessage: { messageId: 'm-1', role: 'USER', content: 'Hello' },
                characterMessage: { messageId: 'm-2', role: 'CHARACTER', content: 'Welcome.' }
            });
        }
    });
    const context = { chapterId: 'chapter-1', chapterIndex: 2, chapterTitle: 'Three', paragraphIndex: 4 };

    await client.send('character-1', 'Hello', context, 'request-1');
    await client.send('character-1', 'Hello', context, 'request-1');

    assert.equal(requests.length, 2);
    for (const [url, options] of requests) {
        assert.equal(url, '/api/account/chats/characters/character-1/messages');
        assert.equal(options.headers['Idempotency-Key'], 'request-1');
        assert.deepEqual(JSON.parse(options.body), { content: 'Hello', context });
    }
});

test('retry response replaces the optimistic message and repeated responses do not duplicate turns', () => {
    const pending = createPendingUserMessage('Hello', 'request-1', 100);
    const exchange = {
        userMessage: { messageId: 'm-1', role: 'USER', content: 'Hello', createdAt: '2026-07-22T12:00:00Z' },
        characterMessage: { messageId: 'm-2', role: 'CHARACTER', content: 'Welcome.', createdAt: '2026-07-22T12:00:01Z' }
    };

    const firstMerge = mergeServerExchange([pending], exchange, 'request-1');
    const replayMerge = mergeServerExchange(firstMerge, exchange, 'request-1');

    assert.deepEqual(replayMerge.map(message => message.messageId), ['m-1', 'm-2']);
    assert.equal(replayMerge.some(message => message.pending), false);
});

test('normalization removes duplicate server messages without re-sorting the transcript', () => {
    const messages = normalizeMessages([
        { messageId: 'm-2', role: 'CHARACTER', content: 'Second' },
        { messageId: 'm-1', role: 'USER', content: 'First' },
        { messageId: 'm-2', role: 'CHARACTER', content: 'Second duplicate' }
    ]);

    assert.deepEqual(messages.map(message => message.messageId), ['m-2', 'm-1']);
});

test('legacy character-chat cache is explicitly discarded while unrelated local state remains', () => {
    const values = new Map([
        ['reader_characterChat_book-1_character-1', '[{"role":"user"}]'],
        ['reader_characterChat_book-2_character-2', '[{"role":"user"}]'],
        ['reader_readerPreferences', '{}']
    ]);
    const storage = {
        get length() { return values.size; },
        key(index) { return [...values.keys()][index] ?? null; },
        removeItem(key) { values.delete(key); }
    };

    assert.equal(discardLegacyCharacterChatCache(storage), 2);
    assert.deepEqual([...values.keys()], ['reader_readerPreferences']);
});

test('legacy cleanup failure does not turn a successful server load into an error', () => {
    const blockedStorage = {
        get length() { throw new Error('storage blocked'); },
        key() { return null; },
        removeItem() {}
    };

    assert.equal(discardLegacyCharacterChatCache(blockedStorage), 0);
});

test('API failures include status and server message for retry-state mapping', async () => {
    const client = createCharacterChatClient({
        fetchImpl: async () => response(
            { error: { message: 'Authentication required.' } },
            { ok: false, status: 401 }
        )
    });

    await assert.rejects(
        () => client.load('character-1'),
        error => error.status === 401 && error.message === 'Authentication required.'
    );
});
