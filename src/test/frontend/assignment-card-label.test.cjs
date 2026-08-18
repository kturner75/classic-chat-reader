const test = require('node:test');
const assert = require('node:assert/strict');

const {
    assignmentScopeLabel,
    assignmentTarget,
    looksLikeUuid
} = require('../../main/resources/static/js/assignment-card-label.js');

const gatsbyChapterOne = '29b20b57-358d-4cb6-bc91-bb7732efb15c';
const gatsbyChapterTwo = '3cc1e8bf-8b8b-4dfc-a73b-82d7c523dde8';

const gatsby = {
    id: 'gatsby',
    title: 'The Great Gatsby',
    chapters: [
        { id: gatsbyChapterOne, title: 'Chapter I' },
        { id: gatsbyChapterTwo, title: 'Chapter II' },
        { id: 'chapter-3', title: 'Chapter III' }
    ]
};

const northanger = {
    id: 'northanger',
    title: 'Northanger Abbey',
    chapters: [
        { id: 'na-1', title: 'Chapter 1' }
    ]
};

test('looksLikeUuid recognizes assignment chapter ids', () => {
    assert.equal(looksLikeUuid(gatsbyChapterOne), true);
    assert.equal(looksLikeUuid('Chapter I'), false);
});

test('range assignment card uses Chapters 1–2 instead of raw UUIDs', () => {
    const assignment = {
        title: 'The Great Gatsby - Chapters 1-2',
        bookId: 'gatsby',
        chapters: [
            { chapterId: gatsbyChapterOne, chapterIndex: 0, chapterTitle: null },
            { chapterId: gatsbyChapterTwo, chapterIndex: 1, chapterTitle: null }
        ]
    };

    assert.equal(assignmentScopeLabel(assignment, gatsby), 'Chapters 1–2');
    assert.equal(assignmentTarget(assignment, gatsby), 'The Great Gatsby · Chapters 1–2');
    assert.equal(assignmentTarget(assignment, gatsby).includes(gatsbyChapterOne), false);
    assert.equal(assignmentTarget(assignment, gatsby).includes(gatsbyChapterTwo), false);
});

test('range assignment still avoids UUIDs when the book catalog is missing titles', () => {
    const assignment = {
        bookId: 'gatsby',
        chapters: [
            { chapterId: gatsbyChapterOne, chapterIndex: 0 },
            { chapterId: gatsbyChapterTwo, chapterIndex: 1 }
        ]
    };
    const untitledBook = {
        id: 'gatsby',
        title: 'The Great Gatsby',
        chapters: [
            { id: gatsbyChapterOne },
            { id: gatsbyChapterTwo }
        ]
    };

    assert.equal(assignmentTarget(assignment, untitledBook), 'The Great Gatsby · Chapters 1–2');
});

test('whole-book assignment card shows only the book title', () => {
    const assignment = {
        title: 'Northanger Abbey',
        bookId: 'northanger',
        characterChatRequired: true,
        chapters: []
    };

    assert.equal(assignmentScopeLabel(assignment, northanger), '');
    assert.equal(assignmentTarget(assignment, northanger), 'Northanger Abbey');
});

test('single-chapter assignment keeps the chapter title', () => {
    const assignment = {
        bookId: 'gatsby',
        chapters: [{ chapterId: gatsbyChapterOne, chapterIndex: 0, chapterTitle: null }]
    };

    assert.equal(assignmentTarget(assignment, gatsby), 'The Great Gatsby · Chapter I');
});

test('non-consecutive chapters join human labels, never ids', () => {
    const assignment = {
        bookId: 'gatsby',
        chapters: [
            { chapterId: gatsbyChapterOne, chapterIndex: 0, chapterTitle: null },
            { chapterId: 'chapter-3', chapterIndex: 2, chapterTitle: null }
        ]
    };

    assert.equal(assignmentTarget(assignment, gatsby), 'The Great Gatsby · Chapter I, Chapter III');
});

test('missing book stays unavailable without leaking chapter ids', () => {
    const assignment = {
        bookId: 'missing',
        chapters: [
            { chapterId: gatsbyChapterOne, chapterIndex: 0 },
            { chapterId: gatsbyChapterTwo, chapterIndex: 1 }
        ]
    };

    assert.equal(assignmentTarget(assignment, null), 'Book unavailable');
});
