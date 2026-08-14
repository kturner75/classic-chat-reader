const { test, expect } = require('@playwright/test');

const TEST_BOOK = {
  id: 'book-1',
  title: 'BL-047 Assignment Book',
  author: 'Casey Reader',
  chapters: [{ id: 'chapter-1', title: 'Chapter One' }],
  ttsEnabled: false,
  illustrationEnabled: false,
  characterEnabled: true
};

const MULTI_CHAPTER_BOOK = {
  id: 'book-1',
  title: 'BL-047 Assignment Book',
  author: 'Casey Reader',
  chapters: [
    { id: 'chapter-1', title: 'Chapter One' },
    { id: 'chapter-2', title: 'Chapter Two' },
    { id: 'chapter-3', title: 'Chapter Three' }
  ],
  ttsEnabled: false,
  illustrationEnabled: false,
  characterEnabled: true
};

const TEST_CHARACTER = {
  id: 'character-1',
  name: 'Ada',
  description: 'A character with an existing student conversation.',
  portraitReady: false,
  characterType: 'PRIMARY',
  chatEligible: true,
  firstChapterId: 'chapter-1',
  firstChapterTitle: 'Chapter One',
  firstChapterIndex: 0,
  firstParagraphIndex: 0
};

function json(route, status, payload) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(payload)
  });
}

