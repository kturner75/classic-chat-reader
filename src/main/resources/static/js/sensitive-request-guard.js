(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
        return;
    }
    root.SensitiveRequestGuard = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    const USER_INITIATED_SENSITIVE_ROUTES = [
        { methods: ['POST'], path: /^\/api\/characters\/[^/]+\/chat$/ },
        { methods: ['POST'], path: /^\/api\/characters\/[^/]+\/call-session$/ },
        { methods: ['POST'], path: /^\/api\/recaps\/book\/[^/]+\/chat$/ },
        { methods: ['POST'], path: /^\/api\/reading-buddy\/chat$/ },
        { methods: ['PUT'], path: /^\/api\/reading-buddy\/preferences$/ },
        { methods: ['DELETE'], path: /^\/api\/reading-buddy\/history$/ },
        { methods: ['POST'], path: /^\/api\/illustrations\/chapter\/[^/]+\/regenerate$/ },
        { methods: ['POST'], path: /^\/api\/tts\/speak(?:\/|$)/ }
    ];

    const BACKGROUND_SENSITIVE_PATHS = [
        /^\/api\/characters\/book\/[^/]+\/prefetch$/,
        /^\/api\/characters\/chapter\/[^/]+\/(?:analyze|prefetch-next)$/,
        /^\/api\/illustrations\/analyze\/[^/]+$/,
        /^\/api\/illustrations\/chapter\/[^/]+\/(?:request|prefetch-next)$/,
        /^\/api\/tts\/analyze\/[^/]+$/,
        /^\/api\/recaps\/chapter\/[^/]+\/generate$/,
        /^\/api\/quizzes\/chapter\/[^/]+\/generate$/,
        /^\/api\/reading-buddy\/check-comment$/
    ];

    function normalizeMethod(method) {
        return typeof method === 'string' && method.trim()
            ? method.trim().toUpperCase()
            : '';
    }

    function canPostSensitiveGeneration(flags) {
        const source = flags || {};
        if (source.cacheOnly === true) {
            return false;
        }
        if (source.canAccessSensitive === false) {
            return false;
        }
        return true;
    }

    /**
     * Shared-cache TTS play is public. Collaborator auth must not gate server
     * paragraph speak; browser speech is only a real provider-failure fallback.
     */
    function shouldCallServerTts(flags) {
        const source = flags || {};
        return source.serverTtsAvailable === true;
    }

    function routeMatches(route, path, method) {
        if (!route.path.test(path)) {
            return false;
        }
        if (!method) {
            return true;
        }
        return route.methods.indexOf(method) !== -1;
    }

    function isUserInitiatedSensitivePath(path, method) {
        if (typeof path !== 'string' || !path) {
            return false;
        }
        const normalizedMethod = normalizeMethod(method);
        return USER_INITIATED_SENSITIVE_ROUTES.some((route) => routeMatches(route, path, normalizedMethod));
    }

    function isBackgroundSensitivePath(path) {
        if (typeof path !== 'string' || !path) {
            return false;
        }
        return BACKGROUND_SENSITIVE_PATHS.some((pattern) => pattern.test(path));
    }

    function shouldPromptCollaboratorOnUnauthorized(input) {
        const source = input || {};
        if (source.publicMode !== true) {
            return false;
        }
        const path = typeof source.path === 'string' ? source.path : '';
        const method = normalizeMethod(source.method);
        if (!path.startsWith('/api/')) {
            return false;
        }
        if (path.startsWith('/api/auth') || path.startsWith('/api/account')) {
            return false;
        }
        if (isBackgroundSensitivePath(path)) {
            return false;
        }
        return isUserInitiatedSensitivePath(path, method);
    }

    return {
        canPostSensitiveGeneration,
        isBackgroundSensitivePath,
        isUserInitiatedSensitivePath,
        shouldCallServerTts,
        shouldPromptCollaboratorOnUnauthorized
    };
});
