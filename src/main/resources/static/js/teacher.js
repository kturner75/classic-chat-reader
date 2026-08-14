(function () {
    'use strict';

    const state = {
        account: null,
        capabilities: null,
        classes: [],
        selectedClass: null,
        books: [],
        roster: [],
        assignments: [],
        features: null,
        readingBuddyStatus: null,
        editingAssignmentId: null,
        provisionalAssignmentId: null,
        assignmentScreen: 'assign1',
        quizChoice: '',
        quizHost: 'standalone',
        quizMode: 'default',
        quizAuthorIndex: 0,
        quizHasGenerated: false,
        quizOverrideConfirmed: false,
        quizSimIndex: 0,
        quizSimAnswers: [],
        quizSimAttempt: 1,
        quizSimPhase: 'taking',
        quizSimScore: 0,
        quizSimSeed: 11,
        quizSuggestBusy: null,
        featureSaveTimer: null,
        activeBookOption: -1,
        selectedChapterIds: [],
        quizWizardStep: 1,
        quizDraftQuestions: [],
        quizGeneratedBase: [],
        quizContentVersion: null,
        activeQuizBookOption: -1,
        activeQuizChapterOption: -1,
        studentOverviewUserId: null,
        studentOverviewReturnFocus: null
    };

    const el = Object.fromEntries(Array.from(document.querySelectorAll('[id]')).map(node => [node.id, node]));

    function show(node, visible = true) {
        if (node) node.classList.toggle('hidden', !visible);
    }

    function escapeHtml(value) {
        return String(value ?? '')
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#039;');
    }

    async function api(path, options = {}) {
        const response = await fetch(path, {
            cache: 'no-store',
            ...options,
            headers: options.body ? { 'Content-Type': 'application/json', ...(options.headers || {}) } : options.headers
        });
        let payload = null;
        const type = response.headers.get('content-type') || '';
        if (type.includes('application/json')) {
            payload = await response.json();
        }
        if (!response.ok) {
            const message = payload?.detail || payload?.message || payload?.error || `Request failed (${response.status})`;
            const error = new Error(message);
            error.status = response.status;
            throw error;
        }
        return payload;
    }

    function setPageState(name, message = '') {
        show(el['loading-state'], name === 'loading');
        show(el['signed-out-state'], name === 'signed-out');
        show(el['access-denied-state'], name === 'access-denied');
        show(el['error-state'], name === 'error');
        show(el['teacher-app'], name === 'ready');
        if (message) el['error-message'].textContent = message;
    }

    function toast(message) {
        el.toast.textContent = message;
        show(el.toast, true);
        window.clearTimeout(toast.timer);
        toast.timer = window.setTimeout(() => show(el.toast, false), 3200);
    }

    function formatDate(value) {
        if (!value) return '—';
        const date = new Date(`${value}T12:00:00`);
        return Number.isNaN(date.getTime())
            ? value
            : new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' }).format(date);
    }

    function bookById(id) {
        return state.books.find(book => book.id === id);
    }

    function sortedBooks() {
        return [...state.books].sort((a, b) => {
            const titleComparison = String(a.title || '').localeCompare(String(b.title || ''), undefined, {
                sensitivity: 'base',
                numeric: true
            });
            if (titleComparison !== 0) return titleComparison;
            const authorComparison = String(a.author || '').localeCompare(String(b.author || ''), undefined, {
                sensitivity: 'base',
                numeric: true
            });
            return authorComparison || String(a.id || '').localeCompare(String(b.id || ''));
        });
    }

    function bookDisplayName(book) {
        return `${book.title || 'Untitled'}${book.author ? ` · ${book.author}` : ''}`;
    }

    function classById(id) {
        return state.classes.find(item => item.classId === id);
    }

    async function initialize() {
        setPageState('loading');
        try {
            const [account, capabilities, books, readingBuddyStatus] = await Promise.all([
                api('/api/account/status'),
                api('/api/classroom/capabilities'),
                api('/api/library').catch(() => []),
                api('/api/reading-buddy/status').catch(() => ({ available: false }))
            ]);
            state.account = account;
            state.capabilities = capabilities || {};
            state.books = Array.isArray(books) ? books : [];
            state.readingBuddyStatus = readingBuddyStatus || { available: false };
            el['account-email'].textContent = account?.authenticated ? account.email || 'Signed in' : '';
            show(el['sign-out'], account?.authenticated === true);
            if (!account?.authenticated) {
                setPageState('signed-out');
                return;
            }
            if (state.capabilities.canTeach !== true) {
                setPageState('access-denied');
                return;
            }
            state.classes = await api('/api/classroom/classes');
            setPageState('ready');
            renderClassPicker();
            if (state.classes.length > 0) {
                await selectClass(state.classes[0].classId);
            } else {
                state.selectedClass = null;
                renderNoClasses();
            }
        } catch (error) {
            console.error(error);
            setPageState(error.status === 401 ? 'signed-out' : 'error', error.message);
        }
    }

    function renderClassPicker() {
        const hasClasses = state.classes.length > 0;
        show(el['class-selector-label'], hasClasses);
        el['class-selector'].innerHTML = state.classes.map(item => (
            `<option value="${escapeHtml(item.classId)}">${escapeHtml(item.className)} · ${escapeHtml(item.activeTermName || 'No active term')}</option>`
        )).join('');
        if (state.selectedClass) el['class-selector'].value = state.selectedClass.classId;
    }

    function renderNoClasses() {
        el['workspace-title'].textContent = 'Your classrooms';
        const canCreateClass = state.capabilities?.canCreateClass === true;
        el['workspace-subtitle'].textContent = canCreateClass
            ? 'Create a class and invite students to begin.'
            : 'No classrooms are currently assigned to this account.';
        el['empty-class-state'].querySelector('h2').textContent = canCreateClass
            ? 'Create your first classroom'
            : 'No classrooms available';
        el['empty-class-state'].querySelector('.onboarding-copy p:last-child').textContent = canCreateClass
            ? 'Create a semester workspace, choose the reading features available to students, and share one secure join link.'
            : 'Ask a classroom administrator to add you to a teaching term.';
        show(el['empty-class-state'], true);
        show(el['classroom-content'], false);
        show(el['new-class-button'], false);
        show(el['empty-create-button'], canCreateClass);
    }

    async function selectClass(classId) {
        const selected = classById(classId);
        if (!selected?.activeTermId) {
            toast('This class does not have an active term.');
            return;
        }
        state.selectedClass = selected;
        el['class-selector'].value = selected.classId;
        show(el['empty-class-state'], false);
        show(el['classroom-content'], true);
        show(el['new-class-button'], state.capabilities?.canCreateClass === true);
        el['workspace-title'].textContent = selected.className;
        el['workspace-subtitle'].textContent = `${selected.activeTermName || 'Current term'} · Classroom management`;
        el['class-name'].textContent = selected.className;
        el['term-name'].textContent = selected.code
            ? `${selected.activeTermName || 'Current term'} · ${selected.code}`
            : selected.activeTermName || 'Current term';
        resetInvite();
        await loadSelectedClass();
    }

    async function loadSelectedClass() {
        const termId = state.selectedClass.activeTermId;
        setClassroomBusy(true);
        try {
            const [roster, assignments, features] = await Promise.all([
                api(`/api/classroom/terms/${encodeURIComponent(termId)}/roster`),
                api(`/api/classroom/terms/${encodeURIComponent(termId)}/assignments`),
                api(`/api/classroom/terms/${encodeURIComponent(termId)}/features`)
            ]);
            state.roster = Array.isArray(roster) ? roster : [];
            state.assignments = Array.isArray(assignments) ? assignments : [];
            state.features = features || {};
            renderRoster();
            renderAssignments();
            renderFeatures();
        } catch (error) {
            toast(error.message || 'Unable to load this classroom.');
        } finally {
            setClassroomBusy(false);
        }
    }

    function setClassroomBusy(busy) {
        el['class-selector'].disabled = busy;
        el['new-assignment-button'].disabled = busy;
    }

    function renderRoster() {
        el['student-count'].textContent = String(state.roster.length);
        const hasStudents = state.roster.length > 0;
        show(el['roster-empty'], !hasStudents);
        show(el['roster-table'], hasStudents);
        el['roster-body'].innerHTML = state.roster.map(row => {
            const preferredName = row.displayNameOverride || row.email || 'Student';
            const secondary = row.displayNameOverride && row.email ? row.email : '';
            const userId = row.userId || '';
            // Row click is progressive enhancement only — the Overview button is the accessible control.
            return `<tr class="roster-row" data-user-id="${escapeHtml(userId)}">
                <td><span class="student-name">${escapeHtml(preferredName)}</span>${secondary ? `<span class="student-email">${escapeHtml(secondary)}</span>` : ''}</td>
                <td>${escapeHtml(formatDate(row.joinedDate))}</td>
                <td><span class="status-pill">${escapeHtml((row.status || 'active').toLowerCase())}</span></td>
                <td><button class="secondary-button roster-open-button" type="button" data-open-student="${escapeHtml(userId)}" aria-label="Open overview for ${escapeHtml(preferredName)}">Overview</button></td>
            </tr>`;
        }).join('');
    }

    function isModalOpen(node) {
        return Boolean(node && !node.classList.contains('hidden'));
    }

    function focusStudentOverviewClose() {
        const closeButton = el['student-overview-close']
            || el['student-overview-modal']?.querySelector('[data-close-student-overview]');
        if (closeButton && typeof closeButton.focus === 'function') {
            closeButton.focus();
            return;
        }
        const page = el['student-overview-modal']?.querySelector('.student-overview-page');
        if (page && typeof page.focus === 'function') {
            page.focus();
        }
    }

    function setStudentOverviewBusy(busy) {
        const modal = el['student-overview-modal'];
        if (!modal) return;
        modal.setAttribute('aria-busy', busy ? 'true' : 'false');
    }

    function formatDuration(ms) {
        const totalSeconds = Math.max(0, Math.round(Number(ms) / 1000));
        if (totalSeconds < 60) return `${totalSeconds}s`;
        const minutes = Math.floor(totalSeconds / 60);
        const seconds = totalSeconds % 60;
        if (minutes < 60) return seconds ? `${minutes}m ${seconds}s` : `${minutes}m`;
        const hours = Math.floor(minutes / 60);
        const remMinutes = minutes % 60;
        return remMinutes ? `${hours}h ${remMinutes}m` : `${hours}h`;
    }

    function formatDateTime(value) {
        if (!value) return '—';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return value;
        return new Intl.DateTimeFormat(undefined, {
            month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit'
        }).format(date);
    }

    function statusPillClass(label) {
        const normalized = String(label || '').toLowerCase();
        if (normalized.includes('complete')) return 'completed';
        if (normalized.includes('progress')) return 'in-progress';
        return 'not-started';
    }

    const OVERVIEW_COLLAPSE_STORAGE_KEY = 'teacher_overviewCollapsedSections';

    function readOverviewCollapsedSections() {
        try {
            const raw = localStorage.getItem(OVERVIEW_COLLAPSE_STORAGE_KEY);
            const parsed = raw ? JSON.parse(raw) : {};
            return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
        } catch (_error) {
            return {};
        }
    }

    function isOverviewSectionCollapsed(sectionId) {
        return readOverviewCollapsedSections()[sectionId] === true;
    }

    function persistOverviewSectionCollapsed(sectionId, collapsed) {
        if (!sectionId) return;
        try {
            const current = readOverviewCollapsedSections();
            if (collapsed) {
                current[sectionId] = true;
            } else {
                delete current[sectionId];
            }
            localStorage.setItem(OVERVIEW_COLLAPSE_STORAGE_KEY, JSON.stringify(current));
        } catch (_error) {
            // Private mode or quota — keep the in-page toggle working.
        }
    }

    function applyOverviewSectionCollapsed(section, collapsed) {
        if (!section) return;
        const toggle = section.querySelector('[data-overview-collapse]');
        const panel = section.querySelector('.overview-section-panel');
        section.classList.toggle('is-collapsed', collapsed);
        if (toggle) toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
        if (panel) panel.classList.toggle('hidden', collapsed);
    }

    function renderCollapsibleOverviewSection(sectionId, title, count, bodyHtml) {
        const collapsed = isOverviewSectionCollapsed(sectionId);
        const panelId = `overview-section-${sectionId}`;
        return `<section class="overview-section${collapsed ? ' is-collapsed' : ''}" data-overview-section="${escapeHtml(sectionId)}">
            <h3>
                <button type="button" class="overview-collapse-toggle" data-overview-collapse="${escapeHtml(sectionId)}" aria-expanded="${collapsed ? 'false' : 'true'}" aria-controls="${escapeHtml(panelId)}">
                    <span class="overview-collapse-label">${escapeHtml(title)}</span>
                    <span class="overview-collapse-count">${escapeHtml(String(count))}</span>
                    <span class="overview-collapse-icon" aria-hidden="true"></span>
                </button>
            </h3>
            <div id="${escapeHtml(panelId)}" class="overview-section-panel${collapsed ? ' hidden' : ''}">
                ${bodyHtml}
            </div>
        </section>`;
    }

    function renderOverviewMetric(value, label) {
        return `<div class="overview-metric">
            <span class="overview-metric-value">${escapeHtml(value)}</span>
            ${label ? `<span class="overview-metric-label">${escapeHtml(label)}</span>` : ''}
        </div>`;
    }

    function renderAssignmentOverviewList(items) {
        if (!Array.isArray(items) || items.length === 0) {
            return '<p class="overview-empty">None</p>';
        }
        return `<ul class="overview-list">${items.map(item => {
            const openLabel = item.opened ? 'Opened' : 'Not opened';
            const openClass = item.opened ? 'opened' : 'not-opened';
            const quizBits = [];
            if (item.quizRequired) {
                quizBits.push(`Quiz ${item.quizStatus || 'PENDING'}`);
                if (item.quizAttemptsUsed != null) {
                    const allowed = item.quizAttemptsAllowed != null ? `/${item.quizAttemptsAllowed}` : '';
                    quizBits.push(`Attempts ${item.quizAttemptsUsed}${allowed}`);
                }
            }
            return `<li class="overview-item">
                <strong>${escapeHtml(item.title || 'Assignment')}</strong>
                <div class="meta">
                    <span>${escapeHtml(item.bookTitle || 'Book')}</span>
                    <span>${escapeHtml(overviewChapterLabel(item))}</span>
                    ${item.dueDate ? `<span>Due ${escapeHtml(formatDate(item.dueDate))}</span>` : ''}
                    <span class="status-pill ${statusPillClass(item.statusLabel)}">${escapeHtml(item.statusLabel || '—')}</span>
                    <span class="status-pill ${openClass}">${openLabel}${item.firstOpenedAt ? ` · ${escapeHtml(formatDateTime(item.firstOpenedAt))}` : ''}</span>
                    ${quizBits.map(bit => `<span>${escapeHtml(bit)}</span>`).join('')}
                </div>
            </li>`;
        }).join('')}</ul>`;
    }

    function renderStudentOverview(overview) {
        const student = overview.student || {};
        const preferred = student.displayNameOverride || student.email || 'Student';
        el['student-overview-title'].textContent = preferred;
        el['student-overview-subtitle'].textContent = student.displayNameOverride && student.email
            ? student.email
            : (student.joinedDate ? `Joined ${formatDate(student.joinedDate)}` : 'Rostered student');

        const time = overview.timeInReader || {};
        const timeBooks = Array.isArray(time.byBook) ? time.byBook : [];
        const progress = Array.isArray(overview.progressByBook) ? overview.progressByBook : [];
        const quizzes = Array.isArray(overview.quizzesForBook) ? overview.quizzesForBook : [];

        const completedAssignments = Array.isArray(overview.completedAssignments) ? overview.completedAssignments : [];

        el['student-overview-body'].innerHTML = `
            <section class="overview-section">
                <h3>Current assignments</h3>
                ${renderAssignmentOverviewList(overview.currentAssignments)}
            </section>
            ${renderCollapsibleOverviewSection(
                'completed-assignments',
                'Completed assignments',
                completedAssignments.length,
                renderAssignmentOverviewList(completedAssignments)
            )}
            <section class="overview-section">
                <h3>Progress by book</h3>
                ${progress.length === 0 ? '<p class="overview-empty">No assigned books yet.</p>' : `<ul class="overview-list">${progress.map(row => `
                    <li class="overview-item overview-item-metric">
                        <div class="overview-item-copy">
                            <strong>${escapeHtml(row.bookTitle || 'Book')}</strong>
                            <div class="meta">
                                <span>Chapter ${escapeHtml(row.chapterLabel || '—')}</span>
                                ${row.lastReadAt ? `<span>Last read ${escapeHtml(formatDateTime(row.lastReadAt))}</span>` : ''}
                            </div>
                        </div>
                        ${renderOverviewMetric(`${row.percentComplete ?? 0}%`, 'complete')}
                    </li>`).join('')}</ul>`}
            </section>
            <section class="overview-section">
                <h3>Quizzes</h3>
                ${quizzes.length === 0 ? '<p class="overview-empty">No quiz-required assignments.</p>' : `<ul class="overview-list">${quizzes.map(quiz => {
                    const retries = quiz.retryAttemptsUsed != null ? quiz.retryAttemptsUsed : Math.max(0, (quiz.attemptsUsed || 0) - 1);
                    const allowed = quiz.attemptsAllowed != null ? ` of ${quiz.attemptsAllowed}` : '';
                    const hasAttempt = (quiz.attemptsUsed || 0) > 0 || Boolean(quiz.latestAttemptAt);
                    const fraction = quiz.totalQuestions != null
                        ? `${quiz.bestCorrectAnswers}/${quiz.totalQuestions}`
                        : '';
                    const metricValue = hasAttempt ? `${quiz.bestScorePercent ?? 0}%` : '—';
                    const metricLabel = hasAttempt
                        ? (fraction ? `${fraction} best` : 'best score')
                        : 'no attempts';
                    return `<li class="overview-item overview-item-metric">
                        <div class="overview-item-copy">
                            <strong>${escapeHtml(quiz.assignmentTitle || quiz.chapterTitle || 'Quiz')}</strong>
                            <div class="meta">
                                <span>${escapeHtml(quiz.bookTitle || 'Book')}</span>
                                ${quiz.chapterTitle ? `<span>${escapeHtml(quiz.chapterTitle)}</span>` : ''}
                                <span class="status-pill ${quiz.complete ? 'completed' : 'in-progress'}">${quiz.complete ? 'Complete' : 'Incomplete'}</span>
                                <span>Attempts ${escapeHtml(String(quiz.attemptsUsed || 0))}${escapeHtml(allowed)}</span>
                                <span>Retries used ${escapeHtml(String(retries))}</span>
                                ${quiz.latestAttemptAt ? `<span>Latest ${escapeHtml(formatDateTime(quiz.latestAttemptAt))}</span>` : ''}
                            </div>
                        </div>
                        ${renderOverviewMetric(metricValue, metricLabel)}
                    </li>`;
                }).join('')}</ul>`}
            </section>
            <section class="overview-section">
                <h3>${escapeHtml(time.label || 'Approximate time in reader')}</h3>
                <p class="hint">${escapeHtml(time.caveat || 'Approximate engagement proxy from reader heartbeats.')}</p>
                <ul class="overview-list">
                    <li class="overview-item">
                        <strong>${escapeHtml(formatDuration(time.approximateTotalMs || 0))} total</strong>
                        <div class="meta">
                            ${timeBooks.length === 0
                                ? '<span>No heartbeat time recorded yet for this term.</span>'
                                : timeBooks.map(row => `<span>${escapeHtml(row.bookTitle || 'Book')}: ${escapeHtml(formatDuration(row.approximateMs || 0))}</span>`).join('')}
                        </div>
                    </li>
                </ul>
            </section>
        `;
        el['student-overview-ferpa'].textContent = overview.ferpaNote || '';
        show(el['student-overview-ferpa'], Boolean(overview.ferpaNote));
        show(el['student-overview-body'], true);
    }

    async function openStudentOverview(userId, activator = null) {
        // ClassSummary exposes activeTermId (not termId); prefer it for consistency with loadSelectedClass/invites/etc.
        const termId = state.selectedClass?.activeTermId || state.selectedClass?.termId;
        if (!termId || !userId) return;
        const preferredActivator = activator
            || el['roster-body']?.querySelector(`[data-open-student="${CSS.escape(userId)}"]`)
            || document.activeElement;
        state.studentOverviewReturnFocus = preferredActivator instanceof HTMLElement ? preferredActivator : null;
        state.studentOverviewUserId = userId;
        const rosterRow = state.roster.find(row => row.userId === userId);
        const preferred = rosterRow?.displayNameOverride || rosterRow?.email || 'Student';
        el['student-overview-title'].textContent = preferred;
        el['student-overview-subtitle'].textContent = 'Loading…';
        show(el['student-overview-modal'], true);
        el['student-overview-modal'].scrollTop = 0;
        setPageWizardOpen();
        setStudentOverviewBusy(true);
        show(el['student-overview-loading'], true);
        show(el['student-overview-error'], false);
        show(el['student-overview-body'], false);
        show(el['student-overview-ferpa'], false);
        focusStudentOverviewClose();
        try {
            const overview = await api(
                `/api/classroom/terms/${encodeURIComponent(termId)}/students/${encodeURIComponent(userId)}/overview`
            );
            if (state.studentOverviewUserId !== userId) return;
            renderStudentOverview(overview);
        } catch (error) {
            el['student-overview-error'].textContent = error.message || 'Unable to load student overview.';
            show(el['student-overview-error'], true);
        } finally {
            show(el['student-overview-loading'], false);
            setStudentOverviewBusy(false);
            if (state.studentOverviewUserId === userId && isModalOpen(el['student-overview-modal'])) {
                focusStudentOverviewClose();
            }
        }
    }

    function closeStudentOverview() {
        if (!isModalOpen(el['student-overview-modal'])) {
            return;
        }
        const returnUserId = state.studentOverviewUserId;
        state.studentOverviewUserId = null;
        setStudentOverviewBusy(false);
        show(el['student-overview-modal'], false);
        setPageWizardOpen();
        // Prefer the stored activator; fall back to that student's Overview button.
        const stored = state.studentOverviewReturnFocus;
        if (stored && typeof stored.focus === 'function' && document.contains(stored)) {
            state.studentOverviewReturnFocus = null;
            stored.focus();
            return;
        }
        state.studentOverviewReturnFocus = null;
        const fallback = returnUserId
            ? el['roster-body']?.querySelector(`[data-open-student="${CSS.escape(returnUserId)}"]`)
            : null;
        if (fallback && typeof fallback.focus === 'function') {
            fallback.focus();
        }
    }

    function closeTopmostModalOnEscape(event) {
        if (event.key !== 'Escape') return;
        // Close only the topmost open surface (student overview stacks above the wizards).
        if (isModalOpen(el['student-overview-modal'])) {
            event.preventDefault();
            closeStudentOverview();
            return;
        }
        if (isModalOpen(el['quiz-wizard-modal'])) {
            event.preventDefault();
            closeQuizWizard();
            return;
        }
        if (isModalOpen(el['assignment-modal'])) {
            event.preventDefault();
            closeAssignmentModal();
            return;
        }
        if (isModalOpen(el['class-modal'])) {
            event.preventDefault();
            closeClassModal();
        }
    }

    function overviewChapterLabel(item) {
        const chapters = Array.isArray(item?.chapters) ? item.chapters : [];
        if (chapters.length === 0 && !item?.chapterTitle && !Number.isInteger(item?.chapterIndex)) {
            return 'Whole book';
        }
        if (chapters.length === 1) {
            return chapters[0].chapterTitle
                || (Number.isInteger(chapters[0].chapterIndex) ? `Chapter ${chapters[0].chapterIndex + 1}` : '1 chapter');
        }
        if (chapters.length > 1) {
            if (chapters.length <= 3 && chapters.every(chapter => chapter.chapterTitle)) {
                return chapters.map(chapter => chapter.chapterTitle).join(', ');
            }
            return `${chapters.length} chapters`;
        }
        return item?.chapterTitle || (Number.isInteger(item?.chapterIndex) ? `Chapter ${item.chapterIndex + 1}` : 'Whole book');
    }

    function assignmentTarget(assignment) {
        const book = bookById(assignment.bookId);
        if (!book) return 'Book unavailable';
        const chapters = Array.isArray(assignment.chapters) ? assignment.chapters : [];
        if (chapters.length === 0 && !assignment.chapterId) {
            return book.title;
        }
        if (chapters.length === 1 || assignment.chapterId) {
            const chapter = book.chapters?.find(item => item.id === (chapters[0]?.chapterId || assignment.chapterId))
                || (Number.isInteger(assignment.chapterIndex) ? book.chapters?.[assignment.chapterIndex] : null);
            return chapter ? `${book.title} · ${chapter.title}` : book.title;
        }
        if (chapters.length <= 3) {
            const titles = chapters.map(item => item.chapterTitle || item.chapterId).filter(Boolean);
            return `${book.title} · ${titles.join(', ')}`;
        }
        return `${book.title} · ${chapters.length} chapters`;
    }

    function renderAssignments() {
        const assignments = [...state.assignments].sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));
        const published = assignments.filter(item => item.status === 'PUBLISHED').length;
        el['published-count'].textContent = String(published);
        show(el['assignments-empty'], assignments.length === 0);
        el['assignment-list'].innerHTML = assignments.map(item => {
            const details = [assignmentTarget(item)];
            if (item.dueDate) details.push(`Due ${formatDate(item.dueDate)}`);
            if (item.quizRequired) {
                if (item.quizPassMinCorrect != null && item.quizMaxRetries != null) {
                    details.push(`Quiz pass ${item.quizPassMinCorrect}+ correct, ${item.quizMaxRetries} retries`);
                } else {
                    details.push('Quiz required');
                }
            }
            if (item.characterChatRequired) details.push('Character chat required');
            const draft = String(item.status || '').toUpperCase() === 'DRAFT';
            return `<article class="assignment-card">
                <div>
                    <h3>${escapeHtml(item.title)}</h3>
                    <div class="assignment-meta">
                        <span class="assignment-status ${(item.status || '').toLowerCase()}">${escapeHtml((item.status || 'draft').toLowerCase())}</span>
                        ${details.map(detail => `<span>${escapeHtml(detail)}</span>`).join('')}
                    </div>
                </div>
                <div class="assignment-card-actions">
                    <button class="secondary-button" type="button" data-edit-assignment="${escapeHtml(item.assignmentId)}">Edit</button>
                    ${draft ? `<button class="text-button" type="button" data-delete-assignment="${escapeHtml(item.assignmentId)}">Delete draft</button>` : ''}
                </div>
            </article>`;
        }).join('');
    }

    function renderFeatures() {
        Array.from(el['features-form'].elements).forEach(input => {
            if (input.type === 'checkbox') {
                input.checked = state.features?.[input.name] !== false;
                if (input.name === 'readingBuddyEnabled') {
                    const available = state.readingBuddyStatus?.available === true;
                    input.disabled = !available;
                    input.closest('.feature-control')?.classList.toggle('feature-unavailable', !available);
                    el['reading-buddy-feature-description'].textContent = available
                        ? 'Available in this deployment; this saved policy controls student access'
                        : `Unavailable in this deployment. Saved classroom policy: ${input.checked ? 'On' : 'Off'}; it will apply automatically when available.`;
                }
                return;
            }
            if (input.type === 'number') {
                const value = state.features?.[input.name];
                input.value = value == null || value === '' ? '' : String(value);
            }
        });
    }

    function syncAssignmentPassRuleVisibility() {
        const required = state.quizChoice === 'require';
        show(el['assignment-quiz-pass-rules'], required);
        if (el['assignment-quiz-required']) {
            el['assignment-quiz-required'].value = required ? 'on' : '';
        }
        document.querySelectorAll('[data-quiz-choice]').forEach(button => {
            button.setAttribute('aria-pressed', String(button.dataset.quizChoice === state.quizChoice));
        });
        if (el['assignment-next-2']) {
            el['assignment-next-2'].textContent = required ? 'Define quiz' : 'Next';
        }
    }

    function resetInvite() {
        el['invite-link'].value = '';
        show(el['invite-value-row'], false);
        show(el['generate-invite'], true);
        el['invite-help'].textContent = 'Generate a link for students to join this term with their reader accounts.';
    }

    function displayInvite(code) {
        const url = new URL('/', window.location.origin);
        url.searchParams.set('join', code);
        el['invite-link'].value = url.toString();
        show(el['invite-value-row'], true);
        show(el['generate-invite'], false);
        el['invite-help'].textContent = 'Anyone with this link can request enrollment in this active term.';
    }

    async function generateInvite() {
        if (!state.selectedClass) return;
        el['generate-invite'].disabled = true;
        try {
            const result = await api(`/api/classroom/terms/${encodeURIComponent(state.selectedClass.activeTermId)}/invites`, {
                method: 'POST', body: JSON.stringify({ label: 'Teacher workspace invite' })
            });
            displayInvite(result.code);
            toast('Student join link generated.');
        } catch (error) {
            toast(error.message);
        } finally {
            el['generate-invite'].disabled = false;
        }
    }

    async function copyInvite() {
        const value = el['invite-link'].value;
        if (!value) return;
        try {
            await navigator.clipboard.writeText(value);
            el['copy-invite'].textContent = 'Copied';
            window.setTimeout(() => { el['copy-invite'].textContent = 'Copy'; }, 1800);
        } catch (_error) {
            el['invite-link'].select();
            document.execCommand('copy');
        }
    }

    function openClassModal() {
        el['class-form'].reset();
        el['create-term-name'].value = new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' }).format(new Date());
        show(el['class-form-error'], false);
        show(el['class-modal'], true);
        window.setTimeout(() => el['create-class-name'].focus(), 0);
    }

    function closeClassModal() {
        show(el['class-modal'], false);
    }

    async function createClass(event) {
        event.preventDefault();
        const form = new FormData(el['class-form']);
        const body = {
            name: String(form.get('name') || '').trim(),
            code: String(form.get('code') || '').trim() || null,
            termName: String(form.get('termName') || '').trim(),
            startDate: form.get('startDate') || null,
            endDate: form.get('endDate') || null,
            features: {
                quizEnabled: true,
                recapEnabled: false,
                ttsEnabled: true,
                illustrationEnabled: true,
                characterEnabled: true,
                chatEnabled: true,
                speedReadingEnabled: true,
                readingBuddyEnabled: true
            }
        };
        el['create-class-submit'].disabled = true;
        show(el['class-form-error'], false);
        try {
            const created = await api('/api/classroom/classes', { method: 'POST', body: JSON.stringify(body) });
            const summary = {
                classId: created.classId,
                className: created.className,
                code: body.code,
                activeTermId: created.termId,
                activeTermName: created.termName
            };
            state.classes.push(summary);
            closeClassModal();
            renderClassPicker();
            await selectClass(summary.classId);
            displayInvite(created.inviteCode);
            toast('Classroom created. Your student join link is ready.');
        } catch (error) {
            el['class-form-error'].textContent = error.message;
            show(el['class-form-error'], true);
        } finally {
            el['create-class-submit'].disabled = false;
        }
    }

    function positionAnchoredList(list, anchor) {
        if (!list || !anchor || list.classList.contains('hidden')) return;
        const rect = anchor.getBoundingClientRect();
        const spaceBelow = window.innerHeight - rect.bottom - 12;
        const spaceAbove = rect.top - 80;
        const maxHeight = Math.max(140, Math.min(320, spaceBelow >= 160 ? spaceBelow : spaceAbove));
        const openUp = spaceBelow < 160 && spaceAbove > spaceBelow;
        list.style.position = 'fixed';
        list.style.left = `${Math.round(rect.left)}px`;
        list.style.width = `${Math.round(rect.width)}px`;
        list.style.right = 'auto';
        list.style.maxHeight = `${Math.round(maxHeight)}px`;
        if (openUp) {
            list.style.top = 'auto';
            list.style.bottom = `${Math.round(window.innerHeight - rect.top + 4)}px`;
        } else {
            list.style.bottom = 'auto';
            list.style.top = `${Math.round(rect.bottom + 4)}px`;
        }
    }

    function syncOpenBookLists() {
        positionAnchoredList(el['assignment-book-options'], el['assignment-book-search']);
        positionAnchoredList(el['quiz-book-options'], el['quiz-book-search']);
        positionAnchoredList(el['quiz-chapter-options'], el['quiz-chapter-search']);
    }

    function setPageWizardOpen() {
        const open = isModalOpen(el['assignment-modal'])
            || isModalOpen(el['quiz-wizard-modal'])
            || isModalOpen(el['student-overview-modal']);
        document.body.classList.toggle('page-wizard-open', open);
    }

    function populateBookOptions(selectedId = '') {
        const selectedBook = bookById(selectedId);
        el['assignment-book'].value = selectedId;
        el['assignment-book-search'].value = selectedBook ? bookDisplayName(selectedBook) : '';
        el['assignment-book-search'].setCustomValidity('');
        closeBookOptions();
        populateChapterOptions([], selectedId);
    }

    function matchingBooks(query) {
        const normalizedQuery = String(query || '').trim().toLocaleLowerCase();
        return sortedBooks().filter(book => (
            !normalizedQuery
            || String(book.title || '').toLocaleLowerCase().includes(normalizedQuery)
            || String(book.author || '').toLocaleLowerCase().includes(normalizedQuery)
        ));
    }

    function renderBookOptions() {
        const books = matchingBooks(el['assignment-book-search'].value);
        state.activeBookOption = -1;
        el['assignment-book-options'].innerHTML = books.length > 0
            ? books.map(book => `<span id="assignment-book-option-${escapeHtml(book.id)}" class="book-option" role="option" data-book-id="${escapeHtml(book.id)}" aria-selected="false"><span class="book-option-title">${escapeHtml(book.title || 'Untitled')}</span>${book.author ? `<span class="book-option-author">${escapeHtml(book.author)}</span>` : ''}</span>`).join('')
            : '<span class="book-options-empty">No matching books</span>';
        show(el['assignment-book-options'], true);
        el['assignment-book-search'].setAttribute('aria-expanded', 'true');
        el['assignment-book-search'].removeAttribute('aria-activedescendant');
        positionAnchoredList(el['assignment-book-options'], el['assignment-book-search']);
    }

    function closeBookOptions() {
        show(el['assignment-book-options'], false);
        el['assignment-book-search'].setAttribute('aria-expanded', 'false');
        el['assignment-book-search'].removeAttribute('aria-activedescendant');
        state.activeBookOption = -1;
    }

    function selectBook(bookId) {
        const book = bookById(bookId);
        if (!book) return;
        el['assignment-book'].value = book.id;
        el['assignment-book-search'].value = bookDisplayName(book);
        el['assignment-book-search'].setCustomValidity('');
        closeBookOptions();
        populateChapterOptions([], book.id);
    }

    function moveActiveBookOption(direction) {
        const options = Array.from(el['assignment-book-options'].querySelectorAll('[role="option"]'));
        if (options.length === 0) return;
        if (el['assignment-book-options'].classList.contains('hidden')) renderBookOptions();
        state.activeBookOption = (state.activeBookOption + direction + options.length) % options.length;
        options.forEach((option, index) => {
            const active = index === state.activeBookOption;
            option.classList.toggle('active', active);
            option.setAttribute('aria-selected', String(active));
            if (active) {
                el['assignment-book-search'].setAttribute('aria-activedescendant', option.id);
                option.scrollIntoView({ block: 'nearest' });
            }
        });
    }

    function selectedChapterIds() {
        return Array.isArray(state.selectedChapterIds) ? state.selectedChapterIds : [];
    }

    function isWholeBookSelection() {
        return selectedChapterIds().length === 0;
    }

    function populateChapterOptions(selectedIds = [], bookId = el['assignment-book'].value) {
        const book = bookById(bookId);
        const chapters = Array.isArray(book?.chapters) ? book.chapters : [];
        const requested = Array.isArray(selectedIds) ? selectedIds.filter(Boolean) : [];
        state.selectedChapterIds = requested.filter(id => chapters.some(chapter => chapter.id === id));
        if (el['assignment-whole-book']) {
            el['assignment-whole-book'].checked = state.selectedChapterIds.length === 0;
        }
        if (el['assignment-chapter-search']) {
            el['assignment-chapter-search'].value = '';
        }
        renderChapterOptions();
    }

    function matchingAssignmentChapters(query) {
        const book = bookById(el['assignment-book'].value);
        const chapters = Array.isArray(book?.chapters) ? book.chapters : [];
        const normalized = String(query || '').trim().toLocaleLowerCase();
        if (!normalized) return chapters;
        return chapters.filter(chapter => String(chapter.title || '').toLocaleLowerCase().includes(normalized));
    }

    function renderChapterOptions() {
        if (!el['assignment-chapter-options']) return;
        const wholeBook = Boolean(el['assignment-whole-book']?.checked);
        const matches = matchingAssignmentChapters(el['assignment-chapter-search']?.value);
        const selected = new Set(selectedChapterIds());
        el['assignment-chapter-options'].setAttribute('aria-disabled', wholeBook ? 'true' : 'false');
        el['assignment-chapter-options'].innerHTML = matches.map(chapter => {
            const checked = selected.has(chapter.id) ? ' checked' : '';
            const disabled = wholeBook ? ' disabled' : '';
            return `<label class="chapter-option">
                <input type="checkbox" data-chapter-id="${escapeHtml(chapter.id)}"${checked}${disabled}>
                <span>${escapeHtml(chapter.title || 'Untitled chapter')}</span>
            </label>`;
        }).join('');
        show(el['assignment-chapter-empty'], matches.length === 0);
        el['assignment-chapter-options'].querySelectorAll('input[data-chapter-id]').forEach(input => {
            input.addEventListener('change', () => {
                const id = input.dataset.chapterId;
                const next = new Set(selectedChapterIds());
                if (input.checked) next.add(id);
                else next.delete(id);
                const book = bookById(el['assignment-book'].value);
                state.selectedChapterIds = (book?.chapters || [])
                    .map(chapter => chapter.id)
                    .filter(chapterId => next.has(chapterId));
                if (el['assignment-whole-book']) {
                    el['assignment-whole-book'].checked = state.selectedChapterIds.length === 0;
                }
                renderChapterOptions();
            });
        });
    }

    function resetQuizSession() {
        state.quizDraftQuestions = [];
        state.quizGeneratedBase = [];
        state.quizContentVersion = null;
        state.quizMode = 'default';
        state.quizAuthorIndex = 0;
        state.quizHasGenerated = false;
        state.quizOverrideConfirmed = false;
        state.quizSimIndex = 0;
        state.quizSimAnswers = [];
        state.quizSimAttempt = 1;
        state.quizSimPhase = 'taking';
        state.quizSimScore = 0;
        state.quizSimSeed = 11;
        state.quizSuggestBusy = null;
    }

    function setAssignmentScreen(screen) {
        state.assignmentScreen = screen;
        const assignScreens = ['assign1', 'assign2', 'assign3'];
        const quizScreens = ['quizAuthor', 'quizSim', 'quizSummary'];
        show(el['assignment-step-rail'], assignScreens.includes(screen));
        show(el['assignment-quiz-rail'], quizScreens.includes(screen));
        show(el['assignment-pane-1'], screen === 'assign1');
        show(el['assignment-pane-2'], screen === 'assign2');
        show(el['assignment-quiz-author'], screen === 'quizAuthor');
        show(el['assignment-quiz-sim'], screen === 'quizSim');
        show(el['assignment-quiz-summary'], screen === 'quizSummary');
        show(el['assignment-pane-3'], screen === 'assign3');
        const assignIndex = screen === 'assign1' ? 1 : screen === 'assign2' ? 2 : 3;
        [1, 2, 3].forEach(index => {
            el[`assignment-step-indicator-${index}`]?.classList.toggle('active', index === assignIndex);
        });
        const quizIndex = screen === 'quizAuthor' ? 1 : screen === 'quizSim' ? 2 : 3;
        [1, 2, 3].forEach(index => {
            el[`assignment-quiz-indicator-${index}`]?.classList.toggle('active', quizScreens.includes(screen) && index === quizIndex);
        });
        if (quizScreens.includes(screen)) {
            const book = bookById(el['assignment-book'].value);
            const ids = selectedChapterIds();
            let scope = 'Whole book';
            if (ids.length === 1) {
                const chapter = book?.chapters?.find(item => item.id === ids[0]);
                scope = chapter?.title || '1 chapter';
            } else if (ids.length > 1) {
                scope = `${ids.length} chapters`;
            }
            el['assignment-quiz-context'].textContent = `Define quiz · ${book?.title || 'Book'} · ${scope}`;
        }
        if (screen === 'assign3') updateAssignmentQuizAttached();
        syncAssignmentDeleteVisibility();
    }

    function syncAssignmentDeleteVisibility() {
        const editing = state.assignments.find(item => item.assignmentId === state.editingAssignmentId);
        const draft = Boolean(editing && String(editing.status || '').toUpperCase() === 'DRAFT');
        show(el['assignment-delete-draft'], draft);
    }

    function updateAssignmentQuizAttached() {
        if (!el['assignment-quiz-attached']) return;
        if (state.quizChoice !== 'require') {
            el['assignment-quiz-attached'].textContent = 'No quiz. Students only need the reading.';
            return;
        }
        const questions = activeQuizQuestions();
        const noThreshold = state.quizHost === 'assignment' && !hasExplicitPassMin();
        const passMin = currentPassMin();
        const retries = currentMaxRetries();
        const source = usingDefaultQuiz() ? 'Default quiz' : 'Override quiz';
        const passLabel = noThreshold ? 'any attempt completes' : `pass ${passMin}`;
        el['assignment-quiz-attached'].textContent =
            `${source} · ${questions.length} questions · ${passLabel} · ${retries} retr${retries === 1 ? 'y' : 'ies'}.`;
    }

    function openAssignmentModal(assignment = null) {
        state.editingAssignmentId = assignment?.assignmentId || null;
        state.quizHost = 'assignment';
        state.quizChoice = assignment?.quizRequired === true ? 'require' : '';
        resetQuizSession();
        el['assignment-form'].reset();
        el['assignment-modal-title'].textContent = assignment ? 'Edit assignment' : 'New assignment';
        populateBookOptions(assignment?.bookId || '');
        if (assignment) {
            el['assignment-title'].value = assignment.title || '';
            const selectedIds = Array.isArray(assignment.chapters) && assignment.chapters.length > 0
                ? assignment.chapters.map(item => item.chapterId).filter(Boolean)
                : (assignment.chapterId ? [assignment.chapterId] : []);
            populateChapterOptions(selectedIds, assignment.bookId);
            el['assignment-form'].elements.dueDate.value = assignment.dueDate || '';
            el['assignment-form'].elements.availableFromDate.value = assignment.availableFromDate || '';
            el['assignment-form'].elements.status.value = assignment.status || 'DRAFT';
            el['assignment-form'].elements.characterChatRequired.checked = assignment.characterChatRequired === true;
            el['assignment-form'].elements.quizPassMinCorrect.value =
                assignment.quizPassMinCorrect != null ? String(assignment.quizPassMinCorrect) : '';
            el['assignment-form'].elements.quizMaxRetries.value =
                assignment.quizMaxRetries != null ? String(assignment.quizMaxRetries) : '';
            state.quizChoice = assignment.quizRequired === true ? 'require' : 'none';
        } else {
            const defaults = state.features || {};
            el['assignment-form'].elements.quizPassMinCorrect.value =
                defaults.defaultQuizPassMinCorrect != null ? String(defaults.defaultQuizPassMinCorrect) : '';
            el['assignment-form'].elements.quizMaxRetries.value =
                defaults.defaultQuizMaxRetries != null ? String(defaults.defaultQuizMaxRetries) : '';
        }
        const defaults = state.features || {};
        if (el['assignment-quiz-question-count']) {
            el['assignment-quiz-question-count'].value = String(defaults.defaultQuizQuestionCount || 10);
        }
        if (el['assignment-quiz-option-count']) {
            el['assignment-quiz-option-count'].value = String(defaults.defaultQuizOptionCount || 4);
        }
        syncAssignmentPassRuleVisibility();
        show(el['assignment-form-error'], false);
        setAssignmentScreen('assign1');
        show(el['assignment-modal'], true);
        setPageWizardOpen();
        window.setTimeout(() => el['assignment-title'].focus(), 0);
    }

    async function deleteDraftAssignment(assignmentId) {
        const assignment = state.assignments.find(item => item.assignmentId === assignmentId);
        if (!assignment || String(assignment.status || '').toUpperCase() !== 'DRAFT') {
            toast('Only draft assignments can be deleted.');
            return;
        }
        const confirmed = window.confirm(`Delete draft “${assignment.title || 'assignment'}”? Students have not seen it.`);
        if (!confirmed) return;
        try {
            await api(`/api/classroom/assignments/${encodeURIComponent(assignmentId)}`, { method: 'DELETE' });
            state.assignments = state.assignments.filter(item => item.assignmentId !== assignmentId);
            renderAssignments();
            if (state.editingAssignmentId === assignmentId) closeAssignmentModal();
            toast('Draft assignment deleted.');
        } catch (error) {
            toast(error.message);
        }
    }

    async function closeAssignmentModal() {
        const provisionalId = state.provisionalAssignmentId;
        if (provisionalId && state.editingAssignmentId === provisionalId) {
            try {
                await api(`/api/classroom/assignments/${encodeURIComponent(provisionalId)}`, { method: 'DELETE' });
            } catch (error) {
                toast(error.message || 'Could not discard unfinished assignment.');
            }
        }
        show(el['assignment-modal'], false);
        setPageWizardOpen();
        state.editingAssignmentId = null;
        state.provisionalAssignmentId = null;
        state.assignmentScreen = 'assign1';
        state.quizChoice = '';
        resetQuizSession();
    }

    function goAssignmentNextFrom1() {
        const title = String(el['assignment-title'].value || '').trim();
        const bookId = el['assignment-book'].value;
        if (!title || !bookId) {
            toast('Enter an assignment name and choose a book.');
            return;
        }
        setAssignmentScreen('assign2');
    }

    async function goAssignmentNextFrom2() {
        if (!state.quizChoice) {
            toast('Choose whether this assignment requires a quiz.');
            return;
        }
        if (state.quizChoice === 'none') {
            setAssignmentScreen('assign3');
            return;
        }
        if (!validateAssignmentQuizRules()) return;
        await enterDefineQuizFromAssignment();
    }

    async function saveAssignment(event) {
        event.preventDefault();
        if (state.assignmentScreen !== 'assign3') {
            if (state.assignmentScreen === 'assign1') goAssignmentNextFrom1();
            else if (state.assignmentScreen === 'assign2') goAssignmentNextFrom2();
            return;
        }
        const form = new FormData(el['assignment-form']);
        const chapterIds = selectedChapterIds();
        const dueDateRaw = String(form.get('dueDate') || '').trim();
        const availableFromRaw = String(form.get('availableFromDate') || '').trim();
        const quizRequired = state.quizChoice === 'require';
        const requestedStatus = String(form.get('status') || 'DRAFT');
        const quizSource = quizRequired
            ? (usingDefaultQuiz() ? 'CHAPTER' : 'CUSTOM')
            : null;
        if (quizRequired && quizSource === 'CHAPTER' && chapterIds.length !== 1) {
            toast('The default chapter quiz is only available for a single-chapter assignment.');
            return;
        }
        const body = {
            title: String(form.get('title') || '').trim(),
            bookId: String(form.get('bookId') || ''),
            chapterIds,
            dueDate: dueDateRaw || null,
            availableFromDate: availableFromRaw || null,
            quizRequired,
            quizSource,
            characterChatRequired: form.get('characterChatRequired') === 'on',
            status: requestedStatus
        };
        const minCorrectRaw = String(form.get('quizPassMinCorrect') || '').trim();
        const maxRetriesRaw = String(form.get('quizMaxRetries') || '').trim();
        if (quizRequired) {
            if (!validateAssignmentQuizRules()) return;
            const minEmpty = !minCorrectRaw;
            const retriesEmpty = !maxRetriesRaw;
            if (minEmpty !== retriesEmpty) {
                toast('Set both pass min correct and max retries, or clear both.');
                return;
            }
            body.quizPassMinCorrect = minCorrectRaw ? Number(minCorrectRaw) : null;
            body.quizMaxRetries = maxRetriesRaw ? Number(maxRetriesRaw) : null;
            if (body.quizPassMinCorrect == null) {
                body.clearQuizPassRules = true;
            }
        } else {
            body.clearQuizPassRules = true;
            body.quizPassMinCorrect = null;
            body.quizMaxRetries = null;
        }
        if (state.editingAssignmentId) {
            body.clearDueDate = !dueDateRaw;
            body.clearAvailableFromDate = !availableFromRaw;
        }
        const needsCustomQuiz = quizRequired && quizSource === 'CUSTOM';
        if (needsCustomQuiz) {
            const questions = usingDefaultQuiz() ? [] : state.quizDraftQuestions;
            if (!questions.length) {
                toast('Define at least one quiz question.');
                return;
            }
            if (questions.some(item => !questionComplete(item))) {
                toast(incompleteQuestionMessage());
                return;
            }
            body.customQuizQuestions = questions.map(item => flattenQuestion(item));
        }
        const path = state.editingAssignmentId
            ? `/api/classroom/assignments/${encodeURIComponent(state.editingAssignmentId)}`
            : `/api/classroom/terms/${encodeURIComponent(state.selectedClass.activeTermId)}/assignments`;
        el['assignment-submit'].disabled = true;
        show(el['assignment-form-error'], false);
        try {
            let saved = await api(path, { method: state.editingAssignmentId ? 'PUT' : 'POST', body: JSON.stringify(body) });
            state.editingAssignmentId = saved.assignmentId;
            const existingIndex = state.assignments.findIndex(item => item.assignmentId === saved.assignmentId);
            if (existingIndex >= 0) state.assignments.splice(existingIndex, 1, saved);
            else state.assignments.push(saved);
            renderAssignments();
            state.provisionalAssignmentId = null;
            closeAssignmentModal();
            toast(saved.status === 'PUBLISHED' ? 'Assignment published.' : 'Assignment saved.');
        } catch (error) {
            el['assignment-form-error'].textContent = error.message;
            show(el['assignment-form-error'], true);
        } finally {
            el['assignment-submit'].disabled = false;
        }
    }

    async function saveFeatures() {
        if (!state.selectedClass) return;
        window.clearTimeout(state.featureSaveTimer);
        el['feature-save-status'].textContent = 'Saving…';
        const body = {};
        Array.from(el['features-form'].elements).forEach(input => {
            if (input.type === 'checkbox') {
                body[input.name] = input.checked;
                return;
            }
            if (input.type === 'number') {
                const raw = String(input.value || '').trim();
                if (input.name === 'defaultQuizPassMinCorrect' || input.name === 'defaultQuizMaxRetries') {
                    if (!raw) {
                        // Cleared together below when both empty.
                        return;
                    }
                    body[input.name] = Number(raw);
                    return;
                }
                if (raw) body[input.name] = Number(raw);
            }
        });
        const minEmpty = !String(el['features-form'].elements.defaultQuizPassMinCorrect?.value || '').trim();
        const retriesEmpty = !String(el['features-form'].elements.defaultQuizMaxRetries?.value || '').trim();
        if (minEmpty && retriesEmpty) {
            body.clearDefaultQuizPassRules = true;
        } else if (minEmpty || retriesEmpty) {
            el['feature-save-status'].textContent = 'Could not save';
            toast('Set both pass defaults, or clear both fields.');
            return;
        }
        const featureQuestionCount = Number(el['features-form'].elements.defaultQuizQuestionCount?.value);
        const featureMinCorrect = Number(el['features-form'].elements.defaultQuizPassMinCorrect?.value);
        if (!minEmpty && Number.isFinite(featureQuestionCount) && Number.isFinite(featureMinCorrect)
                && featureMinCorrect > featureQuestionCount) {
            el['feature-save-status'].textContent = 'Could not save';
            toast('Min correct to pass cannot be greater than the number of questions.');
            return;
        }
        try {
            state.features = await api(`/api/classroom/terms/${encodeURIComponent(state.selectedClass.activeTermId)}/features`, {
                method: 'PUT', body: JSON.stringify(body)
            });
            el['feature-save-status'].textContent = 'Saved';
            state.featureSaveTimer = window.setTimeout(() => { el['feature-save-status'].textContent = ''; }, 2200);
        } catch (error) {
            el['feature-save-status'].textContent = 'Could not save';
            toast(error.message);
        }
    }

    function newId() {
        return crypto.randomUUID ? crypto.randomUUID() : `q-${Date.now()}-${Math.random()}`;
    }

    function defaultSlotCount() {
        const assignmentSlots = Number(el['assignment-quiz-question-count']?.value);
        if (state.quizHost === 'assignment' && Number.isFinite(assignmentSlots) && assignmentSlots >= 1) {
            return Math.min(20, Math.max(1, assignmentSlots));
        }
        return Math.min(20, Math.max(1, Number(el['quiz-slot-count']?.value) || Number(state.features?.defaultQuizQuestionCount) || 10));
    }

    function defaultOptionCount() {
        const assignmentOptions = Number(el['assignment-quiz-option-count']?.value);
        if (state.quizHost === 'assignment' && Number.isFinite(assignmentOptions) && assignmentOptions >= 2) {
            return Math.min(6, Math.max(2, assignmentOptions));
        }
        return Math.min(6, Math.max(2, Number(el['quiz-option-count']?.value) || Number(state.features?.defaultQuizOptionCount) || 4));
    }

    function validateAssignmentQuizRules() {
        const questionCount = Number(el['assignment-quiz-question-count']?.value);
        if (!Number.isInteger(questionCount) || questionCount < 1 || questionCount > 20) {
            toast('Enter the number of questions (1–20).');
            el['assignment-quiz-question-count']?.focus();
            return false;
        }
        const minCorrectRaw = String(el['assignment-form']?.elements?.quizPassMinCorrect?.value || '').trim();
        if (minCorrectRaw) {
            const minCorrect = Number(minCorrectRaw);
            if (!Number.isInteger(minCorrect) || minCorrect < 1) {
                toast('Min correct to pass must be at least 1.');
                el['assignment-form']?.elements?.quizPassMinCorrect?.focus();
                return false;
            }
            if (minCorrect > questionCount) {
                toast('Min correct to pass cannot be greater than the number of questions.');
                el['assignment-form']?.elements?.quizPassMinCorrect?.focus();
                return false;
            }
        }
        return true;
    }

    function isTrueFalseQuestion(item) {
        if (!item) return false;
        if (item.kind === 'truefalse') return true;
        const options = Array.isArray(item.options) ? item.options : null;
        if (!options || options.length !== 2) return false;
        const labels = options.map(option => String(option || '').trim().toLowerCase());
        return labels.includes('true') && labels.includes('false');
    }

    function applyTrueFalseShape(item, correctValue) {
        const correct = correctValue === 'False' ? 'False' : 'True';
        item.kind = 'truefalse';
        item.correct = correct;
        item.distractors = [correct === 'True' ? 'False' : 'True'];
        item.locked = [true];
        return item;
    }

    function applyMultipleChoiceShape(item) {
        const optionCount = defaultOptionCount();
        const keepCorrect = item.correct === 'True' || item.correct === 'False' ? '' : String(item.correct || '');
        item.kind = 'choice';
        item.correct = keepCorrect;
        item.distractors = Array.from({ length: Math.max(1, optionCount - 1) }, () => '');
        item.locked = item.distractors.map(() => false);
        return item;
    }

    function blankQuestion(optionCount) {
        const distractorCount = Math.max(1, (optionCount || defaultOptionCount()) - 1);
        return {
            id: newId(),
            kind: 'choice',
            question: '',
            correct: '',
            distractors: Array.from({ length: distractorCount }, () => ''),
            locked: Array.from({ length: distractorCount }, () => false),
            sourceQuestionId: null,
            mode: 'add',
            citationParagraphIndex: null,
            citationSnippet: ''
        };
    }

    function questionFromApi(question, optionCount, generatedIds) {
        const options = normalizeQuestionOptions(question, optionCount);
        const correctIndex = Number.isInteger(question.correctOptionIndex) ? question.correctOptionIndex : 0;
        const sourceId = question.id && generatedIds?.has(question.id) ? question.id : null;
        if (isTrueFalseQuestion({ ...question, options })) {
            const correctLabel = /^true$/i.test(String(options[correctIndex] || '')) ? 'True' : 'False';
            return applyTrueFalseShape({
                id: question.id || newId(),
                question: question.question || '',
                sourceQuestionId: sourceId,
                mode: sourceId ? 'override' : 'add',
                citationParagraphIndex: Number.isInteger(question.citationParagraphIndex)
                    ? question.citationParagraphIndex
                    : null,
                citationSnippet: question.citationSnippet || ''
            }, correctLabel);
        }
        const correct = String(options[correctIndex] || '');
        const distractors = options.filter((_, index) => index !== correctIndex);
        const needed = Math.max(1, (optionCount || defaultOptionCount()) - 1);
        while (distractors.length < needed) distractors.push('');
        return {
            id: question.id || newId(),
            kind: 'choice',
            question: question.question || '',
            correct,
            distractors,
            locked: distractors.map(value => Boolean(String(value || '').trim())),
            savedOptions: options.slice(),
            savedCorrectIndex: correctIndex,
            sourceQuestionId: sourceId,
            mode: sourceId ? 'override' : 'add',
            citationParagraphIndex: Number.isInteger(question.citationParagraphIndex)
                ? question.citationParagraphIndex
                : null,
            citationSnippet: question.citationSnippet || ''
        };
    }

    function flattenQuestion(item) {
        if (isTrueFalseQuestion(item)) {
            const correctIsTrue = item.correct !== 'False';
            return {
                id: item.id,
                question: item.question,
                options: ['True', 'False'],
                correctOptionIndex: correctIsTrue ? 0 : 1,
                citationParagraphIndex: Number.isInteger(item.citationParagraphIndex) ? item.citationParagraphIndex : null,
                citationSnippet: item.citationSnippet || ''
            };
        }
        const currentChoices = [item.correct, ...(item.distractors || [])].map(value => String(value || ''));
        if (Array.isArray(item.savedOptions)
                && Number.isInteger(item.savedCorrectIndex)
                && sameChoiceSet(item.savedOptions, currentChoices)) {
            return {
                id: item.id,
                question: item.question,
                options: item.savedOptions.slice(),
                correctOptionIndex: item.savedCorrectIndex,
                citationParagraphIndex: Number.isInteger(item.citationParagraphIndex) ? item.citationParagraphIndex : null,
                citationSnippet: item.citationSnippet || ''
            };
        }
        const shuffled = shuffleChoices(item.correct, item.distractors);
        item.savedOptions = shuffled.options.slice();
        item.savedCorrectIndex = shuffled.correctOptionIndex;
        return {
            id: item.id,
            question: item.question,
            options: shuffled.options,
            correctOptionIndex: shuffled.correctOptionIndex,
            citationParagraphIndex: Number.isInteger(item.citationParagraphIndex) ? item.citationParagraphIndex : null,
            citationSnippet: item.citationSnippet || ''
        };
    }

    function shuffleChoices(correct, distractors) {
        const options = [String(correct || ''), ...(distractors || []).map(value => String(value || ''))];
        for (let index = options.length - 1; index > 0; index -= 1) {
            const swap = Math.floor(Math.random() * (index + 1));
            const current = options[index];
            options[index] = options[swap];
            options[swap] = current;
        }
        return {
            options,
            correctOptionIndex: Math.max(0, options.indexOf(String(correct || '')))
        };
    }

    function sameChoiceSet(left, right) {
        if (!Array.isArray(left) || !Array.isArray(right) || left.length !== right.length) {
            return false;
        }
        const counts = new Map();
        left.forEach(value => counts.set(value, (counts.get(value) || 0) + 1));
        for (const value of right) {
            const remaining = counts.get(value);
            if (!remaining) {
                return false;
            }
            counts.set(value, remaining - 1);
        }
        return true;
    }

    function questionComplete(item) {
        if (isTrueFalseQuestion(item)) {
            return Boolean(String(item?.question || '').trim() && (item.correct === 'True' || item.correct === 'False'));
        }
        return Boolean(
            String(item?.question || '').trim()
            && String(item?.correct || '').trim()
            && Array.isArray(item?.distractors)
            && item.distractors.every(choice => String(choice || '').trim())
        );
    }

    function incompleteQuestionMessage() {
        return 'Every question needs a stem and a complete answer set. Multiple choice needs all wrong answers; True/False needs True or False selected.';
    }

    function usingDefaultQuiz() {
        return state.quizMode === 'default' && state.quizHasGenerated;
    }

    function activeQuizQuestions() {
        return usingDefaultQuiz()
            ? state.quizGeneratedBase
            : state.quizDraftQuestions;
    }

    function questionsReady() {
        if (usingDefaultQuiz()) return activeQuizQuestions().length > 0;
        return state.quizDraftQuestions.length > 0 && state.quizDraftQuestions.every(questionComplete);
    }

    function hasExplicitPassMin() {
        if (state.quizHost === 'assignment') {
            return String(el['assignment-form']?.elements?.quizPassMinCorrect?.value || '').trim() !== '';
        }
        return state.features?.defaultQuizPassMinCorrect != null;
    }

    function currentPassMin() {
        if (state.quizHost === 'assignment') {
            const raw = Number(el['assignment-form']?.elements?.quizPassMinCorrect?.value);
            if (Number.isFinite(raw) && raw >= 1) return raw;
            return 0;
        } else if (state.features?.defaultQuizPassMinCorrect != null) {
            return Number(state.features.defaultQuizPassMinCorrect);
        }
        return Math.max(1, Math.ceil(activeQuizQuestions().length * 0.7));
    }

    function currentMaxRetries() {
        if (state.quizHost === 'assignment') {
            const raw = Number(el['assignment-form']?.elements?.quizMaxRetries?.value);
            if (Number.isFinite(raw) && raw >= 0) return raw;
        } else if (state.features?.defaultQuizMaxRetries != null) {
            return Number(state.features.defaultQuizMaxRetries);
        }
        return 1;
    }

    function currentQuizChapterId() {
        if (state.quizHost === 'assignment') {
            const ids = selectedChapterIds();
            return ids.length === 1 ? ids[0] : '';
        }
        return el['quiz-chapter'].value;
    }

    function assignmentQuizSuggestPath(suffix) {
        if (state.quizHost === 'assignment' && state.editingAssignmentId) {
            return `/api/classroom/assignments/${encodeURIComponent(state.editingAssignmentId)}/${suffix}`;
        }
        const chapterId = currentQuizChapterId();
        return `/api/classroom/terms/${encodeURIComponent(state.selectedClass.activeTermId)}/chapters/${encodeURIComponent(chapterId)}/${suffix}`;
    }

    function shuffle(items, seed) {
        const next = [...items];
        let value = seed || 1;
        for (let i = next.length - 1; i > 0; i--) {
            value = (value * 16807) % 2147483647;
            const j = value % (i + 1);
            [next[i], next[j]] = [next[j], next[i]];
        }
        return next;
    }

    function setQuizWizardStep(step) {
        state.quizWizardStep = step;
        show(el['quiz-wizard-step-1'], step === 1);
        show(el['quiz-wizard-step-2'], step === 2);
        show(el['quiz-wizard-step-3'], step === 3);
        show(el['quiz-wizard-step-4'], step === 4);
        [1, 2, 3, 4].forEach(index => {
            el[`quiz-step-indicator-${index}`]?.classList.toggle('active', index === step);
        });
    }

    function populateQuizChapterOptions(selectedChapterId = '', bookId = '', selectedChapterIndex = null) {
        const book = bookById(bookId || el['quiz-book'].value);
        const chapters = Array.isArray(book?.chapters) ? book.chapters : [];
        let selectedId = selectedChapterId || '';
        if (!selectedId && Number.isInteger(selectedChapterIndex)) {
            const match = chapters[selectedChapterIndex];
            if (match?.id) selectedId = match.id;
        }
        if (selectedId) {
            const chapter = chapters.find(item => item.id === selectedId);
            el['quiz-chapter'].value = selectedId;
            el['quiz-chapter-search'].value = chapter
                ? (chapter.title || `Chapter ${(chapter.index ?? chapters.indexOf(chapter)) + 1}`)
                : '';
        } else {
            el['quiz-chapter'].value = '';
            el['quiz-chapter-search'].value = '';
        }
        closeQuizChapterOptions();
    }

    function matchingQuizChapters(query) {
        const book = bookById(el['quiz-book'].value);
        const chapters = Array.isArray(book?.chapters) ? book.chapters : [];
        const normalized = String(query || '').trim().toLowerCase();
        return chapters
            .map((chapter, index) => ({
                id: chapter.id,
                title: chapter.title || `Chapter ${index + 1}`,
                index
            }))
            .filter(chapter => !normalized || chapter.title.toLowerCase().includes(normalized));
    }

    function renderQuizChapterOptions() {
        const chapters = matchingQuizChapters(el['quiz-chapter-search']?.value);
        state.activeQuizChapterOption = -1;
        if (!el['quiz-chapter-options']) return;
        el['quiz-chapter-options'].innerHTML = chapters.length
            ? chapters.map(chapter => `<button type="button" id="quiz-chapter-option-${escapeHtml(chapter.id)}" role="option" data-chapter-id="${escapeHtml(chapter.id)}" aria-selected="false">${escapeHtml(chapter.title)}</button>`).join('')
            : '<div class="book-option-empty">No matching chapters</div>';
        show(el['quiz-chapter-options'], true);
        el['quiz-chapter-search'].setAttribute('aria-expanded', 'true');
        el['quiz-chapter-search'].removeAttribute('aria-activedescendant');
        positionAnchoredList(el['quiz-chapter-options'], el['quiz-chapter-search']);
    }

    function closeQuizChapterOptions() {
        if (!el['quiz-chapter-options']) return;
        show(el['quiz-chapter-options'], false);
        el['quiz-chapter-search']?.setAttribute('aria-expanded', 'false');
        el['quiz-chapter-search']?.removeAttribute('aria-activedescendant');
        state.activeQuizChapterOption = -1;
    }

    function selectQuizChapter(chapterId) {
        const book = bookById(el['quiz-book'].value);
        const chapters = Array.isArray(book?.chapters) ? book.chapters : [];
        const chapter = chapters.find(item => item.id === chapterId);
        if (!chapter) return;
        const index = chapters.indexOf(chapter);
        el['quiz-chapter'].value = chapter.id;
        el['quiz-chapter-search'].value = chapter.title || `Chapter ${index + 1}`;
        el['quiz-chapter-search'].setCustomValidity('');
        closeQuizChapterOptions();
    }

    function moveActiveQuizChapterOption(direction) {
        const options = Array.from(el['quiz-chapter-options'].querySelectorAll('[role="option"]'));
        if (options.length === 0) return;
        if (el['quiz-chapter-options'].classList.contains('hidden')) renderQuizChapterOptions();
        state.activeQuizChapterOption = (state.activeQuizChapterOption + direction + options.length) % options.length;
        options.forEach((option, index) => {
            const active = index === state.activeQuizChapterOption;
            option.classList.toggle('active', active);
            option.setAttribute('aria-selected', String(active));
            if (active) {
                el['quiz-chapter-search'].setAttribute('aria-activedescendant', option.id);
                option.scrollIntoView({ block: 'nearest' });
            }
        });
    }

    function renderQuizBookOptions() {
        const query = String(el['quiz-book-search'].value || '').trim().toLowerCase();
        const books = sortedBooks().filter(book => {
            if (!query) return true;
            return `${book.title || ''} ${book.author || ''}`.toLowerCase().includes(query);
        });
        state.activeQuizBookOption = -1;
        el['quiz-book-options'].innerHTML = books.length
            ? books.map((book) => `<button type="button" id="quiz-book-option-${escapeHtml(book.id)}" role="option" data-book-id="${escapeHtml(book.id)}" aria-selected="false">${escapeHtml(bookDisplayName(book))}</button>`).join('')
            : '<div class="book-option-empty">No matching books</div>';
        show(el['quiz-book-options'], true);
        el['quiz-book-search'].setAttribute('aria-expanded', 'true');
        el['quiz-book-search'].removeAttribute('aria-activedescendant');
        positionAnchoredList(el['quiz-book-options'], el['quiz-book-search']);
    }

    function closeQuizBookOptions() {
        show(el['quiz-book-options'], false);
        el['quiz-book-search'].setAttribute('aria-expanded', 'false');
        el['quiz-book-search'].removeAttribute('aria-activedescendant');
        state.activeQuizBookOption = -1;
    }

    function selectQuizBook(bookId) {
        const book = bookById(bookId);
        if (!book) return;
        el['quiz-book'].value = book.id;
        el['quiz-book-search'].value = bookDisplayName(book);
        el['quiz-book-search'].setCustomValidity('');
        closeQuizBookOptions();
        populateQuizChapterOptions('', book.id);
    }

    function moveActiveQuizBookOption(direction) {
        const options = Array.from(el['quiz-book-options'].querySelectorAll('[role="option"]'));
        if (options.length === 0) return;
        if (el['quiz-book-options'].classList.contains('hidden')) renderQuizBookOptions();
        state.activeQuizBookOption = (state.activeQuizBookOption + direction + options.length) % options.length;
        options.forEach((option, index) => {
            const active = index === state.activeQuizBookOption;
            option.classList.toggle('active', active);
            option.setAttribute('aria-selected', String(active));
            if (active) {
                el['quiz-book-search'].setAttribute('aria-activedescendant', option.id);
                option.scrollIntoView({ block: 'nearest' });
            }
        });
    }

    function openQuizWizard() {
        if (!state.selectedClass) return;
        state.quizHost = 'standalone';
        resetQuizSession();
        const defaults = state.features || {};
        el['quiz-slot-count'].value = String(defaults.defaultQuizQuestionCount || 10);
        el['quiz-option-count'].value = String(defaults.defaultQuizOptionCount || 4);
        el['quiz-book'].value = '';
        el['quiz-book-search'].value = '';
        el['quiz-chapter'].value = '';
        if (el['quiz-chapter-search']) el['quiz-chapter-search'].value = '';
        populateQuizChapterOptions();
        setQuizWizardStep(1);
        show(el['quiz-wizard-modal'], true);
        setPageWizardOpen();
        window.setTimeout(() => el['quiz-book-search'].focus(), 0);
    }

    function closeQuizWizard() {
        show(el['quiz-wizard-modal'], false);
        setPageWizardOpen();
        state.quizWizardStep = 1;
        state.quizHost = 'standalone';
        resetQuizSession();
    }

    function authorHost() {
        return state.quizHost === 'assignment'
            ? {
                mode: el['assignment-quiz-mode'],
                noDefault: el['assignment-quiz-no-default'],
                progress: el['assignment-quiz-author-progress'],
                body: el['assignment-quiz-author-body'],
                error: el['assignment-quiz-author-error'],
                prev: el['assignment-quiz-author-prev'],
                next: el['assignment-quiz-author-next'],
                add: el['assignment-quiz-author-add']
            }
            : {
                mode: el['quiz-wizard-mode'],
                noDefault: el['quiz-wizard-no-default'],
                progress: el['quiz-author-progress'],
                body: el['quiz-question-editor'],
                error: el['quiz-wizard-step2-error'],
                prev: el['quiz-author-prev'],
                next: el['quiz-wizard-next-2'],
                add: el['quiz-add-question']
            };
    }

    function simHost() {
        return state.quizHost === 'assignment'
            ? {
                body: el['assignment-quiz-sim-body'],
                prev: el['assignment-quiz-sim-prev'],
                next: el['assignment-quiz-sim-next']
            }
            : {
                body: el['quiz-sim-body'],
                prev: el['quiz-sim-prev'],
                next: el['quiz-sim-next']
            };
    }

    async function loadEffectiveQuizForChapter(chapterId) {
        const optionCount = defaultOptionCount();
        const slotCount = defaultSlotCount();
        const effective = await api(`/api/classroom/terms/${encodeURIComponent(state.selectedClass.activeTermId)}/chapters/${encodeURIComponent(chapterId)}/effective-quiz`);
        const rawGenerated = Array.isArray(effective.generatedQuestions) ? effective.generatedQuestions : [];
        const generatedIds = new Set(rawGenerated.map(item => item.id).filter(Boolean));
        state.quizGeneratedBase = rawGenerated.map(question => questionFromApi(question, optionCount, generatedIds));
        state.quizContentVersion = effective.contentVersion || null;
        state.quizHasGenerated = state.quizGeneratedBase.length > 0;
        const overrides = Array.isArray(effective.overrides) ? effective.overrides : [];
        if (overrides.length > 0 && Array.isArray(effective.effectiveQuestions) && effective.effectiveQuestions.length > 0) {
            state.quizMode = 'override';
            state.quizDraftQuestions = effective.effectiveQuestions.map(question => questionFromApi(question, optionCount, generatedIds));
        } else if (state.quizHasGenerated) {
            state.quizMode = 'default';
            state.quizDraftQuestions = [];
        } else {
            state.quizMode = 'override';
            state.quizDraftQuestions = Array.from({ length: slotCount }, () => blankQuestion(optionCount));
        }
        state.quizAuthorIndex = 0;
    }

    function applyQuizMode(mode) {
        state.quizMode = mode;
        state.quizAuthorIndex = 0;
        state.quizOverrideConfirmed = false;
        if (mode === 'override') {
            state.quizDraftQuestions = Array.from({ length: defaultSlotCount() }, () => blankQuestion(defaultOptionCount()));
        } else {
            state.quizDraftQuestions = [];
        }
        renderPagedQuizAuthor();
    }

    async function enterDefineQuizFromAssignment() {
        state.quizHost = 'assignment';
        try {
            await ensureDraftAssignment();
            await loadEffectiveQuizForAssignment(state.editingAssignmentId);
            setAssignmentScreen('quizAuthor');
            renderPagedQuizAuthor();
        } catch (error) {
            toast(error.message);
        }
    }

    async function ensureDraftAssignment() {
        if (state.editingAssignmentId) return state.editingAssignmentId;
        const title = String(el['assignment-title'].value || '').trim();
        const bookId = el['assignment-book'].value;
        const saved = await api(`/api/classroom/terms/${encodeURIComponent(state.selectedClass.activeTermId)}/assignments`, {
            method: 'POST',
            body: JSON.stringify({
                title,
                bookId,
                chapterIds: selectedChapterIds(),
                quizRequired: true,
                status: 'DRAFT'
            })
        });
        state.editingAssignmentId = saved.assignmentId;
        state.provisionalAssignmentId = saved.assignmentId;
        return saved.assignmentId;
    }

    async function loadEffectiveQuizForAssignment(assignmentId) {
        const optionCount = defaultOptionCount();
        const slotCount = defaultSlotCount();
        const effective = await api(`/api/classroom/assignments/${encodeURIComponent(assignmentId)}/effective-quiz`);
        const questions = Array.isArray(effective.questions) ? effective.questions : [];
        state.quizContentVersion = effective.contentVersion || null;
        state.quizHasGenerated = Boolean(effective.chapterDefaultAvailable) && questions.length > 0;
        state.quizGeneratedBase = state.quizHasGenerated
            ? questions.map(question => questionFromApi(question, optionCount, new Set(questions.map(item => item.id).filter(Boolean))))
            : [];
        if (effective.quizSource === 'CUSTOM' && questions.length > 0) {
            state.quizMode = 'override';
            state.quizDraftQuestions = questions.map(question => questionFromApi(question, optionCount, new Set()));
        } else if (state.quizHasGenerated) {
            state.quizMode = 'default';
            state.quizDraftQuestions = [];
        } else {
            state.quizMode = 'override';
            state.quizDraftQuestions = Array.from({ length: slotCount }, () => blankQuestion(optionCount));
        }
        state.quizAuthorIndex = 0;
    }

    function renderQuizModeChoices() {
        const host = authorHost();
        if (!host.mode) return;
        show(host.mode, state.quizHasGenerated);
        show(host.noDefault, !state.quizHasGenerated);
        if (!state.quizHasGenerated) {
            host.mode.innerHTML = '';
            return;
        }
        host.mode.innerHTML = `
            <button type="button" class="choice-card" data-quiz-mode="default" aria-pressed="${state.quizMode === 'default'}">
                <strong>Use default chapter quiz</strong>
                <span>Use the existing quiz for this chapter. Students get these questions unless you define an assignment quiz.</span>
            </button>
            <button type="button" class="choice-card" data-quiz-mode="override" aria-pressed="${state.quizMode === 'override'}">
                <strong>Define assignment quiz</strong>
                <span>Author questions for this assignment. Type each stem and correct answer; generate distractors per question.</span>
            </button>
        `;
        host.mode.querySelectorAll('[data-quiz-mode]').forEach(button => {
            button.addEventListener('click', () => applyQuizMode(button.dataset.quizMode));
        });
    }

    function currentAuthorQuestion() {
        const questions = activeQuizQuestions();
        if (questions.length === 0) return null;
        const index = Math.min(Math.max(0, state.quizAuthorIndex), questions.length - 1);
        state.quizAuthorIndex = index;
        return questions[index];
    }

    function renderPagedQuizAuthor() {
        const host = authorHost();
        if (!host.body) return;
        renderQuizModeChoices();
        const questions = activeQuizQuestions();
        const question = currentAuthorQuestion();
        const index = state.quizAuthorIndex;
        const readOnly = usingDefaultQuiz();
        const completed = readOnly
            ? questions.length
            : state.quizDraftQuestions.filter(questionComplete).length;
        if (host.progress) {
            host.progress.textContent = questions.length
                ? `Question ${index + 1} of ${questions.length}${readOnly ? '' : ` · ${completed} complete`}`
                : '';
        }
        const suggestBusy = state.quizSuggestBusy;
        const questionBusy = suggestBusy && suggestBusy.questionIndex === index;
        const generatingAll = questionBusy && suggestBusy.kind === 'all';
        const regeneratingIndex = questionBusy && suggestBusy.kind === 'one' ? suggestBusy.distractorIndex : -1;
        show(host.add, !readOnly);
        if (host.add) host.add.disabled = Boolean(suggestBusy);
        if (host.prev) host.prev.disabled = index === 0 || Boolean(suggestBusy);
        if (host.next) {
            host.next.disabled = Boolean(suggestBusy);
            host.next.textContent = index < questions.length - 1 ? 'Next question' : 'Next · simulate quiz';
        }
        show(host.error, false);
        if (!question) {
            host.body.innerHTML = '<p class="form-help">No questions yet.</p>';
            return;
        }
        if (readOnly) {
            const options = isTrueFalseQuestion(question)
                ? ['True', 'False']
                : [question.correct, ...(question.distractors || [])];
            host.body.innerHTML = `<article class="quiz-question-card">
                <header><strong>Question ${index + 1}${isTrueFalseQuestion(question) ? ' · True/False' : ''}</strong></header>
                <p>${escapeHtml(question.question)}</p>
                <ol>${options.map(choice => `<li${choice === question.correct ? ' style="color: var(--success); font-weight: 600;"' : ''}>${choice === question.correct ? 'Correct · ' : ''}${escapeHtml(choice)}</li>`).join('')}</ol>
            </article>`;
            return;
        }
        const trueFalse = isTrueFalseQuestion(question);
        const canGenerate = !trueFalse && Boolean(String(question.question || '').trim() && String(question.correct || '').trim());
        const statusText = generatingAll
            ? 'Writing wrong answers… this can take a few seconds.'
            : regeneratingIndex >= 0
                ? `Regenerating wrong answer ${regeneratingIndex + 1}…`
                : 'Keep, regenerate, or type over each distractor.';
        const kindRadios = `<div class="quiz-kind-row" role="radiogroup" aria-label="Question type">
                <label class="quiz-kind-option"><input type="radio" name="quiz-kind-${index}" value="choice" data-question-kind="choice" ${trueFalse ? '' : 'checked'}> Multiple choice</label>
                <label class="quiz-kind-option"><input type="radio" name="quiz-kind-${index}" value="truefalse" data-question-kind="truefalse" ${trueFalse ? 'checked' : ''}> True/False</label>
            </div>`;
        const trueFalseBody = `<fieldset class="quiz-true-false">
                <legend>Correct answer</legend>
                <label class="quiz-kind-option"><input type="radio" name="quiz-tf-${index}" value="True" data-true-false="True" ${question.correct === 'False' ? '' : 'checked'}> True</label>
                <label class="quiz-kind-option"><input type="radio" name="quiz-tf-${index}" value="False" data-true-false="False" ${question.correct === 'False' ? 'checked' : ''}> False</label>
            </fieldset>
            <p class="form-help">Students will choose True or False. Wrong-answer generation is not used.</p>`;
        const choiceBody = `<label>Correct answer<input type="text" data-field="correct" value="${escapeHtml(question.correct || '')}" placeholder="The right option"></label>
            <div class="wizard-toolbar">
                <button type="button" class="secondary-button" data-generate-distractors aria-busy="${generatingAll ? 'true' : 'false'}" ${!canGenerate || suggestBusy ? 'disabled' : ''}>
                    ${generatingAll ? '<span class="button-spinner" aria-hidden="true"></span> Generating wrong answers…' : 'Generate wrong answers'}
                </button>
                <span class="form-help quiz-suggest-status" role="status" aria-live="polite">${escapeHtml(statusText)}</span>
            </div>
            ${(question.distractors || []).map((choice, dIndex) => {
                const rowBusy = generatingAll || regeneratingIndex === dIndex;
                return `
                <div class="quiz-distractor-row${rowBusy ? ' is-generating' : ''}">
                    <input type="text" data-field="distractor" data-distractor-index="${dIndex}" value="${escapeHtml(choice || '')}" placeholder="${rowBusy ? 'Generating…' : `Wrong answer ${dIndex + 1}`}" ${rowBusy ? 'aria-busy="true"' : ''}>
                    <button type="button" class="text-button" data-keep-distractor="${dIndex}" ${suggestBusy ? 'disabled' : ''}>${question.locked?.[dIndex] ? 'Kept' : 'Keep'}</button>
                    <button type="button" class="text-button" data-regen-distractor="${dIndex}" ${suggestBusy ? 'disabled' : ''}>${regeneratingIndex === dIndex ? 'Regenerating…' : 'Regen'}</button>
                </div>`;
            }).join('')}`;
        host.body.innerHTML = `<article class="quiz-question-card" data-author-index="${index}" aria-busy="${questionBusy ? 'true' : 'false'}">
            <header>
                <strong>Question ${index + 1}</strong>
                <button type="button" class="text-button" data-remove-current ${questions.length <= 1 || suggestBusy ? 'disabled' : ''}>Delete</button>
            </header>
            ${kindRadios}
            <label>Question stem<textarea data-field="stem" rows="2" placeholder="${trueFalse ? 'A statement that is true or false' : 'Question stem'}">${escapeHtml(question.question || '')}</textarea></label>
            ${trueFalse ? trueFalseBody : choiceBody}
        </article>`;
        const stem = host.body.querySelector('[data-field="stem"]');
        const correct = host.body.querySelector('[data-field="correct"]');
        stem?.addEventListener('input', () => {
            question.question = stem.value;
            const generate = host.body.querySelector('[data-generate-distractors]');
            if (generate) generate.disabled = !stem.value.trim() || !correct?.value.trim();
        });
        correct?.addEventListener('input', () => {
            question.correct = correct.value;
            const generate = host.body.querySelector('[data-generate-distractors]');
            if (generate) generate.disabled = !stem.value.trim() || !correct.value.trim();
        });
        host.body.querySelectorAll('[data-question-kind]').forEach(input => {
            input.addEventListener('change', () => {
                if (input.value === 'truefalse') applyTrueFalseShape(question, question.correct);
                else applyMultipleChoiceShape(question);
                renderPagedQuizAuthor();
            });
        });
        host.body.querySelectorAll('[data-true-false]').forEach(input => {
            input.addEventListener('change', () => applyTrueFalseShape(question, input.value));
        });
        host.body.querySelectorAll('[data-field="distractor"]').forEach(input => {
            input.addEventListener('input', () => {
                const dIndex = Number(input.dataset.distractorIndex);
                question.distractors[dIndex] = input.value;
            });
        });
        host.body.querySelector('[data-remove-current]')?.addEventListener('click', () => {
            if (state.quizDraftQuestions.length <= 1) return;
            state.quizDraftQuestions.splice(index, 1);
            state.quizAuthorIndex = Math.min(index, state.quizDraftQuestions.length - 1);
            renderPagedQuizAuthor();
        });
        host.body.querySelector('[data-generate-distractors]')?.addEventListener('click', () => generateDistractors(index));
        host.body.querySelectorAll('[data-keep-distractor]').forEach(button => {
            button.addEventListener('click', () => {
                const dIndex = Number(button.dataset.keepDistractor);
                question.locked = question.locked || [];
                question.locked[dIndex] = !question.locked[dIndex];
                renderPagedQuizAuthor();
            });
        });
        host.body.querySelectorAll('[data-regen-distractor]').forEach(button => {
            button.addEventListener('click', () => regenerateDistractor(index, Number(button.dataset.regenDistractor)));
        });
    }

    function goAuthorQuestion(delta) {
        const questions = activeQuizQuestions();
        if (delta > 0 && state.quizAuthorIndex >= questions.length - 1) {
            if (!questionsReady()) {
                toast('Finish every question before simulating.');
                const host = authorHost();
                if (host.error) {
                    host.error.textContent = incompleteQuestionMessage();
                    show(host.error, true);
                }
                return;
            }
            startQuizSimulation();
            return;
        }
        const next = state.quizAuthorIndex + delta;
        if (next < 0 || next >= questions.length) return;
        state.quizAuthorIndex = next;
        renderPagedQuizAuthor();
    }

    function addAuthorQuestion() {
        if (usingDefaultQuiz()) return;
        if (state.quizDraftQuestions.length >= 20) {
            toast('Maximum 20 questions.');
            return;
        }
        state.quizDraftQuestions.push(blankQuestion(defaultOptionCount()));
        state.quizAuthorIndex = state.quizDraftQuestions.length - 1;
        renderPagedQuizAuthor();
    }

    async function generateDistractors(index) {
        const item = state.quizDraftQuestions[index];
        if (isTrueFalseQuestion(item)) {
            toast('True/False questions do not use generated wrong answers.');
            return;
        }
        if (!item?.question?.trim() || !item?.correct?.trim()) {
            toast('Enter a stem and correct answer first.');
            return;
        }
        if (state.quizSuggestBusy) return;
        const needed = Math.max(1, (item.distractors || []).length);
        state.quizSuggestBusy = { kind: 'all', questionIndex: index };
        renderPagedQuizAuthor();
        try {
            const result = await api(assignmentQuizSuggestPath('suggest-distractors'), {
                method: 'POST',
                body: JSON.stringify({
                    question: item.question,
                    correctAnswer: item.correct,
                    count: needed
                })
            });
            const distractors = Array.isArray(result.distractors) ? result.distractors : [];
            item.distractors = (item.distractors || []).map((value, dIndex) => {
                if (item.locked?.[dIndex] && String(value || '').trim()) return value;
                return distractors[dIndex] || value;
            });
            toast('Wrong answers filled. Adjust as needed.');
        } catch (error) {
            toast(error.message);
        } finally {
            state.quizSuggestBusy = null;
            renderPagedQuizAuthor();
        }
    }

    async function regenerateDistractor(index, dIndex) {
        const item = state.quizDraftQuestions[index];
        if (isTrueFalseQuestion(item)) {
            toast('True/False questions do not use generated wrong answers.');
            return;
        }
        if (!item?.question?.trim() || !item?.correct?.trim()) {
            toast('Enter a stem and correct answer first.');
            return;
        }
        if (state.quizSuggestBusy) return;
        state.quizSuggestBusy = { kind: 'one', questionIndex: index, distractorIndex: dIndex };
        renderPagedQuizAuthor();
        try {
            const result = await api(assignmentQuizSuggestPath('suggest-distractors'), {
                method: 'POST',
                body: JSON.stringify({
                    question: item.question,
                    correctAnswer: item.correct,
                    count: 1
                })
            });
            const next = Array.isArray(result.distractors) ? result.distractors[0] : '';
            if (!next) {
                toast('Could not regenerate that answer.');
                return;
            }
            item.distractors[dIndex] = next;
        } catch (error) {
            toast(error.message);
        } finally {
            state.quizSuggestBusy = null;
            renderPagedQuizAuthor();
        }
    }

    function simOptions(question, qIndex) {
        if (isTrueFalseQuestion(question)) return ['True', 'False'];
        return shuffle([question.correct, ...(question.distractors || [])], state.quizSimSeed + qIndex * 17);
    }

    function startQuizSimulation() {
        const questions = activeQuizQuestions();
        state.quizSimIndex = 0;
        state.quizSimAnswers = Array.from({ length: questions.length }, () => -1);
        state.quizSimAttempt = 1;
        state.quizSimPhase = 'taking';
        state.quizSimScore = 0;
        state.quizSimSeed = Date.now() % 10000;
        if (state.quizHost === 'assignment') {
            setAssignmentScreen('quizSim');
        } else {
            setQuizWizardStep(3);
        }
        renderQuizSimulation();
    }

    function renderQuizSimulation() {
        const host = simHost();
        if (!host.body) return;
        const questions = activeQuizQuestions();
        const noThreshold = state.quizHost === 'assignment' && !hasExplicitPassMin();
        const passMin = currentPassMin();
        const retries = currentMaxRetries();
        const totalAttempts = 1 + retries;
        if (state.quizSimPhase === 'result') {
            const passed = noThreshold || state.quizSimScore >= passMin;
            const heading = noThreshold
                ? `Submitted · ${state.quizSimScore}/${questions.length}`
                : `${passed ? 'Passed' : 'Not yet'} · ${state.quizSimScore}/${questions.length}`;
            const detail = noThreshold
                ? 'No pass threshold. Any submitted attempt completes the student requirement.'
                : passed
                    ? 'This is the student pass experience.'
                    : state.quizSimAttempt < totalAttempts
                        ? `${totalAttempts - state.quizSimAttempt} retr${totalAttempts - state.quizSimAttempt === 1 ? 'y' : 'ies'} remaining.`
                        : 'Retries exhausted. The assignment quiz requirement would stay unmet.';
            host.body.innerHTML = `<div class="quiz-callout ${passed ? 'success' : 'warning'}">
                <strong>${heading}</strong>
                <p class="form-help">${detail}</p>
            </div>`;
            show(host.prev, false);
            if (host.next) {
                host.next.textContent = !passed && state.quizSimAttempt < totalAttempts ? 'Retry' : 'Next · summary';
                host.next.disabled = false;
            }
            return;
        }
        const question = questions[state.quizSimIndex];
        if (!question) return;
        const choices = simOptions(question, state.quizSimIndex);
        host.body.innerHTML = `
            <p class="wizard-progress">Question ${state.quizSimIndex + 1} of ${questions.length} · Attempt ${state.quizSimAttempt} of ${totalAttempts} · ${noThreshold ? 'any attempt completes' : `pass ${passMin}/${questions.length}`}</p>
            <p><strong>${escapeHtml(question.question)}</strong></p>
            <div class="choice-stack">
                ${choices.map((choice, optionIndex) => `
                    <button type="button" class="choice-card" data-sim-choice="${optionIndex}" aria-pressed="${state.quizSimAnswers[state.quizSimIndex] === optionIndex}">
                        <strong>${escapeHtml(choice)}</strong>
                    </button>
                `).join('')}
            </div>
        `;
        host.body.querySelectorAll('[data-sim-choice]').forEach(button => {
            button.addEventListener('click', () => {
                state.quizSimAnswers[state.quizSimIndex] = Number(button.dataset.simChoice);
                renderQuizSimulation();
            });
        });
        show(host.prev, true);
        if (host.prev) host.prev.disabled = state.quizSimIndex === 0;
        if (host.next) {
            const isLast = state.quizSimIndex === questions.length - 1;
            host.next.textContent = isLast ? 'Submit attempt' : 'Next question';
            host.next.disabled = isLast
                ? state.quizSimAnswers.some(value => value < 0)
                : state.quizSimAnswers[state.quizSimIndex] < 0;
        }
    }

    function goSimQuestion(delta) {
        const questions = activeQuizQuestions();
        if (state.quizSimPhase === 'result') {
            const noThreshold = state.quizHost === 'assignment' && !hasExplicitPassMin();
            const passMin = currentPassMin();
            const retries = currentMaxRetries();
            if (!noThreshold && state.quizSimScore < passMin && state.quizSimAttempt < 1 + retries) {
                state.quizSimAttempt += 1;
                state.quizSimIndex = 0;
                state.quizSimAnswers = Array.from({ length: questions.length }, () => -1);
                state.quizSimPhase = 'taking';
                state.quizSimSeed += 9;
                renderQuizSimulation();
                return;
            }
            openQuizSummary();
            return;
        }
        if (delta > 0 && state.quizSimIndex >= questions.length - 1) {
            const score = questions.reduce((sum, question, index) => {
                const choices = simOptions(question, index);
                return sum + (choices[state.quizSimAnswers[index]] === question.correct ? 1 : 0);
            }, 0);
            state.quizSimScore = score;
            state.quizSimPhase = 'result';
            renderQuizSimulation();
            return;
        }
        const next = state.quizSimIndex + delta;
        if (next < 0 || next >= questions.length) return;
        state.quizSimIndex = next;
        renderQuizSimulation();
    }

    function openQuizSummary() {
        const questions = activeQuizQuestions();
        const html = `<p>${usingDefaultQuiz() ? 'Using the default chapter quiz.' : 'Assignment quiz ready to publish.'} ${questions.length} questions · ${state.quizHost === 'assignment' && !hasExplicitPassMin() ? 'any attempt completes' : `pass ${currentPassMin()}`} · ${currentMaxRetries()} retr${currentMaxRetries() === 1 ? 'y' : 'ies'}.</p>`
            + questions.map((question, index) => `
                <article class="quiz-review-item">
                    <strong>${index + 1}. ${escapeHtml(question.question || '(empty)')}</strong>
                    <p class="form-help">Correct: ${escapeHtml(question.correct || '')}</p>
                </article>
            `).join('');
        if (state.quizHost === 'assignment') {
            el['assignment-quiz-summary-body'].innerHTML = html;
            setAssignmentScreen('quizSummary');
        } else {
            el['quiz-review-list'].innerHTML = html;
            setQuizWizardStep(4);
        }
    }

    function confirmAssignmentQuiz() {
        state.quizOverrideConfirmed = !usingDefaultQuiz();
        setAssignmentScreen('assign3');
    }

    async function continueQuizWizardFromStep1() {
        const bookId = el['quiz-book'].value;
        const chapterId = el['quiz-chapter'].value;
        if (!bookId || !chapterId) {
            toast('Choose a book and chapter first.');
            return;
        }
        const slotCount = Math.min(20, Math.max(1, Number(el['quiz-slot-count'].value) || 10));
        const optionCount = Math.min(6, Math.max(2, Number(el['quiz-option-count'].value) || 4));
        el['quiz-slot-count'].value = String(slotCount);
        el['quiz-option-count'].value = String(optionCount);
        state.quizHost = 'standalone';
        try {
            await loadEffectiveQuizForChapter(chapterId);
            if (!state.quizHasGenerated) {
                state.quizDraftQuestions = Array.from({ length: slotCount }, () => blankQuestion(optionCount));
            }
            setQuizWizardStep(2);
            renderPagedQuizAuthor();
        } catch (error) {
            toast(error.message);
        }
    }

    async function publishAssignmentQuiz(assignmentId) {
        const questions = usingDefaultQuiz() ? [] : state.quizDraftQuestions;
        if (!questions.length) {
            throw new Error('Define at least one quiz question.');
        }
        const incomplete = questions.some(item => !questionComplete(item));
        if (incomplete) {
            throw new Error(incompleteQuestionMessage());
        }
        await api(`/api/classroom/assignments/${encodeURIComponent(assignmentId)}/quiz`, {
            method: 'PUT',
            body: JSON.stringify({
                questions: questions.map(item => flattenQuestion(item))
            })
        });
    }

    async function publishQuizOverrides(chapterId) {
        const questions = usingDefaultQuiz() ? [] : state.quizDraftQuestions;
        if (!questions.length) return;
        const incomplete = questions.some(item => !questionComplete(item));
        if (incomplete) {
            throw new Error(incompleteQuestionMessage());
        }
        const generatedIds = new Set(state.quizGeneratedBase.map(item => item.id).filter(Boolean));
        const operations = [];
        const keptSourceIds = new Set(
            questions
                .filter(item => item.sourceQuestionId && generatedIds.has(item.sourceQuestionId))
                .map(item => item.sourceQuestionId)
        );
        generatedIds.forEach(sourceId => {
            if (!keptSourceIds.has(sourceId)) {
                operations.push({ operation: 'DISABLE', sourceQuestionId: sourceId, sortOrder: 0 });
            }
        });
        questions.forEach((item, index) => {
            const question = flattenQuestion(item);
            if (item.sourceQuestionId && generatedIds.has(item.sourceQuestionId)) {
                operations.push({
                    operation: 'OVERRIDE',
                    sourceQuestionId: item.sourceQuestionId,
                    sortOrder: index,
                    question: { ...question, id: item.sourceQuestionId }
                });
            } else {
                operations.push({
                    operation: 'ADD',
                    sortOrder: index,
                    question
                });
            }
        });
        await api(`/api/classroom/terms/${encodeURIComponent(state.selectedClass.activeTermId)}/chapters/${encodeURIComponent(chapterId)}/quiz-overrides`, {
            method: 'PUT',
            body: JSON.stringify({
                expectedContentVersion: state.quizContentVersion,
                operations
            })
        });
    }

    async function publishQuizWizard() {
        const chapterId = el['quiz-chapter'].value;
        if (usingDefaultQuiz()) {
            closeQuizWizard();
            toast('Using the default generated quiz. No class override published.');
            return;
        }
        el['quiz-wizard-publish'].disabled = true;
        show(el['quiz-wizard-step3-error'], false);
        try {
            await publishQuizOverrides(chapterId);
            closeQuizWizard();
            toast('Class quiz published for this chapter.');
        } catch (error) {
            if (el['quiz-wizard-step3-error']) {
                el['quiz-wizard-step3-error'].textContent = error.message;
                show(el['quiz-wizard-step3-error'], true);
            } else {
                toast(error.message);
            }
        } finally {
            el['quiz-wizard-publish'].disabled = false;
        }
    }

    function questionOptionCount(item, defaultOptionCount) {
        const existingCount = Array.isArray(item?.options) ? item.options.length : 0;
        return Math.max(2, Number(defaultOptionCount) || 0, existingCount);
    }

    function normalizeQuestionOptions(question, defaultOptionCount) {
        const existing = Array.isArray(question?.options)
            ? question.options.map(option => String(option ?? ''))
            : [];
        // Preserve exact existing option counts; only pad brand-new blanks.
        if (existing.length >= 2) {
            return existing;
        }
        const count = questionOptionCount({ options: existing }, defaultOptionCount);
        return Array.from({ length: count }, (_, i) => existing[i] || '');
    }


    async function signOut() {
        try {
            await api('/api/account/logout', { method: 'POST' });
        } catch (_error) {
            // Returning to the library still gives the account UI a chance to reconcile state.
        }
        window.location.assign('/');
    }

    function bindEvents() {
        el['retry-load'].addEventListener('click', initialize);
        el['sign-out'].addEventListener('click', signOut);
        el['new-class-button'].addEventListener('click', openClassModal);
        el['empty-create-button'].addEventListener('click', openClassModal);
        el['class-selector'].addEventListener('change', event => selectClass(event.target.value));
        el['class-form'].addEventListener('submit', createClass);
        document.querySelectorAll('[data-close-modal]').forEach(node => node.addEventListener('click', closeClassModal));
        el['generate-invite'].addEventListener('click', generateInvite);
        el['roster-invite-button'].addEventListener('click', () => {
            document.getElementById('overview').scrollIntoView({ behavior: 'smooth' });
            if (el['invite-link'].value) copyInvite(); else generateInvite();
        });
        el['roster-body'].addEventListener('click', event => {
            const button = event.target.closest('[data-open-student]');
            if (button) {
                event.preventDefault();
                openStudentOverview(button.dataset.openStudent, button);
                return;
            }
            // Progressive enhancement: clicking the row (not a nested control) opens overview.
            const row = event.target.closest('tr[data-user-id]');
            if (!row || event.target.closest('a, button, input, select, textarea, label')) return;
            const userId = row.dataset.userId;
            if (!userId) return;
            const overviewButton = row.querySelector(`[data-open-student="${CSS.escape(userId)}"]`);
            openStudentOverview(userId, overviewButton || null);
        });
        document.querySelectorAll('[data-close-student-overview]').forEach(node => {
            node.addEventListener('click', closeStudentOverview);
        });
        el['student-overview-body']?.addEventListener('click', event => {
            const toggle = event.target.closest('[data-overview-collapse]');
            if (!toggle) return;
            const section = toggle.closest('[data-overview-section]');
            if (!section) return;
            const nextCollapsed = toggle.getAttribute('aria-expanded') === 'true';
            applyOverviewSectionCollapsed(section, nextCollapsed);
            persistOverviewSectionCollapsed(toggle.dataset.overviewCollapse, nextCollapsed);
        });
        el['copy-invite'].addEventListener('click', copyInvite);
        el['new-assignment-button'].addEventListener('click', () => openAssignmentModal());
        el['assignment-list'].addEventListener('click', event => {
            const deleteButton = event.target.closest('[data-delete-assignment]');
            if (deleteButton) {
                deleteDraftAssignment(deleteButton.dataset.deleteAssignment);
                return;
            }
            const button = event.target.closest('[data-edit-assignment]');
            if (!button) return;
            const assignment = state.assignments.find(item => item.assignmentId === button.dataset.editAssignment);
            if (assignment) openAssignmentModal(assignment);
        });
        el['assignment-book-search'].addEventListener('focus', renderBookOptions);
        el['assignment-book-search'].addEventListener('input', () => {
            el['assignment-book'].value = '';
            el['assignment-book-search'].setCustomValidity('Choose a book from the suggestions.');
            populateChapterOptions();
            renderBookOptions();
        });
        el['assignment-book-search'].addEventListener('keydown', event => {
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                event.preventDefault();
                moveActiveBookOption(event.key === 'ArrowDown' ? 1 : -1);
                return;
            }
            if (event.key === 'Enter' && state.activeBookOption >= 0) {
                event.preventDefault();
                const options = el['assignment-book-options'].querySelectorAll('[role="option"]');
                selectBook(options[state.activeBookOption]?.dataset.bookId);
                return;
            }
            if (event.key === 'Escape' && el['assignment-book-search'].getAttribute('aria-expanded') === 'true') {
                event.preventDefault();
                event.stopPropagation();
                closeBookOptions();
            }
        });
        el['assignment-book-search'].addEventListener('blur', () => window.setTimeout(closeBookOptions, 100));
        el['assignment-chapter-search']?.addEventListener('input', renderChapterOptions);
        el['assignment-whole-book']?.addEventListener('change', () => {
            if (el['assignment-whole-book'].checked) {
                state.selectedChapterIds = [];
            }
            renderChapterOptions();
        });
        el['assignment-book-options'].addEventListener('mousedown', event => {
            const option = event.target.closest('[data-book-id]');
            if (!option) return;
            event.preventDefault();
            selectBook(option.dataset.bookId);
        });
        el['assignment-form'].addEventListener('submit', saveAssignment);
        document.querySelectorAll('[data-close-assignment]').forEach(node => node.addEventListener('click', closeAssignmentModal));
        el['assignment-next-1']?.addEventListener('click', goAssignmentNextFrom1);
        el['assignment-back-2']?.addEventListener('click', () => setAssignmentScreen('assign1'));
        el['assignment-next-2']?.addEventListener('click', goAssignmentNextFrom2);
        el['assignment-back-3']?.addEventListener('click', () => {
            setAssignmentScreen(state.quizChoice === 'require' ? 'quizSummary' : 'assign2');
        });
        el['assignment-delete-draft']?.addEventListener('click', () => {
            if (state.editingAssignmentId) deleteDraftAssignment(state.editingAssignmentId);
        });
        document.querySelectorAll('[data-quiz-choice]').forEach(button => {
            button.addEventListener('click', () => {
                state.quizChoice = button.dataset.quizChoice;
                syncAssignmentPassRuleVisibility();
            });
        });
        el['assignment-quiz-author-back']?.addEventListener('click', () => setAssignmentScreen('assign2'));
        el['assignment-quiz-author-prev']?.addEventListener('click', () => goAuthorQuestion(-1));
        el['assignment-quiz-author-next']?.addEventListener('click', () => goAuthorQuestion(1));
        el['assignment-quiz-author-add']?.addEventListener('click', addAuthorQuestion);
        el['assignment-quiz-sim-back']?.addEventListener('click', () => {
            setAssignmentScreen('quizAuthor');
            renderPagedQuizAuthor();
        });
        el['assignment-quiz-sim-prev']?.addEventListener('click', () => goSimQuestion(-1));
        el['assignment-quiz-sim-next']?.addEventListener('click', () => goSimQuestion(1));
        el['assignment-quiz-summary-back']?.addEventListener('click', () => {
            setAssignmentScreen('quizSim');
            renderQuizSimulation();
        });
        el['assignment-quiz-summary-confirm']?.addEventListener('click', confirmAssignmentQuiz);
        el['features-form'].addEventListener('change', saveFeatures);
        el['open-quiz-wizard']?.addEventListener('click', openQuizWizard);
        document.querySelectorAll('[data-close-quiz-wizard]').forEach(node => node.addEventListener('click', closeQuizWizard));
        el['quiz-wizard-next-1']?.addEventListener('click', continueQuizWizardFromStep1);
        el['quiz-wizard-back-2']?.addEventListener('click', () => setQuizWizardStep(1));
        el['quiz-wizard-next-2']?.addEventListener('click', () => goAuthorQuestion(1));
        el['quiz-author-prev']?.addEventListener('click', () => goAuthorQuestion(-1));
        el['quiz-add-question']?.addEventListener('click', addAuthorQuestion);
        el['quiz-wizard-back-3']?.addEventListener('click', () => {
            setQuizWizardStep(2);
            renderPagedQuizAuthor();
        });
        el['quiz-sim-prev']?.addEventListener('click', () => goSimQuestion(-1));
        el['quiz-sim-next']?.addEventListener('click', () => goSimQuestion(1));
        el['quiz-wizard-back-4']?.addEventListener('click', () => {
            setQuizWizardStep(3);
            renderQuizSimulation();
        });
        el['quiz-wizard-publish']?.addEventListener('click', publishQuizWizard);
        el['quiz-book-search']?.addEventListener('focus', renderQuizBookOptions);
        el['quiz-book-search']?.addEventListener('input', () => {
            el['quiz-book'].value = '';
            el['quiz-book-search'].setCustomValidity('Choose a book from the suggestions.');
            renderQuizBookOptions();
        });
        el['quiz-book-search']?.addEventListener('keydown', event => {
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                event.preventDefault();
                moveActiveQuizBookOption(event.key === 'ArrowDown' ? 1 : -1);
                return;
            }
            if (event.key === 'Enter' && state.activeQuizBookOption >= 0) {
                event.preventDefault();
                const options = el['quiz-book-options'].querySelectorAll('[role="option"]');
                selectQuizBook(options[state.activeQuizBookOption]?.dataset.bookId);
                return;
            }
            if (event.key === 'Escape' && el['quiz-book-search'].getAttribute('aria-expanded') === 'true') {
                event.preventDefault();
                event.stopPropagation();
                closeQuizBookOptions();
            }
        });
        el['quiz-book-search']?.addEventListener('blur', () => window.setTimeout(closeQuizBookOptions, 100));
        el['quiz-book-options']?.addEventListener('mousedown', event => {
            const option = event.target.closest('[data-book-id]');
            if (!option) return;
            event.preventDefault();
            selectQuizBook(option.dataset.bookId);
        });
        el['quiz-chapter-search']?.addEventListener('focus', renderQuizChapterOptions);
        el['quiz-chapter-search']?.addEventListener('input', () => {
            el['quiz-chapter'].value = '';
            el['quiz-chapter-search'].setCustomValidity('Choose a chapter from the suggestions.');
            renderQuizChapterOptions();
        });
        el['quiz-chapter-search']?.addEventListener('keydown', event => {
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                event.preventDefault();
                moveActiveQuizChapterOption(event.key === 'ArrowDown' ? 1 : -1);
                return;
            }
            if (event.key === 'Enter' && state.activeQuizChapterOption >= 0) {
                event.preventDefault();
                const options = el['quiz-chapter-options'].querySelectorAll('[role="option"]');
                selectQuizChapter(options[state.activeQuizChapterOption]?.dataset.chapterId);
                return;
            }
            if (event.key === 'Escape' && el['quiz-chapter-search'].getAttribute('aria-expanded') === 'true') {
                event.preventDefault();
                event.stopPropagation();
                closeQuizChapterOptions();
            }
        });
        el['quiz-chapter-search']?.addEventListener('blur', () => window.setTimeout(closeQuizChapterOptions, 100));
        el['quiz-chapter-options']?.addEventListener('mousedown', event => {
            const option = event.target.closest('[data-chapter-id]');
            if (!option) return;
            event.preventDefault();
            selectQuizChapter(option.dataset.chapterId);
        });
        document.addEventListener('keydown', closeTopmostModalOnEscape);
        window.addEventListener('resize', syncOpenBookLists);
        el['assignment-modal']?.addEventListener('scroll', syncOpenBookLists);
        el['quiz-wizard-modal']?.addEventListener('scroll', syncOpenBookLists);
    }

    bindEvents();
    initialize();
}());
