const test = require('node:test');
const assert = require('node:assert/strict');

const {
    deletionNotice,
    deletionRequestBody,
    canSubmitDeletion
} = require('../../main/resources/static/js/account-data.js');

test('notice for an enrolled student names the classes and offers the export first', () => {
    const notice = deletionNotice({
        email: 'alex@example.test',
        passwordRequired: true,
        classes: [{ className: 'English 101', termName: 'Fall', status: 'ACTIVE' }],
        blockedReason: null
    });
    assert.equal(notice.blocked, false);
    const text = notice.paragraphs.join(' ');
    assert.match(text, /cannot be undone/);
    assert.match(text, /English 101 — Fall/);
    assert.match(text, /leave this class/);
    assert.match(text, /kept without your name/);
    assert.match(text, /Download your data first/);
});

test('notice for a student with no classes skips the classroom paragraph', () => {
    const notice = deletionNotice({ email: 'a@b.test', passwordRequired: false, classes: [], blockedReason: null });
    assert.equal(notice.paragraphs.length, 2);
    assert.doesNotMatch(notice.paragraphs.join(' '), /enrolled/);
});

test('blocked teacher accounts get only the reason', () => {
    const notice = deletionNotice({ classes: [], blockedReason: 'This account teaches or manages classes.' });
    assert.deepEqual(notice, { blocked: true, paragraphs: ['This account teaches or manages classes.'] });
});

test('confirm requires the exact account email, and the password when the account has one', () => {
    assert.equal(canSubmitDeletion('', 'alex@example.test', 'pw', true), false);
    assert.equal(canSubmitDeletion('other@example.test', 'alex@example.test', 'pw', true), false);
    assert.equal(canSubmitDeletion(' Alex@Example.test ', 'alex@example.test', '', true), false);
    assert.equal(canSubmitDeletion(' Alex@Example.test ', 'alex@example.test', 'pw', true), true);
    assert.equal(canSubmitDeletion('alex@example.test', 'alex@example.test', '', false), true);
});

test('request body always confirms and only carries a password when required', () => {
    assert.deepEqual(deletionRequestBody(' a@b.test ', 'pw', true), { confirm: true, email: 'a@b.test', password: 'pw' });
    assert.deepEqual(deletionRequestBody('a@b.test', 'pw', false), { confirm: true, email: 'a@b.test' });
});
