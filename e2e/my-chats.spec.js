const { test, expect } = require('@playwright/test');

const SESSION_ONE = {
  sessionId: 'session-1',
  character: { id: 'character-1', name: 'Elizabeth Bennet', portraitUrl: null },
  book: { id: 'book-1', title: 'Pride and Prejudice', author: 'Jane Austen' },
  previewText: 'First impressions can be misleading.',
  previewRole: 'CHARACTER',
  messageCount: 2,
  createdAt: '2026-07-20T12:00:00Z',
  lastMessageAt: '2026-07-21T12:00:00Z',
  updatedAt: '2026-07-21T12:00:00Z',
  context: { chapterId: 'chapter-1', chapterIndex: 0, chapterTitle: 'Chapter One', paragraphIndex: 4 },
  resume: { available: true, url: '/my-chats?session=session-1', unavailableReason: null }
};

const SESSION_TWO = {
  ...SESSION_ONE,
  sessionId: 'session-2',
  character: { id: 'character-2', name: 'Mr. Darcy', portraitUrl: null },
  previewText: 'My good opinion once lost is lost forever.',
  lastMessageAt: '2026-07-20T12:00:00Z',
  resume: { available: true, url: '/my-chats?session=session-2', unavailableReason: null }
};

const UNAVAILABLE_SESSION = {
  ...SESSION_ONE,
  sessionId: 'session-unavailable',
  character: { id: 'character-gone', name: 'Archived Character', portraitUrl: null },
  resume: { available: false, url: '/my-chats?session=session-unavailable', unavailableReason: 'CHARACTER_UNAVAILABLE' }
};

function json(route, status, payload) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(payload) });
}

async function installMocks(page, options = {}) {
  const state = {
    listRequests: [],
    detailRequests: [],
    continueRequests: [],
    failList: options.failList === true,
    empty: options.empty === true,
    unavailable: options.unavailable === true
  };

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    if (method === 'GET' && path === '/api/account/status') {
      return json(route, 200, {
        accountAuthEnabled: true,
        authenticated: true,
        email: 'reader@example.com',
        googleAuthEnabled: false,
        rolloutMode: 'optional',
        accountRequired: false
      });
    }
    if (method === 'GET' && path === '/api/account/chats') {
      state.listRequests.push(url.toString());
      if (state.failList) return json(route, 503, { error: { message: 'History service unavailable.' } });
      if (state.empty) return json(route, 200, { items: [], page: { limit: 20, nextCursor: null, hasMore: false } });
      if (state.unavailable) {
        return json(route, 200, { items: [UNAVAILABLE_SESSION], page: { limit: 20, nextCursor: null, hasMore: false } });
      }
      if (url.searchParams.get('cursor') === 'page-2') {
        return json(route, 200, {
          items: [{ ...SESSION_ONE, sessionId: 'session-3', previewText: 'Third session', resume: { ...SESSION_ONE.resume, url: '/my-chats?session=session-3' } }],
          page: { limit: 20, nextCursor: null, hasMore: false }
        });
      }
      return json(route, 200, {
        items: [SESSION_ONE, SESSION_TWO],
        page: { limit: 20, nextCursor: 'page-2', hasMore: true }
      });
    }
    if (method === 'GET' && path.startsWith('/api/account/chats/')) {
      const sessionId = decodeURIComponent(path.split('/').pop());
      state.detailRequests.push(sessionId);
      if (sessionId === 'foreign-session') {
        return json(route, 404, { error: { code: 'CHAT_NOT_FOUND', message: 'Chat session not found.' } });
      }
      const session = sessionId === 'session-2' ? SESSION_TWO : { ...SESSION_ONE, sessionId };
      return json(route, 200, {
        session,
        messages: [
          { messageId: 'message-1', role: 'USER', content: 'Hello', createdAt: '2026-07-21T11:59:00Z' },
          { messageId: 'message-2', role: 'CHARACTER', content: session.previewText, createdAt: '2026-07-21T12:00:00Z' }
        ]
      });
    }
    if (method === 'POST' && /^\/api\/account\/chats\/[^/]+\/messages$/.test(path)) {
      const sessionId = decodeURIComponent(path.split('/')[4]);
      const body = request.postDataJSON();
      state.continueRequests.push({ sessionId, body });
      return json(route, 200, {
        userMessage: { messageId: 'message-3', role: 'USER', content: body.content, createdAt: '2026-07-21T12:01:00Z' },
        characterMessage: { messageId: 'message-4', role: 'CHARACTER', content: 'I remember this conversation.', createdAt: '2026-07-21T12:01:01Z' },
        context: SESSION_ONE.context,
        lastMessageAt: '2026-07-21T12:01:01Z'
      });
    }

    // Baseline reader-page dependencies used by the landing-page portion of this flow.
    if (method === 'GET' && path === '/api/library') return json(route, 200, []);
    if (method === 'GET' && path === '/api/import/popular') return json(route, 200, []);
    if (method === 'GET' && path === '/api/classroom/context') return json(route, 200, { enrolled: false });
    if (method === 'GET' && path === '/api/classroom/capabilities') return json(route, 200, { canTeach: false, canCreateClass: false });
    if (method === 'GET' && path === '/api/features') return json(route, 200, { speedReadingEnabled: false });
    if (method === 'GET' && path === '/api/auth/status') return json(route, 200, { publicMode: false, authRequired: false, authenticated: false, canAccessSensitive: true });
    if (method === 'GET' && path === '/api/tts/status') return json(route, 200, { openaiConfigured: false, cachedAvailable: false, cacheOnly: false });
    if (method === 'GET' && path === '/api/illustrations/status') return json(route, 200, { comfyuiAvailable: false, ollamaAvailable: false, cacheOnly: false });
    if (method === 'GET' && path === '/api/characters/status') return json(route, 200, { enabled: false, chatEnabled: false, chatProviderAvailable: false });
    if (method === 'GET' && path === '/api/recaps/status') return json(route, 200, { enabled: false, available: false, chatEnabled: false });
    if (method === 'GET' && path === '/api/quizzes/status') return json(route, 200, { enabled: false, available: false });
    if (method === 'GET' && path === '/api/reading-buddy/status') return json(route, 200, { enabled: false, available: false });
    return json(route, 404, { error: { message: `Unhandled test route: ${method} ${path}` } });
  });

  return state;
}