async function installApiMocks(page, options = {}) {
  const state = { classroomContextRequests: 0 };
  const book = options.book || TEST_BOOK;
  const character = options.character || TEST_CHARACTER;
  let quizAttemptsUsed = options.quizAttemptsUsed || 0;
  const assignmentChapters = options.assignmentChapters || book.chapters.map((chapter, index) => ({
    chapterId: chapter.id,
    chapterIndex: index,
    chapterTitle: chapter.title
  }));
  const firstChapter = assignmentChapters[0] || book.chapters[0] || { id: 'chapter-1', title: 'Chapter One' };

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    if (method === 'GET' && path === '/api/classroom/context') {
      state.classroomContextRequests += 1;
      const pendingThenComplete = state.classroomContextRequests === 1 ? 'PENDING' : 'COMPLETE';
      return json(route, 200, {
        enrolled: true,
        classId: 'class-1',
        className: 'British Literature',
        teacherName: 'Prof. Evans',
        termId: 'term-1',
        role: 'STUDENT',
        features: {
          quizEnabled: true,
          recapEnabled: false,
          ttsEnabled: false,
          illustrationEnabled: false,
          characterEnabled: true,
          chatEnabled: true,
          speedReadingEnabled: false,
          readingBuddyEnabled: false,
          citationEnabled: false
        },
        assignments: [{
          assignmentId: 'assignment-1',
          title: options.assignmentTitle || 'Read, quiz, and chat',
          bookId: book.id,
          bookTitle: book.title,
          bookAuthor: book.author,
          chapters: assignmentChapters,
          chapterId: firstChapter.chapterId || firstChapter.id,
          chapterIndex: Number.isInteger(firstChapter.chapterIndex) ? firstChapter.chapterIndex : 0,
          chapterTitle: firstChapter.chapterTitle || firstChapter.title,
          quizRequired: options.quizRequired !== false,
          quizSource: 'CHAPTER',
          quizStatus: options.quizStatus || (options.quizRequired === false ? 'NOT_REQUIRED' : pendingThenComplete),
          quizAttemptsUsed,
          quizAttemptsAllowed: options.quizAttemptsAllowed || 2,
          quizPassMinCorrect: options.quizPassMinCorrect || 1,
          quizBestScorePercent: options.quizBestScorePercent != null
            ? options.quizBestScorePercent
            : ((options.quizStatus || pendingThenComplete) === 'COMPLETE' ? 100 : null),
          characterChatRequired: options.characterChatRequired !== false,
          bookAvailable: true
        }]
      });
    }
    if (method === 'GET' && path === '/api/quizzes/assignment/assignment-1') {
      return json(route, 200, {
        assignmentId: 'assignment-1',
        bookId: book.id,
        quizSource: 'CHAPTER',
        ready: true,
        payload: {
          questions: [{
            id: 'q1',
            question: 'Who is the narrator?',
            options: ['Jim', 'Silver', 'Trelawney', 'Livesey']
          }],
          contentVersion: 'v1'
        }
      });
    }
    if (method === 'GET' && path === '/api/library') return json(route, 200, [book]);
    if (method === 'GET' && path === '/api/import/popular') return json(route, 200, []);
    if (method === 'GET' && path === '/api/features') return json(route, 200, { speedReadingEnabled: false });
    if (method === 'GET' && path === '/api/auth/status') {
      return json(route, 200, {
        publicMode: false,
        authRequired: false,
        authenticated: false,
        canAccessSensitive: true
      });
    }
    if (method === 'GET' && path === '/api/account/status') {
      return json(route, 200, {
        accountAuthEnabled: false,
        authenticated: false,
        email: null,
        googleAuthEnabled: false,
        rolloutMode: 'disabled',
        accountRequired: false
      });
    }
    if (method === 'GET' && path === '/api/classroom/capabilities') {
      return json(route, 200, { canTeach: false, canCreateClass: false });
    }
    if (method === 'GET' && path === '/api/tts/status') {
      return json(route, 200, { openaiConfigured: false, cachedAvailable: false, cacheOnly: false });
    }
    if (method === 'GET' && path === '/api/illustrations/status') {
      return json(route, 200, { comfyuiAvailable: false, ollamaAvailable: false, cacheOnly: false });
    }
    if (method === 'GET' && path === '/api/characters/status') {
      return json(route, 200, {
        enabled: true,
        cacheOnly: false,
        chatEnabled: true,
        chatProviderAvailable: true
      });
    }
    if (method === 'GET' && path === '/api/recaps/status') {
      return json(route, 200, { enabled: false, available: false, chatEnabled: false });
    }
    if (method === 'GET' && path === `/api/recaps/book/${book.id}/status`) {
      return json(route, 200, { enabled: false, available: false, chatEnabled: false });
    }
    if (method === 'GET' && path === '/api/quizzes/status') {
      return json(route, 200, { enabled: true, available: true, generationAvailable: false });
    }
    if (method === 'GET' && path === `/api/quizzes/book/${book.id}/status`) {
      return json(route, 200, { enabled: true, available: true, generationAvailable: false });
    }
    if (method === 'GET' && path.startsWith(`/api/library/${book.id}/chapters/`)) {
      const chapterId = path.split('/').pop();
      return json(route, 200, {
        chapterId,
        paragraphs: [{ content: `The complete text of ${chapterId}.` }]
      });
    }
    if (method === 'GET' && path === `/api/library/${book.id}/annotations`) return json(route, 200, []);
    if (method === 'GET' && path === `/api/library/${book.id}/bookmarks`) return json(route, 200, []);
    if (method === 'GET' && path === `/api/characters/book/${book.id}/up-to`) {
      return json(route, 200, [character]);
    }
    if (method === 'GET' && path === `/api/characters/book/${book.id}`) {
      return json(route, 200, [character]);
    }
    if (method === 'GET' && path === `/api/characters/book/${book.id}/new-since`) {
      return json(route, 200, []);
    }
    if (method === 'GET' && path === `/api/account/chats/characters/${character.id}`) {
      return json(route, 200, {
        session: { sessionId: 'session-1' },
        messages: [
          { messageId: 'message-1', role: 'USER', content: 'Tell me about yourself.' },
          { messageId: 'message-2', role: 'CHARACTER', content: 'I am Ada.' }
        ]
      });
    }
    if (method === 'POST' && path === '/api/quizzes/assignment/assignment-1/grade') {
      quizAttemptsUsed += 1;
      const totalQuestions = options.gradeTotalQuestions || 1;
      const correctAnswers = options.gradeCorrectAnswers != null ? options.gradeCorrectAnswers : 0;
      const scorePercent = Math.round((correctAnswers * 100) / totalQuestions);
      return json(route, 200, {
        bookId: book.id,
        chapterId: firstChapter.chapterId || firstChapter.id,
        totalQuestions,
        correctAnswers,
        scorePercent,
        difficultyLevel: 0,
        unlockedTrophies: [],
        progress: { totalAttempts: 1, perfectAttempts: 0, currentPerfectStreak: 0 },
        results: [{
          questionIndex: 0,
          question: 'Who is the narrator?',
          selectedOptionIndex: 0,
          correctOptionIndex: 1,
          correct: false,
          correctAnswer: 'Silver'
        }]
      });
    }
    if (method === 'POST') return json(route, 202, {});

    return json(route, 404, { error: `Unhandled API route: ${method} ${path}` });
  });

  return state;
}

