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

    function isReadingCompleteForAssignment(assignment, activity) {
        if (!activity) {
            return false;
        }
        if (activity.completed || toFiniteNumber(activity.maxProgressRatio, 0) >= 0.999) {
            return true;
        }

        // Unread / never-opened books must not count as having reached chapter 0.
        if (!hasBookActivity(activity)) {
            return false;
        }

        const targetIndex = Number.isInteger(assignment?.chapterIndex)
            ? assignment.chapterIndex
            : null;
        // Whole-book assignment: only full-book completion counts.
        if (targetIndex == null) {
            return false;
        }

        const reachedChapterIndex = maxReachedChapterIndex(activity);
        if (reachedChapterIndex == null) {
            return false;
        }
        // Reaching or passing the assigned chapter counts as reading complete for chapter work.
        // Quiz COMPLETE is a strong secondary signal handled by the caller for overall status.
        return reachedChapterIndex >= targetIndex;
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

        const targetLabel = typeof assignment.chapterTitle === 'string' && assignment.chapterTitle.trim()
            ? assignment.chapterTitle.trim()
            : (Number.isInteger(assignment.chapterIndex)
                ? `Chapter ${assignment.chapterIndex + 1}`
                : 'Whole book');

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

    return {
        buildBookProgressSnapshot,
        buildAssignmentProgressSnapshot
    };
});
