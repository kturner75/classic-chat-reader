const { test, expect } = require('@playwright/test');

function json(route, payload, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(payload)
  });
}

async function installTeacherMocks(page, canTeach) {
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === 'GET' && path === '/api/account/status') {
      return json(route, {
        accountAuthEnabled: true,
        authenticated: true,
        email: canTeach ? 'teacher@example.com' : 'student@example.com',
        rolloutMode: 'internal',
        accountRequired: false
      });
    }
    if (request.method() === 'GET' && path === '/api/classroom/capabilities') {
      return json(route, { canTeach, canCreateClass: canTeach });
    }
    if (request.method() === 'GET' && path === '/api/library') {
      return json(route, []);
    }
    if (request.method() === 'GET' && path === '/api/classroom/classes') {
      return json(route, []);
    }
    return json(route, { error: `Unhandled route: ${request.method()} ${path}` }, 404);
  });
}

async function installAssignmentMocks(page, options = {}) {
  const books = [
    { id: 'treasure', title: 'Treasure Island', author: 'Robert Louis Stevenson', chapters: [{ id: 'treasure-1', title: 'The Old Sea-dog' }] },
    { id: 'pride', title: 'Pride and Prejudice', author: 'Jane Austen', chapters: [
      { id: 'pride-1', title: 'Chapter 1' },
      { id: 'pride-2', title: 'Chapter 2' }
    ] },
    { id: 'cities', title: 'A Tale of Two Cities', author: 'Charles Dickens', chapters: [{ id: 'cities-1', title: 'Recalled to Life' }] }
  ];
  let lastAssignmentBody = {};
  const generated = [
    {
      id: 'q1',
      question: 'Who has taken Netherfield Park?',
      options: ['Mr. Bingley', 'Mr. Darcy', 'Mr. Collins', 'Colonel Fitzwilliam'],
      correctOptionIndex: 0
    },
    {
      id: 'q2',
      question: 'What is Mrs. Bennet’s chief object in life?',
      options: ['To see her daughters married', 'To move to London', 'To publish letters', 'To keep Longbourn'],
      correctOptionIndex: 0
    }
  ];

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === 'GET' && path === '/api/account/status') {
      return json(route, { authenticated: true, email: 'teacher@example.com' });
    }
    if (request.method() === 'GET' && path === '/api/classroom/capabilities') {
      return json(route, { canTeach: true, canCreateClass: true });
    }
    if (request.method() === 'GET' && path === '/api/library') {
      return json(route, books);
    }
    if (request.method() === 'GET' && path === '/api/reading-buddy/status') {
      const available = options.readingBuddyAvailable !== false;
      return json(route, { available, enabled: available, chatEnabled: true, providerAvailable: true });
    }
    if (request.method() === 'GET' && path === '/api/classroom/classes') {
      return json(route, [{ classId: 'class-1', className: 'Literature 101', activeTermId: 'term-1', activeTermName: 'Fall' }]);
    }
    if (request.method() === 'GET' && path === '/api/classroom/terms/term-1/roster') {
      return json(route, []);
    }
    if (request.method() === 'GET' && path === '/api/classroom/terms/term-1/assignments') {
      return json(route, options.assignments || []);
    }
    if (request.method() === 'GET' && path === '/api/classroom/terms/term-1/features') {
      return json(route, {
        readingBuddyEnabled: options.classroomReadingBuddyEnabled !== false,
        defaultQuizQuestionCount: 10,
        defaultQuizOptionCount: 4,
        defaultQuizPassMinCorrect: 1,
        defaultQuizMaxRetries: 1
      });
    }
    if (request.method() === 'GET' && path === '/api/classroom/assignments/asg-1/effective-quiz') {
      const chapterIds = Array.isArray(lastAssignmentBody.chapterIds) ? lastAssignmentBody.chapterIds : [];
      const singleDefault = chapterIds.length === 1 && chapterIds[0] === 'pride-1';
      return json(route, {
        assignmentId: 'asg-1',
        termId: 'term-1',
        bookId: lastAssignmentBody.bookId || 'pride',
        quizSource: singleDefault ? 'CHAPTER' : 'CUSTOM',
        chapterDefaultAvailable: singleDefault,
        questions: singleDefault ? generated : [],
        contentVersion: 'v1'
      });
    }
    if (request.method() === 'GET' && path.endsWith('/effective-quiz')) {
      const chapterId = path.split('/').at(-2);
      const chapterGenerated = chapterId === 'pride-1' ? generated : [];
      return json(route, {
        termId: 'term-1',
        chapterId,
        generatedQuestions: chapterGenerated,
        overrides: [],
        effectiveQuestions: chapterGenerated,
        contentVersion: 'v1'
      });
    }
    if (request.method() === 'POST' && path === '/api/classroom/terms/term-1/assignments') {
      const body = request.postDataJSON();
      lastAssignmentBody = body;
      if (typeof options.onCreateAssignment === 'function') options.onCreateAssignment(body);
      if (typeof options.onAssignmentQuiz === 'function' && Array.isArray(body.customQuizQuestions)) {
        options.onAssignmentQuiz({ questions: body.customQuizQuestions });
      }
      return json(route, {
        assignmentId: 'asg-1',
        termId: 'term-1',
        title: body.title,
        bookId: body.bookId,
        chapterIds: body.chapterIds || [],
        chapters: (body.chapterIds || []).map((chapterId, index) => ({ chapterId, chapterIndex: index })),
        chapterId: Array.isArray(body.chapterIds) && body.chapterIds.length === 1 ? body.chapterIds[0] : body.chapterId,
        status: body.status || 'DRAFT',
        quizRequired: body.quizRequired === true,
        quizSource: body.quizSource || null,
        characterChatRequired: body.characterChatRequired === true,
        quizPassMinCorrect: body.quizPassMinCorrect ?? null,
        quizMaxRetries: body.quizMaxRetries ?? null
      }, 201);
    }
    if (request.method() === 'PUT' && path === '/api/classroom/assignments/asg-1') {
      const body = request.postDataJSON();
      lastAssignmentBody = { ...lastAssignmentBody, ...body };
      if (typeof options.onUpdateAssignment === 'function') options.onUpdateAssignment(body);
      if (typeof options.onCreateAssignment === 'function') options.onCreateAssignment(body);
      if (typeof options.onAssignmentQuiz === 'function' && Array.isArray(body.customQuizQuestions)) {
        options.onAssignmentQuiz({ questions: body.customQuizQuestions });
      }
      return json(route, {
        assignmentId: 'asg-1',
        termId: 'term-1',
        title: body.title || lastAssignmentBody.title,
        bookId: body.bookId || lastAssignmentBody.bookId,
        chapterIds: body.chapterIds || lastAssignmentBody.chapterIds || [],
        status: body.status || 'DRAFT',
        quizRequired: body.quizRequired === true,
        quizSource: body.quizSource || null,
        quizPassMinCorrect: body.quizPassMinCorrect ?? null,
        quizMaxRetries: body.quizMaxRetries ?? null
      });
    }
    if (request.method() === 'DELETE' && path.startsWith('/api/classroom/assignments/')) {
      if (typeof options.onDeleteAssignment === 'function') {
        options.onDeleteAssignment(path.split('/').at(-1));
      }
      return route.fulfill({ status: 204, body: '' });
    }
    if (request.method() === 'PUT' && path === '/api/classroom/assignments/asg-1/quiz') {
      if (typeof options.onAssignmentQuiz === 'function') options.onAssignmentQuiz(request.postDataJSON());
      return json(route, { assignmentId: 'asg-1', quizSource: 'CUSTOM', contentVersion: 'v2', questions: [] });
    }
    if (request.method() === 'PUT' && path.endsWith('/quiz-overrides')) {
      if (typeof options.onQuizOverrides === 'function') options.onQuizOverrides(request.postDataJSON());
      return json(route, { contentVersion: 'v2' });
    }
    if (request.method() === 'POST' && path.endsWith('/suggest-distractors')) {
      const delayMs = Number(options.suggestDelayMs) || 0;
      if (delayMs > 0) {
        await new Promise(resolve => setTimeout(resolve, delayMs));
      }
      if (typeof options.onSuggestDistractors === 'function') {
        await options.onSuggestDistractors(request.postDataJSON());
      }
      const count = Number(request.postDataJSON()?.count) || 3;
      return json(route, {
        distractors: Array.from({ length: count }, (_, index) => `Wrong choice ${index + 1}`)
      });
    }
    return json(route, { error: `Unhandled route: ${request.method()} ${path}` }, 404);
  });
}

