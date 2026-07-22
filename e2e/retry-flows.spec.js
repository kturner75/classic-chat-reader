const { test, expect } = require('@playwright/test');

const TEST_BOOK = {
  id: 'book-1',
  title: 'Retry Test Book',
  author: 'Jane Tester',
  chapters: [
    { id: 'ch-1', title: 'Chapter One' },
    { id: 'ch-2', title: 'Chapter Two' }
  ],
  ttsEnabled: false,
  illustrationEnabled: false,
  characterEnabled: true
};

const CATALOG_BOOK = {
  gutenbergId: 101,
  title: TEST_BOOK.title,
  author: TEST_BOOK.author,
  downloadCount: 10,
  alreadyImported: true
};

const TEST_CHARACTER = {
  id: 'char-1',
  name: 'A. Character',
  description: 'Primary character used by retry tests.',
  portraitReady: false,
  characterType: 'PRIMARY',
  firstChapterId: 'ch-1',
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
  const state = {
    recapChapterFetchAttempts: 0,
    recapChatAttempts: 0,
    characterChatAttempts: 0,
    characterChatRequestIds: []
  };

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

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
        accountAuthEnabled: true,
        authenticated: true,
        email: 'reader@example.test',
        googleAuthEnabled: false,
        rolloutMode: 'optional',
        accountRequired: false
      });
    }
    if (method === 'GET' && path === '/api/library') {
      return json(route, 200, [TEST_BOOK]);
    }
    if (method === 'GET' && path === '/api/import/popular') {
      return json(route, 200, [CATALOG_BOOK]);
    }
    if (method === 'GET' && path === '/api/features') {
      return json(route, 200, { speedReadingEnabled: false });
    }
    if (method === 'GET' && path === '/api/tts/status') {
      return json(route, 200, {
        openaiConfigured: false,
        cachedAvailable: false,
        cacheOnly: false
      });
    }
    if (method === 'GET' && path === '/api/illustrations/status') {
      return json(route, 200, {
        comfyuiAvailable: false,
        ollamaAvailable: false,
        allowPromptEditing: false,
        cacheOnly: false
      });
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
      return json(route, 200, {
        enabled: true,
        reasoningEnabled: true,
        available: false,
        cacheOnly: false,
        chatEnabled: true,
        chatProviderAvailable: true
      });
    }
    if (method === 'GET' && path === '/api/recaps/book/book-1/status') {
      return json(route, 200, {
        enabled: true,
        reasoningEnabled: true,
        available: true,
        cacheOnly: false,
        chatEnabled: true,
        chatProviderAvailable: true
      });
    }
    if (method === 'GET' && path === '/api/quizzes/status') {
      return json(route, 200, {
        enabled: true,
        reasoningEnabled: true,
        available: false,
        cacheOnly: false,
        generationAvailable: false
      });
    }
    if (method === 'GET' && path === '/api/quizzes/book/book-1/status') {
      return json(route, 200, {
        enabled: true,
        reasoningEnabled: true,
        available: false,
        cacheOnly: false,
        generationAvailable: false
      });
    }
    if (method === 'GET' && path === '/api/library/book-1/annotations') {
      return json(route, 200, []);
    }
    if (method === 'GET' && path === '/api/library/book-1/bookmarks') {
      return json(route, 200, []);
    }
    if (method === 'GET' && path === '/api/library/book-1/chapters/ch-1') {
      return json(route, 200, {
        chapterId: 'ch-1',
        paragraphs: [{ content: 'Chapter one paragraph used for recap retry tests.' }]
      });
    }
    if (method === 'GET' && path === '/api/library/book-1/chapters/ch-2') {
      return json(route, 200, {
        chapterId: 'ch-2',
        paragraphs: [{ content: 'Chapter two paragraph used for chapter transition.' }]
      });
    }
    if (method === 'POST' && path === '/api/recaps/analytics') {
      return json(route, 202, {});
    }
    if (method === 'POST' && path === '/api/recaps/chapter/ch-1/generate') {
      return json(route, 202, {});
    }
    if (method === 'POST' && path === '/api/recaps/chapter/ch-2/generate') {
      return json(route, 202, {});
    }
    if (method === 'POST' && path === '/api/quizzes/chapter/ch-1/generate') {
      return json(route, 202, {});
    }
    if (method === 'POST' && path === '/api/quizzes/chapter/ch-2/generate') {
      return json(route, 202, {});
    }
    if (method === 'GET' && path === '/api/quizzes/chapter/ch-1') {
      return json(route, 404, { error: 'Quiz unavailable' });
    }
    if (method === 'GET' && path === '/api/recaps/chapter/ch-1') {
      state.recapChapterFetchAttempts += 1;
      if (state.recapChapterFetchAttempts === 1) {
        return json(route, 500, { error: 'Transient recap error' });
      }
      return json(route, 200, {
        status: 'COMPLETED',
        payload: {
          shortSummary: 'Recovered recap summary.',
          keyEvents: ['Event A'],
          characterDeltas: [{ characterName: 'A. Character', delta: 'Appears in chapter one.' }]
        }
      });
    }
    if (method === 'POST' && path === '/api/recaps/book/book-1/chat') {
      state.recapChatAttempts += 1;
      if (state.recapChatAttempts === 1) {
        return json(route, 500, { error: 'Recap chat unavailable' });
      }
      return json(route, 200, {
        response: 'Recovered recap chat response.'
      });
    }
    if (method === 'POST' && path === '/api/characters/book/book-1/prefetch') {
      return json(route, 202, {});
    }
    if (method === 'POST' && path === '/api/characters/chapter/ch-1/analyze') {
      return json(route, 202, {});
    }
    if (method === 'POST' && path === '/api/characters/chapter/ch-1/prefetch-next') {
      return json(route, 202, {});
    }
    if (method === 'GET' && path === '/api/characters/book/book-1/new-since') {
      return json(route, 200, []);
    }
    if (method === 'GET' && path === '/api/characters/book/book-1/up-to') {
      return json(route, 200, [TEST_CHARACTER]);
    }
    if (method === 'GET' && path === '/api/characters/book/book-1') {
      return json(route, 200, [TEST_CHARACTER]);
    }
    if (method === 'GET' && path === '/api/account/chats/characters/char-1') {
      return json(route, 200, { session: null, messages: [] });
    }
    if (method === 'POST' && path === '/api/account/chats/characters/char-1/messages') {
      state.characterChatAttempts += 1;
      state.characterChatRequestIds.push(request.headers()['idempotency-key']);
      if (state.characterChatAttempts === 1) {
        return json(route, 500, { error: { message: 'Character chat unavailable' } });
      }
      return json(route, 200, {
        sessionId: 'session-1',
        userMessage: {
          messageId: 'message-user-1',
          role: 'USER',
          content: 'Who are you?',
          createdAt: '2026-07-22T12:00:00Z'
        },
        characterMessage: {
          messageId: 'message-character-1',
          role: 'CHARACTER',
          content: 'Recovered character chat response.',
          createdAt: '2026-07-22T12:00:01Z'
        }
      });
    }

    return json(route, 404, {
      error: `Unhandled API route in e2e mock: ${method} ${path}`
    });
  });

  return state;
}

