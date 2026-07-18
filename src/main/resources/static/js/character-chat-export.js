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

    function formatCharacterChatMarkdown(options) {
        const opts = options && typeof options === 'object' ? options : {};
        const bookTitle = asText(opts.bookTitle) || 'Unknown book';
        const bookAuthor = asText(opts.bookAuthor);
        const characterName = asText(opts.characterName) || 'Character';
        const exportedAt = formatTimestamp(opts.exportedAt || Date.now()) || new Date().toISOString();
        const chapterLabel = asText(opts.chapterLabel);
        const messages = Array.isArray(opts.messages) ? opts.messages : [];

        const lines = [
            '# Character conversation',
            '',
            `- Book: ${bookTitle}${bookAuthor ? ` (${bookAuthor})` : ''}`,
            `- Character: ${characterName}`,
            `- Exported: ${exportedAt}`
        ];
        if (chapterLabel) {
            lines.push(`- Reader position: ${chapterLabel}`);
        }
        lines.push('', '## Transcript', '');

        let wroteMessage = false;
        for (const message of messages) {
            const content = asText(message && message.content);
            if (!content) {
                continue;
            }
            const label = speakerLabel(message && message.role, characterName);
            lines.push(`**${label}:** ${content}`, '');
            wroteMessage = true;
        }
        if (!wroteMessage) {
            lines.push('_No messages in this conversation yet._', '');
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
        return `character-chat_${bookSlug}_${characterSlug}_${day}.md`;
    }

    function downloadTextFile(filename, content, mimeType) {
        const safeName = asText(filename) || 'download.txt';
        const blob = new Blob([content == null ? '' : String(content)], {
            type: mimeType || 'text/markdown;charset=utf-8'
        });
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
        formatCharacterChatMarkdown,
        buildCharacterChatFilename,
        downloadTextFile
    };
});
