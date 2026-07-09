const { test, expect } = require('@playwright/test');

/**
 * Reading Buddy smoke E2E (PR 6 gate).
 * Static server + API mocks — no live LLM required.
 * Covers: status-gated settings, toggle, Talk modal open/close, optional toast path.
 */

const LONG_PARA_A =
  'It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.';
const LONG_PARA_B =
  'However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed.';
const LONG_PARA_C =
  'My dear Mr. Bennet, said his lady to him one day, have you heard that Netherfield Park is let at last?';

const TEST_BOOK = {
  id: 'book-buddy-1',
  title: 'Reading Buddy Smoke Book',
  author: 'Jane Tester',
  chapters: [
    { id: 'ch-1', title: 'Chapter One' },
    { id: 'ch-2', title: 'Chapter Two' }
  ],
  ttsEnabled: false,
  illustrationEnabled: false,
  characterEnabled: false
};

const PERSONAS = [
  {
    id: 'historian',
    displayName: 'The Archivist',
    shortBlurb: 'Historic context without spoilers.',
    toneTags: ['informative'],
    portraitUrl: '/images/buddies/historian.png'
  },
  {
    id: 'humorist',
    displayName: 'The Peanut Gallery',
    shortBlurb: 'School-safe light wit.',
    toneTags: ['witty'],
    portraitUrl: '/images/buddies/humorist.png'
  }
];

function json(route, status, payload) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(payload)
  });
}

async function installBuddyApiMocks(page, options = {}) {
  const state = {
    prefs: {
      enabled: false,
      frequency: 'rare',
      defaultPersonaId: 'historian',
      personaId: 'historian',
      personaSource: 'global',
      suppressUntilEpochMs: null,
      bookId: null
    },
    checkCommentCalls: 0,
    forceCommentOnCheck: options.forceCommentOnCheck !== false
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
        accountAuthEnabled: false,
        authenticated: false,
        email: null,
        rolloutMode: 'disabled',
        accountRequired: false
      });
    }
    if (method === 'GET' && path === '/api/classroom/context') {
      return json(route, 200, { enrolled: false });
    }
    if (method === 'GET' && path === '/api/library') {
      return json(route, 200, [TEST_BOOK]);
    }
    if (method === 'GET' && path === '/api/import/popular') {
      return json(route, 200, []);
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
        enabled: false,
        cacheOnly: false,
        chatEnabled: false,
        chatProviderAvailable: false
      });
    }
    if (method === 'GET' && path === '/api/recaps/status') {
      return json(route, 200, {
        enabled: false,
        reasoningEnabled: false,
        available: false,
        cacheOnly: false,
        chatEnabled: false,
        chatProviderAvailable: false
      });
    }
    if (method === 'GET' && path === `/api/recaps/book/${TEST_BOOK.id}/status`) {
      return json(route, 200, {
        enabled: false,
        reasoningEnabled: false,
        available: false,
        cacheOnly: false,
        chatEnabled: false,
        chatProviderAvailable: false
      });
    }
    if (method === 'GET' && path === '/api/quizzes/status') {
      return json(route, 200, {
        enabled: false,
        reasoningEnabled: false,
        available: false,
        cacheOnly: false,
        generationAvailable: false
      });
    }
    if (method === 'GET' && path === `/api/quizzes/book/${TEST_BOOK.id}/status`) {
      return json(route, 200, {
        enabled: false,
        reasoningEnabled: false,
        available: false,
        cacheOnly: false,
        generationAvailable: false
      });
    }
    if (method === 'GET' && path === `/api/library/${TEST_BOOK.id}/annotations`) {
      return json(route, 200, []);
    }
    if (method === 'GET' && path === `/api/library/${TEST_BOOK.id}/bookmarks`) {
      return json(route, 200, []);
    }
    if (method === 'GET' && path === `/api/library/${TEST_BOOK.id}/chapters/ch-1`) {
      return json(route, 200, {
        chapterId: 'ch-1',
        paragraphs: [
          { content: LONG_PARA_A },
          { content: LONG_PARA_B },
          { content: LONG_PARA_C }
        ]
      });
    }
    if (method === 'GET' && path === `/api/library/${TEST_BOOK.id}/chapters/ch-2`) {
      return json(route, 200, {
        chapterId: 'ch-2',
        paragraphs: [{ content: LONG_PARA_A }]
      });
    }
    if (method === 'POST' && path.startsWith('/api/recaps/')) {
      return json(route, 202, {});
    }
    if (method === 'POST' && path.startsWith('/api/quizzes/')) {
      return json(route, 202, {});
    }

    // --- Reading buddy ---
    if (method === 'GET' && path === '/api/reading-buddy/status') {
      return json(route, 200, {
        enabled: true,
        chatEnabled: true,
        providerAvailable: true,
        available: true,
        quietDefaultMinutes: 45
      });
    }
    if (method === 'GET' && path === '/api/reading-buddy/personas') {
      return json(route, 200, PERSONAS);
    }
    if (method === 'GET' && path === '/api/reading-buddy/preferences') {
      return json(route, 200, { ...state.prefs, bookId: TEST_BOOK.id });
    }
    if (method === 'PUT' && path === '/api/reading-buddy/preferences') {
      const body = request.postDataJSON() || {};
      if (typeof body.enabled === 'boolean') {
        state.prefs.enabled = body.enabled;
      }
      if (typeof body.frequency === 'string') {
        state.prefs.frequency = body.frequency;
      }
      if (typeof body.personaId === 'string') {
        state.prefs.personaId = body.personaId;
      }
      if (typeof body.defaultPersonaId === 'string') {
        state.prefs.defaultPersonaId = body.defaultPersonaId;
      }
      if (typeof body.quietMinutes === 'number') {
        state.prefs.suppressUntilEpochMs = Date.now() + body.quietMinutes * 60 * 1000;
      }
      if (body.bookId) {
        state.prefs.bookId = body.bookId;
      }
      return json(route, 200, { ...state.prefs });
    }
    if (method === 'GET' && path === '/api/reading-buddy/history') {
      return json(route, 200, {
        bookId: TEST_BOOK.id,
        personaId: state.prefs.personaId || 'historian',
        messages: []
      });
    }
    if (method === 'POST' && path === '/api/reading-buddy/check-comment') {
      state.checkCommentCalls += 1;
      if (state.forceCommentOnCheck) {
        return json(route, 200, {
          action: 'COMMENT',
          text: 'A quiet aside on the neighbourhood’s curiosity — nothing later in the plot.',
          messageId: 'proactive-1',
          personaId: state.prefs.personaId || 'historian',
          portraitUrl: '/images/buddies/historian.png',
          chapterIndex: 0,
          paragraphIndex: 1,
          nextEligibleAfterMs: 60_000
        });
      }
      return json(route, 200, {
        action: 'SILENCE',
        reason: 'DECIDED_NONE',
        nextEligibleAfterMs: 30_000
      });
    }
    if (method === 'POST' && path === '/api/reading-buddy/chat') {
      return json(route, 200, {
        response: 'I only know what is on the page so far.',
        personaId: state.prefs.personaId || 'historian',
        messageId: 'buddy-msg-1',
        userMessageId: 'user-msg-1',
        timestamp: Date.now()
      });
    }

    return json(route, 404, {
      error: `Unhandled API route in e2e mock: ${method} ${path}`
    });
  });

  return state;
}

