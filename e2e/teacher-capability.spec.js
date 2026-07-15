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
