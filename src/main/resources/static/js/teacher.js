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
        featureSaveTimer: null,
        activeBookOption: -1,
        quizWizardStep: 1,
        quizDraftQuestions: [],
        quizGeneratedBase: [],
        activeQuizBookOption: -1,
        activeQuizChapterOption: -1
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
            return `<tr>
                <td><span class="student-name">${escapeHtml(preferredName)}</span>${secondary ? `<span class="student-email">${escapeHtml(secondary)}</span>` : ''}</td>
                <td>${escapeHtml(formatDate(row.joinedDate))}</td>
                <td><span class="status-pill">${escapeHtml((row.status || 'active').toLowerCase())}</span></td>
            </tr>`;
        }).join('');
    }

    function assignmentTarget(assignment) {
        const book = bookById(assignment.bookId);
        const chapter = book?.chapters?.find(item => item.id === assignment.chapterId)
            || (Number.isInteger(assignment.chapterIndex) ? book?.chapters?.[assignment.chapterIndex] : null);
        if (!book) return 'Book unavailable';
        return chapter ? `${book.title} · ${chapter.title}` : book.title;
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
            return `<article class="assignment-card">
                <div>
                    <h3>${escapeHtml(item.title)}</h3>
                    <div class="assignment-meta">
                        <span class="assignment-status ${(item.status || '').toLowerCase()}">${escapeHtml((item.status || 'draft').toLowerCase())}</span>
                        ${details.map(detail => `<span>${escapeHtml(detail)}</span>`).join('')}
                    </div>
                </div>
                <button class="secondary-button" type="button" data-edit-assignment="${escapeHtml(item.assignmentId)}">Edit</button>
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
        const required = el['assignment-quiz-required']?.checked === true;
        show(el['assignment-quiz-pass-rules'], required);
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

    function populateBookOptions(selectedId = '') {
        const selectedBook = bookById(selectedId);
        el['assignment-book'].value = selectedId;
        el['assignment-book-search'].value = selectedBook ? bookDisplayName(selectedBook) : '';
        el['assignment-book-search'].setCustomValidity('');
        closeBookOptions();
        populateChapterOptions('', selectedId);
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
        populateChapterOptions('', book.id);
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

    function populateChapterOptions(
        selectedChapterId = '',
        bookId = el['assignment-book'].value,
        selectedChapterIndex = null
    ) {
        const book = bookById(bookId);
        el['assignment-chapter'].innerHTML = '<option value="">Whole book</option>' + (book?.chapters || []).map((chapter, index) => (
            `<option value="${escapeHtml(chapter.id)}" data-index="${index}">${escapeHtml(chapter.title)}</option>`
        )).join('');

        if (selectedChapterId) {
            el['assignment-chapter'].value = selectedChapterId;
            return;
        }
        if (Number.isInteger(selectedChapterIndex)) {
            const match = Array.from(el['assignment-chapter'].options).find(
                option => option.dataset.index === String(selectedChapterIndex)
            );
            if (match) {
                el['assignment-chapter'].value = match.value;
                return;
            }
        }
        el['assignment-chapter'].value = '';
    }

    function openAssignmentModal(assignment = null) {
        state.editingAssignmentId = assignment?.assignmentId || null;
        el['assignment-form'].reset();
        el['assignment-modal-title'].textContent = assignment ? 'Edit assignment' : 'New assignment';
        populateBookOptions(assignment?.bookId || '');
        if (assignment) {
            el['assignment-title'].value = assignment.title || '';
            const chapterIndex = Number.isInteger(assignment.chapterIndex) ? assignment.chapterIndex : null;
            populateChapterOptions(assignment.chapterId || '', assignment.bookId, chapterIndex);
            el['assignment-form'].elements.dueDate.value = assignment.dueDate || '';
            el['assignment-form'].elements.availableFromDate.value = assignment.availableFromDate || '';
            el['assignment-form'].elements.status.value = assignment.status || 'DRAFT';
            el['assignment-form'].elements.quizRequired.checked = assignment.quizRequired === true;
            el['assignment-form'].elements.characterChatRequired.checked = assignment.characterChatRequired === true;
            el['assignment-form'].elements.quizPassMinCorrect.value =
                assignment.quizPassMinCorrect != null ? String(assignment.quizPassMinCorrect) : '';
            el['assignment-form'].elements.quizMaxRetries.value =
                assignment.quizMaxRetries != null ? String(assignment.quizMaxRetries) : '';
        } else {
            const defaults = state.features || {};
            el['assignment-form'].elements.quizPassMinCorrect.value =
                defaults.defaultQuizPassMinCorrect != null ? String(defaults.defaultQuizPassMinCorrect) : '';
            el['assignment-form'].elements.quizMaxRetries.value =
                defaults.defaultQuizMaxRetries != null ? String(defaults.defaultQuizMaxRetries) : '';
        }
        syncAssignmentPassRuleVisibility();
        show(el['assignment-form-error'], false);
        show(el['assignment-modal'], true);
        window.setTimeout(() => el['assignment-title'].focus(), 0);
    }

    function closeAssignmentModal() {
        show(el['assignment-modal'], false);
        state.editingAssignmentId = null;
    }

    async function saveAssignment(event) {
        event.preventDefault();
        const form = new FormData(el['assignment-form']);
        const chapterOption = el['assignment-chapter'].selectedOptions[0];
        const chapterId = String(form.get('chapterId') || '');
        const dueDateRaw = String(form.get('dueDate') || '').trim();
        const availableFromRaw = String(form.get('availableFromDate') || '').trim();
        const chapterIndex = chapterId
            ? Number(chapterOption?.dataset.index)
            : (chapterOption?.dataset.index != null && chapterOption.value
                ? Number(chapterOption.dataset.index)
                : null);
        const body = {
            title: String(form.get('title') || '').trim(),
            bookId: String(form.get('bookId') || ''),
            chapterId,
            chapterIndex: Number.isInteger(chapterIndex) ? chapterIndex : null,
            dueDate: dueDateRaw || null,
            availableFromDate: availableFromRaw || null,
            quizRequired: form.get('quizRequired') === 'on',
            characterChatRequired: form.get('characterChatRequired') === 'on',
            status: String(form.get('status') || 'DRAFT')
        };
        const minCorrectRaw = String(form.get('quizPassMinCorrect') || '').trim();
        const maxRetriesRaw = String(form.get('quizMaxRetries') || '').trim();
        if (body.quizRequired) {
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
            // Null date fields mean "leave unchanged" on the API; explicit clear flags allow removal.
            body.clearDueDate = !dueDateRaw;
            body.clearAvailableFromDate = !availableFromRaw;
        }
        const path = state.editingAssignmentId
            ? `/api/classroom/assignments/${encodeURIComponent(state.editingAssignmentId)}`
            : `/api/classroom/terms/${encodeURIComponent(state.selectedClass.activeTermId)}/assignments`;
        el['assignment-submit'].disabled = true;
        show(el['assignment-form-error'], false);
        try {
            const saved = await api(path, { method: state.editingAssignmentId ? 'PUT' : 'POST', body: JSON.stringify(body) });
            const existingIndex = state.assignments.findIndex(item => item.assignmentId === saved.assignmentId);
            if (existingIndex >= 0) state.assignments.splice(existingIndex, 1, saved);
            else state.assignments.push(saved);
            renderAssignments();
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

    function blankQuestion(optionCount) {
        const options = Array.from({ length: Math.max(2, optionCount || 4) }, () => '');
        return {
            id: crypto.randomUUID ? crypto.randomUUID() : `q-${Date.now()}-${Math.random()}`,
            question: '',
            options,
            correctOptionIndex: 0,
            sourceQuestionId: null,
            mode: 'add'
        };
    }

    function setQuizWizardStep(step) {
        state.quizWizardStep = step;
        show(el['quiz-wizard-step-1'], step === 1);
        show(el['quiz-wizard-step-2'], step === 2);
        show(el['quiz-wizard-step-3'], step === 3);
        [1, 2, 3].forEach(index => {
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
        const defaults = state.features || {};
        el['quiz-slot-count'].value = String(defaults.defaultQuizQuestionCount || 5);
        el['quiz-option-count'].value = String(defaults.defaultQuizOptionCount || 4);
        el['quiz-book'].value = '';
        el['quiz-book-search'].value = '';
        el['quiz-chapter'].value = '';
        if (el['quiz-chapter-search']) el['quiz-chapter-search'].value = '';
        populateQuizChapterOptions();
        state.quizDraftQuestions = [];
        state.quizGeneratedBase = [];
        setQuizWizardStep(1);
        show(el['quiz-wizard-modal'], true);
        window.setTimeout(() => el['quiz-book-search'].focus(), 0);
    }

    function closeQuizWizard() {
        show(el['quiz-wizard-modal'], false);
        state.quizWizardStep = 1;
        state.quizDraftQuestions = [];
        state.quizGeneratedBase = [];
    }

    function questionOptionCount(item, defaultOptionCount) {
        const existingCount = Array.isArray(item?.options) ? item.options.length : 0;
        return Math.max(2, Number(defaultOptionCount) || 0, existingCount);
    }

    function normalizeQuestionOptions(question, defaultOptionCount) {
        const existing = Array.isArray(question?.options)
            ? question.options.map(option => String(option ?? ''))
            : [];
        const count = questionOptionCount({ options: existing }, defaultOptionCount);
        return Array.from({ length: count }, (_, i) => existing[i] || '');
    }

    function renderQuizQuestionEditor() {
        const defaultOptionCount = Math.max(2, Number(el['quiz-option-count'].value) || 4);
        el['quiz-question-editor'].innerHTML = state.quizDraftQuestions.map((item, index) => {
            const options = normalizeQuestionOptions(item, defaultOptionCount);
            return `<article class="quiz-question-card" data-question-index="${index}">
                <header>
                    <strong>Question ${index + 1}</strong>
                    <span class="quiz-question-actions">
                        <button type="button" class="text-button" data-ai-distractors="${index}">AI distractors</button>
                        <button type="button" class="text-button" data-remove-question="${index}" ${state.quizDraftQuestions.length <= 1 ? 'disabled' : ''}>Remove</button>
                    </span>
                </header>
                <label>Stem<textarea data-field="question">${escapeHtml(item.question || '')}</textarea></label>
                ${options.map((option, optionIndex) => `
                    <div class="quiz-option-row">
                        <input type="radio" name="correct-${index}" value="${optionIndex}" ${Number(item.correctOptionIndex) === optionIndex ? 'checked' : ''} aria-label="Mark option ${optionIndex + 1} correct">
                        <input type="text" data-field="option" data-option-index="${optionIndex}" value="${escapeHtml(option)}" placeholder="Option ${optionIndex + 1}">
                    </div>
                `).join('')}
            </article>`;
        }).join('') + `<div class="wizard-toolbar"><button type="button" class="secondary-button" id="quiz-add-question">Add question</button></div>`;
        el['quiz-add-question']?.addEventListener('click', () => {
            collectQuizDraftFromEditor();
            if (state.quizDraftQuestions.length >= 20) {
                toast('Maximum 20 questions.');
                return;
            }
            state.quizDraftQuestions.push(blankQuestion(defaultOptionCount));
            renderQuizQuestionEditor();
        });
    }

    function collectQuizDraftFromEditor() {
        const defaultOptionCount = Math.max(2, Number(el['quiz-option-count'].value) || 4);
        const cards = Array.from(el['quiz-question-editor'].querySelectorAll('.quiz-question-card'));
        state.quizDraftQuestions = cards.map((card, index) => {
            const existing = state.quizDraftQuestions[index] || blankQuestion(defaultOptionCount);
            const question = card.querySelector('[data-field="question"]')?.value?.trim() || '';
            const options = Array.from(card.querySelectorAll('[data-field="option"]'))
                .map(input => String(input.value || '').trim());
            const checked = card.querySelector(`input[name="correct-${index}"]:checked`);
            let correct = checked != null ? Number(checked.value) : existing.correctOptionIndex;
            if (!Number.isInteger(correct) || correct < 0 || correct >= options.length) {
                correct = 0;
            }
            return {
                ...existing,
                question,
                options,
                correctOptionIndex: correct
            };
        });
    }

    function renderQuizReview() {
        el['quiz-review-list'].innerHTML = state.quizDraftQuestions.map((item, index) => `
            <article class="quiz-review-item">
                <strong>${index + 1}. ${escapeHtml(item.question || '(empty)')}</strong>
                <ol>${(item.options || []).map((option, optionIndex) =>
                    `<li${optionIndex === item.correctOptionIndex ? ' style="color: var(--success); font-weight: 600;"' : ''}>${escapeHtml(option || '(blank)')}</li>`
                ).join('')}</ol>
            </article>
        `).join('');
    }

    async function continueQuizWizardFromStep1() {
        const bookId = el['quiz-book'].value;
        const chapterId = el['quiz-chapter'].value;
        if (!bookId || !chapterId) {
            toast('Choose a book and chapter first.');
            return;
        }
        const slotCount = Math.max(1, Number(el['quiz-slot-count'].value) || 5);
        const optionCount = Math.max(2, Number(el['quiz-option-count'].value) || 4);
        try {
            const effective = await api(`/api/classroom/terms/${encodeURIComponent(state.selectedClass.activeTermId)}/chapters/${encodeURIComponent(chapterId)}/effective-quiz`);
            state.quizGeneratedBase = Array.isArray(effective.generatedQuestions) ? effective.generatedQuestions : [];
            if (Array.isArray(effective.effectiveQuestions) && effective.effectiveQuestions.length > 0) {
                state.quizDraftQuestions = effective.effectiveQuestions.map(question => ({
                    id: question.id || (crypto.randomUUID ? crypto.randomUUID() : `q-${Date.now()}`),
                    question: question.question || '',
                    options: normalizeQuestionOptions(question, optionCount),
                    correctOptionIndex: Number.isInteger(question.correctOptionIndex) ? question.correctOptionIndex : 0,
                    sourceQuestionId: state.quizGeneratedBase.some(base => base.id === question.id) ? question.id : null,
                    mode: state.quizGeneratedBase.some(base => base.id === question.id) ? 'override' : 'add'
                }));
                // Honor configured slot count so teachers can grow/shrink the set manually.
                while (state.quizDraftQuestions.length < slotCount) {
                    state.quizDraftQuestions.push(blankQuestion(optionCount));
                }
                if (state.quizDraftQuestions.length > slotCount) {
                    state.quizDraftQuestions = state.quizDraftQuestions.slice(0, slotCount);
                }
            } else {
                state.quizDraftQuestions = Array.from({ length: slotCount }, () => blankQuestion(optionCount));
            }
            renderQuizQuestionEditor();
            setQuizWizardStep(2);
        } catch (error) {
            toast(error.message);
        }
    }

    function loadGeneratedIntoWizard() {
        const optionCount = Math.max(2, Number(el['quiz-option-count'].value) || 4);
        if (!state.quizGeneratedBase.length) {
            toast('No generated quiz is cached for this chapter yet.');
            return;
        }
        state.quizDraftQuestions = state.quizGeneratedBase.map(question => ({
            id: question.id,
            question: question.question || '',
            options: normalizeQuestionOptions(question, optionCount),
            correctOptionIndex: Number.isInteger(question.correctOptionIndex) ? question.correctOptionIndex : 0,
            sourceQuestionId: question.id,
            mode: 'override'
        }));
        renderQuizQuestionEditor();
        toast('Loaded generated questions. Edit freely before publishing.');
    }

    async function aiSuggestQuestions() {
        const chapterId = el['quiz-chapter'].value;
        const count = Math.max(1, Number(el['quiz-slot-count'].value) || 5);
        const optionCount = Math.max(2, Number(el['quiz-option-count'].value) || 4);
        el['quiz-ai-suggest'].disabled = true;
        try {
            const result = await api(`/api/classroom/terms/${encodeURIComponent(state.selectedClass.activeTermId)}/chapters/${encodeURIComponent(chapterId)}/suggest-questions`, {
                method: 'POST',
                body: JSON.stringify({ count, optionCount })
            });
            const questions = Array.isArray(result.questions) ? result.questions : [];
            if (!questions.length) {
                toast('AI returned no questions.');
                return;
            }
            state.quizDraftQuestions = questions.map(question => ({
                id: question.id || (crypto.randomUUID ? crypto.randomUUID() : `q-${Date.now()}`),
                question: question.question || '',
                options: Array.from({ length: optionCount }, (_, i) => question.options?.[i] || ''),
                correctOptionIndex: Number.isInteger(question.correctOptionIndex) ? question.correctOptionIndex : 0,
                sourceQuestionId: null,
                mode: 'add'
            }));
            renderQuizQuestionEditor();
            toast('AI suggestions loaded. Review and edit before publishing.');
        } catch (error) {
            toast(error.message);
        } finally {
            el['quiz-ai-suggest'].disabled = false;
        }
    }

    async function aiSuggestDistractors(index) {
        collectQuizDraftFromEditor();
        const item = state.quizDraftQuestions[index];
        if (!item?.question) {
            toast('Enter a question stem first.');
            return;
        }
        const correct = item.options?.[item.correctOptionIndex] || '';
        if (!correct) {
            toast('Mark and fill the correct answer first.');
            return;
        }
        const chapterId = el['quiz-chapter'].value;
        const needed = Math.max(1, (item.options?.length || 4) - 1);
        try {
            const result = await api(`/api/classroom/terms/${encodeURIComponent(state.selectedClass.activeTermId)}/chapters/${encodeURIComponent(chapterId)}/suggest-distractors`, {
                method: 'POST',
                body: JSON.stringify({
                    question: item.question,
                    correctAnswer: correct,
                    count: needed
                })
            });
            const distractors = Array.isArray(result.distractors) ? result.distractors : [];
            const nextOptions = [...(item.options || [])];
            let distractorIndex = 0;
            for (let i = 0; i < nextOptions.length; i++) {
                if (i === item.correctOptionIndex) continue;
                if (distractorIndex < distractors.length) {
                    nextOptions[i] = distractors[distractorIndex++];
                }
            }
            state.quizDraftQuestions[index] = { ...item, options: nextOptions };
            renderQuizQuestionEditor();
            toast('Distractors filled. Adjust as needed.');
        } catch (error) {
            toast(error.message);
        }
    }

    async function publishQuizWizard() {
        collectQuizDraftFromEditor();
        const chapterId = el['quiz-chapter'].value;
        const incomplete = state.quizDraftQuestions.some(item =>
            !item.question
            || !Array.isArray(item.options)
            || item.options.some(option => !option)
            || !Number.isInteger(item.correctOptionIndex)
        );
        if (incomplete) {
            el['quiz-wizard-step3-error'].textContent = 'Every question needs a stem, all options filled, and a correct answer.';
            show(el['quiz-wizard-step3-error'], true);
            return;
        }
        const generatedIds = new Set(state.quizGeneratedBase.map(item => item.id).filter(Boolean));
        const operations = [];
        // Disable generated questions that are no longer represented as overrides.
        const keptSourceIds = new Set(
            state.quizDraftQuestions
                .filter(item => item.sourceQuestionId && generatedIds.has(item.sourceQuestionId))
                .map(item => item.sourceQuestionId)
        );
        generatedIds.forEach(sourceId => {
            if (!keptSourceIds.has(sourceId)) {
                operations.push({ operation: 'DISABLE', sourceQuestionId: sourceId, sortOrder: 0 });
            }
        });
        state.quizDraftQuestions.forEach((item, index) => {
            const question = {
                id: item.id,
                question: item.question,
                options: item.options,
                correctOptionIndex: item.correctOptionIndex,
                citationParagraphIndex: null,
                citationSnippet: ''
            };
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
        el['quiz-wizard-publish'].disabled = true;
        show(el['quiz-wizard-step3-error'], false);
        try {
            await api(`/api/classroom/terms/${encodeURIComponent(state.selectedClass.activeTermId)}/chapters/${encodeURIComponent(chapterId)}/quiz-overrides`, {
                method: 'PUT',
                body: JSON.stringify({ operations })
            });
            closeQuizWizard();
            toast('Class quiz published for this chapter.');
        } catch (error) {
            el['quiz-wizard-step3-error'].textContent = error.message;
            show(el['quiz-wizard-step3-error'], true);
        } finally {
            el['quiz-wizard-publish'].disabled = false;
        }
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
        el['copy-invite'].addEventListener('click', copyInvite);
        el['new-assignment-button'].addEventListener('click', () => openAssignmentModal());
        el['assignment-list'].addEventListener('click', event => {
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
        el['assignment-book-options'].addEventListener('mousedown', event => {
            const option = event.target.closest('[data-book-id]');
            if (!option) return;
            event.preventDefault();
            selectBook(option.dataset.bookId);
        });
        el['assignment-form'].addEventListener('submit', saveAssignment);
        document.querySelectorAll('[data-close-assignment]').forEach(node => node.addEventListener('click', closeAssignmentModal));
        el['assignment-quiz-required']?.addEventListener('change', syncAssignmentPassRuleVisibility);
        el['features-form'].addEventListener('change', saveFeatures);
        el['open-quiz-wizard']?.addEventListener('click', openQuizWizard);
        document.querySelectorAll('[data-close-quiz-wizard]').forEach(node => node.addEventListener('click', closeQuizWizard));
        el['quiz-wizard-next-1']?.addEventListener('click', continueQuizWizardFromStep1);
        el['quiz-wizard-back-2']?.addEventListener('click', () => setQuizWizardStep(1));
        el['quiz-wizard-next-2']?.addEventListener('click', () => {
            collectQuizDraftFromEditor();
            renderQuizReview();
            show(el['quiz-wizard-step2-error'], false);
            setQuizWizardStep(3);
        });
        el['quiz-wizard-back-3']?.addEventListener('click', () => {
            renderQuizQuestionEditor();
            setQuizWizardStep(2);
        });
        el['quiz-wizard-publish']?.addEventListener('click', publishQuizWizard);
        el['quiz-load-generated']?.addEventListener('click', loadGeneratedIntoWizard);
        el['quiz-ai-suggest']?.addEventListener('click', aiSuggestQuestions);
        el['quiz-question-editor']?.addEventListener('click', event => {
            const removeButton = event.target.closest('[data-remove-question]');
            if (removeButton) {
                collectQuizDraftFromEditor();
                const index = Number(removeButton.dataset.removeQuestion);
                if (!Number.isInteger(index) || state.quizDraftQuestions.length <= 1) return;
                state.quizDraftQuestions.splice(index, 1);
                renderQuizQuestionEditor();
                return;
            }
            const button = event.target.closest('[data-ai-distractors]');
            if (!button) return;
            aiSuggestDistractors(Number(button.dataset.aiDistractors));
        });
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
        document.addEventListener('keydown', event => {
            if (event.key !== 'Escape') return;
            closeClassModal();
            closeAssignmentModal();
            closeQuizWizard();
        });
    }

    bindEvents();
    initialize();
}());
