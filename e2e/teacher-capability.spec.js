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
    { id: 'pride', title: 'Pride and Prejudice', author: 'Jane Austen', chapters: [{ id: 'pride-1', title: 'Chapter 1' }] },
    { id: 'cities', title: 'A Tale of Two Cities', author: 'Charles Dickens', chapters: [{ id: 'cities-1', title: 'Recalled to Life' }] }
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
      return json(route, []);
    }
    if (request.method() === 'GET' && path === '/api/classroom/terms/term-1/features') {
      return json(route, { readingBuddyEnabled: options.classroomReadingBuddyEnabled !== false });
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

  await search.fill('pride');
  await expect(page.locator('.book-option-title')).toHaveText(['Pride and Prejudice']);
  await page.locator('[data-book-id="pride"]').click();

  await expect(search).toHaveValue('Pride and Prejudice · Jane Austen');
  await expect(page.locator('#assignment-book')).toHaveValue('pride');
  await expect(page.locator('#assignment-chapter option')).toHaveText(['Whole book', 'Chapter 1']);
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
  await expect(page.locator('#student-overview-title')).toHaveText('Alex Student');
  await expect(page.locator('#student-overview-body')).toBeVisible();
  expect(overviewPath).toBe('/api/classroom/terms/term-1/students/student-1/overview');
});