async function openReaderForTestBook(page) {
  await page.goto('/');
  const firstChapterLoaded = page.waitForResponse((response) =>
    response.url().includes('/api/library/book-1/chapters/ch-1') && response.ok());
  await page.click(`#continue-reading-list .book-item[data-book-id="${TEST_BOOK.id}"]`);
  await firstChapterLoaded;
  await expect(page.locator('#reader-view')).toBeVisible();
  await expect(page.locator('#book-title')).toHaveText(TEST_BOOK.title);
}

test('recap overlay and recap chat expose retry and recover', async ({ page }) => {
  await installApiMocks(page);
  await openReaderForTestBook(page);

  await page.click('#gutter-right');
  await expect(page.locator('#chapter-recap-overlay')).toBeVisible();
  await expect(page.locator('#chapter-recap-error')).toBeVisible();
  await expect(page.locator('#chapter-recap-error-message')).toContainText('Transient recap error');

  await page.click('#chapter-recap-retry');
  await expect(page.locator('#chapter-recap-error')).toBeHidden();
  await expect(page.locator('#chapter-recap-status')).toContainText('Recap ready');
  await expect(page.locator('#chapter-recap-summary')).toContainText('Recovered recap summary.');

  await page.click('#chapter-recap-tab-chat');
  await page.fill('#chapter-recap-chat-input', 'What happened?');
  await page.click('#chapter-recap-chat-send');

  await expect(page.locator('#chapter-recap-chat-error')).toBeVisible();
  await expect(page.locator('#chapter-recap-chat-error-message')).toContainText('Recap chat unavailable');

  await page.click('#chapter-recap-chat-retry');
  await expect(page.locator('#chapter-recap-chat-error')).toBeHidden();
  await expect(page.locator('#chapter-recap-chat-messages')).toContainText('Recovered recap chat response.');
  await expect(page.locator('#chapter-recap-chat-messages .chat-message.user')).toHaveCount(1);
});

test('character chat exposes retry and recovers without duplicating user message', async ({ page }) => {
  const state = await installApiMocks(page);
  await openReaderForTestBook(page);

  await expect(page.locator('#character-toggle')).toBeVisible();
  await page.click('#character-toggle');
  await expect(page.locator('.character-card[data-character-id="char-1"]')).toBeVisible();
  await page.click('.character-card[data-character-id="char-1"]');

  await expect(page.locator('#character-chat-btn')).toBeVisible();
  await page.click('#character-chat-btn');

  await page.fill('#chat-input', 'Who are you?');
  await page.click('#chat-send-btn');

  await expect(page.locator('#chat-error')).toBeVisible();
  await expect(page.locator('#chat-error-message')).toContainText('Character chat unavailable');

  await page.click('#chat-error-retry');
  await expect(page.locator('#chat-error')).toBeHidden();
  await expect(page.locator('#chat-messages')).toContainText('Recovered character chat response.');
  await expect(page.locator('#chat-messages .chat-message.user')).toHaveCount(1);
  expect(state.characterChatAttempts).toBe(2);
  expect(new Set(state.characterChatRequestIds).size).toBe(1);
});
