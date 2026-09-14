const { test, expect } = require('@playwright/test');

const TEST_BOOK = {
  id: 'bookmark-toggle-book',
  title: 'Bookmark Toggle Fixture',
  author: 'QA Fixture',
  chapters: [
    { id: 'bookmark-chapter-1', title: 'Chapter One' }
  ],
  ttsEnabled: false,
  illustrationEnabled: false,
  characterEnabled: false
};

const PARAGRAPHS = [
  { content: 'A first paragraph is already selected so a bookmark toggle has a current target.' },
  { content: 'A second paragraph keeps the chapter from being a single-block edge case.' }
];

function json(route, status, payload) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(payload)
  });
}

async function installBookmarkApiMocks(page, options = {}) {
  const putDelayMs = options.putDelayMs || 0;
  const failPuts = options.failPuts === true;
  const state = {
    annotations: [],
    bookmarks: [],
    putCalls: [],
    putStartedCount: 0
  };

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();

    if (method === 'GET' && path === '/api/auth/status') {
      return json(route, 200, { publicMode: false, authRequired: false, authenticated: false, canAccessSensitive: true });
    }
    if (method === 'GET' && path === '/api/account/status') {
      return json(route, 200, { accountAuthEnabled: false, authenticated: false, rolloutMode: 'disabled', accountRequired: false });
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
    if (method === 'GET' && path === `/api/library/${TEST_BOOK.id}/chapters/bookmark-chapter-1`) {
      return json(route, 200, { chapterId: 'bookmark-chapter-1', paragraphs: PARAGRAPHS });
    }
    if (method === 'GET' && path === `/api/library/${TEST_BOOK.id}/annotations`) {
      return json(route, 200, state.annotations);
    }
    if (method === 'GET' && path === `/api/library/${TEST_BOOK.id}/bookmarks`) {
      return json(route, 200, state.bookmarks);
    }
    if (method === 'PUT' && path.startsWith(`/api/library/${TEST_BOOK.id}/annotations/`)) {
      const parts = path.split('/');
      const chapterId = parts[parts.length - 2];
      const paragraphIndex = Number(parts[parts.length - 1]);
      const body = request.postDataJSON() || {};
      state.putStartedCount += 1;
      state.putCalls.push({ chapterId, paragraphIndex, body });
      if (putDelayMs > 0) {
        await new Promise((resolve) => setTimeout(resolve, putDelayMs));
      }
      if (failPuts) {
        return json(route, 500, { error: 'bookmark save failed' });
      }

      const annotation = {
        chapterId,
        paragraphIndex,
        highlighted: !!body.highlighted,
        noteText: body.noteText || '',
        bookmarked: !!body.bookmarked,
        updatedAt: null
      };
      if (annotation.bookmarked || annotation.highlighted || annotation.noteText.trim()) {
        state.annotations = [annotation];
        state.bookmarks = annotation.bookmarked
          ? [{
              chapterId,
              chapterTitle: 'Chapter One',
              paragraphIndex,
              snippet: PARAGRAPHS[paragraphIndex]?.content || '',
              updatedAt: null
            }]
          : [];
        return json(route, 200, annotation);
      }
      state.annotations = [];
      state.bookmarks = [];
      return route.fulfill({ status: 204, body: '' });
    }
    if (method === 'GET' && path.endsWith('/status')) {
      return json(route, 200, { enabled: false, available: false, cacheOnly: true });
    }
    if (method === 'POST') {
      return json(route, 202, {});
    }
    return json(route, 404, { error: `Unhandled bookmark fixture route: ${method} ${path}` });
  });

  return state;
}

async function openBookmarkFixture(page) {
  await page.goto('/');
  await page.click(`#continue-reading-list .book-item[data-book-id="${TEST_BOOK.id}"], .book-item[data-book-id="${TEST_BOOK.id}"]`);
  await expect(page.locator('#reader-view')).toBeVisible();
  await expect(page.locator('#column-left .paragraph').first()).toBeVisible();
  await page.locator('#column-left').click({ position: { x: 8, y: 8 } }).catch(() => {});
}

test('rapid bookmark toggles ignore re-entry until the in-flight PUT settles', async ({ page }) => {
  const api = await installBookmarkApiMocks(page, { putDelayMs: 400 });
  await openBookmarkFixture(page);

  await page.keyboard.press('b');
  await page.keyboard.press('b');
  await expect.poll(() => api.putStartedCount).toBe(1);

  await expect(page.locator('.app-toast-message')).toHaveText('Bookmark added.');
  await expect(page.locator('.app-toast-message')).toHaveCount(1);
  await expect(page.locator('.paragraph.highlighted .paragraph-bookmark, .paragraph[data-index="0"] .paragraph-bookmark').first()).toBeVisible();
  expect(api.putCalls).toHaveLength(1);
  expect(api.putCalls[0].body.bookmarked).toBe(true);

  await expect(page.locator('.app-toast')).toHaveCount(1);
  await page.keyboard.press('b');
  await expect.poll(() => api.putCalls.length).toBe(2);
  await expect(page.locator('.app-toast-message').last()).toHaveText('Bookmark removed.');
  await expect(page.locator('.paragraph-bookmark')).toHaveCount(0);
  expect(api.putCalls[1].body.bookmarked).toBe(false);
});

test('failed bookmark save keeps the gutter empty and shows one error toast', async ({ page }) => {
  const api = await installBookmarkApiMocks(page, { failPuts: true });
  await openBookmarkFixture(page);

  await page.keyboard.press('b');
  await expect.poll(() => api.putCalls.length).toBe(1);
  await expect(page.locator('.app-toast-message')).toHaveText('Could not update the bookmark. Please try again.');
  await expect(page.locator('.paragraph-bookmark')).toHaveCount(0);
});
