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
  await page.evaluate(() => document.fonts.ready);
  await expect(page.locator('#library-search-status')).toBeHidden();
  await page.click('#continue-reading-list .book-item[data-book-id="bl050-book"]');
  await expect(page.locator('#reader-view')).toBeVisible();
  await expect(page.locator('#column-left .paragraph').first()).toBeVisible();
}

async function setFontSize(page, fontSize) {
  const input = page.locator('#reader-font-size');
  if (!await input.isVisible()) {
    if (await page.locator('#reader-settings-toggle').isVisible()) {
      await page.click('#reader-settings-toggle');
    } else {
      await page.click('#mobile-header-menu-toggle');
      await page.click('#mobile-menu-reader-settings');
    }
    await expect(page.locator('#reader-settings-panel')).toBeVisible();
  }
  const stepsFromCurrent = await input.evaluate((element, value) =>
    Math.round((value - Number(element.value)) / Number(element.step)), fontSize
  );
  await input.focus();
  const direction = stepsFromCurrent < 0 ? 'ArrowLeft' : 'ArrowRight';
  for (let step = 0; step < Math.abs(stepsFromCurrent); step += 1) {
    await input.press(direction);
  }
  await expect.poll(async () => Number(await input.inputValue())).toBe(fontSize);
  await expect(page.locator('#reader-font-size-value')).toHaveText(`${fontSize.toFixed(2)}rem`);
  await expect.poll(() => page.evaluate(() =>
    JSON.parse(localStorage.getItem('reader_readerPreferences')).fontSize
  )).toBe(fontSize);
  await page.keyboard.press('Escape');
  await expect(page.locator('#reader-settings-panel')).toBeHidden();
}