test('student navigating directly to Teaching sees an access-denied state', async ({ page }) => {
  await installTeacherMocks(page, false);

  await page.goto('/teacher.html');

  await expect(page.locator('#access-denied-state')).toBeVisible();
  await expect(page.locator('#access-denied-state')).toContainText('Teaching access isn’t enabled');
  await expect(page.locator('#teacher-app')).toBeHidden();
});
test('teacher-capable account can open first-class onboarding', async ({ page }) => {
  await installTeacherMocks(page, true);

  await page.goto('/teacher.html');

  await expect(page.locator('#teacher-app')).toBeVisible();
  await expect(page.locator('#empty-class-state')).toBeVisible();
  await expect(page.locator('#empty-create-button')).toBeVisible();
});

test('teacher can delete a draft assignment but not a published one', async ({ page }) => {
  let deletedId = null;
  await installAssignmentMocks(page, {
    assignments: [
      {
        assignmentId: 'draft-1',
        title: 'Unfinished Pride quiz',
        bookId: 'pride',
        status: 'DRAFT',
        quizRequired: true
      },
      {
        assignmentId: 'pub-1',
        title: 'Published reading',
        bookId: 'pride',
        status: 'PUBLISHED',
        quizRequired: false
      }
    ],
    onDeleteAssignment: (id) => { deletedId = id; }
  });
  await page.goto('/teacher.html');
  await expect(page.locator('.assignment-card', { hasText: 'Unfinished Pride quiz' })).toBeVisible();
  await expect(page.locator('[data-delete-assignment="draft-1"]')).toBeVisible();
  await expect(page.locator('[data-delete-assignment="pub-1"]')).toHaveCount(0);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('[data-delete-assignment="draft-1"]').click();
  await expect.poll(() => deletedId).toBe('draft-1');
  await expect(page.locator('.assignment-card', { hasText: 'Unfinished Pride quiz' })).toHaveCount(0);
  await expect(page.locator('.assignment-card', { hasText: 'Published reading' })).toBeVisible();
  await expect(page.locator('#toast')).toContainText('Draft assignment deleted.');
});