test('BL-047 first Library return rerenders completed quiz and all assignment requirements', async ({ page }) => {
  const state = await installApiMocks(page);
  await page.goto('/');

  const assignment = page.locator('#classroom-assignments-list [data-assignment-id="assignment-1"]');
  await expect(assignment).toContainText('Pass 1+');
  await expect(assignment.locator('.assignment-quiz-action')).toHaveCount(0);
  await expect(assignment.locator('.assignment-chat-action')).toHaveText('Chat with Character');
  await assignment.locator('.book-item-title').click();
  await expect(page.locator('#reader-view')).toBeVisible();
  await expect(page.locator('#assignment-mode-banner')).toHaveText('Assignment · Read, quiz, and chat');

  await page.locator('#character-toggle').click();
  await page.locator('.character-card', { hasText: TEST_CHARACTER.name }).click();
  await page.locator('#character-chat-btn').click();
  await expect(page.locator('#chat-messages')).toContainText('I am Ada.');
  await page.locator('#character-chat-close').click();

  await page.locator('#back-to-library').click();

  await expect.poll(() => state.classroomContextRequests).toBe(2);
  await expect(assignment).toContainText('Quiz complete');
  await expect(assignment).toContainText('3/3 complete');
});

test('Take Quiz stays hidden until reading is complete and opens the assignment quiz overlay', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('reader_bookActivity', JSON.stringify({
      'book-1': {
        chapterCount: 1,
        lastChapterIndex: 0,
        lastPage: 0,
        progressRatio: 1,
        maxProgressRatio: 1,
        completed: true,
        lastReadAt: '2026-08-12T12:00:00Z'
      }
    }));
  });
  await installApiMocks(page, {
    quizStatus: 'PENDING',
    quizAttemptsUsed: 0,
    characterChatRequired: false
  });
  await page.goto('/');

  const assignment = page.locator('#classroom-assignments-list [data-assignment-id="assignment-1"]');
  await expect(assignment.locator('.assignment-quiz-action')).toHaveText('Take Quiz');
  await assignment.locator('.assignment-quiz-action').click();
  const overlay = page.locator('#chapter-recap-overlay');
  await expect(overlay).toBeVisible();
  await expect(overlay).toHaveClass(/assignment-quiz-mode/);
  await expect(page.locator('#chapter-recap-title')).toHaveText('Assignment Quiz');
  await expect(page.locator('#chapter-recap-tab-recap')).toBeHidden();
  await expect(page.locator('#chapter-quiz-questions')).toContainText('Who is the narrator?');
  await page.locator('#chapter-quiz-questions input[type="radio"]').first().check();
  await page.locator('#chapter-quiz-submit').click();
  await expect(page.locator('#chapter-quiz-feedback')).toContainText('Not quite');
  await expect(page.locator('#assignment-quiz-retry')).toBeVisible();
  await expect(page.locator('#assignment-quiz-retry')).toHaveText('Retry Quiz');
});

test('a passing but imperfect assignment quiz can still be retried', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('reader_bookActivity', JSON.stringify({
      'book-1': {
        chapterCount: 1,
        lastChapterIndex: 0,
        lastPage: 0,
        progressRatio: 1,
        maxProgressRatio: 1,
        completed: true,
        lastReadAt: '2026-08-12T12:00:00Z'
      }
    }));
  });
  await installApiMocks(page, {
    quizStatus: 'PENDING',
    quizAttemptsUsed: 0,
    quizAttemptsAllowed: 2,
    quizPassMinCorrect: 1,
    characterChatRequired: false,
    gradeCorrectAnswers: 1,
    gradeTotalQuestions: 2
  });
  await page.goto('/');

  const assignment = page.locator('#classroom-assignments-list [data-assignment-id="assignment-1"]');
  await assignment.locator('.assignment-quiz-action').click();
  await page.locator('#chapter-quiz-questions input[type="radio"]').first().check();
  await page.locator('#chapter-quiz-submit').click();
  await expect(page.locator('#chapter-quiz-feedback')).toContainText('You passed');
  await expect(page.locator('#chapter-quiz-feedback')).toContainText('attempt');
  await expect(page.locator('#assignment-quiz-retry')).toBeVisible();
  await expect(page.locator('#assignment-quiz-retry')).toHaveText('Retry Quiz');
});