async function openReaderForBuddyBook(page) {
  await page.goto('/');
  await page.click(`#continue-reading-list .book-item[data-book-id="${TEST_BOOK.id}"], .book-item[data-book-id="${TEST_BOOK.id}"]`);
  await expect(page.locator('#reader-view')).toBeVisible();
  await expect(page.locator('#book-title')).toHaveText(TEST_BOOK.title);
}

async function openReaderSettings(page) {
  await page.click('#reader-settings-toggle');
  await expect(page.locator('#reader-settings-panel')).toBeVisible();
}

test('reading buddy toggle enables Talk; modal opens and closes', async ({ page }) => {
  await installBuddyApiMocks(page, { forceCommentOnCheck: false });
  await openReaderForBuddyBook(page);

  await openReaderSettings(page);
  await expect(page.locator('#reading-buddy-settings')).toBeVisible();
  await expect(page.locator('#reading-buddy-toggle')).toBeVisible();
  await expect(page.locator('#reading-buddy-talk-btn')).toBeDisabled();

  await page.check('#reading-buddy-toggle');
  await expect(page.locator('#reading-buddy-toggle')).toBeChecked();
  await expect(page.locator('#reading-buddy-talk-btn')).toBeEnabled();

  // Persona cards render from mocked catalog.
  await expect(page.locator('.reading-buddy-persona-card').first()).toBeVisible();

  await page.click('#reading-buddy-talk-btn');
  await expect(page.locator('#reading-buddy-chat-modal')).toBeVisible();
  await expect(page.locator('#reading-buddy-chat-name')).toContainText(/Archivist|Peanut|Companion|Marginalian|Reading Buddy/);

  await page.click('#reading-buddy-chat-close');
  await expect(page.locator('#reading-buddy-chat-modal')).toBeHidden();
});

test('reading buddy toast path (mocked check-comment) can open modal', async ({ page }) => {
  const apiState = await installBuddyApiMocks(page, { forceCommentOnCheck: true });
  await openReaderForBuddyBook(page);

  await openReaderSettings(page);
  await page.selectOption('#reading-buddy-frequency', 'chatty');
  await page.check('#reading-buddy-toggle');
  await expect(page.locator('#reading-buddy-talk-btn')).toBeEnabled();

  // Close settings so focused-modal gate is clear.
  await page.keyboard.press('Escape');
  await expect(page.locator('#reader-settings-panel')).toBeHidden();

  // Advance paragraphs (j) so client gates sample; dwell is 800ms.
  await page.keyboard.press('j');
  await page.waitForTimeout(1000);
  await page.keyboard.press('j');
  await page.waitForTimeout(1000);

  // Toast may appear after a successful check-comment COMMENT.
  const toast = page.locator('#reading-buddy-toast');
  await expect
    .poll(async () => {
      const hidden = await toast.evaluate((el) => el.classList.contains('hidden'));
      return !hidden || apiState.checkCommentCalls > 0;
    }, { timeout: 8000 })
    .toBeTruthy();

  if (await toast.evaluate((el) => !el.classList.contains('hidden'))) {
    await expect(page.locator('#reading-buddy-toast-preview')).not.toBeEmpty();
    await page.click('#reading-buddy-toast-open');
    await expect(page.locator('#reading-buddy-chat-modal')).toBeVisible();
    await page.click('#reading-buddy-chat-close');
    await expect(page.locator('#reading-buddy-chat-modal')).toBeHidden();
  } else {
    // Fallback: Talk path still exercises modal if client gates suppressed toast.
    await openReaderSettings(page);
    await page.click('#reading-buddy-talk-btn');
    await expect(page.locator('#reading-buddy-chat-modal')).toBeVisible();
    await page.click('#reading-buddy-chat-close');
    await expect(page.locator('#reading-buddy-chat-modal')).toBeHidden();
  }
});