test('assignment book autocomplete sorts titles and narrows the choices', async ({ page }) => {
  await installAssignmentMocks(page);
  await page.goto('/teacher.html');
  await expect(page.locator('#new-assignment-button')).toBeEnabled();

  await page.locator('#new-assignment-button').click();
  const search = page.locator('#assignment-book-search');
  await search.click();
  await expect(page.locator('.book-option-title')).toHaveText([
    'A Tale of Two Cities',
    'Pride and Prejudice',
    'Treasure Island'
  ]);
  await expect(page.locator('[data-book-id="cities"]')).toBeInViewport();
  await expect(page.locator('[data-book-id="treasure"]')).toBeInViewport();

  await search.fill('pride');
  await expect(page.locator('.book-option-title')).toHaveText(['Pride and Prejudice']);
  await page.locator('[data-book-id="pride"]').click();

  await expect(search).toHaveValue('Pride and Prejudice · Jane Austen');
  await expect(page.locator('#assignment-book')).toHaveValue('pride');
  await expect(page.locator('#assignment-whole-book')).toBeChecked();
  await expect(page.locator('#assignment-chapter-options')).toContainText('Chapter 1');
  await expect(page.locator('#assignment-chapter-options')).toContainText('Chapter 2');
});

test('global-off/classroom-on shows the saved Reading Buddy policy as unavailable', async ({ page }) => {
  await installAssignmentMocks(page, { readingBuddyAvailable: false, classroomReadingBuddyEnabled: true });
  await page.goto('/teacher.html');

  const toggle = page.locator('input[name="readingBuddyEnabled"]');
  await expect(toggle).toBeChecked();
  await expect(toggle).toBeDisabled();
  await expect(page.locator('#reading-buddy-feature-description')).toContainText('Unavailable in this deployment');
  await expect(page.locator('#reading-buddy-feature-description')).toContainText('Saved classroom policy: On');
});

