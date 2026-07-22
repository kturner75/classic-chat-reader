const test = require('node:test');
const assert = require('node:assert/strict');

const {
    buildRecentChatsView,
    createController,
    ERROR_MESSAGE,
    formatRelativeTime
} = require('../../main/resources/static/js/my-chats-landing.js');

const NOW = Date.parse('2026-07-21T23:30:00Z');

function fakeElement(initialClasses = []) {
    const classes = new Set(initialClasses);
    const listeners = new Map();
    return {
        innerHTML: '',
        textContent: '',
        classList: {
            add: value => classes.add(value),
            contains: value => classes.has(value),
            toggle(value, force) {
                if (force === true) classes.add(value);
                else if (force === false) classes.delete(value);
                else if (classes.has(value)) classes.delete(value);
                else classes.add(value);
            }
        },
        addEventListener(type, listener) {
            listeners.set(type, listener);
        },
        dispatch(type, event = {}) {
            listeners.get(type)?.(event);
        }
    };
}

test('populated recent chats preserve API order and use server resume routes', () => {
    const view = buildRecentChatsView([
        {
            sessionId: 'chat-newest',
            character: {
                id: 'character-1',
                name: 'Elizabeth Bennet',
                portraitUrl: '/api/characters/character-1/portrait'
            },
            book: { id: 'book-1', title: 'Pride & Prejudice' },
            previewText: '<A recent reply>',
            lastMessageAt: '2026-07-21T23:14:35Z',
            resume: { available: true, url: '/my-chats?session=chat-newest' }
        },
        {
            sessionId: 'chat-older',
            character: { id: 'character-2', name: 'Mr. Darcy', portraitUrl: null },
            book: { id: 'book-1', title: 'Pride & Prejudice' },
            previewText: 'An older reply',
            lastMessageAt: '2026-07-20T23:30:00Z',
            resume: { available: true, url: '/my-chats?session=chat-older' }
        }
    ], { now: NOW });

    assert.equal(view.kind, 'loaded');
    assert.equal(view.showViewAll, true);
    assert.ok(view.html.indexOf('Elizabeth Bennet') < view.html.indexOf('Mr. Darcy'));
    assert.match(view.html, /href="\/my-chats\?session=chat-newest"/);
    assert.match(view.html, /href="\/my-chats\?session=chat-older"/);
    assert.match(view.html, /Pride &amp; Prejudice/);
    assert.match(view.html, /&lt;A recent reply&gt;/);
    assert.match(view.html, /<time datetime="2026-07-21T23:14:35Z"/);
    assert.match(view.html, /aria-label="[^\"]+"/);
    assert.doesNotMatch(view.html, /href="[^\"]*character-1/);
});

test('recent chats empty state links readers back to the Library', () => {
    const view = buildRecentChatsView([], { now: NOW });

    assert.deepEqual(view, {
        kind: 'empty',
        showViewAll: false,
        status: 'Your character conversations will appear here after you start chatting.',
        html: '<a class="my-chats-empty-action" href="/">Find a character</a>'
    });
});

test('recent chats are capped at four cards without client-side reordering', () => {
    const sessions = Array.from({ length: 5 }, (_, index) => ({
        sessionId: `chat-${index}`,
        character: { name: `Character ${index}` },
        book: { title: `Book ${index}` },
        previewText: `Preview ${index}`,
        lastMessageAt: `2026-07-2${index + 1}T12:00:00Z`,
        resume: { available: true, url: `/my-chats?session=chat-${index}` }
    }));

    const view = buildRecentChatsView(sessions, { now: NOW });

    assert.equal((view.html.match(/class="my-chat-card"/g) || []).length, 4);
    assert.ok(view.html.indexOf('Character 0') < view.html.indexOf('Character 3'));
    assert.doesNotMatch(view.html, /Character 4/);
});

test('relative activity labels cover recent and older activity', () => {
    assert.equal(formatRelativeTime('2026-07-21T23:29:45Z', NOW), 'Just now');
    assert.equal(formatRelativeTime('2026-07-21T21:30:00Z', NOW), '2 hours ago');
    assert.equal(formatRelativeTime('2026-07-20T23:30:00Z', NOW), 'Yesterday');
});

test('controller omits signed-out UI, then loads the four-item account endpoint', async () => {
    const section = fakeElement(['hidden']);
    const list = fakeElement();
    const status = fakeElement();
    const viewAll = fakeElement(['hidden']);
    const retry = fakeElement(['hidden']);
    const requests = [];
    let resolveResponse;
    const responsePromise = new Promise(resolve => { resolveResponse = resolve; });
    const controller = createController({
        section,
        list,
        status,
        viewAll,
        retry,
        fetchImpl: async (...args) => {
            requests.push(args);
            return responsePromise;
        }
    });

    await controller.sync({ authenticated: false });
    assert.equal(section.classList.contains('hidden'), true);
    assert.equal(requests.length, 0);

    const loading = controller.sync({ authenticated: true });
    assert.equal(section.classList.contains('hidden'), false);
    assert.equal(status.textContent, 'Loading My Chats…');
    assert.equal((list.innerHTML.match(/my-chat-card-skeleton/g) || []).length, 4);
    assert.deepEqual(requests[0], ['/api/account/chats?limit=4', { cache: 'no-store' }]);

    resolveResponse({ ok: true, json: async () => ({ items: [] }) });
    await loading;
    assert.equal(status.textContent, 'Your character conversations will appear here after you start chatting.');
    assert.match(list.innerHTML, /Find a character/);
    assert.equal(viewAll.classList.contains('hidden'), true);
});

test('controller failure keeps the shelf usable with a retry action', async () => {
    const section = fakeElement(['hidden']);
    const list = fakeElement();
    const status = fakeElement();
    const viewAll = fakeElement(['hidden']);
    const retry = fakeElement(['hidden']);
    let requestCount = 0;
    const controller = createController({
        section,
        list,
        status,
        viewAll,
        retry,
        fetchImpl: async () => {
            requestCount += 1;
            return { ok: false, status: 503 };
        }
    });

    await controller.sync({ authenticated: true });
    assert.equal(status.textContent, ERROR_MESSAGE);
    assert.equal(list.innerHTML, '');
    assert.equal(retry.classList.contains('hidden'), false);

    retry.dispatch('click');
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(requestCount, 2);
});
