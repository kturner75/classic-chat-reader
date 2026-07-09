/**
 * Reading Buddy Mode — client helpers and UI controller factory.
 * Availability comes only from GET /api/reading-buddy/status (+ classroom FE gates).
 */
(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
        return;
    }
    root.ReadingBuddy = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    const MIN_PARAGRAPH_CHARS = 40;
    const DWELL_MS = 800;
    const TOAST_AUTO_HIDE_MS = 8000;
    const QUIET_MINUTES = 45;
    const TOAST_PREVIEW_CHARS = 120;
    const FREQUENCIES = Object.freeze(['rare', 'occasional', 'chatty']);
    /** Client sample interval: only attempt check every N advances (server still gates). */
    const FREQUENCY_SAMPLE_INTERVAL = Object.freeze({
        rare: 5,
        occasional: 3,
        chatty: 1
    });

    function normalizeFrequency(value) {
        const freq = typeof value === 'string' ? value.trim().toLowerCase() : '';
        return FREQUENCIES.includes(freq) ? freq : 'rare';
    }

    function sampleIntervalFor(frequency) {
        return FREQUENCY_SAMPLE_INTERVAL[normalizeFrequency(frequency)] || FREQUENCY_SAMPLE_INTERVAL.rare;
    }

    /**
     * True when enough paragraph advances have occurred for a client-side sample.
     * advancesSinceSample is 1-based count since last sample attempt.
     */
    function clientFrequencyAllows(frequency, advancesSinceSample) {
        const interval = sampleIntervalFor(frequency);
        const advances = Number(advancesSinceSample) || 0;
        return advances > 0 && advances % interval === 0;
    }

    function stripHtml(html) {
        if (typeof html !== 'string' || html.length === 0) {
            return '';
        }
        return html
            .replace(/<[^>]*>/g, ' ')
            .replace(/&nbsp;/gi, ' ')
            .replace(/&amp;/gi, '&')
            .replace(/&lt;/gi, '<')
            .replace(/&gt;/gi, '>')
            .replace(/&quot;/gi, '"')
            .replace(/&#39;/gi, "'")
            .replace(/\s+/g, ' ')
            .trim();
    }

    function paragraphTextLength(htmlOrText) {
        return stripHtml(htmlOrText).length;
    }

    function previewText(text, maxChars) {
        const limit = Number.isInteger(maxChars) && maxChars > 0 ? maxChars : TOAST_PREVIEW_CHARS;
        const clean = stripHtml(text || '');
        if (clean.length <= limit) {
            return clean;
        }
        return clean.slice(0, Math.max(0, limit - 1)).trimEnd() + '…';
    }

    function formatHiddenPlaceholder(chapterIndex, paragraphIndex) {
        const ch = Number.isInteger(chapterIndex) ? chapterIndex + 1 : '?';
        return `Hidden until you re-read past Ch. ${ch}`;
    }

    function escapeHtml(text) {
        const div = typeof document !== 'undefined' ? document.createElement('div') : null;
        if (div) {
            div.textContent = text == null ? '' : String(text);
            return div.innerHTML;
        }
        return String(text == null ? '' : text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    /**
     * Render history messages; future-relative (visibleAtPosition === false) are collapsed.
     * Roles: user | buddy (server); maps buddy/proactive to "buddy" bubble.
     */
    function renderHistoryMessagesHtml(messages, esc) {
        const escape = typeof esc === 'function' ? esc : escapeHtml;
        if (!Array.isArray(messages) || messages.length === 0) {
            return '';
        }
        return messages.map(msg => {
            if (!msg) {
                return '';
            }
            if (msg.visibleAtPosition === false) {
                const placeholder = formatHiddenPlaceholder(msg.chapterIndex, msg.paragraphIndex);
                return `<div class="chat-message buddy-hidden" data-hidden="true">${escape(placeholder)}</div>`;
            }
            const role = msg.role === 'user' ? 'user' : 'buddy';
            const content = typeof msg.content === 'string' ? msg.content : '';
            return `<div class="chat-message ${role}">${escape(content)}</div>`;
        }).join('');
    }

    /**
     * Client gates for proactive check-comment. All must pass.
     * @returns {{ ok: boolean, reason?: string }}
     */
    function evaluateClientGates(ctx) {
        if (!ctx) {
            return { ok: false, reason: 'no_context' };
        }
        if (ctx.statusAvailable !== true) {
            return { ok: false, reason: 'status_unavailable' };
        }
        if (ctx.prefsEnabled !== true) {
            return { ok: false, reason: 'prefs_disabled' };
        }
        if (!ctx.personaId) {
            return { ok: false, reason: 'no_persona' };
        }
        if (ctx.focusedModal === true) {
            return { ok: false, reason: 'focused_modal' };
        }
        if (ctx.speedReadingActive === true) {
            return { ok: false, reason: 'speed_reading' };
        }
        if (ctx.cooldownActive === true) {
            return { ok: false, reason: 'cooldown' };
        }
        if (!clientFrequencyAllows(ctx.frequency, ctx.advancesSinceSample)) {
            return { ok: false, reason: 'frequency_sample' };
        }
        const len = Number(ctx.paragraphLength) || 0;
        if (len < MIN_PARAGRAPH_CHARS) {
            return { ok: false, reason: 'paragraph_short' };
        }
        const dwell = Number(ctx.dwellMs) || 0;
        if (dwell < DWELL_MS) {
            return { ok: false, reason: 'dwell' };
        }
        const suppressUntil = Number(ctx.suppressUntilEpochMs) || 0;
        const now = Number.isFinite(ctx.nowMs) ? ctx.nowMs : Date.now();
        if (suppressUntil > now) {
            return { ok: false, reason: 'suppressed' };
        }
        if (ctx.renderSettled !== true) {
            return { ok: false, reason: 'render_not_settled' };
        }
        if (ctx.classroomAllowed === false) {
            return { ok: false, reason: 'classroom' };
        }
        return { ok: true };
    }

    /**
     * Effective FE availability: status.available && classroom chat + buddy flags when enrolled.
     */
    function isFeatureAvailable(status, classroomContext) {
        if (!status || status.available !== true) {
            return false;
        }
        if (!classroomContext || classroomContext.enrolled !== true) {
            return true;
        }
        const features = classroomContext.features || {};
        return features.chatEnabled !== false && features.readingBuddyEnabled !== false;
    }

    /**
     * Factory for reader.js wiring. Host supplies state/DOM callbacks.
     */
    function createController(host) {
        if (!host || typeof host !== 'object') {
            throw new Error('ReadingBuddy.createController requires a host');
        }

        const state = {
            statusAvailable: false,
            personas: [],
            prefs: {
                enabled: false,
                frequency: 'rare',
                defaultPersonaId: null,
                personaId: null,
                personaSource: 'global',
                suppressUntilEpochMs: null,
                bookId: null
            },
            checkRequestId: 0,
            advancesSinceSample: 0,
            paragraphEnteredAt: 0,
            clientCooldownUntilMs: 0,
            lastSampledAtAdvance: 0,
            pendingComment: null,
            toastTimer: null,
            dwellTimer: null,
            chatOpen: false,
            chatHistory: [],
            chatLoading: false,
            chatRetryHandler: null,
            prefsLoadedForBook: null,
            renderSettled: false,
            enabledEverAdvanced: false
        };

        function els() {
            return host.elements || {};
        }

        function bookId() {
            return host.getBookId ? host.getBookId() : null;
        }

        function position() {
            return host.getPosition
                ? host.getPosition()
                : { chapterIndex: 0, paragraphIndex: 0 };
        }

        function isFocusedModal() {
            if (typeof host.isFocusedModal === 'function') {
                return host.isFocusedModal();
            }
            return false;
        }

        function classroomAllowed() {
            if (typeof host.isClassroomAllowed === 'function') {
                return host.isClassroomAllowed();
            }
            return true;
        }

        function currentParagraphHtml() {
            return typeof host.getCurrentParagraphHtml === 'function'
                ? host.getCurrentParagraphHtml()
                : '';
        }

        function isSpeedReading() {
            return typeof host.isSpeedReadingActive === 'function'
                ? host.isSpeedReadingActive()
                : false;
        }

        function findPersona(id) {
            return state.personas.find(p => p && p.id === id) || null;
        }

        function effectivePersonaId() {
            return state.prefs.personaId || state.prefs.defaultPersonaId || (state.personas[0] && state.personas[0].id) || null;
        }

        function updateTalkButton() {
            const talk = els().talkBtn;
            if (!talk) return;
            const canTalk = state.statusAvailable
                && classroomAllowed()
                && state.prefs.enabled === true
                && !!effectivePersonaId()
                && !!bookId();
            talk.disabled = !canTalk;
        }

        function syncSettingsPanel() {
            const section = els().settingsSection;
            if (section) {
                const show = state.statusAvailable && classroomAllowed();
                section.classList.toggle('hidden', !show);
            }
            const toggle = els().toggle;
            if (toggle) {
                toggle.checked = state.prefs.enabled === true;
            }
            const freq = els().frequency;
            if (freq) {
                freq.value = normalizeFrequency(state.prefs.frequency);
            }
            renderPersonaList();
            updateTalkButton();
        }

        function renderPersonaList() {
            const list = els().personaList;
            if (!list) return;
            const selectedId = effectivePersonaId();
            if (!state.personas.length) {
                list.innerHTML = '<p class="reader-settings-note">No personas available.</p>';
                return;
            }
            list.innerHTML = state.personas.map(p => {
                const selected = p.id === selectedId ? ' selected' : '';
                const portrait = p.portraitUrl || `/images/buddies/${p.id}.png`;
                return `
                    <button type="button" class="reading-buddy-persona-card${selected}" data-persona-id="${escapeHtml(p.id)}" role="option" aria-selected="${p.id === selectedId}">
                        <img src="${escapeHtml(portrait)}" alt="" />
                        <div class="reading-buddy-persona-meta">
                            <p class="reading-buddy-persona-name">${escapeHtml(p.displayName || p.id)}</p>
                            <p class="reading-buddy-persona-blurb">${escapeHtml(p.shortBlurb || '')}</p>
                        </div>
                    </button>
                `;
            }).join('');
        }

        async function checkAvailability() {
            try {
                const response = await host.fetch('/api/reading-buddy/status', { cache: 'no-store' });
                if (!response.ok) {
                    state.statusAvailable = false;
                } else {
                    const status = await response.json();
                    state.statusAvailable = isFeatureAvailable(status, host.getClassroomContext
                        ? host.getClassroomContext()
                        : null);
                }
            } catch (error) {
                console.debug('Reading buddy status check failed:', error);
                state.statusAvailable = false;
            }
            syncSettingsPanel();
            return state.statusAvailable;
        }

        async function loadPersonas() {
            if (!state.statusAvailable) {
                state.personas = [];
                return;
            }
            try {
                const response = await host.fetch('/api/reading-buddy/personas', { cache: 'no-store' });
                if (!response.ok) {
                    state.personas = [];
                    return;
                }
                const data = await response.json();
                state.personas = Array.isArray(data) ? data : [];
            } catch (error) {
                console.debug('Reading buddy personas failed:', error);
                state.personas = [];
            }
            syncSettingsPanel();
        }

        async function loadPreferences() {
            if (!state.statusAvailable) {
                return;
            }
            const id = bookId();
            const qs = id ? `?bookId=${encodeURIComponent(id)}` : '';
            try {
                const response = await host.fetch(`/api/reading-buddy/preferences${qs}`, { cache: 'no-store' });
                if (!response.ok) {
                    return;
                }
                const prefs = await response.json();
                applyPrefs(prefs);
                state.prefsLoadedForBook = id || null;
            } catch (error) {
                console.debug('Reading buddy preferences failed:', error);
            }
            syncSettingsPanel();
        }

        function applyPrefs(prefs) {
            if (!prefs || typeof prefs !== 'object') {
                return;
            }
            state.prefs = {
                enabled: prefs.enabled === true,
                frequency: normalizeFrequency(prefs.frequency),
                defaultPersonaId: prefs.defaultPersonaId || null,
                personaId: prefs.personaId || prefs.defaultPersonaId || null,
                personaSource: prefs.personaSource || 'global',
                suppressUntilEpochMs: prefs.suppressUntilEpochMs == null
                    ? null
                    : Number(prefs.suppressUntilEpochMs),
                bookId: prefs.bookId || null
            };
        }

        async function putPreferences(body) {
            const response = await host.fetch('/api/reading-buddy/preferences', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body || {})
            });
            if (!response.ok) {
                const payload = await response.json().catch(() => ({}));
                throw new Error(payload.message || 'Failed to update reading buddy preferences');
            }
            const prefs = await response.json();
            applyPrefs(prefs);
            syncSettingsPanel();
            return prefs;
        }

        function markParagraphEntered() {
            state.paragraphEnteredAt = Date.now();
            state.renderSettled = true;
        }

        function onParagraphAdvanced() {
            if (!state.statusAvailable || !state.prefs.enabled) {
                return;
            }
            state.advancesSinceSample += 1;
            state.enabledEverAdvanced = true;
            markParagraphEntered();
            scheduleProactiveCheck();
        }

        function onPageRendered() {
            markParagraphEntered();
            if (state.statusAvailable && state.prefs.enabled) {
                scheduleProactiveCheck();
            }
        }

        function scheduleProactiveCheck() {
            if (state.dwellTimer) {
                clearTimeout(state.dwellTimer);
                state.dwellTimer = null;
            }
            state.dwellTimer = setTimeout(() => {
                state.dwellTimer = null;
                void maybeCheckComment();
            }, DWELL_MS);
        }

        function cooldownActive(nowMs) {
            return (state.clientCooldownUntilMs || 0) > nowMs;
        }

        async function maybeCheckComment() {
            if (!bookId()) {
                return;
            }
            const pos = position();
            const dwellMs = Math.max(0, Date.now() - (state.paragraphEnteredAt || 0));
            const nowMs = Date.now();
            const gates = evaluateClientGates({
                statusAvailable: state.statusAvailable,
                prefsEnabled: state.prefs.enabled,
                personaId: effectivePersonaId(),
                focusedModal: isFocusedModal() || state.chatOpen,
                speedReadingActive: isSpeedReading(),
                cooldownActive: cooldownActive(nowMs),
                frequency: state.prefs.frequency,
                advancesSinceSample: state.advancesSinceSample,
                paragraphLength: paragraphTextLength(currentParagraphHtml()),
                dwellMs,
                suppressUntilEpochMs: state.prefs.suppressUntilEpochMs,
                nowMs,
                renderSettled: state.renderSettled,
                classroomAllowed: classroomAllowed()
            });
            if (!gates.ok) {
                return;
            }

            // Reset sample counter on attempt (whether server silences or comments)
            state.advancesSinceSample = 0;

            const requestId = ++state.checkRequestId;
            const personaId = effectivePersonaId();
            const chapterIndex = pos.chapterIndex;
            const paragraphIndex = pos.paragraphIndex;

            try {
                const response = await host.fetch('/api/reading-buddy/check-comment', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        bookId: bookId(),
                        personaId,
                        readerChapterIndex: chapterIndex,
                        readerParagraphIndex: paragraphIndex,
                        clientHint: {
                            paragraphsSinceLastComment: null,
                            dwellMs
                        }
                    })
                });

                if (requestId !== state.checkRequestId) {
                    return;
                }
                const current = position();
                if (current.chapterIndex !== chapterIndex || current.paragraphIndex !== paragraphIndex) {
                    return;
                }
                if (state.chatOpen || isFocusedModal()) {
                    return;
                }

                if (!response.ok) {
                    if (response.status === 429) {
                        const retryAfter = Number(response.headers.get('Retry-After'));
                        if (Number.isFinite(retryAfter) && retryAfter > 0) {
                            state.clientCooldownUntilMs = Date.now() + retryAfter * 1000;
                        } else {
                            state.clientCooldownUntilMs = Date.now() + 60000;
                        }
                    }
                    return;
                }

                const data = await response.json().catch(() => null);
                if (!data || requestId !== state.checkRequestId) {
                    return;
                }

                if (typeof data.nextEligibleAfterMs === 'number' && data.nextEligibleAfterMs > 0) {
                    state.clientCooldownUntilMs = Date.now() + data.nextEligibleAfterMs;
                }

                if (data.action === 'COMMENT' && typeof data.text === 'string' && data.text.trim()) {
                    // Never auto-open modal — toast only
                    showToast({
                        messageId: data.messageId,
                        text: data.text,
                        personaId: data.personaId || personaId,
                        portraitUrl: data.portraitUrl,
                        chapterIndex: data.chapterIndex ?? chapterIndex,
                        paragraphIndex: data.paragraphIndex ?? paragraphIndex
                    });
                }
            } catch (error) {
                console.debug('Reading buddy check-comment failed:', error);
            }
        }

        function showToast(comment) {
            const toast = els().toast;
            if (!toast) {
                return;
            }
            const persona = findPersona(comment.personaId);
            const name = persona?.displayName || 'Reading Buddy';
            const portrait = comment.portraitUrl
                || persona?.portraitUrl
                || (comment.personaId ? `/images/buddies/${comment.personaId}.png` : '');

            state.pendingComment = comment;

            if (els().toastName) {
                els().toastName.textContent = name;
            }
            if (els().toastPreview) {
                els().toastPreview.textContent = previewText(comment.text, TOAST_PREVIEW_CHARS);
            }
            if (els().toastImage) {
                els().toastImage.src = portrait;
                els().toastImage.alt = name;
            }

            toast.classList.remove('hidden', 'fade-out');

            if (state.toastTimer) {
                clearTimeout(state.toastTimer);
            }
            state.toastTimer = setTimeout(() => {
                dismissToast();
            }, TOAST_AUTO_HIDE_MS);
        }

        function dismissToast() {
            const toast = els().toast;
            if (!toast || toast.classList.contains('hidden')) {
                state.pendingComment = null;
                return;
            }
            if (state.toastTimer) {
                clearTimeout(state.toastTimer);
                state.toastTimer = null;
            }
            toast.classList.add('fade-out');
            setTimeout(() => {
                toast.classList.add('hidden');
                toast.classList.remove('fade-out');
                state.pendingComment = null;
            }, 400);
        }

        async function quietForAWhile() {
            dismissToast();
            try {
                await putPreferences({ quietMinutes: QUIET_MINUTES });
                if (typeof host.onQuietApplied === 'function') {
                    host.onQuietApplied(QUIET_MINUTES);
                }
            } catch (error) {
                console.debug('Reading buddy quiet failed:', error);
            }
        }

        function isModalVisible() {
            const modal = els().chatModal;
            return !!(modal && !modal.classList.contains('hidden'));
        }

        function clearChatError() {
            state.chatRetryHandler = null;
            const err = els().chatError;
            if (err) err.classList.add('hidden');
            if (els().chatErrorMessage) els().chatErrorMessage.textContent = '';
            if (els().chatErrorRetry) els().chatErrorRetry.classList.add('hidden');
        }

        function setChatError(message, onRetry) {
            if (!els().chatError || !els().chatErrorMessage || !message) {
                return;
            }
            state.chatRetryHandler = typeof onRetry === 'function' ? onRetry : null;
            els().chatErrorMessage.textContent = message;
            els().chatError.classList.remove('hidden');
            if (els().chatErrorRetry) {
                els().chatErrorRetry.classList.toggle('hidden', !state.chatRetryHandler);
            }
        }

        function renderChatMessages() {
            const container = els().chatMessages;
            if (!container) return;
            container.innerHTML = renderHistoryMessagesHtml(state.chatHistory, host.escapeHtml || escapeHtml);
            container.scrollTop = container.scrollHeight;
        }

        async function loadHistory() {
            const id = bookId();
            const personaId = effectivePersonaId();
            if (!id || !personaId) {
                state.chatHistory = [];
                renderChatMessages();
                return;
            }
            const pos = position();
            const url = `/api/reading-buddy/history?bookId=${encodeURIComponent(id)}`
                + `&personaId=${encodeURIComponent(personaId)}`
                + `&readerChapterIndex=${pos.chapterIndex}`
                + `&readerParagraphIndex=${pos.paragraphIndex}`
                + `&includeHidden=true&limit=50`;
            try {
                const response = await host.fetch(url, { cache: 'no-store' });
                if (!response.ok) {
                    state.chatHistory = [];
                    renderChatMessages();
                    return;
                }
                const data = await response.json();
                state.chatHistory = Array.isArray(data.messages) ? data.messages : [];
            } catch (error) {
                console.debug('Reading buddy history failed:', error);
                state.chatHistory = [];
            }
            renderChatMessages();
        }

        async function openChat(options = {}) {
            if (!state.statusAvailable || !classroomAllowed()) {
                return;
            }
            if (!state.prefs.enabled || !effectivePersonaId() || !bookId()) {
                return;
            }

            dismissToast();
            if (typeof host.closeReaderSettingsPanel === 'function') {
                host.closeReaderSettingsPanel();
            }
            if (typeof host.ttsPauseForModal === 'function') {
                host.ttsPauseForModal();
            }

            const persona = findPersona(effectivePersonaId());
            if (els().chatName) {
                els().chatName.textContent = persona?.displayName || 'Reading Buddy';
            }
            if (els().chatPortrait) {
                const portrait = persona?.portraitUrl
                    || (effectivePersonaId() ? `/images/buddies/${effectivePersonaId()}.png` : '');
                els().chatPortrait.src = portrait;
                els().chatPortrait.alt = persona?.displayName || '';
            }

            clearChatError();
            const modal = els().chatModal;
            if (modal) {
                modal.classList.remove('hidden');
            }
            state.chatOpen = true;

            await loadHistory();

            // If opened from toast, ensure the proactive comment is visible (server history should include it)
            if (options.fromToast && state.pendingComment) {
                state.pendingComment = null;
            }

            if (els().chatInput) {
                els().chatInput.focus();
            }
        }

        function closeChat() {
            const modal = els().chatModal;
            if (modal) {
                modal.classList.add('hidden');
            }
            state.chatOpen = false;
            state.chatHistory = [];
            clearChatError();
            if (els().chatMessages) {
                els().chatMessages.innerHTML = '';
            }
            if (els().chatInput) {
                els().chatInput.value = '';
            }
            if (typeof host.ttsResumeAfterModal === 'function') {
                host.ttsResumeAfterModal();
            }
        }

        async function sendChatMessage(options = {}) {
            const retryMessage = typeof options.retryMessage === 'string' ? options.retryMessage : '';
            const appendUserMessage = options.appendUser !== false;
            const message = (retryMessage || (els().chatInput && els().chatInput.value) || '').trim();
            if (!message || state.chatLoading || !effectivePersonaId() || !bookId()) {
                return;
            }
            clearChatError();

            if (appendUserMessage) {
                state.chatHistory.push({
                    role: 'user',
                    content: message,
                    kind: 'chat',
                    visibleAtPosition: true,
                    chapterIndex: position().chapterIndex,
                    paragraphIndex: position().paragraphIndex
                });
                renderChatMessages();
                if (els().chatInput) {
                    els().chatInput.value = '';
                }
            }

            state.chatLoading = true;
            if (els().chatSend) {
                els().chatSend.disabled = true;
            }

            const loadingDiv = typeof document !== 'undefined' ? document.createElement('div') : null;
            if (loadingDiv && els().chatMessages) {
                loadingDiv.className = 'chat-message buddy loading';
                loadingDiv.textContent = 'Thinking';
                els().chatMessages.appendChild(loadingDiv);
                els().chatMessages.scrollTop = els().chatMessages.scrollHeight;
            }

            const pos = position();
            try {
                const response = await host.fetch('/api/reading-buddy/chat', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        bookId: bookId(),
                        personaId: effectivePersonaId(),
                        message,
                        readerChapterIndex: pos.chapterIndex,
                        readerParagraphIndex: pos.paragraphIndex
                    })
                });

                if (!response.ok) {
                    const payload = await response.json().catch(() => ({}));
                    const mapped = typeof host.mapChatError === 'function'
                        ? host.mapChatError({
                            status: response.status,
                            message: payload.message || payload.error
                        })
                        : { message: payload.message || 'Unable to send message.', retryable: true };
                    if (loadingDiv) loadingDiv.remove();
                    setChatError(
                        mapped.message,
                        mapped.retryable
                            ? () => sendChatMessage({ retryMessage: message, appendUser: false })
                            : null
                    );
                    return;
                }

                const data = await response.json().catch(() => ({}));
                if (loadingDiv) loadingDiv.remove();

                const reply = (data && typeof data.response === 'string') ? data.response.trim() : '';
                state.chatHistory.push({
                    id: data.messageId,
                    role: 'buddy',
                    content: reply || "I don't have enough context to answer that yet.",
                    kind: 'chat',
                    visibleAtPosition: true,
                    chapterIndex: pos.chapterIndex,
                    paragraphIndex: pos.paragraphIndex
                });
                renderChatMessages();
            } catch (error) {
                console.error('Reading buddy chat failed:', error);
                if (loadingDiv) loadingDiv.remove();
                const mapped = typeof host.mapChatError === 'function'
                    ? host.mapChatError({ network: true })
                    : { message: 'Chat service is unavailable right now.', retryable: true };
                setChatError(
                    mapped.message,
                    mapped.retryable
                        ? () => sendChatMessage({ retryMessage: message, appendUser: false })
                        : null
                );
            } finally {
                state.chatLoading = false;
                if (els().chatSend) {
                    els().chatSend.disabled = false;
                }
                if (els().chatInput) {
                    els().chatInput.focus();
                }
            }
        }

        async function clearHistory() {
            const id = bookId();
            const personaId = effectivePersonaId();
            if (!id || !personaId) {
                return;
            }
            if (typeof host.confirm === 'function') {
                const ok = host.confirm('Clear conversation history with this reading buddy for this book?');
                if (!ok) return;
            }
            try {
                const response = await host.fetch(
                    `/api/reading-buddy/history?bookId=${encodeURIComponent(id)}&personaId=${encodeURIComponent(personaId)}`,
                    { method: 'DELETE' }
                );
                if (!response.ok) {
                    return;
                }
                state.chatHistory = [];
                renderChatMessages();
            } catch (error) {
                console.debug('Reading buddy clear history failed:', error);
            }
        }

        async function setEnabled(enabled) {
            await putPreferences({ enabled: !!enabled });
            if (enabled) {
                state.advancesSinceSample = 0;
                state.enabledEverAdvanced = false;
            } else {
                dismissToast();
                if (state.chatOpen) {
                    closeChat();
                }
            }
        }

        async function setFrequency(frequency) {
            await putPreferences({ frequency: normalizeFrequency(frequency) });
        }

        async function setPersona(personaId) {
            if (!personaId) return;
            const id = bookId();
            // Book override when reading a book; otherwise global default
            const body = id
                ? { bookId: id, personaId }
                : { defaultPersonaId: personaId, personaId };
            await putPreferences(body);
            // Persona switch loads that persona's history thread (no auto-clear)
            if (state.chatOpen) {
                await loadHistory();
                const persona = findPersona(effectivePersonaId());
                if (els().chatName) {
                    els().chatName.textContent = persona?.displayName || 'Reading Buddy';
                }
                if (els().chatPortrait && persona) {
                    els().chatPortrait.src = persona.portraitUrl || `/images/buddies/${persona.id}.png`;
                }
            }
            syncSettingsPanel();
        }

        async function onBookOpened() {
            state.advancesSinceSample = 0;
            state.pendingComment = null;
            state.checkRequestId += 1;
            dismissToast();
            if (state.chatOpen) {
                closeChat();
            }
            await checkAvailability();
            if (state.statusAvailable) {
                await loadPersonas();
                await loadPreferences();
            } else {
                syncSettingsPanel();
            }
        }

        function bindEvents() {
            const e = els();
            if (e.toggle) {
                e.toggle.addEventListener('change', () => {
                    void setEnabled(e.toggle.checked).catch(err => console.debug(err));
                });
            }
            if (e.frequency) {
                e.frequency.addEventListener('change', () => {
                    void setFrequency(e.frequency.value).catch(err => console.debug(err));
                });
            }
            if (e.personaList) {
                e.personaList.addEventListener('click', (event) => {
                    const card = event.target.closest('[data-persona-id]');
                    if (!card) return;
                    const id = card.getAttribute('data-persona-id');
                    void setPersona(id).catch(err => console.debug(err));
                });
            }
            if (e.talkBtn) {
                e.talkBtn.addEventListener('click', () => {
                    void openChat();
                });
            }
            if (e.toastOpen) {
                e.toastOpen.addEventListener('click', (event) => {
                    event.stopPropagation();
                    void openChat({ fromToast: true });
                });
            }
            if (e.toastDismiss) {
                e.toastDismiss.addEventListener('click', (event) => {
                    event.stopPropagation();
                    dismissToast();
                });
            }
            if (e.toastQuiet) {
                e.toastQuiet.addEventListener('click', (event) => {
                    event.stopPropagation();
                    void quietForAWhile();
                });
            }
            if (e.chatClose) {
                e.chatClose.addEventListener('click', closeChat);
            }
            if (e.chatModal) {
                e.chatModal.querySelector('.character-modal-backdrop')?.addEventListener('click', closeChat);
            }
            if (e.chatSend) {
                e.chatSend.addEventListener('click', () => void sendChatMessage());
            }
            if (e.chatErrorRetry) {
                e.chatErrorRetry.addEventListener('click', () => {
                    if (typeof state.chatRetryHandler === 'function') {
                        state.chatRetryHandler();
                    }
                });
            }
            if (e.clearHistoryBtn) {
                e.clearHistoryBtn.addEventListener('click', () => void clearHistory());
            }
            if (e.chatInput) {
                e.chatInput.addEventListener('keydown', (event) => {
                    if (event.key === 'Enter' && !event.shiftKey) {
                        event.preventDefault();
                        void sendChatMessage();
                    }
                });
            }
        }

        function handleEscape() {
            if (isModalVisible()) {
                closeChat();
                return true;
            }
            return false;
        }

        function refreshClassroomGating() {
            // Re-evaluate availability after classroom context loads/changes
            void checkAvailability().then(available => {
                if (available && !state.personas.length) {
                    return loadPersonas().then(() => loadPreferences());
                }
                if (available && bookId()) {
                    return loadPreferences();
                }
                syncSettingsPanel();
                return null;
            });
        }

        return {
            checkAvailability,
            loadPersonas,
            loadPreferences,
            onBookOpened,
            onParagraphAdvanced,
            onPageRendered,
            openChat,
            closeChat,
            isModalVisible,
            handleEscape,
            bindEvents,
            syncSettingsPanel,
            refreshClassroomGating,
            getState: () => state,
            // exposed for tests / debugging
            evaluateClientGates,
            isFeatureAvailable
        };
    }

    return {
        MIN_PARAGRAPH_CHARS,
        DWELL_MS,
        TOAST_AUTO_HIDE_MS,
        QUIET_MINUTES,
        TOAST_PREVIEW_CHARS,
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
        createController
    };
});
