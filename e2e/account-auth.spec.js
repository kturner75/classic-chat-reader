const { test, expect } = require('@playwright/test');

const TEST_BOOK = {
  id: 'book-1',
  title: 'Account Flow Test Book',
  author: 'Casey Reader',
  chapters: [
    { id: 'ch-1', title: 'Chapter One' }
  ],
  ttsEnabled: false,
  illustrationEnabled: false,
  characterEnabled: false
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
    accountEnabled: true,
    accountAuthenticated: false,
    accountEmail: null,
    claimSyncRequests: [],
    classroomContextRequests: []
  };

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    if (method === 'GET' && path === '/api/classroom/context') {
      state.classroomContextRequests.push({
        authenticated: state.accountAuthenticated,
        email: state.accountEmail
      });
      if (state.accountAuthenticated) {
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
            ttsEnabled: true,
            illustrationEnabled: true,
            characterEnabled: true,
            chatEnabled: true,
            speedReadingEnabled: true,
            readingBuddyEnabled: true,
            citationEnabled: true
          },
          assignments: [
            {
              assignmentId: 'asg-1',
              title: 'Pride and Prejudice Ch. 1',
              bookId: 'book-1',
              bookTitle: 'Account Flow Test Book',
              bookAuthor: 'Casey Reader',
              chapterId: 'ch-1',
              chapterIndex: 0,
              chapterTitle: 'Chapter One',
              dueAt: '2026-07-20',
              quizRequired: true,
              quizStatus: 'NOT_STARTED',
              bookAvailable: true
            }
          ]
        });
      }
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
    if (method === 'GET' && path === '/api/account/status') {
      return json(route, 200, {
        accountAuthEnabled: state.accountEnabled,
        authenticated: state.accountAuthenticated,
        email: state.accountEmail,
        rolloutMode: 'optional',
        accountRequired: false
      });
    }
    if (method === 'POST' && path === '/api/account/register') {
      const body = request.postDataJSON();
      state.accountAuthenticated = true;
      state.accountEmail = (body.email || '').toLowerCase();
      return json(route, 200, {
        accountAuthEnabled: true,
        authenticated: true,
        email: state.accountEmail,
        message: 'Account created.',
        rolloutMode: 'optional',
        accountRequired: false
      });
    }
    if (method === 'POST' && path === '/api/account/login') {
      const body = request.postDataJSON();
      state.accountAuthenticated = true;
      state.accountEmail = (body.email || '').toLowerCase();
      return json(route, 200, {
        accountAuthEnabled: true,
        authenticated: true,
        email: state.accountEmail,
        message: 'Signed in.',
        rolloutMode: 'optional',
        accountRequired: false
      });
    }
    if (method === 'POST' && path === '/api/account/logout') {
      state.accountAuthenticated = false;
      state.accountEmail = null;
      return json(route, 200, {
        accountAuthEnabled: true,
        authenticated: false,
        email: null,
        message: 'Signed out.',
        rolloutMode: 'optional',
        accountRequired: false
      });
    }
    if (method === 'POST' && path === '/api/account/claim-sync') {
      const body = request.postDataJSON();
      state.claimSyncRequests.push(body);
      return json(route, 200, {
        claimApplied: true,
        state: {
          favoriteBookIds: ['book-1'],
          bookActivity: body?.state?.bookActivity || {},
          readerPreferences: body?.state?.readerPreferences || null,
          recapOptOut: {
            'book-1': false
          }
        }
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
    if (method === 'GET' && path === '/api/quizzes/status') {
      return json(route, 200, {
        enabled: false,
        reasoningEnabled: false,
        available: false,
        cacheOnly: false,
        generationAvailable: false
      });
    }
    if (method === 'GET' && path === '/api/reading-buddy/status') {
      return json(route, 200, {
        available: false,
        enabled: false,
        providerAvailable: false
      });
    }

    return json(route, 404, {
      error: `Unhandled API route in account e2e mock: ${method} ${path}`
    });
  });

  return state;
}

