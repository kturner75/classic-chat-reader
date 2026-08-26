const test = require('node:test');
const assert = require('node:assert/strict');

const {
    canPostSensitiveGeneration,
    isBackgroundSensitivePath,
    isUserInitiatedSensitivePath,
    shouldPromptCollaboratorOnUnauthorized
} = require('../../main/resources/static/js/sensitive-request-guard.js');

test('canPostSensitiveGeneration allows local generation when access is open', () => {
    assert.equal(canPostSensitiveGeneration({
        cacheOnly: false,
        featureCacheOnly: false,
        canAccessSensitive: true
    }), true);
    assert.equal(canPostSensitiveGeneration({}), true);
});

test('canPostSensitiveGeneration blocks cache-only and no-sensitive-access independently', () => {
    assert.equal(canPostSensitiveGeneration({ cacheOnly: true, canAccessSensitive: true }), false);
    assert.equal(canPostSensitiveGeneration({
        featureCacheOnly: true,
        canAccessSensitive: true
    }), false);
    assert.equal(canPostSensitiveGeneration({
        cacheOnly: false,
        featureCacheOnly: false,
        canAccessSensitive: false
    }), false);
});

test('first open of a transferred public book does not post prefetch', () => {
    const firstOpenTransferredBook = {
        cacheOnly: true,
        featureCacheOnly: true,
        canAccessSensitive: false
    };
    assert.equal(canPostSensitiveGeneration(firstOpenTransferredBook), false);
    assert.equal(
        isBackgroundSensitivePath('/api/characters/book/gutenberg-996/prefetch'),
        true
    );
    assert.equal(
        canPostSensitiveGeneration(firstOpenTransferredBook)
            && isBackgroundSensitivePath('/api/characters/book/gutenberg-996/prefetch'),
        false
    );
});

test('shouldPromptCollaboratorOnUnauthorized is silent for prefetch and analyze', () => {
    const public401 = { publicMode: true };
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
        path: '/api/characters/book/gutenberg-996/up-to'
    }), false);
    assert.equal(isUserInitiatedSensitivePath('/api/characters/book/gutenberg-996/up-to'), false);
});

test('shouldPromptCollaboratorOnUnauthorized prompts only for user-initiated sensitive actions', () => {
    const public401 = { publicMode: true };
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/characters/don-quixote/chat'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/characters/sancha/call-session'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/recaps/book/gutenberg-996/chat'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/reading-buddy/chat'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/illustrations/chapter/ch-1/regenerate'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/tts/speak'
    }), true);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        ...public401,
        path: '/api/tts/speak/gutenberg-996/ch-1/0'
    }), true);
});

test('shouldPromptCollaboratorOnUnauthorized never prompts outside public mode or for auth/account', () => {
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: false,
        path: '/api/characters/sancha/chat'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        path: '/api/auth/login'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        path: '/api/account/status'
    }), false);
    assert.equal(shouldPromptCollaboratorOnUnauthorized({
        publicMode: true,
        path: '/api/library/gutenberg-996'
    }), false);
});
