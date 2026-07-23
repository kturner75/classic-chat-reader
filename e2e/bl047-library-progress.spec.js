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

const TEST_CHARACTER = {
  id: 'character-1',
  name: 'Ada',
  description: 'A character with an existing student conversation.',
  portraitReady: false,
  characterType: 'PRIMARY',
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

async function installApiMocks(page) {
  const state = { classroomContextRequests: 0 };

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    if (method === 'GET' && path === '/api/classroom/context') {
      state.classroomContextRequests += 1;
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
          title: 'Read, quiz, and chat',
          bookId: TEST_BOOK.id,
          bookTitle: TEST_BOOK.title,
          bookAuthor: TEST_BOOK.author,
          chapterId: 'chapter-1',
          chapterIndex: 0,
          chapterTitle: 'Chapter One',
          quizRequired: true,
          quizStatus: state.classroomContextRequests === 1 ? 'PENDING' : 'COMPLETE',
          characterChatRequired: true,
          bookAvailable: true
        }]
      });
    }
    if (method === 'GET' && path === '/api/library') return json(route, 200, [TEST_BOOK]);
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
    if (method === 'GET' && path === `/api/recaps/book/${TEST_BOOK.id}/status`) {
      return json(route, 200, { enabled: false, available: false, chatEnabled: false });
    }
    if (method === 'GET' && path === '/api/quizzes/status') {
      return json(route, 200, { enabled: true, available: true, generationAvailable: false });
    }
    if (method === 'GET' && path === `/api/quizzes/book/${TEST_BOOK.id}/status`) {
      return json(route, 200, { enabled: true, available: true, generationAvailable: false });
    }
    if (method === 'GET' && path === `/api/library/${TEST_BOOK.id}/chapters/chapter-1`) {
      return json(route, 200, {
        chapterId: 'chapter-1',
        paragraphs: [{ content: 'The complete assigned chapter.' }]
      });
    }
    if (method === 'GET' && path === `/api/library/${TEST_BOOK.id}/annotations`) return json(route, 200, []);
    if (method === 'GET' && path === `/api/library/${TEST_BOOK.id}/bookmarks`) return json(route, 200, []);
    if (method === 'GET' && path === `/api/characters/book/${TEST_BOOK.id}/up-to`) {
      return json(route, 200, [TEST_CHARACTER]);
    }
    if (method === 'GET' && path === `/api/characters/book/${TEST_BOOK.id}`) {
      return json(route, 200, [TEST_CHARACTER]);
    }
    if (method === 'GET' && path === `/api/characters/book/${TEST_BOOK.id}/new-since`) {
      return json(route, 200, []);
    }
    if (method === 'GET' && path === `/api/account/chats/characters/${TEST_CHARACTER.id}`) {
      return json(route, 200, {
        session: { sessionId: 'session-1' },
        messages: [
          { messageId: 'message-1', role: 'USER', content: 'Tell me about yourself.' },
          { messageId: 'message-2', role: 'CHARACTER', content: 'I am Ada.' }
        ]
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
  await expect(assignment).toContainText('Quiz required');
  await assignment.click();
  await expect(page.locator('#reader-view')).toBeVisible();

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