test('account register/login/logout flow runs one-time claim sync for anonymous state', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('reader_favoriteBooks', JSON.stringify(['book-1']));
    localStorage.setItem('reader_bookActivity', JSON.stringify({
      'book-1': {
        chapterCount: 10,
        lastChapterIndex: 1,
        lastPage: 1,
        totalPages: 2,
        progressRatio: 0.5,
        maxProgressRatio: 0.5,
        completed: false,
        openCount: 3,
        lastOpenedAt: '2026-02-18T00:00:00Z',
        lastReadAt: '2026-02-18T00:00:00Z',
        completedAt: null
      }
    }));
    localStorage.setItem('reader_readerPreferences', JSON.stringify({
      fontSize: 1.2,
      lineHeight: 1.7,
      columnGap: 4,
      theme: 'warm'
    }));
    localStorage.setItem('reader_readerPreferencesUpdatedAt', '2026-02-18T00:00:00Z');
    localStorage.setItem('reader_recapOptOut_book-1', 'true');
  });

  const mockState = await installApiMocks(page);

  await page.goto('/');
  await expect(page.locator('#account-toggle-library')).toBeVisible();
  await page.click('#account-toggle-library');

  await page.fill('#account-email', 'reader@example.com');
  await page.fill('#account-password', 'password123');
  await page.click('#account-register');

  await expect.poll(() => mockState.claimSyncRequests.length).toBe(1);
  expect(mockState.claimSyncRequests[0]?.state?.favoriteBookIds).toEqual(['book-1']);

  await expect(page.locator('#account-library-status')).toContainText('Signed in as reader@example.com');
  const favoritesAfterRegister = await page.evaluate(() => localStorage.getItem('reader_favoriteBooks'));
  expect(favoritesAfterRegister).toBe(JSON.stringify(['book-1']));

  await page.click('#account-toggle-library');
  await expect(page.locator('#account-signout')).toBeVisible();
  await page.click('#account-signout');
  await expect(page.locator('#account-library-status')).toBeHidden();

  await page.click('#account-toggle-library');
  await page.fill('#account-email', 'reader@example.com');
  await page.fill('#account-password', 'password123');
  await page.click('#account-signin');

  await expect.poll(() => mockState.claimSyncRequests.length).toBe(2);
  await expect(page.locator('#account-library-status')).toContainText('Signed in as reader@example.com');
});

test('account login reloads classroom context and shows published assignment without hard refresh', async ({ page }) => {
  const mockState = await installApiMocks(page);

  await page.goto('/');
  await expect(page.locator('#account-toggle-library')).toBeVisible();

  // Initial anonymous page load should request classroom context once as not enrolled.
  await expect.poll(() => mockState.classroomContextRequests.length).toBeGreaterThanOrEqual(1);
  const anonymousRequests = mockState.classroomContextRequests.filter((req) => !req.authenticated);
  expect(anonymousRequests.length).toBeGreaterThanOrEqual(1);
  await expect(page.locator('#classroom-banner')).toBeHidden();

  await page.click('#account-toggle-library');
  await page.fill('#account-email', 'student@example.com');
  await page.fill('#account-password', 'password123');
  await page.click('#account-signin');

  await expect(page.locator('#account-library-status')).toContainText('Signed in as student@example.com');

  // Login must re-fetch classroom context as the authenticated student.
  await expect.poll(() => mockState.classroomContextRequests.some((req) => req.authenticated === true)).toBe(true);
  await expect(page.locator('#classroom-banner')).toBeVisible();
  await expect(page.locator('#classroom-banner')).toContainText('British Literature');
  await expect(page.locator('#classroom-assignments')).toBeVisible();
  await expect(page.locator('#classroom-assignments-list')).toContainText('Pride and Prejudice Ch. 1');

  await page.click('#account-toggle-library');
  await page.click('#account-signout');
  await expect(page.locator('#account-library-status')).toBeHidden();

  // Logout must clear classroom landing without hard refresh.
  await expect.poll(() => mockState.classroomContextRequests.some((req) => req.authenticated === false
    && mockState.classroomContextRequests.indexOf(req) > 0)).toBe(true);
  await expect(page.locator('#classroom-banner')).toBeHidden();
  await expect(page.locator('#classroom-assignments')).toBeHidden();
});