test('global-on/classroom-on keeps the Reading Buddy policy usable', async ({ page }) => {
  await installAssignmentMocks(page, { readingBuddyAvailable: true, classroomReadingBuddyEnabled: true });
  await page.goto('/teacher.html');

  const toggle = page.locator('input[name="readingBuddyEnabled"]');
  await expect(toggle).toBeChecked();
  await expect(toggle).toBeEnabled();
  await expect(page.locator('#reading-buddy-feature-description')).toContainText('Available in this deployment');
});

test('BL-025.10 roster Overview uses activeTermId (ClassSummary has no termId)', async ({ page }) => {
  let overviewPath = null;
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === 'GET' && path === '/api/account/status') {
      return json(route, { authenticated: true, email: 'teacher@example.com' });
    }
    if (request.method() === 'GET' && path === '/api/classroom/capabilities') {
      return json(route, { canTeach: true, canCreateClass: true });
    }
    if (request.method() === 'GET' && path === '/api/library') {
      return json(route, []);
    }
    if (request.method() === 'GET' && path === '/api/reading-buddy/status') {
      return json(route, { available: true, enabled: true, chatEnabled: true, providerAvailable: true });
    }
    // Intentionally omit termId — production ClassSummary only sets activeTermId.
    if (request.method() === 'GET' && path === '/api/classroom/classes') {
      return json(route, [{ classId: 'class-1', className: 'Literature 101', activeTermId: 'term-1', activeTermName: 'Fall' }]);
    }
    if (request.method() === 'GET' && path === '/api/classroom/terms/term-1/roster') {
      return json(route, [{
        userId: 'student-1',
        email: 'student@example.com',
        displayNameOverride: 'Alex Student',
        joinedDate: '2026-08-01',
        status: 'ACTIVE'
      }]);
    }
    if (request.method() === 'GET' && path === '/api/classroom/terms/term-1/assignments') {
      return json(route, []);
    }
    if (request.method() === 'GET' && path === '/api/classroom/terms/term-1/features') {
      return json(route, { readingBuddyEnabled: true });
    }
    if (request.method() === 'GET' && path === '/api/classroom/terms/term-1/students/student-1/overview') {
      overviewPath = path;
      return json(route, {
        termId: 'term-1',
        student: {
          userId: 'student-1',
          email: 'student@example.com',
          displayNameOverride: 'Alex Student',
          joinedDate: '2026-08-01'
        },
        currentAssignments: [],
        completedAssignments: [],
        progressByBook: [],
        quizzesForBook: [],
        timeInReader: {
          label: 'Approximate time in reader',
          caveat: 'Engagement proxy',
          approximateTotalMs: 0,
          byBook: []
        },
        ferpaNote: 'Pilot teacher drill-down (BL-025.10).'
      });
    }
    return json(route, { error: `Unhandled route: ${request.method()} ${path}` }, 404);
  });

  await page.goto('/teacher.html');
  await expect(page.locator('#roster-table')).toBeVisible();
  await page.locator('[data-open-student="student-1"]').click();

  await expect(page.locator('#student-overview-modal')).toBeVisible();
  await expect(page.locator('#student-overview-modal')).toHaveClass(/page-wizard/);
  await expect(page.locator('#student-overview-title')).toHaveText('Alex Student');
  await expect(page.locator('#student-overview-body')).toBeVisible();
  await expect(page.locator('#student-overview-close')).toHaveText('Back to roster');
  expect(overviewPath).toBe('/api/classroom/terms/term-1/students/student-1/overview');
});

