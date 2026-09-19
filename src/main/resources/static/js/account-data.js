(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
        return;
    }
    root.AccountData = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    /**
     * Download my data + account deletion (BL-043.6). Deletion is allowed for enrolled students;
     * the notice names their classes and offers the export first instead of blocking.
     */
    function deletionNotice(preview) {
        if (!preview) return { blocked: false, paragraphs: [] };
        if (preview.blockedReason) {
            return { blocked: true, paragraphs: [preview.blockedReason] };
        }
        const paragraphs = [
            'This permanently deletes your account and everything saved to it: reading progress, notes and bookmarks, quiz history, character chats, and Reading Buddy chats. It cannot be undone.'
        ];
        const classes = Array.isArray(preview.classes) ? preview.classes : [];
        if (classes.length) {
            const names = classes.map((c) => [c.className, c.termName].filter(Boolean).join(' — ')).join('; ');
            paragraphs.push(`You are enrolled in: ${names}. You will leave ${classes.length === 1 ? 'this class' : 'these classes'}, and your teacher will no longer see your progress or Reading Buddy chats. Records of past teacher access are kept without your name, as school records require.`);
        }
        paragraphs.push('Download your data first if you want a copy. Reading data stored only in this browser is not affected.');
        return { blocked: false, paragraphs };
    }

    function deletionRequestBody(email, password, passwordRequired) {
        const body = { confirm: true, email: String(email || '').trim() };
        if (passwordRequired) body.password = String(password || '');
        return body;
    }

    function canSubmitDeletion(typedEmail, accountEmail, password, passwordRequired) {
        const typed = String(typedEmail || '').trim().toLowerCase();
        return Boolean(typed) && typed === String(accountEmail || '').trim().toLowerCase()
            && (!passwordRequired || Boolean(password));
    }

    function bind(doc, fetchImpl, onDeleted) {
        const el = (id) => doc.getElementById(id);
        const panel = el('account-delete-panel');
        const start = el('account-delete-start');
        if (!panel || !start) return { reset() {} };
        const notice = el('account-delete-notice');
        const email = el('account-delete-email');
        const password = el('account-delete-password');
        const passwordRow = el('account-delete-password-row');
        const form = el('account-delete-form');
        const status = el('account-delete-status');
        const confirm = el('account-delete-confirm');
        let preview = null;

        const setStatus = (text, tone) => {
            status.textContent = text || '';
            status.className = `auth-modal-status${tone ? ` ${tone}` : ''}`;
        };
        const refresh = () => {
            confirm.disabled = !preview || !canSubmitDeletion(email.value, preview.email, password.value, preview.passwordRequired);
        };
        const reset = () => {
            preview = null;
            panel.classList.add('hidden');
            start.classList.remove('hidden');
            email.value = '';
            password.value = '';
            setStatus('');
        };

        start.addEventListener('click', async () => {
            setStatus('Checking your account…');
            try {
                const res = await fetchImpl('/api/account/delete-preview', { credentials: 'same-origin' });
                if (!res.ok) throw new Error(`status ${res.status}`);
                preview = await res.json();
            } catch (error) {
                setStatus('Could not load account details. Try again.', 'error');
                return;
            }
            const text = deletionNotice(preview);
            notice.replaceChildren(...text.paragraphs.map((p) => {
                const node = doc.createElement('p');
                node.textContent = p;
                return node;
            }));
            form.classList.toggle('hidden', text.blocked);
            passwordRow.classList.toggle('hidden', !preview.passwordRequired);
            panel.classList.remove('hidden');
            start.classList.add('hidden');
            setStatus('');
            refresh();
            if (!text.blocked) email.focus();
        });
        email.addEventListener('input', refresh);
        password.addEventListener('input', refresh);
        el('account-delete-cancel').addEventListener('click', reset);
        confirm.addEventListener('click', async () => {
            if (!preview || confirm.disabled) return;
            confirm.disabled = true;
            setStatus('Deleting your account…');
            try {
                const res = await fetchImpl('/api/account/delete', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(deletionRequestBody(email.value, password.value, preview.passwordRequired))
                });
                const body = await res.json().catch(() => ({}));
                if (!res.ok) {
                    setStatus(body.error || 'Your account was not deleted.', 'error');
                    refresh();
                    return;
                }
                setStatus('Your account has been deleted.', 'success');
                onDeleted();
            } catch (error) {
                setStatus('Your account was not deleted. Check your connection and try again.', 'error');
                refresh();
            }
        });
        return { reset };
    }

    let bound = { reset() {} };
    if (typeof document !== 'undefined') {
        document.addEventListener('DOMContentLoaded', () => {
            bound = bind(document, (...args) => fetch(...args), () => setTimeout(() => window.location.reload(), 1200));
        });
    }

    return {
        deletionNotice,
        deletionRequestBody,
        canSubmitDeletion,
        bind,
        reset: () => bound.reset()
    };
});
