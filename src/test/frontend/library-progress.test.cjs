const test = require('node:test');
const assert = require('node:assert/strict');

const {
    buildBookProgressSnapshot,
    buildAssignmentProgressSnapshot
} = require('../../main/resources/static/js/library-progress.js');

test('returns not-started snapshot at 0% progress', () => {
    const snapshot = buildBookProgressSnapshot({
        chapterCount: 12,
        maxProgressRatio: 0
    });

    assert.deepEqual(snapshot, {
        chapterLabel: 'Chapter 0/12',
        percentLabel: '0%',
        statusLabel: 'Not Started',
        statusClass: 'not-started'
    });
});

test('returns in-progress snapshot for mid-progress book', () => {
    const snapshot = buildBookProgressSnapshot({
        chapterCount: 8,
        lastChapterIndex: 2,
        maxProgressRatio: 0.36
    });

    assert.deepEqual(snapshot, {
        chapterLabel: 'Chapter 3/8',
        percentLabel: '36%',
        statusLabel: 'In Progress',
        statusClass: 'in-progress'
    });
});

test('returns completed snapshot when book is completed', () => {
    const snapshot = buildBookProgressSnapshot({
        chapterCount: 5,
        lastChapterIndex: 3,
        maxProgressRatio: 0.81,
        completed: true
    });

    assert.deepEqual(snapshot, {
        chapterLabel: 'Chapter 5/5',
        percentLabel: '100%',
        statusLabel: 'Completed',
        statusClass: 'completed'
    });
});

test('clamps invalid values to safe defaults', () => {
    const snapshot = buildBookProgressSnapshot({
        chapterCount: -3,
        lastChapterIndex: -10,
        maxProgressRatio: -1
    });

    assert.deepEqual(snapshot, {
        chapterLabel: 'Chapter 0/1',
        percentLabel: '0%',
        statusLabel: 'Not Started',
        statusClass: 'not-started'
    });
});

test('assignment progress is not started with no activity', () => {
    const snapshot = buildAssignmentProgressSnapshot({
        assignment: {
            chapterIndex: 0,
            chapterTitle: 'Chapter I.',
            quizRequired: true,
            quizStatus: 'PENDING',
            characterChatRequired: false
        },
        activity: null
    });
    assert.equal(snapshot.statusClass, 'not-started');
    assert.equal(snapshot.statusLabel, 'Not started');
    assert.equal(snapshot.percentLabel, null);
    assert.equal(snapshot.summaryLabel, '0/2 complete');
});

test('assignment complete when chapter reached and quiz done (not whole-book %)', () => {
    // Partner case: finished Ch.1 quiz on a long book; whole-book % was ~27% and looked incomplete.
    const snapshot = buildAssignmentProgressSnapshot({
        assignment: {
            chapterIndex: 0,
            chapterTitle: 'Chapter I.',
            quizRequired: true,
            quizStatus: 'COMPLETE',
            characterChatRequired: false
        },
        activity: {
            chapterCount: 59,
            lastChapterIndex: 15,
            maxProgressRatio: 0.27
        }
    });
    assert.equal(snapshot.statusClass, 'completed');
    assert.equal(snapshot.statusLabel, 'Complete');
    assert.equal(snapshot.percentLabel, null);
    assert.equal(snapshot.summaryLabel, '2/2 complete');
    assert.equal(snapshot.readingComplete, true);
});

test('assignment in progress when reading started but quiz pending', () => {
    const snapshot = buildAssignmentProgressSnapshot({
        assignment: {
            chapterIndex: 0,
            quizRequired: true,
            quizStatus: 'PENDING',
            characterChatRequired: false
        },
        activity: {
            lastChapterIndex: 0,
            maxProgressRatio: 0.02,
            lastReadAt: '2026-07-17T12:00:00.000Z'
        }
    });
    assert.equal(snapshot.statusClass, 'in-progress');
    assert.equal(snapshot.readingComplete, true);
    assert.equal(snapshot.summaryLabel, '1/2 complete');
});

test('character chat requirement blocks complete until local chat started', () => {
    const pending = buildAssignmentProgressSnapshot({
        assignment: {
            chapterIndex: 0,
            quizRequired: false,
            quizStatus: 'NOT_REQUIRED',
            characterChatRequired: true
        },
        activity: {
            lastChapterIndex: 0,
            maxProgressRatio: 0.05
        },
        characterChatStarted: false
    });
    assert.equal(pending.statusClass, 'in-progress');
    assert.equal(pending.summaryLabel, '1/2 complete');

    const done = buildAssignmentProgressSnapshot({
        assignment: {
            chapterIndex: 0,
            quizRequired: false,
            quizStatus: 'NOT_REQUIRED',
            characterChatRequired: true
        },
        activity: {
            lastChapterIndex: 0,
            maxProgressRatio: 0.05
        },
        characterChatStarted: true
    });
    assert.equal(done.statusClass, 'completed');
    assert.equal(done.summaryLabel, '2/2 complete');
});

test('whole-book assignment only completes when book is finished', () => {
    const mid = buildAssignmentProgressSnapshot({
        assignment: {
            chapterIndex: null,
            quizRequired: false,
            quizStatus: 'NOT_REQUIRED',
            characterChatRequired: false
        },
        activity: {
            lastChapterIndex: 10,
            maxProgressRatio: 0.4
        }
    });
    assert.equal(mid.statusClass, 'in-progress');
    assert.equal(mid.readingComplete, false);

    const finished = buildAssignmentProgressSnapshot({
        assignment: {
            chapterIndex: null,
            quizRequired: false,
            quizStatus: 'NOT_REQUIRED'
        },
        activity: {
            completed: true,
            maxProgressRatio: 1
        }
    });
    assert.equal(finished.statusClass, 'completed');
    assert.equal(finished.readingComplete, true);
});