test('student overview collapses completed work and highlights progress and quiz scores', async ({ page }) => {
  const overviewFor = (userId, name, email) => ({
    termId: 'term-1',
    student: { userId, email, displayNameOverride: name, joinedDate: '2026-08-01' },
    currentAssignments: [{
      title: 'Read Chapter 5',
      bookTitle: 'Pride and Prejudice',
      opened: true,
      statusLabel: 'In progress'
    }],
    completedAssignments: [
      { title: 'Read Chapter 1', bookTitle: 'Pride and Prejudice', opened: true, statusLabel: 'Complete' },
      { title: 'Read Chapter 2', bookTitle: 'Pride and Prejudice', opened: true, statusLabel: 'Complete' }
    ],
    progressByBook: [{
      bookTitle: 'Pride and Prejudice',
      chapterLabel: '4/12',
      percentComplete: 72,
      lastReadAt: '2026-08-12T15:00:00Z'
    }],
    quizzesForBook: [{
      assignmentTitle: 'Chapter 1 quiz',
      bookTitle: 'Pride and Prejudice',
      chapterTitle: 'Chapter 1',
      complete: true,
      attemptsUsed: 2,
      attemptsAllowed: 3,
      retryAttemptsUsed: 1,
      bestScorePercent: 80,
      bestCorrectAnswers: 8,
      totalQuestions: 10,
      latestAttemptAt: '2026-08-11T18:00:00Z'
    }],
    timeInReader: {
      label: 'Approximate time in reader',
      caveat: 'Engagement proxy',
      approximateTotalMs: 0,
      byBook: []
    },
    ferpaNote: 'Pilot teacher drill-down (BL-025.10).'
  });

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === 'GET' && path === '/api/account/status') {
      return json(route, { authenticated: true, email: 'teacher@example.com' });
    }
    if (request.method() === 'GET' && path === '/api/classroom/capabilities') {
      return json(route, { canTeach: true, canCreateClass: true });
    }
    if (request.method() === 'GET' && path === '/api/library') {
      return json(route, []);
    }
    if (request.method() === 'GET' && path === '/api/reading-buddy/status') {
      return json(route, { available: true, enabled: true, chatEnabled: true, providerAvailable: true });
    }
    if (request.method() === 'GET' && path === '/api/classroom/classes') {
      return json(route, [{ classId: 'class-1', className: 'Literature 101', activeTermId: 'term-1', activeTermName: 'Fall' }]);
    }
    if (request.method() === 'GET' && path === '/api/classroom/terms/term-1/roster') {
      return json(route, [
        { userId: 'student-1', email: 'alex@example.com', displayNameOverride: 'Alex Student', joinedDate: '2026-08-01', status: 'ACTIVE' },
        { userId: 'student-2', email: 'jordan@example.com', displayNameOverride: 'Jordan Student', joinedDate: '2026-08-01', status: 'ACTIVE' }
      ]);
    }
    if (request.method() === 'GET' && path === '/api/classroom/terms/term-1/assignments') {
      return json(route, []);
    }
    if (request.method() === 'GET' && path === '/api/classroom/terms/term-1/features') {
      return json(route, { readingBuddyEnabled: true });
    }
    if (request.method() === 'GET' && path === '/api/classroom/terms/term-1/students/student-1/overview') {
      return json(route, overviewFor('student-1', 'Alex Student', 'alex@example.com'));
    }
    if (request.method() === 'GET' && path === '/api/classroom/terms/term-1/students/student-2/overview') {
      return json(route, overviewFor('student-2', 'Jordan Student', 'jordan@example.com'));
    }
    return json(route, { error: `Unhandled route: ${request.method()} ${path}` }, 404);
  });

  await page.goto('/teacher.html');
  await expect(page.locator('#roster-table')).toBeVisible();
  await page.locator('[data-open-student="student-1"]').click();
  await expect(page.locator('#student-overview-body')).toBeVisible();

  const completedToggle = page.locator('[data-overview-collapse="completed-assignments"]');
  const completedPanel = page.locator('#overview-section-completed-assignments');
  await expect(completedToggle).toHaveAttribute('aria-expanded', 'true');
  await expect(completedToggle.locator('.overview-collapse-count')).toHaveText('2');
  await expect(completedPanel).toBeVisible();
  await expect(completedPanel.getByText('Read Chapter 1')).toBeVisible();

  const progressCard = page.locator('.overview-section', { hasText: 'Progress by book' }).locator('.overview-item-metric');
  await expect(progressCard.locator('.overview-metric-value')).toHaveText('72%');
  await expect(progressCard.locator('.overview-metric-label')).toHaveText('complete');
  await expect(progressCard.getByText('Chapter 4/12')).toBeVisible();

  const quizCard = page.locator('.overview-section', { hasText: 'Quizzes' }).locator('.overview-item-metric');
  await expect(quizCard.locator('.overview-metric-value')).toHaveText('80%');
  await expect(quizCard.locator('.overview-metric-label')).toHaveText('8/10 best');

  await completedToggle.click();
  await expect(completedToggle).toHaveAttribute('aria-expanded', 'false');
  await expect(completedPanel).toBeHidden();
  const stored = await page.evaluate(() => localStorage.getItem('teacher_overviewCollapsedSections'));
  expect(stored).toContain('completed-assignments');

  await page.locator('#student-overview-close').click();
  await page.locator('[data-open-student="student-2"]').click();
  await expect(page.locator('#student-overview-title')).toHaveText('Jordan Student');
  await expect(page.locator('[data-overview-collapse="completed-assignments"]')).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('#overview-section-completed-assignments')).toBeHidden();
});

