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
        editingAssignmentId: null,
        featureSaveTimer: null
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
            const message = payload?.message || payload?.error || `Request failed (${response.status})`;
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

    function classById(id) {
        return state.classes.find(item => item.classId === id);
    }

    async function initialize() {
        setPageState('loading');
        try {
            const [account, capabilities, books] = await Promise.all([
                api('/api/account/status'),
                api('/api/classroom/capabilities'),
                api('/api/library').catch(() => [])
            ]);
            state.account = account;
            state.capabilities = capabilities || {};
            state.books = Array.isArray(books) ? books : [];
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
            if (item.quizRequired) details.push('Quiz required');
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
            if (input.type === 'checkbox') input.checked = state.features?.[input.name] !== false;
        });
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
        el['assignment-book'].innerHTML = '<option value="">Choose a book</option>' + state.books.map(book => (
            `<option value="${escapeHtml(book.id)}">${escapeHtml(book.title)}${book.author ? ` · ${escapeHtml(book.author)}` : ''}</option>`
        )).join('');
        el['assignment-book'].value = selectedId;
        populateChapterOptions('', selectedId);
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
        }
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
            status: String(form.get('status') || 'DRAFT')
        };
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
            if (input.type === 'checkbox') body[input.name] = input.checked;
        });
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
        el['assignment-book'].addEventListener('change', () => populateChapterOptions());
        el['assignment-form'].addEventListener('submit', saveAssignment);
        document.querySelectorAll('[data-close-assignment]').forEach(node => node.addEventListener('click', closeAssignmentModal));
        el['features-form'].addEventListener('change', saveFeatures);
        document.addEventListener('keydown', event => {
            if (event.key !== 'Escape') return;
            closeClassModal();
            closeAssignmentModal();
        });
    }

    bindEvents();
    initialize();
}());
