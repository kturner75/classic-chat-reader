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

async function installAssignmentMocks(page) {
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
      return json(route, {});
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