async function fillAssignmentIdentity(page, { title, bookId, chapterLabel, chapterLabels }) {
  await page.locator('#assignment-title').fill(title);
  const search = page.locator('#assignment-book-search');
  await search.click();
  await page.locator(`[data-book-id="${bookId}"]`).click();
  const labels = chapterLabels || (chapterLabel ? [chapterLabel] : []);
  if (labels.length > 0) {
    await page.locator('#assignment-whole-book').uncheck();
    for (const label of labels) {
      await page.locator('#assignment-chapter-options label', { hasText: label }).locator('input').check();
    }
  }
}

test('no-quiz assignment path skips define quiz and saves', async ({ page }) => {
  let saved = null;
  await installAssignmentMocks(page, { onCreateAssignment: (body) => { saved = body; } });
  await page.goto('/teacher.html');
  await page.locator('#new-assignment-button').click();
  await fillAssignmentIdentity(page, { title: 'Read Chapter 1', bookId: 'pride', chapterLabel: 'Chapter 1' });
  await page.locator('#assignment-next-1').click();
  await page.locator('#assignment-quiz-choice-none').click();
  await page.locator('#assignment-next-2').click();
  await expect(page.locator('#assignment-pane-3')).toBeVisible();
  await page.locator('#assignment-submit').click();
  await expect.poll(() => saved).not.toBeNull();
  expect(saved.quizRequired).toBe(false);
  expect(saved.title).toBe('Read Chapter 1');
  expect(saved.bookId).toBe('pride');
});

test('require-quiz path uses the default quiz and does not publish overrides', async ({ page }) => {
  let saved = null;
  let overrides = null;
  await installAssignmentMocks(page, {
    onCreateAssignment: (body) => { saved = body; },
    onQuizOverrides: (body) => { overrides = body; }
  });
  await page.goto('/teacher.html');
  await page.locator('#new-assignment-button').click();
  await fillAssignmentIdentity(page, { title: 'Quiz Chapter 1', bookId: 'pride', chapterLabel: 'Chapter 1' });
  await page.locator('#assignment-next-1').click();
  await page.locator('#assignment-quiz-choice-require').click();
  await expect(page.locator('#assignment-quiz-pass-rules')).toBeVisible();
  await expect(page.locator('#assignment-quiz-question-count')).toBeVisible();
  await expect(page.locator('#assignment-quiz-question-count')).toHaveValue('10');
  await page.locator('#assignment-next-2').click();
  await expect(page.locator('#assignment-quiz-author')).toBeVisible();
  await expect(page.locator('#assignment-quiz-author-body')).toContainText('Who has taken Netherfield Park?');
  await page.locator('#assignment-quiz-author-next').click();
  await page.locator('#assignment-quiz-author-next').click();
  await expect(page.locator('#assignment-quiz-sim')).toBeVisible();
  await page.getByRole('button', { name: 'Mr. Bingley' }).click();
  await page.locator('#assignment-quiz-sim-next').click();
  await page.getByRole('button', { name: 'To see her daughters married' }).click();
  await page.locator('#assignment-quiz-sim-next').click();
  await expect(page.locator('#assignment-quiz-sim-body')).toContainText('Passed');
  await page.locator('#assignment-quiz-sim-next').click();
  await expect(page.locator('#assignment-quiz-summary')).toBeVisible();
  await page.locator('#assignment-quiz-summary-confirm').click();
  await expect(page.locator('#assignment-pane-3')).toBeVisible();
  await page.locator('#assignment-submit').click();
  await expect.poll(() => saved).not.toBeNull();
  expect(saved.quizRequired).toBe(true);
  expect(saved.quizPassMinCorrect).toBe(1);
  expect(saved.quizSource).toBe('CHAPTER');
  expect(saved.chapterIds).toEqual(['pride-1']);
  expect(overrides).toBeNull();
});