test('Retry Quiz appears after a failed attempt while retries remain', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('reader_bookActivity', JSON.stringify({
      'book-1': {
        chapterCount: 1,
        lastChapterIndex: 0,
        maxProgressRatio: 1,
        completed: true,
        lastReadAt: '2026-08-12T12:00:00Z'
      }
    }));
  });
  await installApiMocks(page, {
    quizStatus: 'PENDING',
    quizAttemptsUsed: 1,
    quizAttemptsAllowed: 2,
    characterChatRequired: false
  });
  await page.goto('/');

  const assignment = page.locator('#classroom-assignments-list [data-assignment-id="assignment-1"]');
  await expect(assignment.locator('.assignment-quiz-action')).toHaveText('Retry Quiz');
});

test('Chat with Character on the assignment card opens chat when one character is available', async ({ page }) => {
  await installApiMocks(page, {
    quizStatus: 'PENDING',
    characterChatRequired: true
  });
  await page.goto('/');

  const assignment = page.locator('#classroom-assignments-list [data-assignment-id="assignment-1"]');
  await expect(assignment.locator('.assignment-quiz-action')).toHaveCount(0);
  await assignment.locator('.assignment-chat-action').click();
  await expect(page.locator('#reader-view')).toBeVisible();
  await expect(page.locator('#assignment-mode-banner')).toBeVisible();
  await expect(page.locator('#character-chat-modal')).toBeVisible();
  await expect(page.locator('#chat-character-name')).toHaveText(TEST_CHARACTER.name);
});

test('secondary-only characters can be chatted with from the assignment card', async ({ page }) => {
  await installApiMocks(page, {
    quizStatus: 'PENDING',
    characterChatRequired: true,
    character: {
      ...TEST_CHARACTER,
      name: 'Fortunato',
      characterType: 'SECONDARY',
      chatEligible: true
    }
  });
  await page.goto('/');

  const assignment = page.locator('#classroom-assignments-list [data-assignment-id="assignment-1"]');
  await assignment.locator('.assignment-chat-action').click();
  await expect(page.locator('#character-chat-modal')).toBeVisible();
  await expect(page.locator('#chat-character-name')).toHaveText('Fortunato');
});

test('end-of-reading wrap-up offers Take Quiz and Chat, not Continue Reading', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('reader_bookActivity', JSON.stringify({
      'book-1': {
        chapterCount: 1,
        lastChapterIndex: 0,
        lastPage: 0,
        progressRatio: 1,
        maxProgressRatio: 1,
        completed: true,
        lastReadAt: '2026-08-12T12:00:00Z'
      }
    }));
  });
  await installApiMocks(page, {
    quizStatus: 'PENDING',
    quizAttemptsUsed: 0,
    characterChatRequired: true
  });
  await page.goto('/');

  const assignment = page.locator('#classroom-assignments-list [data-assignment-id="assignment-1"]');
  await assignment.locator('.book-item-title').click();
  await expect(page.locator('#reader-view')).toBeVisible();
  await expect(page.locator('#assignment-mode-banner')).toBeVisible();
  await expect(page.locator('#chapter-title')).toContainText('Chapter One');
  await page.keyboard.press('ArrowRight');
  const wrapup = page.locator('#assignment-wrapup-overlay');
  await expect(wrapup).toBeVisible();
  await expect(wrapup).toContainText('Take the quiz and chat with a character');
  await expect(wrapup.locator('[data-assignment-wrapup="quiz"]')).toHaveText('Take Quiz');
  await expect(wrapup.locator('[data-assignment-wrapup="chat"]')).toHaveText('Chat with Character');
  await expect(wrapup.locator('[data-assignment-wrapup="library"]')).toHaveText('Back to library');
  await expect(wrapup.locator('[data-assignment-wrapup="continue"]')).toHaveCount(0);
});

