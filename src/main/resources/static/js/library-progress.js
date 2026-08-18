(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
        return;
    }
    root.LibraryProgress = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    function clampNumber(value, min, max) {
        return Math.min(max, Math.max(min, value));
    }

    function toFiniteNumber(value, fallback) {
        const parsed = Number(value);
        return Number.isFinite(parsed) ? parsed : fallback;
    }

    function clampInteger(value, min, max) {
        return Math.floor(clampNumber(toFiniteNumber(value, min), min, max));
    }

    function buildBookProgressSnapshot(activity) {
        const chapterCount = Math.max(1, clampInteger(activity?.chapterCount, 1, Number.MAX_SAFE_INTEGER));
        const rawProgress = clampNumber(toFiniteNumber(activity?.maxProgressRatio, 0), 0, 1);
        const completed = Boolean(activity?.completed) || rawProgress >= 0.999;

        let chapterNumber = 0;
        let percentComplete = clampInteger(Math.round(rawProgress * 100), 0, 100);
        let statusLabel = 'Not Started';
        let statusClass = 'not-started';

        if (completed) {
            chapterNumber = chapterCount;
            percentComplete = 100;
            statusLabel = 'Completed';
            statusClass = 'completed';
        } else if (rawProgress > 0) {
            chapterNumber = clampInteger(toFiniteNumber(activity?.lastChapterIndex, 0) + 1, 1, chapterCount);
            percentComplete = Math.max(1, percentComplete);
            statusLabel = 'In Progress';
            statusClass = 'in-progress';
        }

        return {
            chapterLabel: `Chapter ${chapterNumber}/${chapterCount}`,
            percentLabel: `${percentComplete}%`,
            statusLabel,
            statusClass
        };
    }

    function hasBookActivity(activity) {
        if (!activity || typeof activity !== 'object') {
            return false;
        }
        if (activity.completed || activity.completedAt) {
            return true;
        }
        if (activity.lastReadAt || activity.lastOpenedAt) {
            return true;
        }
        const progress = Math.max(
            toFiniteNumber(activity.maxProgressRatio, 0),
            toFiniteNumber(activity.progressRatio, 0)
        );
        if (progress > 0) {
            return true;
        }
        // Do NOT treat default lastChapterIndex: 0 from normalizeBookActivity as real activity.
        // Unread local books often get chapter 0 with 0% progress and no timestamps.
        return false;
    }

    function assignmentChapterIndexes(assignment) {
        if (Array.isArray(assignment?.chapters) && assignment.chapters.length > 0) {
            return [...new Set(assignment.chapters
                .map((chapter) => Number.isInteger(chapter?.chapterIndex) ? chapter.chapterIndex : null)
                .filter((index) => index != null))]
                .sort((left, right) => left - right);
        }
        if (Number.isInteger(assignment?.chapterIndex)) {
            return [assignment.chapterIndex];
        }
        return [];
    }

    function assignmentChapterLabel(assignment) {
        const chapters = Array.isArray(assignment?.chapters) ? assignment.chapters : [];
        if (chapters.length === 0 && !Number.isInteger(assignment?.chapterIndex) && !assignment?.chapterTitle) {
            return 'Whole book';
        }
        if (chapters.length === 1) {
            return chapters[0].chapterTitle
                || (Number.isInteger(chapters[0].chapterIndex) ? `Chapter ${chapters[0].chapterIndex + 1}` : '1 chapter');
        }
        if (chapters.length > 1) {
            if (chapters.length <= 3 && chapters.every((item) => item.chapterTitle)) {
                return chapters.map((item) => item.chapterTitle).join(', ');
            }
            return `${chapters.length} chapters`;
        }
        if (typeof assignment?.chapterTitle === 'string' && assignment.chapterTitle.trim()) {
            return assignment.chapterTitle.trim();
        }
        if (Number.isInteger(assignment?.chapterIndex)) {
            return `Chapter ${assignment.chapterIndex + 1}`;
        }
        return 'Whole book';
    }

    function isWholeBookCompleteSignal(activity) {
        return Boolean(activity?.completed) || toFiniteNumber(activity?.maxProgressRatio, 0) >= 0.999;
    }

    function isReadingCompleteForAssignment(assignment, activity) {
        if (!activity) {
            return false;
        }

        const indexes = assignmentChapterIndexes(assignment);
        // Whole-book assignments: only a finished book counts.
        if (indexes.length === 0) {
            return isWholeBookCompleteSignal(activity);
        }

        if (!hasBookActivity(activity)) {
            return false;
        }

        const completed = uniqueCompletedChapterIndexes(activity.completedChapterIndexes);
        if (indexes.every((index) => completed.includes(index))) {
            return true;
        }
        if (completed.length > 0) {
            return false;
        }

        const targetIndex = Math.max(...indexes);
        const last = Number.isFinite(Number(activity.lastChapterIndex))
            ? Number(activity.lastChapterIndex)
            : -1;
        // A leaked whole-book completed / 100% flag must not finish a chapter-range
        // assignment while the student is still on an earlier chapter (first open of
        // an unread Gatsby 1–2 range with a stub or prior book-complete flag).
        if (isWholeBookCompleteSignal(activity) && last < targetIndex) {
            return finishedAssignedChapterByPosition(activity, targetIndex);
        }

        const reachedChapterIndex = maxReachedChapterIndex(activity);
        if (reachedChapterIndex == null) {
            return false;
        }
        return finishedLastAssignedChapter(activity, targetIndex);
    }

    function uniqueCompletedChapterIndexes(values) {
        return [...new Set((Array.isArray(values) ? values : [])
            .map((value) => Number(value))
            .filter((value) => Number.isInteger(value) && value >= 0))]
            .sort((left, right) => left - right);
    }

    function unionBookActivityStores(serverStore, localStore) {
        const result = { ...(serverStore && typeof serverStore === 'object' ? serverStore : {}) };
        Object.entries(localStore && typeof localStore === 'object' ? localStore : {}).forEach(([bookId, local]) => {
            if (!local || typeof local !== 'object') {
                return;
            }
            const server = result[bookId];
            if (!server || typeof server !== 'object') {
                result[bookId] = local;
                return;
            }
            result[bookId] = {
                ...server,
                ...local,
                completedChapterIndexes: uniqueCompletedChapterIndexes([
                    ...(server.completedChapterIndexes || []),
                    ...(local.completedChapterIndexes || [])
                ]),
                maxProgressRatio: Math.max(
                    toFiniteNumber(server.maxProgressRatio, 0),
                    toFiniteNumber(local.maxProgressRatio, 0)
                ),
                completed: Boolean(server.completed || local.completed),
                completedAt: server.completedAt || local.completedAt || null
            };
        });
        return result;
    }

    function finishedAssignedChapterByPosition(activity, targetIndex) {
        const last = Number.isFinite(Number(activity.lastChapterIndex))
            ? Number(activity.lastChapterIndex)
            : -1;
        if (last > targetIndex) {
            return true;
        }
        const lastPage = Number.isInteger(activity.lastPage) ? activity.lastPage : null;
        const totalPages = Number.isInteger(activity.totalPages) ? activity.totalPages : null;
        return last === targetIndex
            && lastPage != null
            && totalPages != null
            && totalPages > 0
            && lastPage >= totalPages - 1;
    }

    function finishedLastAssignedChapter(activity, targetIndex) {
        const chapterCount = Math.max(1, clampInteger(activity.chapterCount, 1, Number.MAX_SAFE_INTEGER));
        const needed = (targetIndex + 1) / chapterCount;
        const maxProgress = Math.max(
            toFiniteNumber(activity.maxProgressRatio, 0),
            toFiniteNumber(activity.progressRatio, 0)
        );
        if (maxProgress + 1e-9 >= needed) {
            return true;
        }
        return finishedAssignedChapterByPosition(activity, targetIndex);
    }

    function isAssignmentQuizPerfect(assignment, result) {
        const resultScore = Number.isFinite(result?.scorePercent) ? result.scorePercent : null;
        if (resultScore != null) {
            return resultScore >= 100;
        }
        if (Number.isFinite(result?.correctAnswers) && Number.isFinite(result?.totalQuestions)
                && result.totalQuestions > 0) {
            return result.correctAnswers >= result.totalQuestions;
        }
        return Number.isInteger(assignment?.quizBestScorePercent) && assignment.quizBestScorePercent >= 100;
    }

    function hasAssignmentQuizAttemptsRemaining(assignment) {
        if (!Number.isInteger(assignment?.quizAttemptsAllowed) || !Number.isInteger(assignment?.quizAttemptsUsed)) {
            return true;
        }
        return assignment.quizAttemptsUsed < assignment.quizAttemptsAllowed;
    }

    function canTakeAssignmentQuiz(assignment, activity) {
        if (!isQuizRequired(assignment)) {
            return false;
        }
        const status = typeof assignment?.quizStatus === 'string'
            ? assignment.quizStatus.toUpperCase()
            : '';
        if (status === 'NOT_REQUIRED') {
            return false;
        }
        if (isAssignmentQuizPerfect(assignment)) {
            return false;
        }
        if (!isReadingCompleteForAssignment(assignment, activity)) {
            return false;
        }
        if (!hasAssignmentQuizAttemptsRemaining(assignment)) {
            return false;
        }
        return true;
    }

    function assignmentQuizActionLabel(assignment) {
        const used = Number.isInteger(assignment?.quizAttemptsUsed) ? assignment.quizAttemptsUsed : 0;
        return used > 0 ? 'Retry Quiz' : 'Take Quiz';
    }

    function isWholeBookAssignment(assignment) {
        return assignmentChapterIndexes(assignment).length === 0;
    }

    function canChatForAssignment(assignment) {
        return assignment?.characterChatRequired === true;
    }

    function assignmentChatActionLabel(characterChatStarted) {
        return characterChatStarted ? 'Continue Chat' : 'Chat with Character';
    }

    function isAssignmentFullyComplete(assignment, activity, characterChatStarted) {
        const snapshot = buildAssignmentProgressSnapshot({
            assignment,
            activity,
            characterChatStarted
        });
        return snapshot.statusClass === 'completed';
    }

    /**
     * Furthest chapter the student has reached (not merely the current resume chapter).
     * Uses maxProgressRatio (monotonic) and falls back to lastChapterIndex.
     * Inverse of computeProgressRatio roughly: ratio = (chapter + pageFrac) / chapterCount.
     */
    function maxReachedChapterIndex(activity) {
        if (!activity || !hasBookActivity(activity)) {
            return null;
        }
        const chapterCount = Math.max(1, clampInteger(activity.chapterCount, 1, Number.MAX_SAFE_INTEGER));
        const maxProgress = clampNumber(toFiniteNumber(activity.maxProgressRatio, 0), 0, 1);
        let fromProgress = -1;
        if (maxProgress > 0) {
            // ceil(progress * n) - 1 maps:
            //  - first page of chapter k  -> k
            //  - last page of chapter k   -> k
            // without treating unfinished prior chapters as the next chapter.
            fromProgress = Math.min(
                chapterCount - 1,
                Math.max(0, Math.ceil(maxProgress * chapterCount - 1e-9) - 1)
            );
        }
        const last = Number.isFinite(Number(activity.lastChapterIndex))
            ? Number(activity.lastChapterIndex)
            : -1;
        const reached = Math.max(fromProgress, last);
        return reached >= 0 ? reached : null;
    }

    function isQuizSatisfied(assignment) {
        const status = typeof assignment?.quizStatus === 'string'
            ? assignment.quizStatus.toUpperCase()
            : '';
        if (status === 'NOT_REQUIRED' || status === 'COMPLETE') {
            return true;
        }
        // Explicit false on quizRequired with missing status → treat as not required.
        if (assignment?.quizRequired === false && status !== 'PENDING' && status !== 'UNKNOWN') {
            return true;
        }
        if (assignment?.quizRequired !== true && status !== 'PENDING' && status !== 'UNKNOWN') {
            // Unknown + not explicitly required → don't block assignment completion.
            return status === '' || status === 'NOT_REQUIRED';
        }
        return false;
    }

    function isQuizRequired(assignment) {
        if (assignment?.quizRequired === true) {
            return true;
        }
        const status = typeof assignment?.quizStatus === 'string'
            ? assignment.quizStatus.toUpperCase()
            : '';
        return status === 'PENDING' || status === 'COMPLETE' || status === 'UNKNOWN';
    }

    /**
     * Assignment-scoped progress for classroom Library cards.
     * Does NOT use whole-book % — that confused teachers/students after finishing a chapter quiz.
     *
     * @param {object} options
     * @param {object} options.assignment
     * @param {object|null} options.activity book activity snapshot
     * @param {boolean} [options.characterChatStarted] local signal that student chatted for this book
     */
    function buildAssignmentProgressSnapshot(options) {
        const opts = options && typeof options === 'object' ? options : {};
        const assignment = opts.assignment && typeof opts.assignment === 'object' ? opts.assignment : {};
        const activity = opts.activity && typeof opts.activity === 'object' ? opts.activity : null;
        const characterChatStarted = Boolean(opts.characterChatStarted);

        const readingStarted = hasBookActivity(activity);
        const quizRequired = isQuizRequired(assignment);
        const characterChatRequired = assignment.characterChatRequired === true;

        // Reading complete: reached assigned chapter (or finished book). Quiz complete also implies
        // the student finished the reading work for a chapter-targeted assignment.
        let readingComplete = isReadingCompleteForAssignment(assignment, activity);
        if (!readingComplete && assignment.quizStatus === 'COMPLETE') {
            readingComplete = true;
        }

        const quizComplete = assignment.quizStatus === 'COMPLETE'
            || assignment.quizStatus === 'NOT_REQUIRED'
            || (!quizRequired && assignment.quizStatus !== 'PENDING' && assignment.quizStatus !== 'UNKNOWN');
        const quizSatisfied = isQuizSatisfied(assignment);
        const characterChatSatisfied = !characterChatRequired || characterChatStarted;

        const requirements = [];
        requirements.push({
            key: 'reading',
            label: readingComplete ? 'Reading done' : (readingStarted ? 'Reading started' : 'Reading'),
            done: readingComplete,
            started: readingStarted
        });
        if (quizRequired || assignment.quizStatus === 'COMPLETE' || assignment.quizStatus === 'PENDING') {
            requirements.push({
                key: 'quiz',
                label: assignment.quizStatus === 'COMPLETE' ? 'Quiz complete' : 'Quiz',
                done: assignment.quizStatus === 'COMPLETE' || assignment.quizStatus === 'NOT_REQUIRED',
                started: assignment.quizStatus === 'COMPLETE' || assignment.quizStatus === 'PENDING'
            });
        }
        if (characterChatRequired) {
            requirements.push({
                key: 'characterChat',
                label: characterChatStarted ? 'Character chat started' : 'Character chat',
                done: characterChatStarted,
                started: characterChatStarted
            });
        }

        const allDone = readingComplete && quizSatisfied && characterChatSatisfied;
        // PENDING quiz alone is not "started" — it only means the teacher required a quiz.
        const anyStarted = readingStarted
            || assignment.quizStatus === 'COMPLETE'
            || characterChatStarted;

        let statusLabel = 'Not started';
        let statusClass = 'not-started';
        if (allDone) {
            statusLabel = 'Complete';
            statusClass = 'completed';
        } else if (anyStarted) {
            statusLabel = 'In progress';
            statusClass = 'in-progress';
        }

        const doneCount = requirements.filter((item) => item.done).length;
        const totalCount = requirements.length;
        const summaryLabel = totalCount > 0
            ? `${doneCount}/${totalCount} complete`
            : statusLabel;

        const targetLabel = assignmentChapterLabel(assignment);

        return {
            statusLabel,
            statusClass,
            summaryLabel,
            targetLabel,
            readingComplete,
            readingStarted,
            quizRequired,
            quizComplete: quizSatisfied && (assignment.quizStatus === 'COMPLETE' || !quizRequired),
            characterChatRequired,
            characterChatStarted,
            requirements,
            // Explicitly no whole-book percent on assignment cards.
            percentLabel: null,
            chapterLabel: null
        };
    }

    function normalizeAssignmentCardTitle(value) {
        return typeof value === 'string' ? value.trim().replace(/\s+/g, ' ') : '';
    }

    function assignmentCardBookSubtitle(assignmentTitle, bookTitle) {
        const heading = normalizeAssignmentCardTitle(assignmentTitle);
        const book = normalizeAssignmentCardTitle(bookTitle);
        if (!book || !heading) {
            return '';
        }
        if (heading.localeCompare(book, undefined, { sensitivity: 'accent' }) === 0) {
            return '';
        }
        return book;
    }

    return {
        buildBookProgressSnapshot,
        buildAssignmentProgressSnapshot,
        isReadingCompleteForAssignment,
        canTakeAssignmentQuiz,
        assignmentQuizActionLabel,
        assignmentChapterLabel,
        assignmentChapterIndexes,
        isWholeBookAssignment,
        canChatForAssignment,
        assignmentChatActionLabel,
        isAssignmentFullyComplete,
        isAssignmentQuizPerfect,
        hasAssignmentQuizAttemptsRemaining,
        unionBookActivityStores,
        assignmentCardBookSubtitle
    };
});