test('require-quiz path rejects min correct greater than question count', async ({ page }) => {
  await installAssignmentMocks(page);
  await page.goto('/teacher.html');
  await page.locator('#new-assignment-button').click();
  await fillAssignmentIdentity(page, { title: 'Quiz Chapter 1', bookId: 'pride', chapterLabel: 'Chapter 1' });
  await page.locator('#assignment-next-1').click();
  await page.locator('#assignment-quiz-choice-require').click();
  await page.locator('#assignment-quiz-question-count').fill('3');
  await page.locator('input[name="quizPassMinCorrect"]').fill('8');
  await page.locator('#assignment-next-2').click();
  await expect(page.locator('#toast')).toContainText('Min correct to pass cannot be greater than the number of questions.');
  await expect(page.locator('#assignment-pane-2')).toBeVisible();
  await expect(page.locator('#assignment-quiz-author')).toBeHidden();
});

test('generate wrong answers shows a working state until results arrive', async ({ page }) => {
  let released;
  const hold = new Promise(resolve => { released = resolve; });
  await installAssignmentMocks(page, {
    suggestDelayMs: 0,
    onSuggestDistractors: async () => { await hold; }
  });
  await page.goto('/teacher.html');
  await page.locator('#new-assignment-button').click();
  await fillAssignmentIdentity(page, {
    title: 'Chapters 1 and 2',
    bookId: 'pride',
    chapterLabels: ['Chapter 1', 'Chapter 2']
  });
  await page.locator('#assignment-next-1').click();
  await page.locator('#assignment-quiz-choice-require').click();
  await page.locator('#assignment-quiz-question-count').fill('2');
  await page.locator('#assignment-next-2').click();
  await page.locator('#assignment-quiz-author-body [data-field="stem"]').fill('Who is the heroine?');
  await page.locator('#assignment-quiz-author-body [data-field="correct"]').fill('Elizabeth Bennet');
  await page.locator('[data-generate-distractors]').click();
  await expect(page.locator('[data-generate-distractors]')).toHaveText(/Generating wrong answers/);
  await expect(page.locator('.quiz-suggest-status')).toContainText('Writing wrong answers');
  await expect(page.locator('.quiz-distractor-row.is-generating')).toHaveCount(3);
  released();
  await expect(page.locator('[data-field="distractor"]').first()).toHaveValue('Wrong choice 1');
  await expect(page.locator('[data-generate-distractors]')).toHaveText('Generate wrong answers');
});

