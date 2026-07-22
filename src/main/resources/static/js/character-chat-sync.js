(function (root, factory) {
    const api = factory();
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    }
    if (root) {
        root.CharacterChatSync = api;
    }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    'use strict';

    const LEGACY_STORAGE_PREFIX = 'reader_characterChat_';

    function normalizeMessage(message) {
        if (!message || typeof message !== 'object') return null;
        const content = typeof message.content === 'string' ? message.content : '';
        if (!content.trim()) return null;
        const normalizedRole = String(message.role || '').toLowerCase();
        const role = normalizedRole === 'assistant' ? 'character' : normalizedRole;
        return {
            ...message,
            messageId: typeof message.messageId === 'string' && message.messageId ? message.messageId : null,
            role,
            content,
            timestamp: message.createdAt || message.timestamp || null
        };
    }

    function normalizeMessages(messages) {
        const seenIds = new Set();
        const normalized = [];
        for (const candidate of Array.isArray(messages) ? messages : []) {
            const message = normalizeMessage(candidate);
            if (!message) continue;
            if (message.messageId) {
                if (seenIds.has(message.messageId)) continue;
                seenIds.add(message.messageId);
            }
            normalized.push(message);
        }
        return normalized;
    }

    function createPendingUserMessage(content, requestId, timestamp = Date.now()) {
        return {
            messageId: null,
            requestId,
            role: 'user',
            content,
            timestamp,
            pending: true
        };
    }

    function mergeServerExchange(messages, exchange, requestId) {
        const withoutPendingRequest = (Array.isArray(messages) ? messages : [])
            .filter(message => !(message && message.pending && message.requestId === requestId));
        return normalizeMessages([
            ...withoutPendingRequest,
            exchange && exchange.userMessage,
            exchange && exchange.characterMessage
        ]);
    }

    function defaultRequestId() {
        if (typeof globalThis !== 'undefined' && globalThis.crypto
                && typeof globalThis.crypto.randomUUID === 'function') {
            return globalThis.crypto.randomUUID();
        }
        return `chat-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    }

    async function readResponse(response) {
        const payload = await response.json().catch(() => ({}));
        if (response.ok) return payload;
        const error = new Error(
            payload?.error?.message
            || payload?.message
            || 'Character chat could not be synchronized.'
        );
        error.status = response.status;
        error.code = payload?.error?.code || null;
        throw error;
    }

    function createCharacterChatClient(options = {}) {
        const fetchImpl = options.fetchImpl
            || (typeof fetch === 'function' ? fetch.bind(globalThis) : null);
        const idFactory = typeof options.idFactory === 'function' ? options.idFactory : defaultRequestId;
        if (!fetchImpl) throw new Error('fetch implementation is required');

        return {
            createRequestId: idFactory,

            async load(characterId) {
                const response = await fetchImpl(
                    `/api/account/chats/characters/${encodeURIComponent(characterId)}`,
                    { credentials: 'same-origin', headers: { Accept: 'application/json' } }
                );
                const payload = await readResponse(response);
                return {
                    session: payload?.session || null,
                    messages: normalizeMessages(payload?.messages)
                };
            },

            async send(characterId, content, context, requestId = idFactory()) {
                const response = await fetchImpl(
                    `/api/account/chats/characters/${encodeURIComponent(characterId)}/messages`,
                    {
                        method: 'POST',
                        credentials: 'same-origin',
                        headers: {
                            Accept: 'application/json',
                            'Content-Type': 'application/json',
                            'Idempotency-Key': requestId
                        },
                        body: JSON.stringify({ content, context: context || null })
                    }
                );
                return {
                    ...(await readResponse(response)),
                    requestId
                };
            }
        };
    }

    function discardLegacyCharacterChatCache(storage) {
        if (!storage || typeof storage.key !== 'function' || typeof storage.removeItem !== 'function') {
            return 0;
        }
        try {
            const keys = [];
            for (let index = 0; index < storage.length; index += 1) {
                const key = storage.key(index);
                if (typeof key === 'string' && key.startsWith(LEGACY_STORAGE_PREFIX)) {
                    keys.push(key);
                }
            }
            keys.forEach(key => storage.removeItem(key));
            return keys.length;
        } catch (_error) {
            return 0;
        }
    }

    return {
        LEGACY_STORAGE_PREFIX,
        createCharacterChatClient,
        createPendingUserMessage,
        discardLegacyCharacterChatCache,
        mergeServerExchange,
        normalizeMessages
    };
});