async function supportedFontSizes(page) {
  return page.locator('#reader-font-size').evaluate((input) => {
    const minimum = Number(input.min);
    const maximum = Number(input.max);
    const step = Number(input.step);
    const values = [];
    for (let value = minimum; value <= maximum + (step / 2); value += step) {
      values.push(Number(value.toFixed(2)));
    }
    return values;
  });
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

    const fontSizes = await supportedFontSizes(page);
    expect(fontSizes).toEqual([1.00, 1.05, 1.10, 1.15, 1.20, 1.25, 1.30, 1.35, 1.40, 1.45, 1.50]);
    for (const fontSize of fontSizes) {
      await test.step(`${fontSize.toFixed(2)}rem remains selected and unclipped`, async () => {
        await setFontSize(page, fontSize);
        await expect.poll(() => clippedParagraphs(page)).toEqual([]);

        for (let pageNumber = 0; pageNumber < 4; pageNumber += 1) {
          await page.click(viewport.next);
          await expect.poll(() => clippedParagraphs(page)).toEqual([]);
        }
      });
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

test('BL-050 next paragraph keeps split final paragraph before chapter change', async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();

  const shortLead = {
    content: 'A short opening paragraph keeps the chapter non-empty before the tall final passage.'
  };
  const tallFinal = {
    content: Array.from({ length: 80 }, (_, index) =>
      `Continuation sentence ${index + 1} keeps this closing paragraph taller than one column so fragments spill across pages.`
    ).join(' ')
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
    if (method === 'GET' && path === '/api/library/bl050-book/chapters/bl050-chapter-1') {
      return json(route, 200, { chapterId: 'bl050-chapter-1', paragraphs: [shortLead, tallFinal] });
    }
    if (method === 'GET' && path === '/api/library/bl050-book/chapters/bl050-chapter-2') {
      return json(route, 200, {
        chapterId: 'bl050-chapter-2',
        paragraphs: [{ content: 'Chapter two proves the navigator left the split final paragraph too early.' }]
      });
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

  await openFixture(page);
  await setFontSize(page, 1.40);

  const initial = await page.evaluate(() => {
    const indicator = document.getElementById('page-indicator')?.textContent || '';
    const match = indicator.match(/Page (\d+) of (\d+)/);
    return {
      page: Number(match?.[1] || 0),
      totalPages: Number(match?.[2] || 0),
      chapterTitle: document.getElementById('chapter-title')?.textContent || '',
      highlightedIndex: Number(document.querySelector('.paragraph.highlighted')?.dataset.index ?? -1),
      visibleIndexes: [...document.querySelectorAll('.column .paragraph')].map((node) => Number(node.dataset.index))
    };
  });

  expect(initial.totalPages).toBeGreaterThan(1);
  expect(initial.visibleIndexes).toContain(1);

  // Jump highlight to the final paragraph without leaving its first fragment page.
  await page.locator('.column .paragraph[data-index="1"]').first().click();
  await expect.poll(async () =>
    page.locator('.paragraph.highlighted').first().getAttribute('data-index')
  ).toBe('1');

  const beforeNext = await page.locator('#page-indicator').textContent();
  await page.keyboard.press('j');

  const afterFirstJ = await page.evaluate(() => {
    const indicator = document.getElementById('page-indicator')?.textContent || '';
    const match = indicator.match(/Page (\d+) of (\d+)/);
    return {
      page: Number(match?.[1] || 0),
      totalPages: Number(match?.[2] || 0),
      chapterTitle: document.getElementById('chapter-title')?.textContent || '',
      highlightedIndex: Number(document.querySelector('.paragraph.highlighted')?.dataset.index ?? -1),
      visibleIndexes: [...document.querySelectorAll('.column .paragraph')].map((node) => Number(node.dataset.index)),
      indicator
    };
  });

  expect(afterFirstJ.chapterTitle).toBe(initial.chapterTitle);
  expect(afterFirstJ.highlightedIndex).toBe(1);
  expect(afterFirstJ.visibleIndexes).toContain(1);
  expect(afterFirstJ.page).toBeGreaterThan(Number(beforeNext.match(/Page (\d+)/)?.[1] || 0));
  expect(afterFirstJ.chapterTitle).toBe('Chapter One');

  // Exhaust remaining final-paragraph pages, then j should advance chapters.
  for (let guard = 0; guard < 40; guard += 1) {
    const chapterTitle = await page.locator('#chapter-title').textContent();
    if (chapterTitle === 'Chapter Two') break;
    await page.keyboard.press('j');
  }

  await expect.poll(async () => page.locator('#chapter-title').textContent()).toBe('Chapter Two');
  await context.close();
});

test('BL-050 next paragraph keeps split middle paragraph before advancing', async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();

  const shortLead = {
    content: 'A short opening paragraph keeps the chapter non-empty before the tall middle passage.'
  };
  const tallMiddle = {
    content: Array.from({ length: 160 }, (_, index) =>
      `Middle sentence ${index + 1} keeps this interior paragraph taller than one column so fragments spill across pages.`
    ).join(' ')
  };
  const shortTrail = {
    content: 'A short trailing paragraph proves navigation did not skip unread middle fragments.'
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
    if (method === 'GET' && path === '/api/library/bl050-book/chapters/bl050-chapter-1') {
      return json(route, 200, { chapterId: 'bl050-chapter-1', paragraphs: [shortLead, tallMiddle, shortTrail] });
    }
    if (method === 'GET' && path === '/api/library/bl050-book/chapters/bl050-chapter-2') {
      return json(route, 200, {
        chapterId: 'bl050-chapter-2',
        paragraphs: [{ content: 'Chapter two should not be reached while middle fragments remain.' }]
      });
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

  await openFixture(page);
  await setFontSize(page, 1.40);
  await page.locator('body').click({ position: { x: 8, y: 8 } });

  const initial = await page.evaluate(() => {
    const indicator = document.getElementById('page-indicator')?.textContent || '';
    const match = indicator.match(/Page (\d+) of (\d+)/);
    return {
      totalPages: Number(match?.[2] || 0),
      visibleIndexes: [...document.querySelectorAll('.column .paragraph')].map((node) => Number(node.dataset.index))
    };
  });
  expect(initial.totalPages).toBeGreaterThan(2);
  expect(initial.visibleIndexes).toContain(1);

  await page.locator('.column .paragraph[data-index="1"]').first().click();
  await expect.poll(async () =>
    page.locator('.paragraph.highlighted').first().getAttribute('data-index')
  ).toBe('1');

  const beforeNext = await page.locator('#page-indicator').textContent();
  await page.keyboard.press('j');

  const afterFirstJ = await page.evaluate(() => {
    const indicator = document.getElementById('page-indicator')?.textContent || '';
    const match = indicator.match(/Page (\d+) of (\d+)/);
    return {
      page: Number(match?.[1] || 0),
      highlightedIndex: Number(document.querySelector('.paragraph.highlighted')?.dataset.index ?? -1),
      visibleIndexes: [...document.querySelectorAll('.column .paragraph')].map((node) => Number(node.dataset.index)),
      chapterTitle: document.getElementById('chapter-title')?.textContent || '',
      activeId: document.activeElement?.id || document.activeElement?.tagName || ''
    };
  });

  expect(afterFirstJ.chapterTitle).toBe('Chapter One');
  expect(afterFirstJ.highlightedIndex).toBe(1);
  expect(afterFirstJ.visibleIndexes).toEqual([1, 1]);
  expect(afterFirstJ.page).toBeGreaterThan(Number(beforeNext.match(/Page (\d+)/)?.[1] || 0));

  // After exhausting middle fragments, j should land on the trailing paragraph.
  for (let guard = 0; guard < 80; guard += 1) {
    const highlighted = await page.locator('.paragraph.highlighted').first().getAttribute('data-index');
    if (highlighted === '2') break;
    await page.keyboard.press('j');
  }

  await expect.poll(async () =>
    page.locator('.paragraph.highlighted').first().getAttribute('data-index')
  ).toBe('2');
  await expect(page.locator('#chapter-title')).toHaveText('Chapter One');
  await context.close();
});
