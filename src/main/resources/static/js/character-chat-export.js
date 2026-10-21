(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
        return;
    }
    root.CharacterChatExport = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    function asText(value) {
        if (value == null) {
            return '';
        }
        return String(value).trim();
    }

    function slugify(value, fallback) {
        const base = asText(value)
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, '-')
            .replace(/^-+|-+$/g, '');
        const clipped = base.slice(0, 48).replace(/-+$/g, '');
        return clipped || fallback || 'export';
    }

    function formatTimestamp(value) {
        if (value == null || value === '') {
            return '';
        }
        const date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) {
            return '';
        }
        return date.toISOString();
    }

    function speakerLabel(role, characterName) {
        const normalized = asText(role).toLowerCase();
        if (normalized === 'user') {
            return 'You';
        }
        if (normalized === 'character' || normalized === 'assistant') {
            return characterName || 'Character';
        }
        if (normalized === 'system') {
            return 'System';
        }
        return role ? String(role) : 'Speaker';
    }

    function hasDownloadableTranscript(messages) {
        if (!Array.isArray(messages) || messages.length === 0) {
            return false;
        }
        return messages.some((message) => asText(message && message.content).length > 0);
    }

    /** "October 21, 2026, 9:14 AM" in the reader's own time zone (or {@code timeZone}, for tests). */
    function formatReadableDate(value, timeZone) {
        const date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) {
            return '';
        }
        try {
            return date.toLocaleString('en-US', {
                dateStyle: 'long',
                timeStyle: 'short',
                ...(timeZone ? { timeZone } : {})
            });
        } catch (error) {
            return formatTimestamp(date);
        }
    }

    /**
     * The conversation as plain text, for students to upload to a course dropbox and quote in a
     * paper: opens in Notepad, Word, Google Docs or a browser with no stray formatting marks.
     */
    function formatCharacterChatTranscript(options) {
        const opts = options && typeof options === 'object' ? options : {};
        const bookTitle = asText(opts.bookTitle) || 'Unknown book';
        const bookAuthor = asText(opts.bookAuthor);
        const characterName = asText(opts.characterName) || 'Character';
        const exportedAt = formatReadableDate(opts.exportedAt || Date.now(), opts.timeZone)
            || formatReadableDate(Date.now(), opts.timeZone);
        const chapterLabel = asText(opts.chapterLabel);
        const messages = Array.isArray(opts.messages) ? opts.messages : [];

        const lines = [
            'Character conversation (Classic Chat Reader)',
            '',
            `Book: ${bookTitle}${bookAuthor ? ` (${bookAuthor})` : ''}`,
            `Character: ${characterName}`,
            `Date: ${exportedAt}`
        ];
        if (chapterLabel) {
            lines.push(`Reader position: ${chapterLabel}`);
        }
        lines.push('', '--------------------------------------------------', '');

        let wroteMessage = false;
        for (const message of messages) {
            const content = asText(message && message.content);
            if (!content) {
                continue;
            }
            const label = speakerLabel(message && message.role, characterName);
            lines.push(`${label}: ${content}`, '');
            wroteMessage = true;
        }
        if (!wroteMessage) {
            lines.push('(No messages in this conversation yet.)', '');
        }

        return lines.join('\n').trim() + '\n';
    }

    function buildCharacterChatFilename(options) {
        const opts = options && typeof options === 'object' ? options : {};
        const bookSlug = slugify(opts.bookTitle, 'book');
        const characterSlug = slugify(opts.characterName, 'character');
        const date = opts.exportedAt instanceof Date
            ? opts.exportedAt
            : new Date(opts.exportedAt || Date.now());
        const day = Number.isNaN(date.getTime())
            ? new Date().toISOString().slice(0, 10)
            : date.toISOString().slice(0, 10);
        return `character-chat_${bookSlug}_${characterSlug}_${day}.txt`;
    }

    function downloadTextFile(filename, content, mimeType) {
        const safeName = asText(filename) || 'download.txt';
        const type = mimeType || 'text/plain;charset=utf-8';
        const text = content == null ? '' : String(content);
        // A byte-order mark makes Word and older Notepad read the file as UTF-8, so the curly
        // quotes and dashes in character replies don't turn into garbage characters.
        const blob = new Blob([type.startsWith('text/plain') ? '\uFEFF' + text : text], { type });
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = safeName;
        anchor.rel = 'noopener';
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        // Delay revoke so the browser can start the download.
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        return safeName;
    }

    return {
        asText,
        slugify,
        speakerLabel,
        hasDownloadableTranscript,
        formatReadableDate,
        formatCharacterChatTranscript,
        buildCharacterChatFilename,
        downloadTextFile
    };
});
