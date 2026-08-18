(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
        return;
    }
    root.AssignmentCardLabel = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

    function looksLikeUuid(value) {
        return UUID_PATTERN.test(String(value || '').trim());
    }

    function usableTitle(value) {
        const title = typeof value === 'string' ? value.trim() : '';
        return title && !looksLikeUuid(title) ? title : '';
    }

    function assignmentChapters(assignment) {
        if (Array.isArray(assignment?.chapters) && assignment.chapters.length > 0) {
            return assignment.chapters;
        }
        if (assignment?.chapterId) {
            return [{
                chapterId: assignment.chapterId,
                chapterIndex: assignment.chapterIndex,
                chapterTitle: assignment.chapterTitle
            }];
        }
        return [];
    }

    function bookChapterFor(ref, book) {
        const chapters = Array.isArray(book?.chapters) ? book.chapters : [];
        if (!ref?.chapterId) {
            return Number.isInteger(ref?.chapterIndex) ? chapters[ref.chapterIndex] || null : null;
        }
        return chapters.find((chapter) => chapter && chapter.id === ref.chapterId) || null;
    }

    function resolvedChapterIndex(ref, book) {
        if (Number.isInteger(ref?.chapterIndex) && ref.chapterIndex >= 0) {
            return ref.chapterIndex;
        }
        const chapters = Array.isArray(book?.chapters) ? book.chapters : [];
        const index = chapters.findIndex((chapter) => chapter && chapter.id === ref?.chapterId);
        return index >= 0 ? index : null;
    }

    function humanChapterLabel(ref, book) {
        const matched = bookChapterFor(ref, book);
        const title = usableTitle(matched?.title) || usableTitle(ref?.chapterTitle);
        if (title) {
            return title;
        }
        const index = resolvedChapterIndex(ref, book);
        if (index != null) {
            return `Chapter ${index + 1}`;
        }
        return '';
    }

    function consecutiveRange(chapters, book) {
        if (!Array.isArray(chapters) || chapters.length < 2) {
            return null;
        }
        const indexes = chapters.map((ref) => resolvedChapterIndex(ref, book));
        if (indexes.some((index) => index == null)) {
            return null;
        }
        const ordered = [...indexes].sort((left, right) => left - right);
        for (let i = 1; i < ordered.length; i += 1) {
            if (ordered[i] !== ordered[i - 1] + 1) {
                return null;
            }
        }
        return { first: ordered[0] + 1, last: ordered[ordered.length - 1] + 1 };
    }

    function assignmentScopeLabel(assignment, book) {
        const chapters = assignmentChapters(assignment);
        if (chapters.length === 0) {
            return '';
        }
        if (chapters.length === 1) {
            return humanChapterLabel(chapters[0], book);
        }
        const range = consecutiveRange(chapters, book);
        if (range && chapters.length <= 3) {
            return `Chapters ${range.first}–${range.last}`;
        }
        if (chapters.length <= 3) {
            const titles = chapters.map((ref) => humanChapterLabel(ref, book)).filter(Boolean);
            if (titles.length) {
                return titles.join(', ');
            }
        }
        return `${chapters.length} chapters`;
    }

    function assignmentTarget(assignment, book) {
        if (!book) {
            return 'Book unavailable';
        }
        const scope = assignmentScopeLabel(assignment, book);
        return scope ? `${book.title} · ${scope}` : book.title;
    }

    return {
        assignmentScopeLabel,
        assignmentTarget,
        looksLikeUuid
    };
});
