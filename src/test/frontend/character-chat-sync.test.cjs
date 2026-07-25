const test = require('node:test');
const assert = require('node:assert/strict');

const {
    createCharacterChatClient,
    createPendingUserMessage,
    createPendingVoiceMessages,
    discardLegacyCharacterChatCache,
    mergePersistedVoiceMessages,
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

test('saveVoiceTurns creates or reuses the character session and sends reader context', async () => {
    const requests = [];
    const ids = ['turn-1', 'turn-2'];
    const client = createCharacterChatClient({
        idFactory: () => ids.shift(),
        fetchImpl: async (...args) => {
            requests.push(args);
            return response({
                sessionId: 'session-1',
                messages: [
                    { messageId: 'm-1', role: 'USER', content: 'Hello Tom' },
                    { messageId: 'm-2', role: 'CHARACTER', content: 'Hello Huck' }
                ]
            });
        }
    });
    const context = { chapterId: 'chapter-1', chapterIndex: 0, chapterTitle: 'One', paragraphIndex: 3 };

    const saved = await client.saveVoiceTurns('character/one', [
        { role: 'user', content: ' Hello Tom ' },
        { role: 'character', content: 'Hello Huck' }
    ], context);

    assert.equal(requests[0][0], '/api/account/chats/characters/character%2Fone/voice-turns');
    assert.equal(requests[0][1].credentials, 'same-origin');
    assert.equal(requests[0][1].keepalive, true);
    assert.deepEqual(JSON.parse(requests[0][1].body), {
        turns: [
            { turnId: 'turn-1', role: 'USER', content: 'Hello Tom' },
            { turnId: 'turn-2', role: 'CHARACTER', content: 'Hello Huck' }
        ],
        context
    });
    assert.equal(saved.sessionId, 'session-1');
    assert.deepEqual(saved.messages.map(message => message.role), ['user', 'character']);
});

test('saveVoiceTurns preserves supplied turn IDs for idempotent retries', async () => {
    const requests = [];
    const client = createCharacterChatClient({
        idFactory: () => {
            throw new Error('retry IDs must not be regenerated');
        },
        fetchImpl: async (...args) => {
            requests.push(args);
            return response({ sessionId: 'session-1', messages: [] });
        }
    });
    const turns = [{ turnId: 'stable-turn-1', role: 'USER', content: 'Hello again' }];

    await client.saveVoiceTurns('character-1', turns, null);
    await client.saveVoiceTurns('character-1', turns, null);

    assert.deepEqual(
        requests.map(([, options]) => JSON.parse(options.body).turns[0].turnId),
        ['stable-turn-1', 'stable-turn-1']
    );
});

test('voice turns remain local until persistence replaces their batch with server messages', () => {
    const pending = createPendingVoiceMessages([
        { role: 'user', content: ' Hello Tom ' },
        { role: 'character', content: 'Hello Huck' }
    ], 'batch-1', 100);

    assert.deepEqual(pending.map(message => [message.role, message.content, message.timestamp]), [
        ['user', 'Hello Tom', 100],
        ['character', 'Hello Huck', 101]
    ]);
    assert.equal(pending.every(message => message.voicePersistenceBatchId === 'batch-1'), true);

    const persisted = mergePersistedVoiceMessages(
        [{ messageId: 'earlier', role: 'USER', content: 'Earlier' }, ...pending],
        [
            { messageId: 'm-1', role: 'USER', content: 'Hello Tom' },
            { messageId: 'm-2', role: 'CHARACTER', content: 'Hello Huck' }
        ],
        'batch-1'
    );

    assert.deepEqual(persisted.map(message => message.messageId), ['earlier', 'm-1', 'm-2']);
    assert.equal(persisted.some(message => message.voicePersistenceBatchId), false);
});

test('persisted voice batch replaces pending turns in place without reordering later batches', () => {
    const first = createPendingVoiceMessages([
        { role: 'user', content: 'First question' },
        { role: 'character', content: 'First answer' }
    ], 'batch-1', 100);
    const second = createPendingVoiceMessages([
        { role: 'user', content: 'Second question' }
    ], 'batch-2', 200);

    const merged = mergePersistedVoiceMessages(
        [...first, ...second],
        [
            { messageId: 'm-1', role: 'USER', content: 'First question' },
            { messageId: 'm-2', role: 'CHARACTER', content: 'First answer' }
        ],
        'batch-1'
    );

    assert.deepEqual(merged.map(message => message.content), [
        'First question', 'First answer', 'Second question'
    ]);
    assert.equal(merged[2].voicePersistenceBatchId, 'batch-2');
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
