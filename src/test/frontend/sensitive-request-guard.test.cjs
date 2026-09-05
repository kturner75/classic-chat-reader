const test = require('node:test');
const assert = require('node:assert/strict');

const {
    canPostSensitiveGeneration,
    isBackgroundSensitivePath,
    isUserInitiatedSensitivePath,
    shouldCallServerTts,
    shouldPromptCollaboratorOnUnauthorized
} = require('../../main/resources/static/js/sensitive-request-guard.js');

test('canPostSensitiveGeneration allows local generation when access is open', () => {
    assert.equal(canPostSensitiveGeneration({
        cacheOnly: false,
        canAccessSensitive: true
    }), true);
    assert.equal(canPostSensitiveGeneration({}), true);
});

test('canPostSensitiveGeneration uses only the feature cache-only flag plus canAccessSensitive', () => {
    assert.equal(canPostSensitiveGeneration({
        cacheOnly: true,
        canAccessSensitive: true
    }), false);
    assert.equal(canPostSensitiveGeneration({
        cacheOnly: false,
        canAccessSensitive: false
    }), false);
});

test('tts cache-only does not suppress character prefetch or illustration generate', () => {
    const ttsCacheOnly = true;
    const characterCacheOnly = false;
    const illustrationCacheOnly = false;
    assert.equal(canPostSensitiveGeneration({
        cacheOnly: characterCacheOnly,
        canAccessSensitive: true
    }), true);
    assert.equal(canPostSensitiveGeneration({
        cacheOnly: illustrationCacheOnly,
        canAccessSensitive: true
    }), true);
    assert.equal(canPostSensitiveGeneration({
        cacheOnly: ttsCacheOnly,
        canAccessSensitive: true
    }), false);
});

test('first open of a transferred public book does not post prefetch', () => {
    const firstOpenTransferredBook = {
        cacheOnly: true,
        canAccessSensitive: false
    };
    assert.equal(canPostSensitiveGeneration(firstOpenTransferredBook), false);
    assert.equal(
        isBackgroundSensitivePath('/api/characters/book/gutenberg-996/prefetch'),
        true
    );
});

test('shouldPromptCollaboratorOnUnauthorized is silent for prefetch and analyze', () => {
    const public401 = { publicMode: true, method: 'POST' };
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/characters/book/gutenberg-996/prefetch'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/characters/chapter/ch-1/analyze'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/characters/chapter/ch-1/prefetch-next'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/tts/analyze/gutenberg-996'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/illustrations/analyze/gutenberg-996'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/illustrations/chapter/ch-1/request'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/illustrations/chapter/ch-1/prefetch-next'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/recaps/chapter/ch-1/generate'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/quizzes/chapter/ch-1/generate'
    }), false);
});

test('first-open roster GET does not open the collaborator modal', () => {
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        method: 'GET',
        path: '/api/characters/book/gutenberg-996/up-to'
    }), false);
    assert.equal(isUserInitiatedSensitivePath('/api/characters/book/gutenberg-996/up-to', 'GET'), false);
});

test('shouldPromptCollaboratorOnUnauthorized prompts only for user-initiated sensitive actions', () => {
    const public401 = { publicMode: true };
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        method: 'POST',
        path: '/api/characters/don-quixote/chat'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        method: 'POST',
        path: '/api/characters/sancha/call-session'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        method: 'POST',
        path: '/api/recaps/book/gutenberg-996/chat'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        method: 'POST',
        path: '/api/reading-buddy/chat'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        method: 'POST',
        path: '/api/illustrations/chapter/ch-1/regenerate'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        method: 'POST',
        path: '/api/tts/speak'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        method: 'GET',
        path: '/api/tts/speak/gutenberg-996/ch-1/0'
    }), false);
});

test('public TTS paragraph play does not open the collaborator modal', () => {
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        method: 'GET',
        path: '/api/tts/speak/gutenberg-1727/ch-1/0'
    }), false);
    assert.equal(isUserInitiatedSensitivePath('/api/tts/speak/gutenberg-1727/ch-1/0', 'GET'), false);
    assert.equal(isUserInitiatedSensitivePath('/api/tts/speak', 'POST'), true);
});

test('shouldCallServerTts ignores collaborator auth for public play', () => {
    assert.equal(shouldCallServerTts({
        serverTtsAvailable: true,
        publicMode: true,
        canAccessSensitive: false
    }), true);
    assert.equal(shouldCallServerTts({
        serverTtsAvailable: true,
        publicMode: false,
        canAccessSensitive: true
    }), true);
    assert.equal(shouldCallServerTts({
        serverTtsAvailable: false,
        publicMode: true,
        canAccessSensitive: false
    }), false);
});

test('user-initiated reading-buddy preference and history 401s open the collaborator modal', () => {
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        method: 'PUT',
        path: '/api/reading-buddy/preferences'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        method: 'DELETE',
        path: '/api/reading-buddy/history'
    }), true);
    assert.equal(isUserInitiatedSensitivePath('/api/reading-buddy/preferences', 'PUT'), true);
    assert.equal(isUserInitiatedSensitivePath('/api/reading-buddy/history', 'DELETE'), true);
});

test('reading-buddy GET and background check-comment do not open the collaborator modal', () => {
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        method: 'GET',
        path: '/api/reading-buddy/preferences'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        method: 'GET',
        path: '/api/reading-buddy/history'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        method: 'POST',
        path: '/api/reading-buddy/check-comment'
    }), false);
    assert.equal(isBackgroundSensitivePath('/api/reading-buddy/check-comment'), true);
    assert.equal(isUserInitiatedSensitivePath('/api/reading-buddy/check-comment', 'POST'), false);
});

test('shouldPromptCollaboratorOnUnauthorized never prompts a registered account', () => {
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        accountAuthenticated: true,
        method: 'POST',
        path: '/api/characters/sancha/call-session'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        accountAuthenticated: true,
        method: 'POST',
        path: '/api/characters/don-quixote/chat'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        accountAuthenticated: false,
        method: 'POST',
        path: '/api/characters/sancha/call-session'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        accountAuthenticated: true,
        method: 'POST',
        path: '/api/illustrations/chapter/ch-1/regenerate'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        accountAuthenticated: true,
        method: 'POST',
        path: '/api/tts/speak'
    }), true);
});

test('shouldPromptCollaboratorOnUnauthorized never prompts outside public mode or for auth/account', () => {
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: false,
        method: 'POST',
        path: '/api/characters/sancha/chat'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        method: 'POST',
        path: '/api/auth/login'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        method: 'GET',
        path: '/api/account/status'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        method: 'GET',
        path: '/api/library/gutenberg-996'
    }), false);
});
