(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
        return;
    }
    root.LibraryGutenbergSource = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function gutenbergEbookId(book) {
        if (!book || typeof book !== 'object') {
            return '';
        }
        const parsed = Number(book.gutenbergId);
        if (!Number.isInteger(parsed) || parsed <= 0) {
            return '';
        }
        return String(parsed);
    }

    function gutenbergEbookUrl(ebookId) {
        const id = typeof ebookId === 'number' || typeof ebookId === 'string'
            ? gutenbergEbookId({ gutenbergId: ebookId })
            : '';
        return id ? `https://www.gutenberg.org/ebooks/${id}` : '';
    }

    function gutenbergSourceLabel(ebookId) {
        return ebookId ? `[Gutenberg ${ebookId}]` : '';
    }

    function gutenbergSourceAccessibleName(ebookId, bookTitle) {
        if (!ebookId) {
            return '';
        }
        const title = typeof bookTitle === 'string' ? bookTitle.replace(/\s+/g, ' ').trim() : '';
        if (title) {
            return `Project Gutenberg source for ${title}, ebook ${ebookId}`;
        }
        return `Project Gutenberg source, ebook ${ebookId}`;
    }

    function renderGutenbergSourceLink(book) {
        const ebookId = gutenbergEbookId(book);
        if (!ebookId) {
            return '';
        }
        const url = gutenbergEbookUrl(ebookId);
        const label = gutenbergSourceLabel(ebookId);
        const accessibleName = gutenbergSourceAccessibleName(ebookId, book?.title);
        return `<a class="book-item-gutenberg-source" href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer" aria-label="${escapeHtml(accessibleName)}">${escapeHtml(label)}</a>`;
    }

    return {
        gutenbergEbookId,
        gutenbergEbookUrl,
        gutenbergSourceLabel,
        gutenbergSourceAccessibleName,
        renderGutenbergSourceLink
    };
});