test('Continue Reading exits assignment mode and restores the full chapter list', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('reader_bookActivity', JSON.stringify({
      'book-1': {
        chapterCount: 3,
        lastChapterIndex: 0,
        lastPage: 0,
        progressRatio: 0.34,
        maxProgressRatio: 0.34,
        lastReadAt: '2026-08-12T12:00:00Z'
      }
    }));
  });
  await installApiMocks(page, {
    book: MULTI_CHAPTER_BOOK,
    assignmentTitle: 'Read chapter one',
    assignmentChapters: [{ chapterId: 'chapter-1', chapterIndex: 0, chapterTitle: 'Chapter One' }],
    quizRequired: false,
    quizStatus: 'NOT_REQUIRED',
    characterChatRequired: false
  });
  await page.goto('/');

  const assignment = page.locator('#classroom-assignments-list [data-assignment-id="assignment-1"]');
  await assignment.locator('.book-item-title').click();
  await expect(page.locator('#reader-view')).toBeVisible();
  await expect(page.locator('#assignment-mode-banner')).toHaveText('Assignment · Read chapter one');
  await expect(page.locator('#chapter-title')).toContainText('Chapter One');

  await page.keyboard.press('c');
  await expect(page.locator('#chapter-list-overlay')).toBeVisible();
  await expect(page.locator('#chapter-list .chapter-list-item')).toHaveCount(1);
  await expect(page.locator('#chapter-list .chapter-list-item')).toContainText('Chapter One');
  await page.keyboard.press('Escape');

  await page.keyboard.press('ArrowRight');
  const wrapup = page.locator('#assignment-wrapup-overlay');
  await expect(wrapup).toBeVisible();
  await expect(wrapup.locator('[data-assignment-wrapup="continue"]')).toHaveText('Continue Reading');
  await wrapup.locator('[data-assignment-wrapup="continue"]').click();

  await expect(wrapup).toBeHidden();
  await expect(page.locator('#assignment-mode-banner')).toBeHidden();
  await page.keyboard.press('c');
  await expect(page.locator('#chapter-list .chapter-list-item')).toHaveCount(3);
});

test('multi-chapter assignment wrap-up waits until the last assigned chapter', async ({ page }) => {
  await installApiMocks(page, {
    book: MULTI_CHAPTER_BOOK,
    assignmentTitle: 'Chapters one and two',
    assignmentChapters: [
      { chapterId: 'chapter-1', chapterIndex: 0, chapterTitle: 'Chapter One' },
      { chapterId: 'chapter-2', chapterIndex: 1, chapterTitle: 'Chapter Two' }
    ],
    quizRequired: false,
    quizStatus: 'NOT_REQUIRED',
    characterChatRequired: false
  });
  await page.goto('/');

  const assignment = page.locator('#classroom-assignments-list [data-assignment-id="assignment-1"]');
  await expect(assignment).toContainText('Chapter One, Chapter Two');
  await assignment.locator('.book-item-title').click();
  await expect(page.locator('#reader-view')).toBeVisible();
  await expect(page.locator('#chapter-title')).toContainText('Chapter One');

  await page.keyboard.press('c');
  await expect(page.locator('#chapter-list .chapter-list-item')).toHaveCount(2);
  await page.keyboard.press('Escape');
  await expect(page.locator('#chapter-list-overlay')).toBeHidden();

  await page.keyboard.press('ArrowRight');
  await expect(page.locator('#assignment-wrapup-overlay')).toBeHidden();
  await expect(page.locator('#chapter-recap-overlay')).toBeHidden();
  await expect(page.locator('#chapter-title')).toContainText('Chapter Two');

  await page.keyboard.press('ArrowRight');
  const wrapup = page.locator('#assignment-wrapup-overlay');
  await expect(wrapup).toBeVisible();
  await expect(wrapup).toContainText('You finished this assignment');
});