test('signed-in landing shelf opens full history, filters, loads more, and resumes the selected existing session', async ({ page }) => {
  const state = await installMocks(page);

  await page.goto('/');
  await expect(page.locator('#my-chats-shelf')).toBeVisible();
  await expect(page.locator('#my-chats-list .my-chat-card')).toHaveCount(2);
  await expect(page.locator('#my-chats-list')).toContainText('Elizabeth Bennet');
  await expect(page.locator('#my-chats-list')).toContainText('First impressions can be misleading.');
  await page.locator('#my-chats-view-all').click();

  await expect(page).toHaveURL(/\/my-chats$/);
  await expect(page.locator('#my-chats-list .my-chat-card')).toHaveCount(2);
  await page.locator('#my-chats-load-more').click();
  await expect(page.locator('#my-chats-list .my-chat-card')).toHaveCount(3);

  await page.locator('#my-chats-search').fill('impressions');
  await page.locator('#my-chats-book').fill('Pride and Prejudice');
  await page.locator('#my-chats-book').dispatchEvent('change');
  await page.locator('#my-chats-character').fill('Elizabeth Bennet');
  await page.locator('#my-chats-character').dispatchEvent('change');
  await page.locator('#my-chats-after').fill('2026-07-01');
  await page.locator('#my-chats-before').fill('2026-07-31');
  await page.locator('#my-chats-filters button[type="submit"]').click();

  await expect.poll(() => state.listRequests.some((requestUrl) => {
    const url = new URL(requestUrl);
    return url.searchParams.get('q') === 'impressions'
      && url.searchParams.get('bookId') === 'book-1'
      && url.searchParams.get('characterId') === 'character-1'
      && url.searchParams.has('activeAfter')
      && url.searchParams.has('activeBefore');
  })).toBe(true);

  await page.locator('#my-chats-clear').click();
  await page.locator('.my-chat-card', { hasText: 'Elizabeth Bennet' }).getByRole('link', { name: 'Resume chat' }).click();
  await expect(page).toHaveURL(/\/my-chats\?session=session-1$/);
  await expect(page.locator('#my-chat-conversation-title')).toHaveText('Elizabeth Bennet');
  await expect(page.locator('#my-chat-messages')).toContainText('First impressions can be misleading.');

  await page.locator('#my-chat-composer').fill('Do you remember me?');
  await page.locator('#my-chat-send').click();
  await expect(page.locator('#my-chat-messages')).toContainText('I remember this conversation.');
  expect(state.detailRequests).toContain('session-1');
  expect(state.continueRequests).toEqual([{ sessionId: 'session-1', body: { content: 'Do you remember me?', context: SESSION_ONE.context } }]);
});

test('empty and retryable API failure states remain usable', async ({ page }) => {
  const state = await installMocks(page, { empty: true });
  await page.goto('/my-chats');
  await expect(page.locator('#my-chats-state')).toContainText('You haven’t started any character chats yet.');

  state.empty = false;
  state.failList = true;
  await page.reload();
  await expect(page.locator('#my-chats-state')).toContainText('History service unavailable.');
  state.failList = false;
  await page.getByRole('button', { name: 'Retry' }).click();
  await expect(page.locator('#my-chats-list .my-chat-card')).toHaveCount(2);
});

test('unavailable characters are readable but cannot be resumed on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installMocks(page, { unavailable: true });
  await page.goto('/my-chats');

  const card = page.locator('#my-chats-list .my-chat-card');
  await expect(card).toHaveCount(1);
  await expect(card).toContainText('This character is no longer available.');
  await expect(card.getByRole('link', { name: 'Resume chat' })).toHaveCount(0);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
  expect(overflow).toBe(false);
  await expect(page.locator('#my-chats-search')).toHaveAttribute('type', 'search');
  await expect(page.locator('#my-chats-status')).toHaveAttribute('aria-live', 'polite');
});

test('another user’s session is indistinguishable from a missing session', async ({ page }) => {
  const state = await installMocks(page);
  await page.goto('/my-chats?session=foreign-session');

  await expect(page.locator('#my-chat-conversation-error')).toContainText('Chat session not found.');
  await expect(page.locator('#my-chat-send-form')).toBeHidden();
  expect(state.detailRequests).toEqual(['foreign-session']);
  expect(state.continueRequests).toEqual([]);
});
