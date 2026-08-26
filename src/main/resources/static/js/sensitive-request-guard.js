(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
        return;
    }
    root.SensitiveRequestGuard = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    const USER_INITIATED_SENSITIVE_PATHS = [
        /^\/api\/characters\/[^/]+\/chat$/,
        /^\/api\/characters\/[^/]+\/call-session$/,
        /^\/api\/recaps\/book\/[^/]+\/chat$/,
        /^\/api\/reading-buddy\/chat$/,
        /^\/api\/illustrations\/chapter\/[^/]+\/regenerate$/,
        /^\/api\/tts\/speak(?:\/|$)/
    ];

    const BACKGROUND_SENSITIVE_PATHS = [
        /^\/api\/characters\/book\/[^/]+\/prefetch$/,
        /^\/api\/characters\/chapter\/[^/]+\/(?:analyze|prefetch-next)$/,
        /^\/api\/illustrations\/analyze\/[^/]+$/,
        /^\/api\/illustrations\/chapter\/[^/]+\/(?:request|prefetch-next)$/,
        /^\/api\/tts\/analyze\/[^/]+$/,
        /^\/api\/recaps\/chapter\/[^/]+\/generate$/,
        /^\/api\/quizzes\/chapter\/[^/]+\/generate$/
    ];

    function canPostSensitiveGeneration(flags) {
        const source = flags || {};
        if (source.cacheOnly === true || source.featureCacheOnly === true) {
            return false;
        }
        if (source.canAccessSensitive === false) {
            return false;
        }
        return true;
    }

    function isUserInitiatedSensitivePath(path) {
        if (typeof path !== 'string' || !path) {
            return false;
        }
        return USER_INITIATED_SENSITIVE_PATHS.some((pattern) => pattern.test(path));
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
        if (!path.startsWith('/api/')) {
            return false;
        }
        if (path.startsWith('/api/auth') || path.startsWith('/api/account')) {
            return false;
        }
        if (isBackgroundSensitivePath(path)) {
            return false;
        }
        return isUserInitiatedSensitivePath(path);
    }

    return {
        canPostSensitiveGeneration,
        isBackgroundSensitivePath,
        isUserInitiatedSensitivePath,
        shouldPromptCollaboratorOnUnauthorized
    };
});
