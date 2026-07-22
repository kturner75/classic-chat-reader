(function (root, factory) {
    const api = factory();
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    }
    if (root) {
        root.MyChatsLanding = api;
    }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    'use strict';

    const EMPTY_MESSAGE = 'Your character conversations will appear here after you start chatting.';
    const ERROR_MESSAGE = 'My Chats couldn’t load.';
    const MAX_RECENT_CHATS = 4;

    function escapeHtml(value) {
        return String(value ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    function safeRelativeUrl(value) {
        const url = typeof value === 'string' ? value.trim() : '';
        return url.startsWith('/') && !url.startsWith('//') ? url : '';
    }

    function safeSessionUrl(session) {
        const serverUrl = typeof session?.resume?.url === 'string' ? session.resume.url.trim() : '';
        const value = serverUrl || (session?.sessionId ? `/my-chats?session=${session.sessionId}` : '');
        if (!/^\/my-chats\?/.test(value)) return '';
        try {
            const parsed = new URL(value, 'https://classicchatreader.invalid');
            if (parsed.origin === 'https://classicchatreader.invalid'
                    && parsed.pathname === '/my-chats'
                    && parsed.searchParams.has('session')) {
                return `${parsed.pathname}${parsed.search}`;
            }
        } catch (_error) {
            // Invalid server-provided targets must not fall back to a fabricated URL.
        }
        return '';
    }

    function formatRelativeTime(value, now = Date.now()) {
        const timestamp = Date.parse(value || '');
        if (!Number.isFinite(timestamp)) {
            return '';
        }
        const elapsedMs = Math.max(0, now - timestamp);
        const minute = 60_000;
        const hour = 60 * minute;
        const day = 24 * hour;
        if (elapsedMs < minute) return 'Just now';
        if (elapsedMs < hour) {
            const minutes = Math.floor(elapsedMs / minute);
            return `${minutes} minute${minutes === 1 ? '' : 's'} ago`;
        }
        if (elapsedMs < day) {
            const hours = Math.floor(elapsedMs / hour);
            return `${hours} hour${hours === 1 ? '' : 's'} ago`;
        }
        if (elapsedMs < 2 * day) return 'Yesterday';
        const days = Math.floor(elapsedMs / day);
        if (days < 7) return `${days} days ago`;
        return new Date(timestamp).toLocaleDateString(undefined, {
            month: 'short',
            day: 'numeric',
            year: new Date(timestamp).getFullYear() === new Date(now).getFullYear() ? undefined : 'numeric'
        });
    }

    function unavailableLabel(reason) {
        switch (reason) {
            case 'CHAT_DISABLED': return 'Chat is temporarily unavailable';
            case 'BOOK_DISABLED': return 'Chat is unavailable for this book';
            case 'CLASSROOM_POLICY': return 'Chat is unavailable in this classroom';
            case 'CHARACTER_UNAVAILABLE': return 'This character is unavailable';
            case 'BOOK_UNAVAILABLE': return 'This book is unavailable';
            default: return 'Conversation unavailable';
        }
    }

    function renderPortrait(character) {
        const name = typeof character?.name === 'string' && character.name.trim()
            ? character.name.trim()
            : 'Character';
        const portraitUrl = safeRelativeUrl(character?.portraitUrl);
        const initial = Array.from(name)[0]?.toUpperCase() || '?';
        return `
            <span class="my-chat-portrait" aria-hidden="true">
                <span class="my-chat-portrait-placeholder">${escapeHtml(initial)}</span>
                ${portraitUrl ? `<img src="${escapeHtml(portraitUrl)}" alt="" loading="lazy" />` : ''}
            </span>`;
    }

    function renderCard(session, now) {
        const characterName = typeof session?.character?.name === 'string' && session.character.name.trim()
            ? session.character.name.trim()
            : 'Unavailable character';
        const bookTitle = typeof session?.book?.title === 'string' && session.book.title.trim()
            ? session.book.title.trim()
            : 'Unavailable book';
        const previewText = typeof session?.previewText === 'string' && session.previewText.trim()
            ? session.previewText.trim()
            : 'Open this conversation to continue chatting.';
        const lastMessageAt = typeof session?.lastMessageAt === 'string' ? session.lastMessageAt : '';
        const relativeTime = formatRelativeTime(lastMessageAt, now);
        const absoluteTime = lastMessageAt && relativeTime ? new Date(lastMessageAt).toLocaleString() : '';
        const timeMarkup = lastMessageAt && relativeTime
            ? `<time datetime="${escapeHtml(lastMessageAt)}" title="${escapeHtml(absoluteTime)}" aria-label="${escapeHtml(absoluteTime)}">${escapeHtml(relativeTime)}</time>`
            : '';
        const sessionUrl = safeSessionUrl(session);
        const canContinue = session?.resume?.available === true && !!sessionUrl;
        const actionLabel = canContinue
            ? 'Continue chat'
            : sessionUrl ? 'View chat' : unavailableLabel(session?.resume?.unavailableReason);
        const content = `
            ${renderPortrait(session?.character)}
            <span class="my-chat-card-content">
                <span class="my-chat-character">${escapeHtml(characterName)}</span>
                <span class="my-chat-book">${escapeHtml(bookTitle)}</span>
                <span class="my-chat-preview">${escapeHtml(previewText)}</span>
                ${timeMarkup ? `<span class="my-chat-time">${timeMarkup}</span>` : ''}
            </span>
            <span class="my-chat-card-action">${escapeHtml(actionLabel)}</span>`;

        if (sessionUrl) {
            const verb = canContinue ? 'Continue chat' : 'View chat';
            return `<a class="my-chat-card" href="${escapeHtml(sessionUrl)}" aria-label="${verb} with ${escapeHtml(characterName)} about ${escapeHtml(bookTitle)}">${content}</a>`;
        }
        return `<article class="my-chat-card my-chat-card-unavailable" aria-label="${escapeHtml(characterName)} chat unavailable">${content}</article>`;
    }

    function buildRecentChatsView(items, options = {}) {
        const sessions = Array.isArray(items) ? items.slice(0, MAX_RECENT_CHATS) : [];
        if (sessions.length === 0) {
            return {
                kind: 'empty',
                showViewAll: false,
                status: EMPTY_MESSAGE,
                html: '<a class="my-chats-empty-action" href="/">Find a character</a>'
            };
        }
        const now = Number.isFinite(options.now) ? options.now : Date.now();
        return {
            kind: 'loaded',
            showViewAll: true,
            status: `${sessions.length} recent ${sessions.length === 1 ? 'chat' : 'chats'} loaded.`,
            html: sessions.map(session => renderCard(session, now)).join('')
        };
    }

    function loadingMarkup() {
        return Array.from({ length: MAX_RECENT_CHATS }, () => `
            <div class="my-chat-card my-chat-card-skeleton" aria-hidden="true">
                <span class="my-chat-skeleton-portrait"></span>
                <span class="my-chat-skeleton-lines"><span></span><span></span><span></span></span>
            </div>`).join('');
    }

    function createController(options = {}) {
        const section = options.section;
        const list = options.list;
        const status = options.status;
        const viewAll = options.viewAll;
        const retry = options.retry;
        const fetchImpl = options.fetchImpl || (typeof fetch === 'function' ? fetch.bind(globalThis) : null);
        let authenticated = false;
        let loaded = false;
        let requestId = 0;

        function setHidden(element, hidden) {
            element?.classList?.toggle('hidden', hidden);
        }

        function reset() {
            requestId += 1;
            authenticated = false;
            loaded = false;
            setHidden(section, true);
            setHidden(viewAll, true);
            setHidden(retry, true);
            if (list) list.innerHTML = '';
            if (status) status.textContent = '';
        }

        function applyView(view) {
            if (list) list.innerHTML = view.html;
            if (status) status.textContent = view.status;
            setHidden(viewAll, !view.showViewAll);
            setHidden(retry, true);
        }

        async function load(force = false) {
            if (!authenticated || !fetchImpl || (loaded && !force)) return;
            const currentRequest = ++requestId;
            loaded = false;
            if (list) list.innerHTML = loadingMarkup();
            if (status) status.textContent = 'Loading My Chats…';
            setHidden(viewAll, true);
            setHidden(retry, true);
            try {
                const response = await fetchImpl(`/api/account/chats?limit=${MAX_RECENT_CHATS}`, { cache: 'no-store' });
                if (!response.ok) throw new Error(`My Chats request failed (${response.status})`);
                const payload = await response.json();
                if (!authenticated || currentRequest !== requestId) return;
                applyView(buildRecentChatsView(payload?.items));
                loaded = true;
            } catch (error) {
                if (!authenticated || currentRequest !== requestId) return;
                if (list) list.innerHTML = '';
                if (status) status.textContent = ERROR_MESSAGE;
                setHidden(viewAll, true);
                setHidden(retry, false);
                options.onError?.(error);
            }
        }

        function sync(syncOptions = {}) {
            const nextAuthenticated = syncOptions.authenticated === true;
            const visible = syncOptions.visible !== false;
            if (!nextAuthenticated) {
                reset();
                return Promise.resolve();
            }
            authenticated = true;
            setHidden(section, !visible);
            if (!visible) return Promise.resolve();
            return load(syncOptions.force === true);
        }

        retry?.addEventListener?.('click', () => load(true));
        list?.addEventListener?.('error', (event) => {
            if (event?.target?.tagName === 'IMG') event.target.classList.add('hidden');
        }, true);

        return { sync, reset, load };
    }

    return {
        EMPTY_MESSAGE,
        ERROR_MESSAGE,
        MAX_RECENT_CHATS,
        buildRecentChatsView,
        createController,
        formatRelativeTime
    };
});
