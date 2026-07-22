const { test, expect } = require('@playwright/test');

const TEST_BOOK = {
  id: 'bl050-book',
  title: 'BL-050 Pagination Fixture',
  author: 'QA Fixture',
  chapters: [
    { id: 'bl050-chapter-1', title: 'Chapter One' },
    { id: 'bl050-chapter-2', title: 'Chapter Two' }
  ],
  ttsEnabled: false,
  illustrationEnabled: false,
  characterEnabled: false
};

const SENTENCES = [
  'The quick brown fox moves quietly through the old garden while the evening light settles over the stone wall.',
  'Readers need every line to remain visible when typography changes, even when a paragraph lands close to the bottom edge.',
  'A careful observer records the shape of each page before moving onward to the next passage in the chapter.',
  'This deliberately varied prose creates paragraphs of different lengths so pagination boundaries exercise realistic wrapping.'
];

const PARAGRAPHS = Array.from({ length: 90 }, (_, index) => ({
  content: Array.from({ length: 2 + (index % 7) }, (__, sentenceIndex) =>
    SENTENCES[(index + sentenceIndex) % SENTENCES.length]
  ).join(' ')
}));

function json(route, status, payload) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(payload)
  });
}

async function installApiMocks(page) {
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
    if (method === 'GET' && path === '/api/library/bl050-book/chapters/bl050-chapter-1') {
      return json(route, 200, { chapterId: 'bl050-chapter-1', paragraphs: PARAGRAPHS });
    }
    if (method === 'GET' && path === '/api/library/bl050-book/chapters/bl050-chapter-2') {
      return json(route, 200, { chapterId: 'bl050-chapter-2', paragraphs: PARAGRAPHS.slice().reverse() });
    }
    if (method === 'GET' && (path.endsWith('/annotations') || path.endsWith('/bookmarks'))) {
      return json(route, 200, []);
    }
    if (method === 'GET' && path.endsWith('/status')) {
      return json(route, 200, { enabled: false, available: false, cacheOnly: true });
    }
    if (method === 'POST') {
      return json(route, 202, {});
    }

    return json(route, 404, { error: `Unhandled BL-050 fixture route: ${method} ${path}` });
  });
}

async function openFixture(page) {
  await page.goto('/');
  await expect(page.locator('#library-search-status')).toBeHidden();
  await page.click('#continue-reading-list .book-item[data-book-id="bl050-book"]');
  await expect(page.locator('#reader-view')).toBeVisible();
  await expect(page.locator('#column-left .paragraph').first()).toBeVisible();
}

async function setFontSize(page, fontSize) {
  await page.locator('#reader-font-size').evaluate((input, value) => {
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }, fontSize.toFixed(2));
  await expect(page.locator('#reader-font-size-value')).toHaveText(`${fontSize.toFixed(2)}rem`);
}

async function clippedParagraphs(page) {
  return page.evaluate(() => {
    const tolerance = 0.5;
    return ['column-left', 'column-right'].flatMap((id) => {
      const column = document.getElementById(id);
      const columnBottom = column.getBoundingClientRect().bottom;
      return [...column.querySelectorAll('.paragraph')]
        .filter((paragraph) => paragraph.getBoundingClientRect().bottom > columnBottom + tolerance)
        .map((paragraph) => ({
          column: id,
          index: Number(paragraph.dataset.index),
          clippedBy: Number((paragraph.getBoundingClientRect().bottom - columnBottom).toFixed(2))
        }));
    });
  });
}

async function readRenderedChapterText(page, nextSelector) {
  const indicator = await page.locator('#page-indicator').textContent();
  const pageCount = Number(indicator.match(/of (\d+)/)?.[1] || 0);
  const fragmentsByParagraph = new Map();

  for (let pageNumber = 0; pageNumber < pageCount; pageNumber += 1) {
    const fragments = await page.locator('.column .paragraph').evaluateAll((paragraphs) =>
      paragraphs.map((paragraph) => ({
        index: Number(paragraph.dataset.index),
        text: paragraph.textContent
      }))
    );
    for (const fragment of fragments) {
      fragmentsByParagraph.set(
        fragment.index,
        `${fragmentsByParagraph.get(fragment.index) || ''} ${fragment.text}`.trim()
      );
    }
    if (pageNumber < pageCount - 1) {
      await page.click(nextSelector);
    }
  }

  return [...fragmentsByParagraph.entries()]
    .sort(([left], [right]) => left - right)
    .map(([, text]) => text.replace(/\s+/g, ' ').trim());
}

for (const viewport of [
  { name: 'desktop', width: 1440, height: 900, next: '#gutter-right' },
  { name: 'laptop', width: 1280, height: 720, next: '#gutter-right' },
  { name: 'tablet', width: 834, height: 1112, next: '#mobile-next-page', isMobile: true, hasTouch: true }
]) {
  test(`BL-050 font changes repaginate without clipping or reload at ${viewport.name}`, async ({ browser }) => {
    const contextOptions = {
      viewport: { width: viewport.width, height: viewport.height },
      hasTouch: viewport.hasTouch || false
    };
    if (browser.browserType().name() !== 'firefox') {
      contextOptions.isMobile = viewport.isMobile || false;
    }
    const context = await browser.newContext(contextOptions);
    const page = await context.newPage();
    await installApiMocks(page);
    await openFixture(page);

    for (const fontSize of [1.00, 1.10, 1.20, 1.30, 1.40, 1.50]) {
      await setFontSize(page, fontSize);
      await expect.poll(() => clippedParagraphs(page)).toEqual([]);

      for (let pageNumber = 0; pageNumber < 4; pageNumber += 1) {
        await page.click(viewport.next);
        await expect.poll(() => clippedParagraphs(page)).toEqual([]);
      }
    }

    const preferences = await page.evaluate(() => localStorage.getItem('reader_readerPreferences'));
    await page.evaluate((savedPreferences) => {
      localStorage.clear();
      localStorage.setItem('reader_readerPreferences', savedPreferences);
    }, preferences);
    await page.reload();
    await expect(page.locator('#library-search-status')).toBeHidden();
    await page.click('#continue-reading-list .book-item[data-book-id="bl050-book"]');
    await expect(page.locator('#column-left .paragraph').first()).toBeVisible();
    await expect.poll(() => clippedParagraphs(page)).toEqual([]);
    if (viewport.name === 'laptop') {
      const renderedParagraphs = await readRenderedChapterText(page, viewport.next);
      expect(renderedParagraphs).toEqual(PARAGRAPHS.map((paragraph) => paragraph.content));
    }
    await context.close();
  });
}
