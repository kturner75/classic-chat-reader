const test = require('node:test');
const assert = require('node:assert/strict');

const {
    buildBookProgressSnapshot,
    buildAssignmentProgressSnapshot,
    isReadingCompleteForAssignment,
    canTakeAssignmentQuiz,
    assignmentQuizActionLabel,
    assignmentChapterLabel,
    isWholeBookAssignment,
    canChatForAssignment,
    assignmentChatActionLabel,
    isAssignmentFullyComplete
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

test('default lastChapterIndex 0 without real activity is not reading-complete', () => {
    // normalizeBookActivity() supplies lastChapterIndex: 0 for unread local books.
    const snapshot = buildAssignmentProgressSnapshot({
        assignment: {
            chapterIndex: 0,
            chapterTitle: 'Chapter I.',
            quizRequired: false,
            quizStatus: 'NOT_REQUIRED',
            characterChatRequired: false
        },
        activity: {
            chapterCount: 59,
            lastChapterIndex: 0,
            lastPage: 0,
            maxProgressRatio: 0,
            progressRatio: 0
        }
    });
    assert.equal(snapshot.statusClass, 'not-started');
    assert.equal(snapshot.readingComplete, false);
    assert.equal(snapshot.readingStarted, false);
    assert.equal(snapshot.summaryLabel, '0/1 complete');
});

test('chapter 0 assignment becomes reading-complete only after finishing the chapter', () => {
    const started = buildAssignmentProgressSnapshot({
        assignment: {
            chapterIndex: 0,
            quizRequired: false,
            quizStatus: 'NOT_REQUIRED'
        },
        activity: {
            chapterCount: 59,
            lastChapterIndex: 0,
            lastPage: 0,
            totalPages: 8,
            maxProgressRatio: 0.01,
            lastReadAt: '2026-07-18T12:00:00.000Z'
        }
    });
    assert.equal(started.readingComplete, false);

    const snapshot = buildAssignmentProgressSnapshot({
        assignment: {
            chapterIndex: 0,
            quizRequired: false,
            quizStatus: 'NOT_REQUIRED'
        },
        activity: {
            lastChapterIndex: 0,
            lastPage: 7,
            totalPages: 8,
            chapterCount: 59,
            maxProgressRatio: 1 / 59,
            lastReadAt: '2026-07-18T12:00:00.000Z'
        }
    });
    assert.equal(snapshot.readingComplete, true);
    assert.equal(snapshot.statusClass, 'completed');
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
            lastPage: 4,
            totalPages: 5,
            chapterCount: 59,
            maxProgressRatio: 1 / 59,
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
            lastPage: 4,
            totalPages: 5,
            chapterCount: 59,
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
            lastPage: 4,
            totalPages: 5,
            chapterCount: 59,
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

test('assignment reading stays complete after student resumes an earlier chapter', () => {
    // Student reached chapter 5 (maxProgress high), then reopened chapter 1 (lastChapterIndex=0).
    const snapshot = buildAssignmentProgressSnapshot({
        assignment: {
            chapterIndex: 5,
            quizRequired: false,
            quizStatus: 'NOT_REQUIRED'
        },
        activity: {
            chapterCount: 20,
            lastChapterIndex: 0,
            maxProgressRatio: 6 / 20,
            lastReadAt: '2026-07-18T15:00:00.000Z'
        }
    });
    assert.equal(snapshot.readingComplete, true);
    assert.equal(snapshot.statusClass, 'completed');
});

test('multi-chapter assignment completes when furthest chapter covers the set', () => {
    const assignment = {
        chapters: [
            { chapterId: 'ch-1', chapterIndex: 0, chapterTitle: 'One' },
            { chapterId: 'ch-3', chapterIndex: 2, chapterTitle: 'Three' }
        ],
        quizRequired: false,
        quizStatus: 'NOT_REQUIRED'
    };
    const incomplete = isReadingCompleteForAssignment(assignment, {
        lastChapterIndex: 1,
        maxProgressRatio: 0.2,
        lastReadAt: '2026-08-12T12:00:00.000Z',
        chapterCount: 10
    });
    assert.equal(incomplete, false);

    const complete = isReadingCompleteForAssignment(assignment, {
        lastChapterIndex: 2,
        maxProgressRatio: 0.35,
        lastReadAt: '2026-08-12T12:00:00.000Z',
        chapterCount: 10
    });
    assert.equal(complete, true);
    assert.equal(assignmentChapterLabel(assignment), 'One, Three');
});

test('multi-chapter assignment requires every selected chapter when completion is tracked', () => {
    const assignment = {
        chapters: [
            { chapterId: 'ch-1', chapterIndex: 0, chapterTitle: 'One' },
            { chapterId: 'ch-3', chapterIndex: 2, chapterTitle: 'Three' }
        ],
        quizRequired: false,
        quizStatus: 'NOT_REQUIRED'
    };
    assert.equal(isReadingCompleteForAssignment(assignment, {
        lastChapterIndex: 2,
        lastPage: 4,
        totalPages: 5,
        completedChapterIndexes: [2],
        lastReadAt: '2026-08-12T12:00:00.000Z',
        chapterCount: 10
    }), false);
    assert.equal(isReadingCompleteForAssignment(assignment, {
        lastChapterIndex: 2,
        completedChapterIndexes: [0, 2],
        lastReadAt: '2026-08-12T12:00:00.000Z',
        chapterCount: 10
    }), true);
});

test('split last paragraph first fragment does not unlock the quiz until the chapter is marked complete', () => {
    const assignment = {
        chapters: [{ chapterId: 'ch-1', chapterIndex: 0, chapterTitle: 'One' }],
        quizRequired: true,
        quizStatus: 'PENDING',
        quizAttemptsUsed: 0,
        quizAttemptsAllowed: 2
    };
    const firstFragment = {
        lastChapterIndex: 0,
        lastPage: 0,
        totalPages: 2,
        chapterCount: 1,
        lastReadAt: '2026-08-14T12:00:00.000Z',
        completedChapterIndexes: []
    };
    assert.equal(isReadingCompleteForAssignment(assignment, firstFragment), false);
    assert.equal(canTakeAssignmentQuiz(assignment, firstFragment), false);
    assert.equal(isReadingCompleteForAssignment(assignment, {
        ...firstFragment,
        completedChapterIndexes: [0]
    }), true);
});

test('Take Quiz is hidden until reading is complete and shown as Retry after a failed attempt', () => {
    const assignment = {
        assignmentId: 'asg-1',
        chapters: [{ chapterId: 'ch-1', chapterIndex: 0, chapterTitle: 'One' }],
        quizRequired: true,
        quizStatus: 'PENDING',
        quizAttemptsUsed: 0,
        quizAttemptsAllowed: 2
    };
    const unread = { maxProgressRatio: 0, lastChapterIndex: 0 };
    assert.equal(canTakeAssignmentQuiz(assignment, unread), false);

    const read = {
        lastChapterIndex: 0,
        lastPage: 4,
        totalPages: 5,
        maxProgressRatio: 1,
        lastReadAt: '2026-08-12T12:00:00.000Z'
    };
    assert.equal(canTakeAssignmentQuiz(assignment, read), true);
    assert.equal(assignmentQuizActionLabel(assignment), 'Take Quiz');

    const retry = { ...assignment, quizAttemptsUsed: 1 };
    assert.equal(canTakeAssignmentQuiz(retry, read), true);
    assert.equal(assignmentQuizActionLabel(retry), 'Retry Quiz');

    const exhausted = { ...assignment, quizAttemptsUsed: 2 };
    assert.equal(canTakeAssignmentQuiz(exhausted, read), false);

    const perfect = { ...assignment, quizStatus: 'COMPLETE', quizBestScorePercent: 100 };
    assert.equal(canTakeAssignmentQuiz(perfect, read), false);

    const passedWithRetries = {
        ...assignment,
        quizStatus: 'COMPLETE',
        quizAttemptsUsed: 1,
        quizBestScorePercent: 80
    };
    assert.equal(canTakeAssignmentQuiz(passedWithRetries, read), true);
    assert.equal(assignmentQuizActionLabel(passedWithRetries), 'Retry Quiz');
});

test('Chat with Character is available whenever the assignment requires it', () => {
    const required = { characterChatRequired: true };
    const optional = { characterChatRequired: false };
    assert.equal(canChatForAssignment(required), true);
    assert.equal(canChatForAssignment(optional), false);
    assert.equal(assignmentChatActionLabel(false), 'Chat with Character');
    assert.equal(assignmentChatActionLabel(true), 'Continue Chat');
});

test('whole-book assignments are detected from an empty chapter set', () => {
    assert.equal(isWholeBookAssignment({}), true);
    assert.equal(isWholeBookAssignment({ chapters: [] }), true);
    assert.equal(isWholeBookAssignment({
        chapters: [{ chapterId: 'ch-1', chapterIndex: 0, chapterTitle: 'One' }]
    }), false);
});

test('assignment is fully complete only when reading, quiz, and required chat are done', () => {
    const assignment = {
        chapters: [{ chapterId: 'ch-1', chapterIndex: 0, chapterTitle: 'One' }],
        quizRequired: true,
        quizStatus: 'PENDING',
        characterChatRequired: true
    };
    const read = {
        lastChapterIndex: 0,
        maxProgressRatio: 0.2,
        lastReadAt: '2026-08-12T12:00:00.000Z',
        chapterCount: 5
    };
    assert.equal(isAssignmentFullyComplete(assignment, read, false), false);
    assert.equal(isAssignmentFullyComplete(assignment, read, true), false);
    assert.equal(isAssignmentFullyComplete({
        ...assignment,
        quizStatus: 'COMPLETE'
    }, read, true), true);
});
