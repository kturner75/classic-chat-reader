const { test, expect } = require('@playwright/test');

const LOCAL_BOOK = {
  id: 'load-perf-book-1',
  title: 'Initial Load Performance Book',
  author: 'Performance Tester',
  chapters: [
    { id: 'load-perf-ch-1', title: 'Chapter One' }
  ],
  ttsEnabled: false,
  illustrationEnabled: false,
  characterEnabled: false
};

const POPULAR_BOOK = {
  gutenbergId: 12345,
  title: 'Fast Popular Catalog Book',
  author: 'Catalog Tester',
  downloadCount: 5000,
  alreadyImported: false
};

const API_DELAYS_MS = {
  '/api/classroom/context': 20,
  '/api/features': 50,
  '/api/library': 250,
  '/api/import/popular': 350
};

const DEFAULT_LOADING_CLEAR_BUDGET_MS = 2_500;
const DEFAULT_POST_API_SETTLE_BUDGET_MS = 1_500;

function json(route, status, payload) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(payload)
  });
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function delayedJson(route, status, payload, delayMs, onComplete) {
  await delay(delayMs);
  await json(route, status, payload);
  onComplete?.();
}

async function installInitialLoadMocks(page, timings) {
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    if (method === 'GET' && path === '/api/classroom/context') {
      return delayedJson(route, 200, { enrolled: false }, API_DELAYS_MS[path], () => {
        timings.classroomContextCompleteAt = Date.now();
      });
    }
    if (method === 'GET' && path === '/api/library') {
      return delayedJson(route, 200, [LOCAL_BOOK], API_DELAYS_MS[path], () => {
        timings.libraryCompleteAt = Date.now();
      });
    }
    if (method === 'GET' && path === '/api/import/popular') {
      return delayedJson(route, 200, [POPULAR_BOOK], API_DELAYS_MS[path], () => {
        timings.popularCompleteAt = Date.now();
      });
    }
    if (method === 'GET' && path === '/api/features') {
      return delayedJson(route, 200, { speedReadingEnabled: false }, API_DELAYS_MS[path], () => {
        timings.featuresCompleteAt ??= Date.now();
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
    if (method === 'GET' && path === '/api/auth/status') {
      return json(route, 200, {
        publicMode: false,
        authRequired: false,
        authenticated: false,
        canAccessSensitive: true
      });
    }
    if (method === 'GET' && path.endsWith('/status')) {
      return json(route, 200, { enabled: false, available: false, cacheOnly: true });
    }

    return json(route, 404, {
      error: `Unhandled API route in initial-load performance mock: ${method} ${path}`
    });
  });
}

test('initial library load clears Loading library promptly after required data resolves', async ({ page }, testInfo) => {
  const timings = {};
  const loadingClearBudgetMs = Number.parseInt(
    process.env.INITIAL_LOAD_CLEAR_BUDGET_MS || `${DEFAULT_LOADING_CLEAR_BUDGET_MS}`,
    10
  );
  const postApiSettleBudgetMs = Number.parseInt(
    process.env.INITIAL_LOAD_POST_API_SETTLE_BUDGET_MS || `${DEFAULT_POST_API_SETTLE_BUDGET_MS}`,
    10
  );

  await installInitialLoadMocks(page, timings);

  timings.navigationStartAt = Date.now();
  await page.goto('/');

  await expect(page.locator('#library-search-status')).toContainText('Loading library');
  timings.loadingVisibleAt = Date.now();

  await expect(page.locator('#library-search-status')).toBeHidden({ timeout: loadingClearBudgetMs });
  timings.loadingClearedAt = Date.now();

  await expect(page.locator('#book-list .book-item')).toHaveCount(1);
  await expect(page.locator('#continue-reading-list .book-item')).toHaveCount(1);

  const maxRequiredApiCompleteAt = Math.max(
    timings.libraryCompleteAt,
    timings.popularCompleteAt,
    timings.featuresCompleteAt
  );

  const result = {
    apiDelaysMs: API_DELAYS_MS,
    budgetsMs: {
      loadingClear: loadingClearBudgetMs,
      postApiSettle: postApiSettleBudgetMs
    },
    measuredMs: {
      loadingVisibleAfterNavigation: timings.loadingVisibleAt - timings.navigationStartAt,
      loadingClearedAfterNavigation: timings.loadingClearedAt - timings.navigationStartAt,
      loadingVisibleDuration: timings.loadingClearedAt - timings.loadingVisibleAt,
      libraryApiDuration: timings.libraryCompleteAt - timings.navigationStartAt,
      popularApiDuration: timings.popularCompleteAt - timings.navigationStartAt,
      featuresApiDuration: timings.featuresCompleteAt - timings.navigationStartAt,
      renderSettledAfterRequiredApis: timings.loadingClearedAt - maxRequiredApiCompleteAt
    }
  };

  await testInfo.attach('initial-load-performance.json', {
    body: JSON.stringify(result, null, 2),
    contentType: 'application/json'
  });

  console.log(`initial-load-performance ${JSON.stringify(result)}`);

  expect(result.measuredMs.loadingVisibleDuration).toBeLessThanOrEqual(loadingClearBudgetMs);
  expect(result.measuredMs.renderSettledAfterRequiredApis).toBeLessThanOrEqual(postApiSettleBudgetMs);
});