test('true/false questions skip wrong-answer generation and simulate True or False', async ({ page }) => {
  let published = null;
  await installAssignmentMocks(page, {
    onAssignmentQuiz: (body) => { published = body; }
  });
  await page.goto('/teacher.html');
  await page.locator('#new-assignment-button').click();
  await fillAssignmentIdentity(page, {
    title: 'True false quiz',
    bookId: 'pride',
    chapterLabels: ['Chapter 1', 'Chapter 2']
  });
  await page.locator('#assignment-next-1').click();
  await page.locator('#assignment-quiz-choice-require').click();
  await page.locator('#assignment-quiz-question-count').fill('1');
  await page.locator('#assignment-next-2').click();
  await expect(page.locator('[data-question-kind="choice"]')).toBeChecked();
  await page.locator('[data-question-kind="truefalse"]').check();
  await expect(page.locator('[data-generate-distractors]')).toHaveCount(0);
  await expect(page.locator('[data-field="correct"]')).toHaveCount(0);
  await page.locator('#assignment-quiz-author-body [data-field="stem"]').fill('Netherfield Park is let at last.');
  await page.locator('[data-true-false="False"]').check();
  await page.locator('#assignment-quiz-author-next').click();
  await expect(page.locator('#assignment-quiz-sim')).toBeVisible();
  await expect(page.getByRole('button', { name: 'True', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'False', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'False', exact: true }).click();
  await page.locator('#assignment-quiz-sim-next').click();
  await expect(page.locator('#assignment-quiz-sim-body')).toContainText('Passed');
  await page.locator('#assignment-quiz-sim-next').click();
  await page.locator('#assignment-quiz-summary-confirm').click();
  await page.locator('#assignment-submit').click();
  await expect.poll(() => published).not.toBeNull();
  expect(published.questions[0].options).toEqual(['True', 'False']);
  expect(published.questions[0].correctOptionIndex).toBe(1);
});

test('multi-chapter assignment hides the default quiz and requires a custom quiz', async ({ page }) => {
  await installAssignmentMocks(page);
  await page.goto('/teacher.html');
  await page.locator('#new-assignment-button').click();
  await fillAssignmentIdentity(page, {
    title: 'Chapters 1 and 2',
    bookId: 'pride',
    chapterLabels: ['Chapter 1', 'Chapter 2']
  });
  await page.locator('#assignment-next-1').click();
  await page.locator('#assignment-quiz-choice-require').click();
  await page.locator('#assignment-quiz-question-count').fill('3');
  await page.locator('#assignment-next-2').click();
  await expect(page.locator('#assignment-quiz-author')).toBeVisible();
  await expect(page.locator('#assignment-quiz-no-default')).toBeVisible();
  await expect(page.locator('[data-quiz-mode="default"]')).toHaveCount(0);
  await expect(page.locator('#assignment-quiz-author-body [data-field="stem"]')).toBeVisible();
  await expect(page.locator('#assignment-quiz-author-progress')).toContainText('Question 1 of 3');
});

test('multi-chapter custom quiz publishes on the assignment quiz endpoint', async ({ page }) => {
  let saved = null;
  let published = null;
  await installAssignmentMocks(page, {
    onCreateAssignment: (body) => { saved = body; },
    onAssignmentQuiz: (body) => { published = body; }
  });
  await page.goto('/teacher.html');
  await page.locator('#new-assignment-button').click();
  await fillAssignmentIdentity(page, {
    title: 'Chapters 1 and 2 quiz',
    bookId: 'pride',
    chapterLabels: ['Chapter 1', 'Chapter 2']
  });
  await page.locator('#assignment-next-1').click();
  await page.locator('#assignment-quiz-choice-require').click();
  await page.locator('#assignment-quiz-question-count').fill('1');
  await page.locator('#assignment-next-2').click();
  await expect(page.locator('#assignment-quiz-no-default')).toBeVisible();
  await page.locator('#assignment-quiz-author-body [data-field="stem"]').fill('Who is the heroine?');
  await page.locator('#assignment-quiz-author-body [data-field="correct"]').fill('Elizabeth Bennet');
  await page.locator('[data-generate-distractors]').click();
  await expect(page.locator('[data-field="distractor"]').first()).toHaveValue('Wrong choice 1');
  await page.locator('#assignment-quiz-author-next').click();
  await expect(page.locator('#assignment-quiz-sim')).toBeVisible();
  await page.getByRole('button', { name: 'Elizabeth Bennet' }).click();
  await page.locator('#assignment-quiz-sim-next').click();
  await expect(page.locator('#assignment-quiz-sim-body')).toContainText('Passed');
  await page.locator('#assignment-quiz-sim-next').click();
  await page.locator('#assignment-quiz-summary-confirm').click();
  await page.locator('#assignment-submit').click();
  await expect.poll(() => saved).not.toBeNull();
  await expect.poll(() => published).not.toBeNull();
  expect(saved.quizRequired).toBe(true);
  expect(saved.chapterIds).toEqual(['pride-1', 'pride-2']);
  expect(published.questions[0].question).toBe('Who is the heroine?');
  expect(published.questions[0].options).toContain('Elizabeth Bennet');
  expect(published.questions[0].options[published.questions[0].correctOptionIndex]).toBe('Elizabeth Bennet');
});
