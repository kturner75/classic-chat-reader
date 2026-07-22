(function(root, factory) {
    const api = factory();
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
        return;
    }
    root.MyChats = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    const DEFAULT_LIMIT = 20;
    const SEARCH_DELAY_MS = 300;
    const UNAVAILABLE_REASONS = {
        CHAT_DISABLED: 'Character chat is temporarily unavailable.',
        BOOK_DISABLED: 'Chat is unavailable for this book.',
        CLASSROOM_POLICY: 'Your classroom settings do not allow continuing this chat.',
        CHARACTER_UNAVAILABLE: 'This character is no longer available.',
        BOOK_UNAVAILABLE: 'This book is no longer available.'
    };

    function normalizedSearch(value) {
        return typeof value === 'string' ? value.trim().replace(/\s+/g, ' ') : '';
    }

    function normalizeFilters(filters) {
        const source = filters && typeof filters === 'object' ? filters : {};
        return {
            q: normalizedSearch(source.q),
            bookId: normalizedSearch(source.bookId),
            characterId: normalizedSearch(source.characterId),
            activeAfter: normalizedSearch(source.activeAfter),
            activeBefore: normalizedSearch(source.activeBefore)
        };
    }

    function hasFilters(filters) {
        return Object.values(normalizeFilters(filters)).some(Boolean);
    }

    function localDateStartIso(value, addDays) {
        if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '')) {
            return '';
        }
        const date = new Date(`${value}T00:00:00`);
        if (Number.isNaN(date.getTime())) {
            return '';
        }
        date.setDate(date.getDate() + (addDays || 0));
        return date.toISOString();
    }

    function buildListRequestUrl(options) {
        const input = options && typeof options === 'object' ? options : {};
        const filters = normalizeFilters(input.filters);
        const params = new URLSearchParams();
        params.set('limit', String(input.limit || DEFAULT_LIMIT));
        params.set('sort', 'recent');
        if (filters.q) params.set('q', filters.q);
        if (filters.bookId) params.set('bookId', filters.bookId);
        if (filters.characterId) params.set('characterId', filters.characterId);
        if (filters.activeAfter) params.set('activeAfter', localDateStartIso(filters.activeAfter, 0));
        if (filters.activeBefore) params.set('activeBefore', localDateStartIso(filters.activeBefore, 1));
        if (input.cursor) params.set('cursor', input.cursor);
        return `/api/account/chats?${params.toString()}`;
    }

    function createInitialListState() {
        return {
            items: [],
            filters: normalizeFilters(),
            loading: true,
            loadingMore: false,
            loaded: false,
            error: '',
            loadMoreError: '',
            nextCursor: null,
            hasMore: false
        };
    }

    function reduceListState(state, event) {
        const current = state || createInitialListState();
        switch (event && event.type) {
            case 'FILTERS_CHANGED':
                return {
                    ...current,
                    filters: normalizeFilters(event.filters),
                    items: [],
                    nextCursor: null,
                    hasMore: false,
                    loaded: false,
                    loading: true,
                    loadingMore: false,
                    error: '',
                    loadMoreError: ''
                };
            case 'LOAD_STARTED':
                return {
                    ...current,
                    loading: current.items.length === 0,
                    loadingMore: false,
                    error: '',
                    loadMoreError: ''
                };
            case 'LOAD_MORE_STARTED':
                return { ...current, loadingMore: true, loadMoreError: '' };
            case 'LOAD_SUCCEEDED': {
                const payload = event.payload || {};
                const page = payload.page || {};
                const incoming = Array.isArray(payload.items) ? payload.items : [];
                const items = current.loadingMore ? [...current.items, ...incoming] : incoming;
                return {
                    ...current,
                    items,
                    loading: false,
                    loadingMore: false,
                    loaded: true,
                    error: '',
                    loadMoreError: '',
                    nextCursor: page.nextCursor || null,
                    hasMore: page.hasMore === true
                };
            }
            case 'LOAD_FAILED':
                if (current.loadingMore || current.items.length > 0) {
                    return {
                        ...current,
                        loading: false,
                        loadingMore: false,
                        loadMoreError: event.message || 'Could not load more chats.'
                    };
                }
                return {
                    ...current,
                    loading: false,
                    loadingMore: false,
                    loaded: false,
                    error: event.message || 'My Chats couldn’t load.'
                };
            default:
                return current;
        }
    }

    function getListViewModel(state) {
        const current = state || createInitialListState();
        if (current.loading && current.items.length === 0) {
            return { kind: 'loading', skeletonCount: 6, announcement: 'Loading My Chats.' };
        }
        if (current.error && current.items.length === 0) {
            return {
                kind: 'error',
                canRetry: true,
                message: current.error,
                announcement: current.error
            };
        }
        if (current.loaded && current.items.length === 0) {
            const filtered = hasFilters(current.filters);
            return {
                kind: filtered ? 'no-results' : 'empty',
                announcement: filtered
                    ? 'No chats match your search and filters.'
                    : 'You haven’t started any character chats yet.'
            };
        }
        return {
            kind: 'results',
            items: current.items,
            hasMore: current.hasMore,
            loadingMore: current.loadingMore,
            loadMoreError: current.loadMoreError,
            announcement: `${current.items.length} ${current.items.length === 1 ? 'chat' : 'chats'} shown.`
        };
    }

    function compareLabels(a, b) {
        return a.label.localeCompare(b.label, undefined, { sensitivity: 'base' });
    }

    function buildFilterOptions(items, selectedBookId) {
        const books = new Map();
        const characters = new Map();
        (Array.isArray(items) ? items : []).forEach((item) => {
            if (item?.book?.id && item.book.title) {
                books.set(item.book.id, { id: item.book.id, label: item.book.title });
            }
            if (item?.character?.id && item.character.name
                    && (!selectedBookId || item?.book?.id === selectedBookId)) {
                characters.set(item.character.id, {
                    id: item.character.id,
                    label: item.character.name,
                    bookId: item?.book?.id || ''
                });
            }
        });
        return {
            books: [...books.values()].sort(compareLabels),
            characters: [...characters.values()].sort(compareLabels)
        };
    }

    function safeResumeUrl(item) {
        if (item?.resume?.available !== true || typeof item.resume.url !== 'string') {
            return null;
        }
        const value = item.resume.url.trim();
        if (!/^\/my-chats\?/.test(value)) {
            return null;
        }
        try {
            const parsed = new URL(value, 'https://classicchatreader.invalid');
            return parsed.origin === 'https://classicchatreader.invalid'
                && parsed.pathname === '/my-chats'
                && parsed.searchParams.has('session')
                ? `${parsed.pathname}${parsed.search}`
                : null;
        } catch (_error) {
            return null;
        }
    }

    function unavailableReason(reason) {
        return UNAVAILABLE_REASONS[reason] || 'This conversation is available to read, but cannot be continued.';
    }

    function formatRelativeTime(value, now) {
        const timestamp = Date.parse(value || '');
        const current = now instanceof Date ? now.getTime() : Date.now();
        if (!Number.isFinite(timestamp)) return '';
        const seconds = Math.max(0, Math.round((current - timestamp) / 1000));
        if (seconds < 60) return 'Just now';
        const minutes = Math.floor(seconds / 60);
        if (minutes < 60) return `${minutes}m ago`;
        const hours = Math.floor(minutes / 60);
        if (hours < 24) return `${hours}h ago`;
        const days = Math.floor(hours / 24);
        if (days < 7) return `${days}d ago`;
        return new Date(timestamp).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
    }

    function createElement(documentRef, tag, className, text) {
        const element = documentRef.createElement(tag);
        if (className) element.className = className;
        if (text != null) element.textContent = text;
        return element;
    }

    function readErrorMessage(payload, fallback) {
        return payload?.error?.message || payload?.message || fallback;
    }

    async function readJson(response) {
        try {
            return await response.json();
        } catch (_error) {
            return null;
        }
    }

    function resolveChoiceId(value, options) {
        const normalized = normalizedSearch(value);
        if (!normalized) return '';
        const match = (options || []).find(option => option.id === normalized
            || option.label.localeCompare(normalized, undefined, { sensitivity: 'base' }) === 0);
        return match?.id || '';
    }

    function initListPage(context) {
        const { document: documentRef, window: windowRef, fetch: fetchRef } = context;
        const list = documentRef.getElementById('my-chats-list');
        const stateRegion = documentRef.getElementById('my-chats-state');
        const liveRegion = documentRef.getElementById('my-chats-status');
        const loadMore = documentRef.getElementById('my-chats-load-more');
        const loadMoreError = documentRef.getElementById('my-chats-load-more-error');
        const loadMoreRetry = documentRef.getElementById('my-chats-load-more-retry');
        const form = documentRef.getElementById('my-chats-filters');
        const search = documentRef.getElementById('my-chats-search');
        const bookInput = documentRef.getElementById('my-chats-book');
        const characterInput = documentRef.getElementById('my-chats-character');
        const afterInput = documentRef.getElementById('my-chats-after');
        const beforeInput = documentRef.getElementById('my-chats-before');
        const clearButton = documentRef.getElementById('my-chats-clear');
        const bookOptionsElement = documentRef.getElementById('my-chats-book-options');
        const characterOptionsElement = documentRef.getElementById('my-chats-character-options');
        const filterNoResults = documentRef.getElementById('my-chats-filter-no-results');
        let state = createInitialListState();
        let knownItems = [];
        let filterOptions = { books: [], characters: [] };
        let debounceTimer = null;
        let requestSequence = 0;

        function renderOptions(element, options) {
            element.replaceChildren();
            options.forEach((option) => {
                const node = documentRef.createElement('option');
                node.value = option.label;
                node.dataset.id = option.id;
                element.appendChild(node);
            });
        }

        function updateFilterOptions() {
            const selectedBookId = resolveChoiceId(bookInput.value, filterOptions.books);
            filterOptions = buildFilterOptions(knownItems, selectedBookId);
            renderOptions(bookOptionsElement, filterOptions.books);
            renderOptions(characterOptionsElement, filterOptions.characters);
        }

        function buildCard(item) {
            const article = createElement(documentRef, 'article', 'my-chat-card');
            const portraitWrap = createElement(documentRef, 'div', 'my-chat-portrait-wrap');
            const placeholder = createElement(
                documentRef,
                'span',
                'my-chat-portrait-placeholder',
                (item?.character?.name || '?').trim().charAt(0).toUpperCase()
            );
            placeholder.setAttribute('aria-hidden', 'true');
            portraitWrap.appendChild(placeholder);
            if (item?.character?.portraitUrl) {
                const image = documentRef.createElement('img');
                image.className = 'my-chat-portrait';
                image.src = item.character.portraitUrl;
                image.alt = '';
                image.loading = 'lazy';
                image.addEventListener('load', () => placeholder.classList.add('hidden'));
                image.addEventListener('error', () => image.remove());
                portraitWrap.appendChild(image);
            }

            const content = createElement(documentRef, 'div', 'my-chat-card-content');
            content.appendChild(createElement(documentRef, 'h2', 'my-chat-character', item?.character?.name || 'Unavailable character'));
            const bookText = [item?.book?.title || 'Unavailable book', item?.book?.author].filter(Boolean).join(' · ');
            content.appendChild(createElement(documentRef, 'p', 'my-chat-book', bookText));
            content.appendChild(createElement(documentRef, 'p', 'my-chat-preview', item?.previewText || 'No preview available.'));
            const time = createElement(documentRef, 'time', 'my-chat-time', formatRelativeTime(item?.lastMessageAt));
            if (item?.lastMessageAt) {
                time.dateTime = item.lastMessageAt;
                time.setAttribute('aria-label', `Last active ${new Date(item.lastMessageAt).toLocaleString()}`);
            }
            content.appendChild(time);

            const actions = createElement(documentRef, 'div', 'my-chat-card-actions');
            const target = safeResumeUrl(item);
            if (target) {
                const resume = createElement(documentRef, 'a', 'my-chat-primary-action', 'Resume chat');
                resume.href = target;
                actions.appendChild(resume);
            } else {
                const reason = createElement(
                    documentRef,
                    'p',
                    'my-chat-unavailable',
                    unavailableReason(item?.resume?.unavailableReason)
                );
                actions.appendChild(reason);
            }

            article.append(portraitWrap, content, actions);
            return article;
        }

        function render() {
            const view = getListViewModel(state);
            list.replaceChildren();
            stateRegion.replaceChildren();
            stateRegion.classList.toggle('hidden', view.kind === 'results');
            list.classList.toggle('hidden', view.kind !== 'results');
            loadMore.classList.add('hidden');
            loadMoreError.classList.add('hidden');

            if (view.kind === 'loading') {
                for (let index = 0; index < view.skeletonCount; index += 1) {
                    const skeleton = createElement(documentRef, 'div', 'my-chat-skeleton');
                    skeleton.setAttribute('aria-hidden', 'true');
                    stateRegion.appendChild(skeleton);
                }
            } else if (view.kind === 'error') {
                stateRegion.appendChild(createElement(documentRef, 'p', 'my-chats-state-title', view.message));
                const retry = createElement(documentRef, 'button', 'my-chat-secondary-action', 'Retry');
                retry.type = 'button';
                retry.addEventListener('click', () => void loadPage(false));
                stateRegion.appendChild(retry);
            } else if (view.kind === 'empty') {
                stateRegion.appendChild(createElement(documentRef, 'p', 'my-chats-state-title', 'You haven’t started any character chats yet.'));
                const find = createElement(documentRef, 'a', 'my-chat-primary-action', 'Find a character');
                find.href = '/';
                stateRegion.appendChild(find);
            } else if (view.kind === 'no-results') {
                stateRegion.appendChild(createElement(documentRef, 'p', 'my-chats-state-title', 'No chats match your search and filters.'));
                const clear = createElement(documentRef, 'button', 'my-chat-secondary-action', 'Clear filters');
                clear.type = 'button';
                clear.addEventListener('click', clearFilters);
                stateRegion.appendChild(clear);
            } else {
                view.items.forEach(item => list.appendChild(buildCard(item)));
                if (view.hasMore || view.loadingMore) {
                    loadMore.classList.remove('hidden');
                    loadMore.disabled = view.loadingMore;
                    loadMore.textContent = view.loadingMore ? 'Loading more…' : 'Load more';
                }
                if (view.loadMoreError) {
                    loadMoreError.classList.remove('hidden');
                    loadMoreError.querySelector('span').textContent = view.loadMoreError;
                }
            }
            liveRegion.textContent = view.announcement;
        }

        function currentFilters() {
            const bookId = resolveChoiceId(bookInput.value, filterOptions.books);
            const scopedOptions = buildFilterOptions(knownItems, bookId).characters;
            const characterId = resolveChoiceId(characterInput.value, scopedOptions);
            const invalidChoice = (bookInput.value && !bookId) || (characterInput.value && !characterId);
            filterNoResults.classList.toggle('hidden', !invalidChoice);
            filterNoResults.textContent = invalidChoice ? 'No matching filter option. Choose a suggestion or clear the field.' : '';
            return normalizeFilters({
                q: search.value,
                bookId,
                characterId,
                activeAfter: afterInput.value,
                activeBefore: beforeInput.value
            });
        }

        async function loadPage(append) {
            const sequence = ++requestSequence;
            state = reduceListState(state, { type: append ? 'LOAD_MORE_STARTED' : 'LOAD_STARTED' });
            render();
            const url = buildListRequestUrl({
                filters: state.filters,
                cursor: append ? state.nextCursor : null,
                limit: DEFAULT_LIMIT
            });
            try {
                const response = await fetchRef(url, { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
                const payload = await readJson(response);
                if (!response.ok) {
                    throw new Error(readErrorMessage(payload, append ? 'Could not load more chats.' : 'My Chats couldn’t load.'));
                }
                if (sequence !== requestSequence) return;
                state = reduceListState(state, { type: 'LOAD_SUCCEEDED', payload });
                const byId = new Map(knownItems.map(item => [item.sessionId, item]));
                state.items.forEach(item => byId.set(item.sessionId, item));
                knownItems = [...byId.values()];
                updateFilterOptions();
            } catch (error) {
                if (sequence !== requestSequence) return;
                state = reduceListState(state, { type: 'LOAD_FAILED', message: error.message });
            }
            render();
        }

        function applyFilters() {
            const filters = currentFilters();
            state = reduceListState(state, { type: 'FILTERS_CHANGED', filters });
            void loadPage(false);
        }

        function clearFilters() {
            form.reset();
            filterNoResults.classList.add('hidden');
            state = reduceListState(state, { type: 'FILTERS_CHANGED', filters: {} });
            updateFilterOptions();
            search.focus();
            void loadPage(false);
        }

        form.addEventListener('submit', (event) => {
            event.preventDefault();
            clearTimeout(debounceTimer);
            applyFilters();
        });
        search.addEventListener('input', () => {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(applyFilters, SEARCH_DELAY_MS);
        });
        [bookInput, characterInput, afterInput, beforeInput].forEach(input => input.addEventListener('change', () => {
            if (input === bookInput) {
                characterInput.value = '';
                updateFilterOptions();
            }
            applyFilters();
        }));
        clearButton.addEventListener('click', clearFilters);
        loadMore.addEventListener('click', () => void loadPage(true));
        loadMoreRetry.addEventListener('click', () => void loadPage(true));
        render();
        void loadPage(false);
    }

    function renderConversationMessages(documentRef, container, messages, characterName) {
        container.replaceChildren();
        (Array.isArray(messages) ? messages : []).forEach((message) => {
            const role = String(message?.role || '').toUpperCase();
            const article = createElement(documentRef, 'article', `my-chat-message ${role === 'USER' ? 'is-user' : 'is-character'}`);
            article.appendChild(createElement(documentRef, 'h3', 'my-chat-message-speaker', role === 'USER' ? 'You' : characterName));
            article.appendChild(createElement(documentRef, 'p', 'my-chat-message-content', message?.content || ''));
            if (message?.createdAt) {
                const time = createElement(documentRef, 'time', 'my-chat-message-time', formatRelativeTime(message.createdAt));
                time.dateTime = message.createdAt;
                article.appendChild(time);
            }
            container.appendChild(article);
        });
    }

    function initConversationPage(context, sessionId) {
        const { document: documentRef, fetch: fetchRef } = context;
        const listPage = documentRef.getElementById('my-chats-list-page');
        const conversation = documentRef.getElementById('my-chat-conversation');
        const loading = documentRef.getElementById('my-chat-conversation-loading');
        const error = documentRef.getElementById('my-chat-conversation-error');
        const title = documentRef.getElementById('my-chat-conversation-title');
        const book = documentRef.getElementById('my-chat-conversation-book');
        const contextLabel = documentRef.getElementById('my-chat-conversation-context');
        const messagesElement = documentRef.getElementById('my-chat-messages');
        const unavailable = documentRef.getElementById('my-chat-conversation-unavailable');
        const openBook = documentRef.getElementById('my-chat-open-book');
        const download = documentRef.getElementById('my-chat-download');
        const form = documentRef.getElementById('my-chat-send-form');
        const composer = documentRef.getElementById('my-chat-composer');
        const send = documentRef.getElementById('my-chat-send');
        const sendStatus = documentRef.getElementById('my-chat-send-status');
        const retry = documentRef.getElementById('my-chat-conversation-retry');
        let detail = null;
        let pendingIdempotencyKey = null;
        let pendingContent = '';

        listPage.classList.add('hidden');
        conversation.classList.remove('hidden');

        function setError(message) {
            loading.classList.add('hidden');
            error.classList.remove('hidden');
            error.querySelector('span').textContent = message;
        }

        function renderDetail() {
            const session = detail.session || {};
            const characterName = session?.character?.name || 'Unavailable character';
            title.textContent = characterName;
            title.focus();
            book.textContent = [session?.book?.title || 'Unavailable book', session?.book?.author].filter(Boolean).join(' · ');
            const chapter = session?.context?.chapterTitle || (Number.isInteger(session?.context?.chapterIndex)
                ? `Chapter ${session.context.chapterIndex + 1}` : 'Reading position unavailable');
            contextLabel.textContent = `Conversation context: ${chapter}`;
            renderConversationMessages(documentRef, messagesElement, detail.messages, characterName);

            if (session?.context?.chapterId && session?.book?.id) {
                const params = new URLSearchParams({
                    book: session.book.id,
                    chapter: session.context.chapterId,
                    paragraph: String(session.context.paragraphIndex || 0)
                });
                openBook.href = `/?${params.toString()}`;
                openBook.classList.remove('hidden');
            } else {
                openBook.classList.add('hidden');
            }
            download.disabled = !Array.isArray(detail.messages) || detail.messages.length === 0;
            if (session?.resume?.available === true) {
                unavailable.classList.add('hidden');
                form.classList.remove('hidden');
                composer.focus({ preventScroll: true });
            } else {
                unavailable.textContent = unavailableReason(session?.resume?.unavailableReason);
                unavailable.classList.remove('hidden');
                form.classList.add('hidden');
            }
            loading.classList.add('hidden');
            error.classList.add('hidden');
        }

        async function loadConversation() {
            loading.classList.remove('hidden');
            error.classList.add('hidden');
            try {
                const response = await fetchRef(`/api/account/chats/${encodeURIComponent(sessionId)}`, {
                    headers: { Accept: 'application/json' },
                    credentials: 'same-origin'
                });
                const payload = await readJson(response);
                if (!response.ok) {
                    throw new Error(readErrorMessage(payload, response.status === 404
                        ? 'This conversation could not be found.'
                        : 'This conversation couldn’t load.'));
                }
                detail = payload;
                renderDetail();
            } catch (requestError) {
                setError(requestError.message);
            }
        }

        async function sendMessage(event) {
            event.preventDefault();
            const content = typeof composer.value === 'string' ? composer.value.trim() : '';
            if (!content || !detail?.session?.resume?.available) return;
            if (!pendingIdempotencyKey || pendingContent !== content) {
                pendingIdempotencyKey = globalThis.crypto?.randomUUID?.()
                    || `${Date.now()}-${Math.random().toString(16).slice(2)}`;
                pendingContent = content;
            }
            send.disabled = true;
            composer.disabled = true;
            sendStatus.textContent = 'Sending message…';
            try {
                const response = await fetchRef(`/api/account/chats/${encodeURIComponent(sessionId)}/messages`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        Accept: 'application/json',
                        'Idempotency-Key': pendingIdempotencyKey
                    },
                    credentials: 'same-origin',
                    body: JSON.stringify({ content, context: detail.session.context })
                });
                const payload = await readJson(response);
                if (!response.ok) {
                    throw new Error(readErrorMessage(payload, 'Your message could not be sent. Retry when you’re ready.'));
                }
                if (payload?.userMessage) detail.messages.push(payload.userMessage);
                if (payload?.characterMessage) detail.messages.push(payload.characterMessage);
                if (payload?.context) detail.session.context = payload.context;
                if (payload?.lastMessageAt) detail.session.lastMessageAt = payload.lastMessageAt;
                composer.value = '';
                pendingIdempotencyKey = null;
                pendingContent = '';
                sendStatus.textContent = 'Message sent.';
                renderDetail();
                messagesElement.lastElementChild?.scrollIntoView({ block: 'nearest' });
            } catch (requestError) {
                sendStatus.textContent = requestError.message;
            } finally {
                send.disabled = false;
                composer.disabled = false;
            }
        }

        retry.addEventListener('click', () => void loadConversation());
        form.addEventListener('submit', sendMessage);
        download.addEventListener('click', () => {
            if (!detail || !globalThis.CharacterChatExport) return;
            const options = {
                bookTitle: detail.session?.book?.title,
                bookAuthor: detail.session?.book?.author,
                characterName: detail.session?.character?.name,
                chapterLabel: detail.session?.context?.chapterTitle,
                messages: detail.messages,
                exportedAt: new Date()
            };
            const markdown = globalThis.CharacterChatExport.formatCharacterChatMarkdown(options);
            const filename = globalThis.CharacterChatExport.buildCharacterChatFilename(options);
            globalThis.CharacterChatExport.downloadTextFile(filename, markdown);
        });
        void loadConversation();
    }

    function initAuthenticatedPage(context) {
        const params = new URL(context.window.location.href).searchParams;
        const sessionId = normalizedSearch(params.get('session'));
        context.document.getElementById('my-chats-auth-shell')?.classList.add('hidden');
        context.document.getElementById('my-chats-app')?.classList.remove('hidden');
        if (sessionId) {
            initConversationPage(context, sessionId);
        } else {
            initListPage(context);
        }
    }

    function init(options) {
        const config = options || {};
        const documentRef = config.document || globalThis.document;
        const windowRef = config.window || globalThis.window;
        const fetchRef = config.fetch || globalThis.fetch.bind(globalThis);
        const context = { document: documentRef, window: windowRef, fetch: fetchRef };
        const authShell = documentRef.getElementById('my-chats-auth-shell');
        const authLoading = documentRef.getElementById('my-chats-auth-loading');
        const authError = documentRef.getElementById('my-chats-auth-error');
        const signIn = documentRef.getElementById('my-chats-sign-in');
        const signInForm = documentRef.getElementById('my-chats-sign-in-form');
        const signInStatus = documentRef.getElementById('my-chats-sign-in-status');
        const googleButton = documentRef.getElementById('my-chats-google-sign-in');
        const email = documentRef.getElementById('my-chats-email');
        const password = documentRef.getElementById('my-chats-password');
        const login = documentRef.getElementById('my-chats-login');
        const register = documentRef.getElementById('my-chats-register');
        const authRetry = documentRef.getElementById('my-chats-auth-retry');
        let statusPayload = null;

        async function checkAuth() {
            authShell.classList.remove('hidden');
            authLoading.classList.remove('hidden');
            authError.classList.add('hidden');
            signIn.classList.add('hidden');
            try {
                const response = await fetchRef('/api/account/status', { credentials: 'same-origin' });
                const payload = await readJson(response);
                if (!response.ok || !payload) throw new Error('Unable to check your account.');
                statusPayload = payload;
                authLoading.classList.add('hidden');
                if (payload.authenticated) {
                    initAuthenticatedPage(context);
                    return;
                }
                signIn.classList.remove('hidden');
                googleButton.classList.toggle('hidden', payload.googleAuthEnabled !== true);
                email.focus();
            } catch (error) {
                authLoading.classList.add('hidden');
                authError.classList.remove('hidden');
                authError.querySelector('span').textContent = error.message;
            }
        }

        async function submitAuth(mode) {
            const emailValue = normalizedSearch(email.value);
            const passwordValue = password.value || '';
            if (!emailValue || !passwordValue) {
                signInStatus.textContent = 'Email and password are required.';
                return;
            }
            login.disabled = true;
            register.disabled = true;
            signInStatus.textContent = mode === 'login' ? 'Signing in…' : 'Creating account…';
            try {
                const response = await fetchRef(`/api/account/${mode}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                    credentials: 'same-origin',
                    body: JSON.stringify({ email: emailValue, password: passwordValue })
                });
                const payload = await readJson(response);
                if (!response.ok) throw new Error(readErrorMessage(payload, 'Account sign-in failed.'));
                await checkAuth();
            } catch (error) {
                signInStatus.textContent = error.message;
            } finally {
                login.disabled = false;
                register.disabled = false;
            }
        }

        authRetry.addEventListener('click', () => void checkAuth());
        signInForm.addEventListener('submit', (event) => {
            event.preventDefault();
            void submitAuth('login');
        });
        register.addEventListener('click', () => void submitAuth('register'));
        googleButton.addEventListener('click', () => {
            if (statusPayload?.googleAuthEnabled !== true) return;
            const returnTo = `${windowRef.location.pathname}${windowRef.location.search}`;
            windowRef.location.assign(`/api/account/google/start?returnTo=${encodeURIComponent(returnTo)}`);
        });
        void checkAuth();
    }

    return {
        buildFilterOptions,
        buildListRequestUrl,
        createInitialListState,
        formatRelativeTime,
        getListViewModel,
        hasFilters,
        init,
        normalizeFilters,
        reduceListState,
        safeResumeUrl,
        unavailableReason
    };
});
