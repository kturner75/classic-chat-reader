(function() {
    'use strict';

    // State
    const state = {
        catalogBooks: [],      // Books from Gutenberg catalog
        localBooks: [],        // Books imported locally
        currentBook: null,
        currentBookMlaCitation: '',
        currentBookCitationKey: null,
        currentBookCitationPromise: null,
        currentChapterIndex: 0,
        chapterLoadRequestId: 0,
        chapters: [],
        paragraphs: [],
        currentPage: 0,
        totalPages: 0,
        currentParagraphIndex: 0,
        pagesData: [],         // Array of { startParagraph, endParagraph } for each page
        searchChapterFilter: '',
        searchLastQuery: '',
        searchHighlightTerms: [],
        searchHighlightChapterId: null,
        searchHighlightParagraphIndex: null,
        readerPreferences: null,
        annotationsByKey: new Map(),
        bookmarks: [],
        noteModalParagraphIndex: null,
        isImporting: false,
        ttsEnabled: false,
        ttsAudio: null,
        ttsWaitingForChapter: false,
        ttsVoiceSettings: null,  // { voice, speed, instructions, reasoning }
        ttsAvailable: false,
        ttsOpenAIAvailable: false,
        ttsOpenAIConfigured: false,
        ttsCachedAvailable: false,
        ttsBrowserAvailable: false,
        ttsUsingBrowser: false,  // true when currently using browser fallback
        ttsPlaybackRate: 1.0,  // 1.0, 1.25, 1.5, 1.75, 2.0
        ttsPrefetchedAudio: null,      // Pre-fetched Audio object for next paragraph
        ttsPrefetchedIndex: -1,        // Paragraph index of pre-fetched audio
        ttsPrefetchedChapter: null,    // Chapter ID of pre-fetched audio
        ttsAbortController: null,      // AbortController for current audio request
        ttsPrefetchAbortController: null,  // AbortController for prefetch request
        // Speed reading state
        speedReadingEnabled: true,
        speedReadingRestoreIllustration: false,
        speedReadingActive: false,
        speedReadingPlaying: false,
        speedReadingWpm: 300,
        speedReadingTimer: null,
        speedReadingTokens: [],
        speedReadingTokenIndex: 0,
        // Illustration mode state
        illustrationMode: false,
        illustrationAvailable: false,
        illustrationCacheOnly: false,
        illustrationRetryHandler: null,
        illustrationSettings: null,  // { style, promptPrefix, reasoning }
        illustrationPolling: null,   // polling interval ID
        currentIllustrationChapterId: null,
        allowPromptEditing: false,   // whether prompt editing is enabled
        // Modal/overlay state
        ttsWasPlayingBeforeModal: false,  // track TTS state when modal opens
        // Prompt modal state
        promptModalChapterId: null,       // chapter being edited in modal
        promptModalPolling: null,         // polling interval for regeneration
        promptModalLastPrompt: '',        // last prompt used (for try again)
        promptRetryHandler: null,
        searchRetryHandler: null,
        recapRetryHandler: null,
        recapChatRetryHandler: null,
        characterChatRetryHandler: null,
        // Character feature state
        characterAvailable: false,
        characterCacheOnly: false,
        characterChatAvailable: false,
        recapAvailable: false,
        recapGenerationAvailable: false,
        recapCacheOnly: false,
        recapChatEnabled: false,
        recapChatAvailable: false,
        recapOptOut: false,
        recapPendingChapterIndex: null,
        recapActiveTab: 'recap',
        recapChatChapterIndex: null,
        recapChatHistory: [],
        recapChatLoading: false,
        recapPollingInterval: null,
        recapPollingChapterId: null,
        recapPollingInFlight: false,
        quizAvailable: false,
        quizGenerationAvailable: false,
        quizCacheOnly: false,
        quizChapterId: null,
        quizQuestions: [],
        quizSelectedAnswers: [],
        quizSubmitting: false,
        quizResult: null,
        quizDifficultyLevel: 0,
        characters: [],                   // Characters met so far
        characterLastCheck: 0,            // Timestamp of last new character check
        characterPollingInterval: null,   // Interval for checking new characters
        newCharacterQueue: [],            // Queue of characters to show toasts for
        discoveredCharacterIds: new Set(), // Per-book discovery tracking
        discoveredCharacterDetails: new Map(),
        characterDiscoveryTimeout: null,
        currentToastCharacter: null,      // Character currently showing in toast
        // Character browser modal state
        characterBrowserOpen: false,
        selectedCharacterId: null,
        // Character chat modal state
        characterChatOpen: false,
        chatCharacterId: null,
        chatCharacter: null,              // Current character being chatted with
        chatHistory: [],                  // Loaded from localStorage
        chatLoading: false,
        isMobileLayout: false,
        mobileHeaderMenuFocusIndex: -1,
        cacheOnly: false,
        authPublicMode: false,
        authRequired: false,
        authCanAccessSensitive: true,
        authAuthenticated: false,
        authPromptShown: false,
        accountAuthEnabled: false,
        accountGoogleAuthEnabled: false,
        accountAuthenticated: false,
        accountEmail: null,
        accountRolloutMode: 'optional',
        accountRequired: false,
        accountClaimSyncedFor: null,
        accountSyncInFlight: false,
        lastBookActivitySignature: '',
        favoriteBookIds: new Set(),
        favoriteBookOrder: [],
        classroomContext: null,
        classroomAssignments: [],
        achievementsLoading: false,
        achievementsLoaded: false,
        achievementsSummary: '',
        achievementsItems: [],
        achievementsAllItems: [],
        popularCatalogBooks: [],
        librarySearchQuery: '',
        librarySearchRetryHandler: null,
        catalogMode: null,
        catalogCuratedOnly: false
    };

    // DOM Elements
    const elements = {
        libraryView: document.getElementById('library-view'),
        readerView: document.getElementById('reader-view'),
        librarySearchContainer: document.querySelector('.library-search-container'),
        librarySearch: document.getElementById('library-search'),
        librarySearchSubmit: document.getElementById('library-search-submit'),
        librarySearchStatus: document.getElementById('library-search-status'),
        librarySearchError: document.getElementById('library-search-error'),
        librarySearchErrorMessage: document.getElementById('library-search-error-message'),
        librarySearchRetry: document.getElementById('library-search-retry'),
        classroomBanner: document.getElementById('classroom-banner'),
        classroomLabel: document.getElementById('classroom-label'),
        classroomFeatureSummary: document.getElementById('classroom-feature-summary'),
        classroomAssignments: document.getElementById('classroom-assignments'),
        classroomAssignmentsLabel: document.getElementById('classroom-assignments-label'),
        classroomAssignmentsList: document.getElementById('classroom-assignments-list'),
        classroomAssignmentsEmpty: document.getElementById('classroom-assignments-empty'),
        achievementsShelf: document.getElementById('achievements-shelf'),
        achievementsSummary: document.getElementById('achievements-summary'),
        achievementsList: document.getElementById('achievements-list'),
        achievementsViewAll: document.getElementById('achievements-view-all'),
        achievementsModal: document.getElementById('achievements-modal'),
        achievementsModalBackdrop: document.getElementById('achievements-modal-backdrop'),
        achievementsModalClose: document.getElementById('achievements-modal-close'),
        achievementsModalSummary: document.getElementById('achievements-modal-summary'),
        achievementsModalList: document.getElementById('achievements-modal-list'),
        continueReading: document.getElementById('continue-reading'),
        continueReadingList: document.getElementById('continue-reading-list'),
        upNext: document.getElementById('up-next'),
        upNextList: document.getElementById('up-next-list'),
        inProgress: document.getElementById('in-progress'),
        inProgressList: document.getElementById('in-progress-list'),
        completedBooks: document.getElementById('completed-books'),
        completedBooksList: document.getElementById('completed-books-list'),
        myList: document.getElementById('my-list'),
        myListList: document.getElementById('my-list-list'),
        allBooks: document.getElementById('all-books'),
        bookList: document.getElementById('book-list'),
        noResults: document.getElementById('no-results'),
        libraryCatalogModeBadge: document.getElementById('library-catalog-mode-badge'),
        bookTitle: document.getElementById('book-title'),
        bookAuthor: document.getElementById('book-author'),
        favoriteToggle: document.getElementById('favorite-toggle'),
        chapterTitle: document.getElementById('chapter-title'),
        columnLeft: document.getElementById('column-left'),
        columnRight: document.getElementById('column-right'),
        readerContent: document.querySelector('.reader-content'),
        readerFooter: document.querySelector('.reader-footer'),
        gutterLeft: document.getElementById('gutter-left'),
        gutterRight: document.getElementById('gutter-right'),
        mobileHeaderMenu: document.getElementById('mobile-header-menu'),
        mobileHeaderMenuToggle: document.getElementById('mobile-header-menu-toggle'),
        mobileHeaderMenuPanel: document.getElementById('mobile-header-menu-panel'),
        mobileMenuSearchInput: document.getElementById('mobile-menu-search-input'),
        mobileMenuSearchSubmit: document.getElementById('mobile-menu-search-submit'),
        mobileMenuTtsToggle: document.getElementById('mobile-menu-tts-toggle'),
        mobileMenuTtsSpeed: document.getElementById('mobile-menu-tts-speed'),
        mobileMenuTtsSpeedValue: document.getElementById('mobile-menu-tts-speed-value'),
        mobileMenuSpeedReadingToggle: document.getElementById('mobile-menu-speed-reading-toggle'),
        mobileMenuIllustrationToggle: document.getElementById('mobile-menu-illustration-toggle'),
        mobileMenuCharacterToggle: document.getElementById('mobile-menu-character-toggle'),
        mobileMenuReaderSettings: document.getElementById('mobile-menu-reader-settings'),
        mobileMenuHighlight: document.getElementById('mobile-menu-highlight'),
        mobileMenuNote: document.getElementById('mobile-menu-note'),
        mobileMenuBookmark: document.getElementById('mobile-menu-bookmark'),
        mobileMenuBookmarks: document.getElementById('mobile-menu-bookmarks'),
        mobileMenuCitation: document.getElementById('mobile-menu-citation'),
        mobileMenuFavorite: document.getElementById('mobile-menu-favorite'),
        mobileMenuRecapEnable: document.getElementById('mobile-menu-recap-enable'),
        mobileMenuAccount: document.getElementById('mobile-menu-account'),
        mobileMenuAuth: document.getElementById('mobile-menu-auth'),
        pageIndicator: document.getElementById('page-indicator'),
        mobileLayoutHint: document.getElementById('mobile-layout-hint'),
        mobileReaderNav: document.getElementById('mobile-reader-nav'),
        mobileChapterList: document.getElementById('mobile-chapter-list'),
        mobilePrevPage: document.getElementById('mobile-prev-page'),
        mobileNextPage: document.getElementById('mobile-next-page'),
        backToLibrary: document.getElementById('back-to-library'),
        searchContainer: document.getElementById('search-container'),
        searchToggle: document.getElementById('search-toggle'),
        searchInput: document.getElementById('search-input'),
        searchResults: document.getElementById('search-results'),
        searchResultsError: document.getElementById('search-results-error'),
        searchResultsErrorMessage: document.getElementById('search-results-error-message'),
        searchResultsRetry: document.getElementById('search-results-retry'),
        searchResultsList: document.getElementById('search-results-list'),
        searchChapterFilter: document.getElementById('search-chapter-filter'),
        readerSettingsToggle: document.getElementById('reader-settings-toggle'),
        readerSettingsPanel: document.getElementById('reader-settings-panel'),
        readerFontSize: document.getElementById('reader-font-size'),
        readerFontSizeValue: document.getElementById('reader-font-size-value'),
        readerLineHeight: document.getElementById('reader-line-height'),
        readerLineHeightValue: document.getElementById('reader-line-height-value'),
        readerColumnGap: document.getElementById('reader-column-gap'),
        readerColumnGapValue: document.getElementById('reader-column-gap-value'),
        readerTheme: document.getElementById('reader-theme'),
        readerSettingsReset: document.getElementById('reader-settings-reset'),
        cacheOnlyIndicator: document.getElementById('cache-only-indicator'),
        shortcutsToggle: document.getElementById('shortcuts-toggle'),
        shortcutsOverlay: document.getElementById('shortcuts-overlay'),
        chapterListOverlay: document.getElementById('chapter-list-overlay'),
        chapterListHint: document.getElementById('chapter-list-hint'),
        chapterList: document.getElementById('chapter-list'),
        annotationMenuToggle: document.getElementById('annotation-menu-toggle'),
        annotationMenuPanel: document.getElementById('annotation-menu-panel'),
        highlightToggle: document.getElementById('highlight-toggle'),
        noteToggle: document.getElementById('note-toggle'),
        bookmarkToggle: document.getElementById('bookmark-toggle'),
        bookmarksToggle: document.getElementById('bookmarks-toggle'),
        bookmarksOverlay: document.getElementById('bookmarks-overlay'),
        bookmarkList: document.getElementById('bookmark-list'),
        noteModal: document.getElementById('note-modal'),
        noteModalBackdrop: document.getElementById('note-modal-backdrop'),
        noteModalClose: document.getElementById('note-modal-close'),
        noteModalLocation: document.getElementById('note-modal-location'),
        noteTextarea: document.getElementById('note-textarea'),
        noteSave: document.getElementById('note-save'),
        noteCancel: document.getElementById('note-cancel'),
        noteDelete: document.getElementById('note-delete'),
        ttsToggle: document.getElementById('tts-toggle'),
        ttsSpeed: document.getElementById('tts-speed'),
        ttsMode: document.getElementById('tts-mode'),
        ttsHint: document.getElementById('tts-hint'),
        ttsSpeedHint: document.getElementById('tts-speed-hint'),
        speedReadingContainer: document.getElementById('speed-reading-container'),
        speedReadingToggle: document.getElementById('speed-reading-toggle'),
        speedReadingOverlay: document.getElementById('speed-reading-overlay'),
        speedReadingWord: document.getElementById('speed-reading-word'),
        speedReadingPlay: document.getElementById('speed-reading-play'),
        speedReadingExitInline: document.getElementById('speed-reading-exit-inline'),
        speedReadingSlider: document.getElementById('speed-reading-slider'),
        speedReadingWpm: document.getElementById('speed-reading-wpm'),
        speedReadingChapterOverlay: document.getElementById('speed-reading-chapter-overlay'),
        speedReadingChapterTitle: document.getElementById('speed-reading-chapter-title'),
        speedReadingContinue: document.getElementById('speed-reading-continue'),
        speedReadingExit: document.getElementById('speed-reading-exit'),
        speedReadingHint: document.getElementById('speed-reading-hint'),
        chapterRecapOverlay: document.getElementById('chapter-recap-overlay'),
        chapterRecapBackdrop: document.getElementById('chapter-recap-backdrop'),
        chapterRecapClose: document.getElementById('chapter-recap-close'),
        chapterRecapChapterTitle: document.getElementById('chapter-recap-chapter-title'),
        chapterRecapTabRecap: document.getElementById('chapter-recap-tab-recap'),
        chapterRecapTabChat: document.getElementById('chapter-recap-tab-chat'),
        chapterRecapTabQuiz: document.getElementById('chapter-recap-tab-quiz'),
        chapterRecapPanelRecap: document.getElementById('chapter-recap-panel-recap'),
        chapterRecapPanelChat: document.getElementById('chapter-recap-panel-chat'),
        chapterRecapPanelQuiz: document.getElementById('chapter-recap-panel-quiz'),
        chapterRecapStatus: document.getElementById('chapter-recap-status'),
        chapterRecapError: document.getElementById('chapter-recap-error'),
        chapterRecapErrorMessage: document.getElementById('chapter-recap-error-message'),
        chapterRecapRetry: document.getElementById('chapter-recap-retry'),
        chapterRecapSummary: document.getElementById('chapter-recap-summary'),
        chapterRecapEvents: document.getElementById('chapter-recap-events'),
        chapterRecapCharacters: document.getElementById('chapter-recap-characters'),
        chapterRecapChatStatus: document.getElementById('chapter-recap-chat-status'),
        chapterRecapChatError: document.getElementById('chapter-recap-chat-error'),
        chapterRecapChatErrorMessage: document.getElementById('chapter-recap-chat-error-message'),
        chapterRecapChatRetry: document.getElementById('chapter-recap-chat-retry'),
        chapterRecapChatMessages: document.getElementById('chapter-recap-chat-messages'),
        chapterRecapChatInput: document.getElementById('chapter-recap-chat-input'),
        chapterRecapChatSend: document.getElementById('chapter-recap-chat-send'),
        chapterQuizStatus: document.getElementById('chapter-quiz-status'),
        chapterQuizQuestions: document.getElementById('chapter-quiz-questions'),
        chapterQuizSubmit: document.getElementById('chapter-quiz-submit'),
        chapterQuizFeedback: document.getElementById('chapter-quiz-feedback'),
        chapterRecapOptout: document.getElementById('chapter-recap-optout'),
        chapterRecapSkip: document.getElementById('chapter-recap-skip'),
        chapterRecapContinue: document.getElementById('chapter-recap-continue'),
        // Illustration elements
        illustrationToggle: document.getElementById('illustration-toggle'),
        illustrationColumn: document.getElementById('illustration-column'),
        illustrationSkeleton: document.getElementById('illustration-skeleton'),
        illustrationImage: document.getElementById('illustration-image'),
        illustrationError: document.getElementById('illustration-error'),
        illustrationErrorMessage: document.getElementById('illustration-error-message'),
        illustrationErrorRetry: document.getElementById('illustration-error-retry'),
        illustrationHint: document.getElementById('illustration-hint'),
        // Prompt editing modal elements
        promptModal: document.getElementById('prompt-modal'),
        promptModalBackdrop: document.getElementById('prompt-modal-backdrop'),
        promptModalClose: document.getElementById('prompt-modal-close'),
        promptModalTitle: document.getElementById('prompt-modal-title'),
        promptTextarea: document.getElementById('prompt-textarea'),
        promptEditMode: document.getElementById('prompt-edit-mode'),
        promptGeneratingMode: document.getElementById('prompt-generating-mode'),
        promptPreviewMode: document.getElementById('prompt-preview-mode'),
        promptPreviewImage: document.getElementById('prompt-preview-image'),
        promptEditButtons: document.getElementById('prompt-edit-buttons'),
        promptPreviewButtons: document.getElementById('prompt-preview-buttons'),
        promptError: document.getElementById('prompt-error'),
        promptErrorMessage: document.getElementById('prompt-error-message'),
        promptErrorRetry: document.getElementById('prompt-error-retry'),
        promptCancel: document.getElementById('prompt-cancel'),
        promptRegenerate: document.getElementById('prompt-regenerate'),
        promptTryAgain: document.getElementById('prompt-try-again'),
        promptAccept: document.getElementById('prompt-accept'),
        appToastRegion: document.getElementById('app-toast-region'),
        // Character elements
        characterToggle: document.getElementById('character-toggle'),
        characterHint: document.getElementById('character-hint'),
        recapEnableBtn: document.getElementById('recap-enable-btn'),
        accountToggle: document.getElementById('account-toggle'),
        accountToggleLibrary: document.getElementById('account-toggle-library'),
        accountLibraryStatus: document.getElementById('account-library-status'),
        characterToast: document.getElementById('character-toast'),
        characterToastImage: document.getElementById('character-toast-image'),
        characterToastName: document.getElementById('character-toast-name'),
        characterToastDesc: document.getElementById('character-toast-desc'),
        characterToastClose: document.getElementById('character-toast-close'),
        characterBrowserModal: document.getElementById('character-browser-modal'),
        characterBrowserClose: document.getElementById('character-browser-close'),
        characterListView: document.getElementById('character-list-view'),
        characterListEmpty: document.getElementById('character-list-empty'),
        characterList: document.getElementById('character-list'),
        characterDetailView: document.getElementById('character-detail-view'),
        characterBackBtn: document.getElementById('character-back-btn'),
        characterDetailPortrait: document.getElementById('character-detail-portrait'),
        characterDetailName: document.getElementById('character-detail-name'),
        characterDetailDesc: document.getElementById('character-detail-desc'),
        characterDetailLink: document.getElementById('character-detail-link'),
        characterDetailChapter: document.getElementById('character-detail-chapter'),
        characterChatBtn: document.getElementById('character-chat-btn'),
        characterChatModal: document.getElementById('character-chat-modal'),
        characterChatClose: document.getElementById('character-chat-close'),
        chatCharacterPortrait: document.getElementById('chat-character-portrait'),
        chatCharacterName: document.getElementById('chat-character-name'),
        chatError: document.getElementById('chat-error'),
        chatErrorMessage: document.getElementById('chat-error-message'),
        chatErrorRetry: document.getElementById('chat-error-retry'),
        chatMessages: document.getElementById('chat-messages'),
        chatInput: document.getElementById('chat-input'),
        chatSendBtn: document.getElementById('chat-send-btn'),
        accountModal: document.getElementById('account-modal'),
        accountModalBackdrop: document.getElementById('account-modal-backdrop'),
        accountModalClose: document.getElementById('account-modal-close'),
        accountModalTitle: document.getElementById('account-modal-title'),
        accountModalHelp: document.getElementById('account-modal-help'),
        accountModalStatus: document.getElementById('account-modal-status'),
        accountModalEmail: document.getElementById('account-modal-email'),
        accountGoogleSignIn: document.getElementById('account-google-signin'),
        accountGoogleSignInLabel: document.getElementById('account-google-signin-label'),
        accountAuthDivider: document.getElementById('account-auth-divider'),
        accountEmail: document.getElementById('account-email'),
        accountPassword: document.getElementById('account-password'),
        accountSignIn: document.getElementById('account-signin'),
        accountRegister: document.getElementById('account-register'),
        accountSignOut: document.getElementById('account-signout'),
        authToggle: document.getElementById('auth-toggle'),
        authModal: document.getElementById('auth-modal'),
        authModalBackdrop: document.getElementById('auth-modal-backdrop'),
        authModalClose: document.getElementById('auth-modal-close'),
        authModalStatus: document.getElementById('auth-modal-status'),
        authPassword: document.getElementById('auth-password'),
        authSignIn: document.getElementById('auth-signin'),
        authSignOut: document.getElementById('auth-signout')
    };

    // Chapter list state
    let chapterListSelectedIndex = 0;
    let bookmarkListSelectedIndex = 0;
    let libraryLoadRequestId = 0;
    let catalogSearchRequestId = 0;
    let catalogSearchAbortController = null;

    // LocalStorage keys
    const STORAGE_KEYS = {
        LAST_BOOK: 'reader_lastBook',
        LAST_CHAPTER: 'reader_lastChapter',
        LAST_PAGE: 'reader_lastPage',
        LAST_PARAGRAPH: 'reader_lastParagraph',
        RECENTLY_READ: 'reader_recentlyRead',
        BOOK_ACTIVITY: 'reader_bookActivity',
        FAVORITE_BOOKS: 'reader_favoriteBooks',
        TTS_SPEED: 'reader_ttsSpeed',
        SPEED_READING_WPM: 'reader_speedReadingWpm',
        ILLUSTRATION_MODE: 'reader_illustrationMode',
        READER_PREFERENCES: 'reader_readerPreferences',
        READER_PREFERENCES_UPDATED_AT: 'reader_readerPreferencesUpdatedAt',
        RECAP_OPTOUT_PREFIX: 'reader_recapOptOut_',
        RECAP_CHAT_PREFIX: 'reader_recapChat_',
        CHARACTER_CHAT_PREFIX: 'reader_characterChat_',
        DISCOVERED_CHARACTERS_PREFIX: 'reader_discoveredCharacters_',
        DISCOVERED_CHARACTER_DETAILS_PREFIX: 'reader_discoveredCharacterDetails_'
    };

    const MAX_RECENTLY_READ = 5;
    const DEFAULT_READER_PREFERENCES = Object.freeze({
        fontSize: 1.2,
        lineHeight: 1.7,
        columnGap: 4,
        theme: 'warm'
    });
    const MOBILE_DEFAULT_READER_PREFERENCES = Object.freeze({
        fontSize: 1.08,
        lineHeight: 1.65,
        columnGap: 4,
        theme: 'warm'
    });
    const SEARCH_PLACEHOLDER_DESKTOP = 'Search... (press /)';
    const SEARCH_PLACEHOLDER_MOBILE = 'Search chapter text...';
    const CHAPTER_LIST_HINT_DESKTOP = 'Arrow keys to navigate, Enter to select, Esc to close';
    const CHAPTER_LIST_HINT_MOBILE = 'Tap a chapter to jump';
    const DEFAULT_CLASSROOM_FEATURES = Object.freeze({
        quizEnabled: true,
        recapEnabled: true,
        ttsEnabled: true,
        illustrationEnabled: true,
        characterEnabled: true,
        chatEnabled: true,
        speedReadingEnabled: true,
        citationEnabled: true
    });

    const READER_THEMES = Object.freeze({
        warm: {
            textColor: '#2c2c2c',
            bgColor: '#fdfbf7',
            mutedColor: '#888',
            borderColor: '#e0ddd5',
            highlightColor: '#f5f0e6'
        },
        paper: {
            textColor: '#22201c',
            bgColor: '#fffefb',
            mutedColor: '#6f6a62',
            borderColor: '#d7d1c8',
            highlightColor: '#f1ece2'
        }
    });
    const libraryProgressHelpers = (typeof globalThis !== 'undefined'
        && globalThis.LibraryProgress
        && typeof globalThis.LibraryProgress.buildBookProgressSnapshot === 'function')
        ? globalThis.LibraryProgress
        : null;
    const libraryRankingHelpers = (typeof globalThis !== 'undefined'
        && globalThis.LibraryRanking
        && typeof globalThis.LibraryRanking.compareForActiveQueue === 'function'
        && typeof globalThis.LibraryRanking.compareForCompleted === 'function')
        ? globalThis.LibraryRanking
        : null;
    const libraryDiscoverHelpers = (typeof globalThis !== 'undefined'
        && globalThis.LibraryDiscover
        && typeof globalThis.LibraryDiscover.buildRecommendations === 'function')
        ? globalThis.LibraryDiscover
        : null;
    const citationUtils = (typeof globalThis !== 'undefined'
        && globalThis.CitationUtils
        && typeof globalThis.CitationUtils.citationPreviewText === 'function'
        && typeof globalThis.CitationUtils.copyTextToClipboard === 'function')
        ? globalThis.CitationUtils
        : null;

    function firstMessageFromPayload(payload) {
        if (!payload) {
            return '';
        }
        if (typeof payload === 'string') {
            return payload.trim();
        }
        if (typeof payload === 'object') {
            const keys = ['message', 'error', 'detail', 'response'];
            for (const key of keys) {
                const value = payload[key];
                if (typeof value === 'string' && value.trim()) {
                    return value.trim();
                }
            }
            if (typeof payload.error === 'object') {
                return firstMessageFromPayload(payload.error);
            }
        }
        return '';
    }

    async function readErrorPayload(response) {
        if (!response) {
            return null;
        }
        const contentType = response.headers?.get('content-type') || '';
        if (contentType.includes('application/json')) {
            return response.json().catch(() => null);
        }
        const text = await response.text().catch(() => '');
        if (!text) {
            return null;
        }
        try {
            return JSON.parse(text);
        } catch (_error) {
            return text;
        }
    }

    function mapSearchError(errorInfo = {}) {
        const status = Number.isInteger(errorInfo.status) ? errorInfo.status : 0;
        const backendMessage = (errorInfo.message || '').trim();
        if (errorInfo.network) {
            return { message: 'Search is unavailable right now. Check connection and retry.', retryable: true };
        }
        if (backendMessage) {
            return { message: backendMessage, retryable: true };
        }
        if (status === 400) {
            return { message: 'Search query could not be processed. Adjust it and retry.', retryable: true };
        }
        if (status === 403) {
            return { message: 'Search is not available for this chapter.', retryable: false };
        }
        if (status === 429) {
            return { message: 'Search is busy. Retry in a moment.', retryable: true };
        }
        if (status >= 500) {
            return { message: 'Search service failed. Retry in a moment.', retryable: true };
        }
        return { message: 'Search failed. Please retry.', retryable: true };
    }

    function mapImportError(errorInfo = {}) {
        const status = Number.isInteger(errorInfo.status) ? errorInfo.status : 0;
        const backendMessage = (errorInfo.message || '').trim();
        const normalizedMessage = backendMessage.toLowerCase();
        if (errorInfo.network) {
            return { message: 'Unable to reach the import service. Retry import.', retryable: true };
        }
        if (backendMessage) {
            if (normalizedMessage.includes('no html version')
                    || normalizedMessage.includes('not found in gutenberg')
                    || normalizedMessage.includes('no content could be parsed')) {
                return { message: backendMessage, retryable: false };
            }
            return { message: backendMessage, retryable: true };
        }
        if (status === 404) {
            return { message: 'This Gutenberg book was not found.', retryable: false };
        }
        if (status === 429) {
            return { message: 'Import is rate-limited. Retry shortly.', retryable: true };
        }
        if (status >= 500) {
            return { message: 'Import service failed. Retry in a moment.', retryable: true };
        }
        return { message: 'Failed to import book.', retryable: true };
    }

    function mapGenerationError(errorInfo = {}) {
        const status = Number.isInteger(errorInfo.status) ? errorInfo.status : 0;
        const backendMessage = (errorInfo.message || '').trim();
        const generationState = (errorInfo.generationState || '').toUpperCase();
        if (errorInfo.timeout) {
            return { message: 'Illustration generation timed out. Retry generation.', retryable: true };
        }
        if (errorInfo.network) {
            return { message: 'Illustration service is unreachable. Retry generation.', retryable: true };
        }
        if (generationState === 'FAILED') {
            return { message: 'Illustration generation failed on the server.', retryable: true };
        }
        if (generationState === 'DISABLED') {
            return { message: 'Illustrations are disabled for this book.', retryable: false };
        }
        if (generationState === 'NOT_FOUND') {
            return { message: 'Illustration is unavailable for this chapter.', retryable: false };
        }
        if (backendMessage) {
            return { message: backendMessage, retryable: true };
        }
        if (status === 400) {
            return { message: 'Prompt is invalid. Update it and retry.', retryable: true };
        }
        if (status === 403) {
            return { message: 'Illustration generation is not available here.', retryable: false };
        }
        if (status === 404) {
            return { message: 'Chapter illustration could not be found.', retryable: false };
        }
        if (status === 409) {
            return { message: 'Generation is disabled in cache-only mode.', retryable: false };
        }
        if (status === 429) {
            return { message: 'Generation is busy. Retry shortly.', retryable: true };
        }
        if (status >= 500) {
            return { message: 'Generation failed on the server. Retry in a moment.', retryable: true };
        }
        return { message: 'Illustration generation failed.', retryable: true };
    }

    function mapRecapError(errorInfo = {}) {
        const status = Number.isInteger(errorInfo.status) ? errorInfo.status : 0;
        const backendMessage = (errorInfo.message || '').trim();
        if (errorInfo.network) {
            return { message: 'Recap service is unavailable right now. Retry loading recap.', retryable: true };
        }
        if (backendMessage) {
            return { message: backendMessage, retryable: true };
        }
        if (status === 403) {
            return { message: 'Recaps are not available for this book.', retryable: false };
        }
        if (status === 404) {
            return { message: 'Recap is unavailable for this chapter.', retryable: false };
        }
        if (status === 409) {
            return { message: 'Recap is still generating.', retryable: true };
        }
        if (status === 429) {
            return { message: 'Recap service is busy. Retry in a moment.', retryable: true };
        }
        if (status >= 500) {
            return { message: 'Recap service failed. Retry in a moment.', retryable: true };
        }
        return { message: 'Unable to load recap right now.', retryable: true };
    }

    function mapChatError(errorInfo = {}) {
        const status = Number.isInteger(errorInfo.status) ? errorInfo.status : 0;
        const backendMessage = (errorInfo.message || '').trim();
        if (errorInfo.network) {
            return { message: 'Chat service is unavailable right now. Retry your message.', retryable: true };
        }
        if (backendMessage) {
            const normalized = backendMessage.toLowerCase();
            const nonRetryable = normalized.includes('disabled')
                || normalized.includes('only available')
                || normalized.includes('not available');
            return { message: backendMessage, retryable: !nonRetryable };
        }
        if (status === 400) {
            return { message: 'Message could not be sent. Edit it and retry.', retryable: true };
        }
        if (status === 403) {
            return { message: 'Chat is not available for this context.', retryable: false };
        }
        if (status === 404) {
            return { message: 'Chat target was not found.', retryable: false };
        }
        if (status === 409) {
            return { message: 'Chat is disabled in cache-only mode.', retryable: false };
        }
        if (status === 429) {
            return { message: 'Chat is busy. Retry in a moment.', retryable: true };
        }
        if (status >= 500) {
            return { message: 'Chat failed on the server. Retry in a moment.', retryable: true };
        }
        return { message: 'Unable to send chat message.', retryable: true };
    }

    function removeAppToast(toast) {
        if (!toast) {
            return;
        }
        toast.classList.add('fade-out');
        setTimeout(() => {
            toast.remove();
        }, 220);
    }

    function showAppToast({ title = 'Error', message, detail, actionLabel, onAction, autoDismissMs = 9000 }) {
        if (!elements.appToastRegion || !message) {
            return;
        }

        const toast = document.createElement('section');
        toast.className = 'app-toast';
        toast.setAttribute('role', 'status');

        const titleRow = document.createElement('div');
        titleRow.className = 'app-toast-title-row';

        const titleEl = document.createElement('div');
        titleEl.className = 'app-toast-title';
        titleEl.textContent = title;
        titleRow.appendChild(titleEl);

        const closeButton = document.createElement('button');
        closeButton.className = 'app-toast-close';
        closeButton.type = 'button';
        closeButton.setAttribute('aria-label', 'Dismiss');
        closeButton.textContent = '×';
        closeButton.addEventListener('click', () => removeAppToast(toast));
        titleRow.appendChild(closeButton);

        const messageEl = document.createElement('div');
        messageEl.className = 'app-toast-message';
        messageEl.textContent = message;

        toast.appendChild(titleRow);
        toast.appendChild(messageEl);

        if (typeof detail === 'string' && detail.trim().length > 0) {
            const detailEl = document.createElement('div');
            detailEl.className = 'app-toast-detail';
            detailEl.textContent = detail.trim();
            toast.appendChild(detailEl);
        }

        if (actionLabel && typeof onAction === 'function') {
            const actionWrap = document.createElement('div');
            actionWrap.className = 'app-toast-actions';
            const actionButton = document.createElement('button');
            actionButton.className = 'app-toast-action';
            actionButton.type = 'button';
            actionButton.textContent = actionLabel;
            actionButton.addEventListener('click', () => {
                removeAppToast(toast);
                onAction();
            });
            actionWrap.appendChild(actionButton);
            toast.appendChild(actionWrap);
        }

        elements.appToastRegion.appendChild(toast);
        if (autoDismissMs > 0) {
            setTimeout(() => removeAppToast(toast), autoDismissMs);
        }
    }

    function citationPreviewText(citation, maxLength = 280) {
        if (citationUtils) {
            return citationUtils.citationPreviewText(citation, maxLength);
        }
        const normalized = typeof citation === 'string'
            ? citation.replace(/\s+/g, ' ').trim()
            : '';
        if (!normalized) {
            return '';
        }
        if (normalized.length <= maxLength) {
            return normalized;
        }
        return `${normalized.slice(0, maxLength - 1).trimEnd()}…`;
    }

    async function copyTextToClipboard(text) {
        if (citationUtils) {
            await citationUtils.copyTextToClipboard(text);
            return;
        }
        if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
            await navigator.clipboard.writeText(text);
            return;
        }

        const textArea = document.createElement('textarea');
        textArea.value = text;
        textArea.setAttribute('readonly', '');
        textArea.style.position = 'fixed';
        textArea.style.top = '-9999px';
        textArea.style.left = '-9999px';
        document.body.appendChild(textArea);
        textArea.focus();
        textArea.select();
        textArea.setSelectionRange(0, text.length);
        const copied = document.execCommand('copy');
        document.body.removeChild(textArea);
        if (!copied) {
            throw new Error('Clipboard copy command failed');
        }
    }

    function getCurrentCitationContext() {
        const bookId = state.currentBook?.id;
        const chapter = state.chapters[state.currentChapterIndex] || null;
        const chapterId = chapter?.id || null;
        const paragraphIndex = Number.isInteger(state.currentParagraphIndex) ? state.currentParagraphIndex : null;
        const citationKey = (bookId && chapterId && Number.isInteger(paragraphIndex))
            ? `${bookId}:${chapterId}:${paragraphIndex}`
            : (bookId || null);
        return {
            bookId,
            chapterId,
            paragraphIndex,
            citationKey
        };
    }

    async function requestMlaCitationForBook(bookId, chapterId, paragraphIndex) {
        const params = new URLSearchParams();
        if (chapterId) {
            params.set('chapterId', chapterId);
        }
        if (Number.isInteger(paragraphIndex)) {
            params.set('paragraphIndex', String(paragraphIndex));
        }
        const query = params.toString();
        const response = await fetch(`/api/library/${bookId}/citation/mla${query ? `?${query}` : ''}`, { cache: 'no-store' });
        if (!response.ok) {
            const payload = await readErrorPayload(response);
            const message = firstMessageFromPayload(payload)
                || `Citation request failed with status ${response.status}`;
            throw new Error(message);
        }
        const payload = await response.json();
        const citation = typeof payload?.citation === 'string' ? payload.citation.trim() : '';
        if (!citation) {
            throw new Error('Citation response was empty');
        }
        return citation;
    }

    async function ensureCurrentBookMlaCitation({ force = false } = {}) {
        const { bookId, chapterId, paragraphIndex, citationKey } = getCurrentCitationContext();
        if (!bookId) {
            return '';
        }

        const hasCachedCitation = state.currentBookCitationKey === citationKey
            && typeof state.currentBookMlaCitation === 'string'
            && state.currentBookMlaCitation.trim().length > 0;
        if (!force && hasCachedCitation) {
            return state.currentBookMlaCitation.trim();
        }

        const hasPendingRequest = state.currentBookCitationKey === citationKey
            && state.currentBookCitationPromise;
        if (!force && hasPendingRequest) {
            return state.currentBookCitationPromise;
        }

        state.currentBookCitationKey = citationKey;
        let requestPromise = null;
        requestPromise = (async () => {
            const citation = await requestMlaCitationForBook(bookId, chapterId, paragraphIndex);
            if (state.currentBook?.id === bookId && state.currentBookCitationKey === citationKey) {
                state.currentBookMlaCitation = citation;
            }
            return citation;
        })().finally(() => {
            if (state.currentBookCitationPromise === requestPromise) {
                state.currentBookCitationPromise = null;
            }
        });
        state.currentBookCitationPromise = requestPromise;
        return requestPromise;
    }

    async function copyCurrentBookMlaCitation() {
        if (!isCitationActionAvailable()) {
            return;
        }

        const { citationKey } = getCurrentCitationContext();
        const citation = state.currentBookCitationKey === citationKey
            && typeof state.currentBookMlaCitation === 'string'
            ? state.currentBookMlaCitation.trim()
            : '';
        if (!citation) {
            // On some mobile browsers clipboard writes require immediate user gesture.
            // Warm citation in background and ask the user to retry once available.
            void ensureCurrentBookMlaCitation();
            showAppToast({
                title: 'Citation loading',
                message: 'Preparing MLA citation. Tap again to copy.',
                autoDismissMs: 2400
            });
            return;
        }

        try {
            await copyTextToClipboard(citation);
            showAppToast({
                title: 'Citation copied',
                message: 'MLA citation copied to clipboard.',
                detail: citationPreviewText(citation),
                autoDismissMs: 5200
            });
        } catch (error) {
            console.debug('Failed to copy MLA citation:', error);
            showAppToast({
                title: 'Citation unavailable',
                message: 'Unable to copy MLA citation right now.',
                actionLabel: 'Retry',
                onAction: () => { void copyCurrentBookMlaCitation(); }
            });
        }
    }

    function setSearchError(message, onRetry) {
        if (!elements.searchResultsError || !elements.searchResultsErrorMessage || !message) {
            return;
        }
        state.searchRetryHandler = typeof onRetry === 'function' ? onRetry : null;
        elements.searchResultsErrorMessage.textContent = message;
        elements.searchResultsError.classList.remove('hidden');
        if (elements.searchResultsRetry) {
            elements.searchResultsRetry.classList.toggle('hidden', !state.searchRetryHandler);
        }
    }

    function clearSearchError() {
        state.searchRetryHandler = null;
        if (elements.searchResultsError) {
            elements.searchResultsError.classList.add('hidden');
        }
        if (elements.searchResultsErrorMessage) {
            elements.searchResultsErrorMessage.textContent = '';
        }
        if (elements.searchResultsRetry) {
            elements.searchResultsRetry.classList.add('hidden');
        }
    }

    function setPromptError(message, onRetry) {
        if (!elements.promptError || !elements.promptErrorMessage || !message) {
            return;
        }
        state.promptRetryHandler = typeof onRetry === 'function' ? onRetry : null;
        elements.promptErrorMessage.textContent = message;
        elements.promptError.classList.remove('hidden');
        if (elements.promptErrorRetry) {
            elements.promptErrorRetry.classList.toggle('hidden', !state.promptRetryHandler);
        }
    }

    function clearPromptError() {
        state.promptRetryHandler = null;
        if (elements.promptError) {
            elements.promptError.classList.add('hidden');
        }
        if (elements.promptErrorMessage) {
            elements.promptErrorMessage.textContent = '';
        }
        if (elements.promptErrorRetry) {
            elements.promptErrorRetry.classList.add('hidden');
        }
    }

    function setIllustrationError(message, onRetry) {
        state.illustrationRetryHandler = typeof onRetry === 'function' ? onRetry : null;
        if (elements.illustrationErrorMessage && message) {
            elements.illustrationErrorMessage.textContent = message;
        }
        if (elements.illustrationErrorRetry) {
            elements.illustrationErrorRetry.classList.toggle('hidden', !state.illustrationRetryHandler);
        }
        if (elements.illustrationError) {
            elements.illustrationError.classList.remove('hidden');
        }
    }

    function clearIllustrationError() {
        state.illustrationRetryHandler = null;
        if (elements.illustrationErrorMessage) {
            elements.illustrationErrorMessage.textContent = 'Illustration unavailable';
        }
        if (elements.illustrationErrorRetry) {
            elements.illustrationErrorRetry.classList.add('hidden');
        }
        if (elements.illustrationError) {
            elements.illustrationError.classList.add('hidden');
        }
    }

    function setRecapOverlayError(message, onRetry) {
        if (!elements.chapterRecapError || !elements.chapterRecapErrorMessage || !message) {
            return;
        }
        state.recapRetryHandler = typeof onRetry === 'function' ? onRetry : null;
        elements.chapterRecapErrorMessage.textContent = message;
        elements.chapterRecapError.classList.remove('hidden');
        if (elements.chapterRecapRetry) {
            elements.chapterRecapRetry.classList.toggle('hidden', !state.recapRetryHandler);
        }
    }

    function clearRecapOverlayError() {
        state.recapRetryHandler = null;
        if (elements.chapterRecapError) {
            elements.chapterRecapError.classList.add('hidden');
        }
        if (elements.chapterRecapErrorMessage) {
            elements.chapterRecapErrorMessage.textContent = '';
        }
        if (elements.chapterRecapRetry) {
            elements.chapterRecapRetry.classList.add('hidden');
        }
    }

    function setRecapChatError(message, onRetry) {
        if (!elements.chapterRecapChatError || !elements.chapterRecapChatErrorMessage || !message) {
            return;
        }
        state.recapChatRetryHandler = typeof onRetry === 'function' ? onRetry : null;
        elements.chapterRecapChatErrorMessage.textContent = message;
        elements.chapterRecapChatError.classList.remove('hidden');
        if (elements.chapterRecapChatRetry) {
            elements.chapterRecapChatRetry.classList.toggle('hidden', !state.recapChatRetryHandler);
        }
    }

    function clearRecapChatError() {
        state.recapChatRetryHandler = null;
        if (elements.chapterRecapChatError) {
            elements.chapterRecapChatError.classList.add('hidden');
        }
        if (elements.chapterRecapChatErrorMessage) {
            elements.chapterRecapChatErrorMessage.textContent = '';
        }
        if (elements.chapterRecapChatRetry) {
            elements.chapterRecapChatRetry.classList.add('hidden');
        }
    }

    function setCharacterChatError(message, onRetry) {
        if (!elements.chatError || !elements.chatErrorMessage || !message) {
            return;
        }
        state.characterChatRetryHandler = typeof onRetry === 'function' ? onRetry : null;
        elements.chatErrorMessage.textContent = message;
        elements.chatError.classList.remove('hidden');
        if (elements.chatErrorRetry) {
            elements.chatErrorRetry.classList.toggle('hidden', !state.characterChatRetryHandler);
        }
    }

    function clearCharacterChatError() {
        state.characterChatRetryHandler = null;
        if (elements.chatError) {
            elements.chatError.classList.add('hidden');
        }
        if (elements.chatErrorMessage) {
            elements.chatErrorMessage.textContent = '';
        }
        if (elements.chatErrorRetry) {
            elements.chatErrorRetry.classList.add('hidden');
        }
    }

    function annotationKey(chapterId, paragraphIndex) {
        return `${chapterId}:${paragraphIndex}`;
    }

    function getCurrentChapterId() {
        return state.chapters[state.currentChapterIndex]?.id || null;
    }

    function getParagraphAnnotation(chapterId, paragraphIndex) {
        if (!chapterId || !Number.isInteger(paragraphIndex)) {
            return null;
        }
        return state.annotationsByKey.get(annotationKey(chapterId, paragraphIndex)) || null;
    }

    function setParagraphAnnotation(annotation) {
        if (!annotation || !annotation.chapterId || !Number.isInteger(annotation.paragraphIndex)) {
            return;
        }
        state.annotationsByKey.set(
            annotationKey(annotation.chapterId, annotation.paragraphIndex),
            annotation
        );
    }

    function removeParagraphAnnotation(chapterId, paragraphIndex) {
        if (!chapterId || !Number.isInteger(paragraphIndex)) {
            return;
        }
        state.annotationsByKey.delete(annotationKey(chapterId, paragraphIndex));
    }

    function isNoteModalVisible() {
        return !!elements.noteModal && !elements.noteModal.classList.contains('hidden');
    }

    function isBookmarksOverlayVisible() {
        return !!elements.bookmarksOverlay && !elements.bookmarksOverlay.classList.contains('hidden');
    }

    function isShortcutsOverlayVisible() {
        return !!elements.shortcutsOverlay && !elements.shortcutsOverlay.classList.contains('hidden');
    }

    function isAnnotationMenuVisible() {
        return !!elements.annotationMenuPanel && !elements.annotationMenuPanel.classList.contains('hidden');
    }

    function isMobileHeaderMenuVisible() {
        return !!elements.mobileHeaderMenuPanel
            && !elements.mobileHeaderMenuPanel.classList.contains('hidden');
    }

    function getMobileHeaderMenuActionElements() {
        if (!elements.mobileHeaderMenuPanel) return [];
        return Array.from(elements.mobileHeaderMenuPanel.querySelectorAll('button'))
            .filter((button) => {
                if (!(button instanceof HTMLButtonElement)) return false;
                if (button.disabled) return false;
                if (button.classList.contains('hidden')) return false;
                return button.offsetParent !== null;
            });
    }

    function syncMobileHeaderMenuFocus({ reset = false, focus = false } = {}) {
        const actions = getMobileHeaderMenuActionElements();
        if (actions.length === 0) {
            state.mobileHeaderMenuFocusIndex = -1;
            return;
        }

        if (reset
            || !Number.isInteger(state.mobileHeaderMenuFocusIndex)
            || state.mobileHeaderMenuFocusIndex < 0
            || state.mobileHeaderMenuFocusIndex >= actions.length) {
            state.mobileHeaderMenuFocusIndex = 0;
        }

        if (focus) {
            actions[state.mobileHeaderMenuFocusIndex].focus();
        }
    }

    function moveMobileHeaderMenuFocus(direction) {
        const actions = getMobileHeaderMenuActionElements();
        if (actions.length === 0) return;

        const activeIndex = actions.indexOf(document.activeElement);
        if (activeIndex >= 0) {
            state.mobileHeaderMenuFocusIndex = activeIndex;
        }
        if (!Number.isInteger(state.mobileHeaderMenuFocusIndex) || state.mobileHeaderMenuFocusIndex < 0) {
            state.mobileHeaderMenuFocusIndex = 0;
        } else {
            const total = actions.length;
            state.mobileHeaderMenuFocusIndex = (state.mobileHeaderMenuFocusIndex + direction + total) % total;
        }

        actions[state.mobileHeaderMenuFocusIndex].focus();
    }

    function activateMobileHeaderMenuFocusedAction() {
        const actions = getMobileHeaderMenuActionElements();
        if (actions.length === 0) return;

        let index = actions.indexOf(document.activeElement);
        if (index < 0) {
            index = Number.isInteger(state.mobileHeaderMenuFocusIndex)
                ? state.mobileHeaderMenuFocusIndex
                : 0;
        }
        if (index < 0 || index >= actions.length) {
            index = 0;
        }

        state.mobileHeaderMenuFocusIndex = index;
        actions[index].click();
    }

    function closeMobileHeaderMenu({ restoreToggleFocus = false } = {}) {
        if (!elements.mobileHeaderMenuPanel || !elements.mobileHeaderMenuToggle) return;
        elements.mobileHeaderMenuPanel.classList.add('hidden');
        elements.mobileHeaderMenuToggle.classList.remove('active');
        state.mobileHeaderMenuFocusIndex = -1;
        if (restoreToggleFocus) {
            elements.mobileHeaderMenuToggle.focus();
        }
    }

    function openMobileHeaderMenu() {
        if (!elements.mobileHeaderMenuPanel || !elements.mobileHeaderMenuToggle) return;
        closeReaderSettingsPanel();
        setSearchInputValues(elements.searchInput?.value || '', { skipDesktop: true });
        updateMobileHeaderMenuState();
        elements.mobileHeaderMenuPanel.classList.remove('hidden');
        elements.mobileHeaderMenuToggle.classList.add('active');
        syncMobileHeaderMenuFocus({ reset: true, focus: !state.isMobileLayout });
    }

    function toggleMobileHeaderMenu() {
        if (isMobileHeaderMenuVisible()) {
            closeMobileHeaderMenu();
        } else {
            openMobileHeaderMenu();
        }
    }

    function updateFavoriteUi() {
        const hasBook = !!state.currentBook?.id;
        const favorite = hasBook && isBookFavorite(state.currentBook.id);

        if (elements.favoriteToggle) {
            elements.favoriteToggle.disabled = !hasBook;
            elements.favoriteToggle.classList.toggle('saved', !!favorite);
            elements.favoriteToggle.textContent = favorite ? 'In My List' : 'Add to My List';
            elements.favoriteToggle.title = favorite ? 'Remove from My List' : 'Add to My List';
        }

        if (elements.mobileMenuFavorite) {
            elements.mobileMenuFavorite.disabled = !hasBook;
            elements.mobileMenuFavorite.textContent = favorite
                ? 'Remove from My List'
                : 'Add to My List';
        }

        updateCitationUi();
    }

    function isCitationActionAvailable() {
        return !!state.currentBook?.id && isClassroomFeatureEnabled('citationEnabled');
    }

    function updateCitationUi() {
        const { citationKey } = getCurrentCitationContext();
        const hasCurrentCitation = citationKey
            && state.currentBookCitationKey === citationKey
            && !!state.currentBookMlaCitation;
        const citationAvailable = isCitationActionAvailable();
        if (citationAvailable
            && !hasCurrentCitation
            && !state.currentBookCitationPromise) {
            void ensureCurrentBookMlaCitation();
        }
        if (elements.mobileMenuCitation) {
            elements.mobileMenuCitation.classList.toggle('hidden', !citationAvailable);
            elements.mobileMenuCitation.disabled = !citationAvailable;
        }
    }

    function updateMobileHeaderMenuState() {
        if (!elements.mobileHeaderMenu) return;

        const showMenuHost = !elements.readerView.classList.contains('hidden');
        elements.mobileHeaderMenu.classList.toggle('hidden', !showMenuHost);
        updateFavoriteUi();

        if (!showMenuHost) {
            closeMobileHeaderMenu();
            return;
        }

        if (elements.mobileMenuTtsToggle) {
            elements.mobileMenuTtsToggle.textContent = state.ttsEnabled
                ? 'Read Aloud: On'
                : 'Read Aloud: Off';
            elements.mobileMenuTtsToggle.disabled = !state.ttsAvailable && !state.ttsBrowserAvailable;
        }
        if (elements.mobileMenuTtsSpeed) {
            const ttsSpeedControlAvailable = state.ttsAvailable || state.ttsBrowserAvailable;
            elements.mobileMenuTtsSpeed.disabled = !ttsSpeedControlAvailable;
            elements.mobileMenuTtsSpeed.title = ttsSpeedControlAvailable
                ? `Cycle audio speed (${state.ttsPlaybackRate}x)`
                : 'Audio unavailable';
        }
        if (elements.mobileMenuTtsSpeedValue) {
            elements.mobileMenuTtsSpeedValue.textContent = `${state.ttsPlaybackRate}x`;
        }
        if (elements.mobileMenuSpeedReadingToggle) {
            elements.mobileMenuSpeedReadingToggle.textContent = state.speedReadingActive
                ? 'Exit Speed Reading'
                : 'Start Speed Reading';
            elements.mobileMenuSpeedReadingToggle.disabled = !state.speedReadingEnabled || !state.currentBook;
        }
        if (elements.mobileMenuIllustrationToggle) {
            elements.mobileMenuIllustrationToggle.textContent = state.illustrationMode
                ? 'Disable Illustration Mode'
                : 'Enable Illustration Mode';
            elements.mobileMenuIllustrationToggle.disabled = !state.illustrationAvailable;
        }
        if (elements.mobileMenuCharacterToggle) {
            elements.mobileMenuCharacterToggle.disabled = !state.characterAvailable;
        }
        const hasBook = !!state.currentBook;
        const hasCurrentParagraph = hasBook
            && Array.isArray(state.paragraphs)
            && state.paragraphs.length > 0
            && Number.isInteger(state.currentParagraphIndex)
            && state.currentParagraphIndex >= 0
            && state.currentParagraphIndex < state.paragraphs.length;
        if (elements.mobileMenuHighlight) {
            elements.mobileMenuHighlight.disabled = !hasCurrentParagraph;
        }
        if (elements.mobileMenuNote) {
            elements.mobileMenuNote.disabled = !hasCurrentParagraph;
        }
        if (elements.mobileMenuBookmark) {
            elements.mobileMenuBookmark.disabled = !hasCurrentParagraph;
        }
        if (elements.mobileMenuBookmarks) {
            elements.mobileMenuBookmarks.disabled = !hasBook;
        }

        const recapEnableAvailable = !!state.currentBook
            && (state.recapAvailable || state.quizAvailable)
            && state.recapOptOut;
        if (elements.mobileMenuRecapEnable) {
            elements.mobileMenuRecapEnable.classList.toggle('hidden', !recapEnableAvailable);
        }

        if (elements.mobileMenuAccount) {
            const showAccount = state.accountAuthEnabled;
            elements.mobileMenuAccount.classList.toggle('hidden', !showAccount);
            if (showAccount) {
                elements.mobileMenuAccount.textContent = state.accountAuthenticated
                    ? 'Account (Signed In)'
                    : 'Sign In';
            }
        }

        if (elements.mobileMenuAuth) {
            const showAuth = state.authPublicMode;
            elements.mobileMenuAuth.classList.toggle('hidden', !showAuth);
            if (showAuth) {
                elements.mobileMenuAuth.textContent = state.authAuthenticated
                    ? 'Collaborator Access (Signed In)'
                    : 'Collaborator Access (Sign In)';
            }
        }

        if (elements.mobileHeaderMenuToggle) {
            const label = state.isMobileLayout
                ? 'Reader actions'
                : 'Reader actions (O)';
            elements.mobileHeaderMenuToggle.title = label;
            elements.mobileHeaderMenuToggle.setAttribute('aria-label', label);
        }

        if (isMobileHeaderMenuVisible()) {
            syncMobileHeaderMenuFocus();
        }

    }

    function isBookFeatureEnabled(flag) {
        return !!(state.currentBook && state.currentBook[flag] === true);
    }

    function updateCacheOnlyIndicator() {
        if (!elements.cacheOnlyIndicator) return;
        elements.cacheOnlyIndicator.classList.toggle('hidden', !state.cacheOnly);
    }

    function detectMobileLayout() {
        const narrowViewport = window.matchMedia('(max-width: 960px)').matches;
        const coarsePointer = window.matchMedia('(pointer: coarse)').matches
            || window.matchMedia('(any-pointer: coarse)').matches;
        const touchCapable = (navigator.maxTouchPoints || 0) > 0;
        return narrowViewport && (coarsePointer || touchCapable);
    }

    function updateTouchNavigationControls() {
        if (!elements.mobileReaderNav) return;
        const showTouchControls = state.isMobileLayout
            && !elements.readerView.classList.contains('hidden')
            && !state.speedReadingActive;
        elements.mobileReaderNav.classList.toggle('hidden', !showTouchControls);
        if (!showTouchControls) return;

        const hasBook = !!state.currentBook && Array.isArray(state.chapters) && state.chapters.length > 0;
        const hasParagraphs = hasBook && Array.isArray(state.paragraphs) && state.paragraphs.length > 0;
        const hasPreviousChapter = hasBook && state.currentChapterIndex > 0;
        const hasNextChapter = hasBook && state.currentChapterIndex < state.chapters.length - 1;
        const hasPreviousPage = hasParagraphs && (state.currentPage > 0 || hasPreviousChapter);
        const hasNextPage = hasParagraphs && (state.currentPage < state.totalPages - 1 || hasNextChapter);

        if (elements.mobileChapterList) elements.mobileChapterList.disabled = !hasBook;
        if (elements.mobilePrevPage) elements.mobilePrevPage.disabled = !hasPreviousPage;
        if (elements.mobileNextPage) elements.mobileNextPage.disabled = !hasNextPage;
    }

    function applyLayoutCapabilities({ repaginate = false } = {}) {
        const mobileLayout = detectMobileLayout();
        state.isMobileLayout = mobileLayout;

        if (elements.readerView) {
            elements.readerView.classList.toggle('mobile-layout', mobileLayout);
        }
        if (elements.shortcutsToggle) {
            elements.shortcutsToggle.classList.toggle('hidden', mobileLayout);
        }
        if (elements.readerFooter) {
            elements.readerFooter.classList.toggle('hidden', mobileLayout);
        }
        if (elements.mobileLayoutHint) {
            elements.mobileLayoutHint.classList.toggle('hidden', !mobileLayout);
        }
        if (elements.searchInput) {
            elements.searchInput.placeholder = mobileLayout
                ? SEARCH_PLACEHOLDER_MOBILE
                : SEARCH_PLACEHOLDER_DESKTOP;
        }
        if (mobileLayout) {
            setDesktopSearchExpanded(false);
        } else {
            const hasQuery = !!(elements.searchInput && elements.searchInput.value.trim().length > 0);
            const focused = document.activeElement === elements.searchInput;
            setDesktopSearchExpanded(hasQuery || focused);
        }
        if (elements.chapterListHint) {
            elements.chapterListHint.textContent = mobileLayout
                ? CHAPTER_LIST_HINT_MOBILE
                : CHAPTER_LIST_HINT_DESKTOP;
        }

        updateMobileHeaderMenuState();
        updateColumnLayout();

        if (repaginate
            && !elements.readerView.classList.contains('hidden')
            && Array.isArray(state.paragraphs)
            && state.paragraphs.length > 0) {
            calculatePages();
            state.currentPage = Math.min(state.currentPage, state.totalPages - 1);
            state.currentPage = Math.max(0, state.currentPage);
            renderPage();
        } else {
            updateTouchNavigationControls();
        }
    }

    const nativeFetch = window.fetch.bind(window);

    function extractPathFromFetchInput(resource) {
        if (!resource) return '';
        try {
            if (typeof resource === 'string') {
                return new URL(resource, window.location.origin).pathname;
            }
            if (resource instanceof Request) {
                return new URL(resource.url, window.location.origin).pathname;
            }
        } catch (_error) {
            return '';
        }
        return '';
    }

    function installAuthAwareFetch() {
        window.fetch = async (resource, options = undefined) => {
            const response = await nativeFetch(resource, options);
            const path = extractPathFromFetchInput(resource);
            if (response.status === 401
                && state.authPublicMode
                && path.startsWith('/api/')
                && !path.startsWith('/api/auth')
                && !path.startsWith('/api/account')) {
                handleSensitiveUnauthorized();
            }
            return response;
        };
    }

    function isAuthModalVisible() {
        return !!elements.authModal && !elements.authModal.classList.contains('hidden');
    }

    function isAchievementsModalVisible() {
        return !!elements.achievementsModal && !elements.achievementsModal.classList.contains('hidden');
    }

    function closeAchievementsModal() {
        if (!elements.achievementsModal) return;
        elements.achievementsModal.classList.add('hidden');
    }

    function renderAchievementsModal() {
        if (!elements.achievementsModalSummary || !elements.achievementsModalList) {
            return;
        }

        elements.achievementsModalSummary.textContent = state.achievementsSummary
            || 'No trophies unlocked yet. Complete quizzes to start collecting achievements.';

        if (!Array.isArray(state.achievementsAllItems) || state.achievementsAllItems.length === 0) {
            elements.achievementsModalList.innerHTML = '';
            return;
        }

        elements.achievementsModalList.innerHTML = state.achievementsAllItems.map((item) => {
            const detail = formatAchievementDetail(item);
            return `
                <button
                    class="achievements-modal-item"
                    type="button"
                    data-achievement-book-id="${item.bookId}"
                    title="${escapeHtml(item.description || item.trophyTitle)}"
                >
                    <span class="achievements-modal-item-title">${escapeHtml(item.trophyTitle)}</span>
                    <span class="achievements-modal-item-subtitle">${escapeHtml(detail)}</span>
                </button>
            `;
        }).join('');
    }

    function openAchievementsModal() {
        if (!elements.achievementsModal) return;
        renderAchievementsModal();
        elements.achievementsModal.classList.remove('hidden');
    }

    function setAuthStatusMessage(message, tone = 'neutral') {
        if (!elements.authModalStatus) return;
        elements.authModalStatus.textContent = message || '';
        elements.authModalStatus.classList.remove('error', 'success');
        if (tone === 'error') {
            elements.authModalStatus.classList.add('error');
        } else if (tone === 'success') {
            elements.authModalStatus.classList.add('success');
        }
    }

    function isAccountModalVisible() {
        return !!elements.accountModal && !elements.accountModal.classList.contains('hidden');
    }

    function setAccountStatusMessage(message, tone = 'neutral') {
        if (!elements.accountModalStatus) return;
        elements.accountModalStatus.textContent = message || '';
        elements.accountModalStatus.classList.remove('error', 'success');
        if (tone === 'error') {
            elements.accountModalStatus.classList.add('error');
        } else if (tone === 'success') {
            elements.accountModalStatus.classList.add('success');
        }
    }

    function updateAccountUi() {
        const showAccount = state.accountAuthEnabled;
        const accountLabel = state.accountAuthenticated ? 'Account' : 'Sign In';

        if (elements.accountToggle) {
            elements.accountToggle.classList.toggle('hidden', !showAccount);
            elements.accountToggle.classList.toggle('authenticated', state.accountAuthenticated);
            elements.accountToggle.textContent = accountLabel;
            elements.accountToggle.title = state.accountAuthenticated && state.accountEmail
                ? `Reader account (${state.accountEmail})`
                : 'Reader account';
        }
        if (elements.accountToggleLibrary) {
            elements.accountToggleLibrary.classList.toggle('hidden', !showAccount);
            elements.accountToggleLibrary.textContent = state.accountAuthenticated
                ? `Account: ${state.accountEmail || 'Signed In'}`
                : 'Sign In';
        }
        if (elements.accountLibraryStatus) {
            const requiredSignIn = showAccount && state.accountRequired && !state.accountAuthenticated;
            const showStatus = showAccount && (
                (state.accountAuthenticated && !!state.accountEmail)
                || requiredSignIn
            );
            elements.accountLibraryStatus.classList.toggle('hidden', !showStatus);
            elements.accountLibraryStatus.textContent = showStatus
                ? (state.accountAuthenticated
                    ? `Signed in as ${state.accountEmail}`
                    : 'Account sign-in is required for this rollout.')
                : '';
        }
        updateMobileHeaderMenuState();
    }

    function accountNoticeConfig(code) {
        switch (code) {
            case 'google_signed_in':
                return { tone: 'success', message: 'Signed in with Google.', toast: true };
            case 'google_unavailable':
                return { tone: 'error', message: 'Google sign-in is not configured right now.' };
            case 'google_cancelled':
                return { tone: 'error', message: 'Google sign-in was cancelled.' };
            case 'google_provider_error':
                return { tone: 'error', message: 'Google sign-in failed before your account could be verified.' };
            case 'google_state_mismatch':
                return { tone: 'error', message: 'Google sign-in could not be verified. Please try again.' };
            case 'google_code_missing':
                return { tone: 'error', message: 'Google sign-in did not return an authorization code.' };
            case 'google_token_exchange_failed':
                return { tone: 'error', message: 'Google sign-in could not be completed right now.' };
            case 'google_id_token_invalid':
                return { tone: 'error', message: 'Google sign-in returned an invalid identity token.' };
            case 'google_email_unverified':
                return { tone: 'error', message: 'Google sign-in requires a verified Google email address.' };
            case 'google_rollout_restricted':
                return { tone: 'error', message: 'This account is not in the current reader-account rollout.' };
            case 'google_signin_failed':
                return { tone: 'error', message: 'Google sign-in could not be completed.' };
            default:
                return null;
        }
    }

    function consumeAccountNotice() {
        const url = new URL(window.location.href);
        const notice = url.searchParams.get('account_notice');
        if (!notice) {
            return;
        }

        url.searchParams.delete('account_notice');
        window.history.replaceState({}, document.title, `${url.pathname}${url.search}${url.hash}`);

        const config = accountNoticeConfig(notice);
        if (!config) {
            return;
        }

        if (config.toast) {
            showAppToast({
                title: 'Reader account',
                message: config.message,
                autoDismissMs: 3200
            });
            return;
        }

        openAccountModal(config.message);
        setAccountStatusMessage(config.message, config.tone);
    }

    function openAccountModal(message = '') {
        if (!elements.accountModal) return;
        closeMobileHeaderMenu();
        elements.accountModal.classList.remove('hidden');

        const authenticated = state.accountAuthenticated;
        if (elements.accountModalTitle) {
            elements.accountModalTitle.textContent = authenticated ? 'Reader Account' : 'Sign In';
        }
        if (elements.accountModalHelp) {
            elements.accountModalHelp.textContent = authenticated
                ? 'Your reader account is active in this browser.'
                : (state.accountGoogleAuthEnabled
                    ? 'Use Google for the quickest sign-in, or use email and password below.'
                    : 'Use email and password to sign in and sync progress across devices.');
        }
        if (elements.accountModalEmail) {
            elements.accountModalEmail.classList.toggle('hidden', !authenticated);
            elements.accountModalEmail.textContent = authenticated
                ? `Signed in as ${state.accountEmail || 'your account'}.`
                : '';
        }
        if (elements.accountSignOut) {
            elements.accountSignOut.classList.toggle('hidden', !authenticated);
        }
        if (elements.accountGoogleSignIn) {
            const showGoogle = !authenticated && state.accountGoogleAuthEnabled;
            elements.accountGoogleSignIn.classList.toggle('hidden', !showGoogle);
            elements.accountGoogleSignIn.disabled = false;
            if (elements.accountGoogleSignInLabel) {
                elements.accountGoogleSignInLabel.textContent = 'Sign in with Google';
            }
        }
        if (elements.accountAuthDivider) {
            const showDivider = !authenticated && state.accountGoogleAuthEnabled;
            elements.accountAuthDivider.classList.toggle('hidden', !showDivider);
        }
        if (elements.accountSignIn) {
            elements.accountSignIn.classList.toggle('hidden', authenticated);
            elements.accountSignIn.disabled = false;
            elements.accountSignIn.textContent = 'Sign In';
        }
        if (elements.accountRegister) {
            elements.accountRegister.classList.toggle('hidden', authenticated);
            elements.accountRegister.disabled = false;
            elements.accountRegister.textContent = 'Register';
        }
        if (elements.accountEmail) {
            elements.accountEmail.disabled = authenticated;
            elements.accountEmail.value = authenticated ? (state.accountEmail || '') : '';
        }
        if (elements.accountPassword) {
            elements.accountPassword.disabled = authenticated;
            elements.accountPassword.value = '';
            if (!authenticated) {
                if (state.accountGoogleAuthEnabled && elements.accountGoogleSignIn && !elements.accountGoogleSignIn.classList.contains('hidden')) {
                    elements.accountGoogleSignIn.focus();
                } else {
                    elements.accountEmail?.focus();
                }
            }
        }

        setAccountStatusMessage(message || (authenticated
            ? 'Your account is active in this browser.'
            : (state.accountRequired
                ? 'Sign in is required in this rollout.'
                : 'Sign in or register to sync your reader data.')));
    }

    function closeAccountModal() {
        if (!elements.accountModal) return;
        elements.accountModal.classList.add('hidden');
        setAccountStatusMessage('');
    }

    function collectRecapOptOutSnapshot() {
        const snapshot = {};
        for (let i = 0; i < localStorage.length; i += 1) {
            const key = localStorage.key(i);
            if (!key || !key.startsWith(STORAGE_KEYS.RECAP_OPTOUT_PREFIX)) {
                continue;
            }
            const bookId = key.slice(STORAGE_KEYS.RECAP_OPTOUT_PREFIX.length);
            if (!bookId) continue;
            snapshot[bookId] = localStorage.getItem(key) === 'true';
        }
        return snapshot;
    }

    function collectLocalAccountStateSnapshot() {
        const readerPreferences = normalizeReaderPreferences(state.readerPreferences || loadStoredReaderPreferences());
        const readerPreferencesUpdatedAt = localStorage.getItem(STORAGE_KEYS.READER_PREFERENCES_UPDATED_AT);
        return {
            favoriteBookIds: readFavoriteBookIdsFromStorage(),
            bookActivity: readBookActivityStore(),
            readerPreferences: {
                ...readerPreferences,
                updatedAt: readerPreferencesUpdatedAt || null
            },
            recapOptOut: collectRecapOptOutSnapshot()
        };
    }

    function applyAccountStateSnapshot(snapshot) {
        if (!snapshot || typeof snapshot !== 'object') {
            return;
        }

        if (Array.isArray(snapshot.favoriteBookIds)) {
            localStorage.setItem(STORAGE_KEYS.FAVORITE_BOOKS, JSON.stringify(snapshot.favoriteBookIds));
        }
        if (snapshot.bookActivity && typeof snapshot.bookActivity === 'object') {
            localStorage.setItem(STORAGE_KEYS.BOOK_ACTIVITY, JSON.stringify(snapshot.bookActivity));
        }
        if (snapshot.readerPreferences && typeof snapshot.readerPreferences === 'object') {
            const normalized = normalizeReaderPreferences(snapshot.readerPreferences);
            localStorage.setItem(STORAGE_KEYS.READER_PREFERENCES, JSON.stringify(normalized));
            const updatedAt = typeof snapshot.readerPreferences.updatedAt === 'string'
                ? snapshot.readerPreferences.updatedAt
                : new Date().toISOString();
            localStorage.setItem(STORAGE_KEYS.READER_PREFERENCES_UPDATED_AT, updatedAt);
            state.readerPreferences = normalized;
            applyReaderPreferences();
            syncReaderPreferencesControls();
        }
        if (snapshot.recapOptOut && typeof snapshot.recapOptOut === 'object') {
            const keysToClear = [];
            for (let i = 0; i < localStorage.length; i += 1) {
                const key = localStorage.key(i);
                if (key && key.startsWith(STORAGE_KEYS.RECAP_OPTOUT_PREFIX)) {
                    keysToClear.push(key);
                }
            }
            keysToClear.forEach(key => localStorage.removeItem(key));
            Object.entries(snapshot.recapOptOut).forEach(([bookId, value]) => {
                if (typeof bookId !== 'string' || !bookId.trim()) {
                    return;
                }
                localStorage.setItem(
                    `${STORAGE_KEYS.RECAP_OPTOUT_PREFIX}${bookId.trim()}`,
                    value === true ? 'true' : 'false'
                );
            });
            if (state.currentBook?.id) {
                state.recapOptOut = getRecapOptOut(state.currentBook.id);
                updateRecapOptOutControl();
            }
        }

        syncFavoriteBooksWithLocalBooks();
        syncBookActivityWithLocalBooks();
        updateFavoriteUi();
    }

    async function runAccountClaimSync(force = false) {
        if (!state.accountAuthEnabled || !state.accountAuthenticated) {
            return;
        }
        const syncKey = state.accountEmail || 'signed-in-account';
        if (!force && state.accountClaimSyncedFor === syncKey) {
            return;
        }
        if (state.accountSyncInFlight) {
            return;
        }

        state.accountSyncInFlight = true;
        if (isAccountModalVisible()) {
            setAccountStatusMessage('Syncing your reader data...');
        }

        try {
            const response = await nativeFetch('/api/account/claim-sync', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ state: collectLocalAccountStateSnapshot() })
            });
            const payload = await readErrorPayload(response);
            if (!response.ok) {
                const message = firstMessageFromPayload(payload) || 'Unable to sync account data.';
                if (isAccountModalVisible()) {
                    setAccountStatusMessage(message, 'error');
                }
                return;
            }

            if (payload && typeof payload === 'object' && payload.state) {
                applyAccountStateSnapshot(payload.state);
            }
            state.accountClaimSyncedFor = syncKey;

            if (payload?.claimApplied) {
                showAppToast({
                    title: 'Account data synced',
                    message: 'Anonymous reader data has been moved into your account.',
                    autoDismissMs: 3200
                });
            }

            if (elements.libraryView && !elements.libraryView.classList.contains('hidden')) {
                renderLibrary(state.librarySearchQuery || '');
            }
            if (state.currentBook?.id) {
                void loadAnnotationsForCurrentBook();
                void refreshBookmarksForCurrentBook();
            }
            if (state.quizAvailable) {
                state.achievementsLoaded = false;
                void loadAchievementsShelf(true);
            }
            if (isAccountModalVisible()) {
                setAccountStatusMessage('Account data is up to date.', 'success');
            }
        } catch (error) {
            console.debug('Account claim sync failed:', error);
            if (isAccountModalVisible()) {
                setAccountStatusMessage('Unable to sync account data right now.', 'error');
            }
        } finally {
            state.accountSyncInFlight = false;
        }
    }

    async function accountCheckStatus(options = {}) {
        const { triggerSync = true } = options;
        try {
            const response = await nativeFetch('/api/account/status', { cache: 'no-store' });
            if (!response.ok) {
                return;
            }
            const status = await response.json();
            state.accountAuthEnabled = status.accountAuthEnabled === true;
            state.accountGoogleAuthEnabled = status.googleAuthEnabled === true;
            state.accountAuthenticated = status.authenticated === true;
            state.accountEmail = typeof status.email === 'string' ? status.email : null;
            state.accountRolloutMode = typeof status.rolloutMode === 'string'
                ? status.rolloutMode
                : 'optional';
            state.accountRequired = status.accountRequired === true;

            if (!state.accountAuthenticated) {
                state.accountClaimSyncedFor = null;
            }
            updateAccountUi();

            if (triggerSync && state.accountAuthenticated) {
                await runAccountClaimSync();
            }
        } catch (error) {
            console.debug('Account status check failed:', error);
        }
    }

    function startGoogleAccountAuth() {
        if (!state.accountGoogleAuthEnabled) {
            setAccountStatusMessage('Google sign-in is not available right now.', 'error');
            return;
        }
        if (elements.accountGoogleSignIn) {
            elements.accountGoogleSignIn.disabled = true;
        }
        if (elements.accountGoogleSignInLabel) {
            elements.accountGoogleSignInLabel.textContent = 'Redirecting...';
        }
        const returnTo = `${window.location.pathname}${window.location.search}${window.location.hash}`;
        window.location.assign(`/api/account/google/start?returnTo=${encodeURIComponent(returnTo)}`);
    }

    async function submitAccountAuth(mode) {
        if (!elements.accountEmail || !elements.accountPassword) return;
        const email = (elements.accountEmail.value || '').trim();
        const password = elements.accountPassword.value || '';
        if (!email || !password) {
            setAccountStatusMessage('Email and password are required.', 'error');
            return;
        }

        if (elements.accountSignIn) {
            elements.accountSignIn.disabled = true;
            elements.accountSignIn.textContent = mode === 'login' ? 'Signing In...' : 'Sign In';
        }
        if (elements.accountRegister) {
            elements.accountRegister.disabled = true;
            elements.accountRegister.textContent = mode === 'register' ? 'Registering...' : 'Register';
        }

        try {
            const response = await nativeFetch(`/api/account/${mode}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email, password })
            });
            const payload = await readErrorPayload(response);
            if (!response.ok) {
                setAccountStatusMessage(firstMessageFromPayload(payload) || 'Account sign-in failed.', 'error');
                return;
            }

            state.accountClaimSyncedFor = null;
            await accountCheckStatus({ triggerSync: true });
            setAccountStatusMessage(firstMessageFromPayload(payload) || 'Signed in.', 'success');
            closeAccountModal();
        } catch (error) {
            console.debug('Account auth request failed:', error);
            setAccountStatusMessage('Unable to complete account request.', 'error');
        } finally {
            if (elements.accountSignIn) {
                elements.accountSignIn.disabled = false;
                elements.accountSignIn.textContent = 'Sign In';
            }
            if (elements.accountRegister) {
                elements.accountRegister.disabled = false;
                elements.accountRegister.textContent = 'Register';
            }
        }
    }

    async function submitAccountLogin() {
        await submitAccountAuth('login');
    }

    async function submitAccountRegister() {
        await submitAccountAuth('register');
    }

    async function submitAccountLogout() {
        try {
            await nativeFetch('/api/account/logout', { method: 'POST' });
            state.accountClaimSyncedFor = null;
            await accountCheckStatus({ triggerSync: false });
            setAccountStatusMessage('Signed out.', 'success');
            closeAccountModal();
        } catch (error) {
            console.debug('Account logout failed:', error);
            setAccountStatusMessage('Unable to sign out.', 'error');
        }
    }

    function updateAuthUi() {
        if (!elements.authToggle) return;

        const showDesktopAuthToggle = state.authPublicMode
            && (!state.accountAuthEnabled || state.authAuthenticated);
        if (!showDesktopAuthToggle) {
            elements.authToggle.classList.add('hidden');
            updateMobileHeaderMenuState();
            return;
        }

        elements.authToggle.classList.remove('hidden');
        elements.authToggle.classList.toggle('authenticated', state.authAuthenticated);
        elements.authToggle.textContent = state.authAuthenticated ? 'Collaborator' : 'Sign In';
        updateMobileHeaderMenuState();
    }

    async function authCheckStatus() {
        try {
            const response = await nativeFetch('/api/auth/status', { cache: 'no-store' });
            if (!response.ok) {
                return;
            }
            const status = await response.json();
            state.authPublicMode = status.publicMode === true;
            state.authRequired = status.authRequired === true;
            state.authAuthenticated = status.authenticated === true;
            state.authCanAccessSensitive = status.canAccessSensitive !== false;
            if (state.authAuthenticated || !state.authPublicMode) {
                state.authPromptShown = false;
            }
            updateAuthUi();
        } catch (error) {
            console.debug('Auth status check failed:', error);
        }
    }

    function openAuthModal(message = '') {
        if (!elements.authModal) return;
        closeMobileHeaderMenu();
        state.authPromptShown = true;
        elements.authModal.classList.remove('hidden');
        if (elements.authSignOut) {
            elements.authSignOut.classList.toggle('hidden', !state.authAuthenticated);
        }
        if (elements.authSignIn) {
            elements.authSignIn.textContent = state.authAuthenticated ? 'Refresh Sign In' : 'Sign In';
        }
        setAuthStatusMessage(message || (state.authAuthenticated
            ? 'You are signed in.'
            : 'Sign in to use protected generation and chat features.'));
        if (elements.authPassword) {
            elements.authPassword.value = '';
            elements.authPassword.focus();
        }
    }

    function closeAuthModal() {
        if (!elements.authModal) return;
        elements.authModal.classList.add('hidden');
        setAuthStatusMessage('');
    }

    async function submitAuthLogin() {
        if (!elements.authPassword) return;
        const password = elements.authPassword.value || '';
        if (!password.trim()) {
            setAuthStatusMessage('Password is required.', 'error');
            return;
        }

        if (elements.authSignIn) {
            elements.authSignIn.disabled = true;
            elements.authSignIn.textContent = 'Signing In...';
        }

        try {
            const response = await nativeFetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password })
            });
            const payload = await response.json().catch(() => ({}));
            if (!response.ok) {
                setAuthStatusMessage(payload.message || 'Sign-in failed.', 'error');
                return;
            }

            setAuthStatusMessage(payload.message || 'Signed in.', 'success');
            await authCheckStatus();
            closeAuthModal();
        } catch (error) {
            console.debug('Auth login failed:', error);
            setAuthStatusMessage('Sign-in failed.', 'error');
        } finally {
            if (elements.authSignIn) {
                elements.authSignIn.disabled = false;
                elements.authSignIn.textContent = state.authAuthenticated ? 'Refresh Sign In' : 'Sign In';
            }
        }
    }

    async function submitAuthLogout() {
        try {
            await nativeFetch('/api/auth/logout', { method: 'POST' });
            await authCheckStatus();
            setAuthStatusMessage('Signed out.', 'success');
        } catch (error) {
            console.debug('Auth logout failed:', error);
            setAuthStatusMessage('Unable to sign out.', 'error');
        }
    }

    function handleSensitiveUnauthorized() {
        if (!state.authPublicMode) return;
        state.authCanAccessSensitive = false;
        state.authAuthenticated = false;
        updateAuthUi();
        if (isAuthModalVisible() || state.authPromptShown) return;
        openAuthModal('Sign in is required for this action.');
    }

    function isReaderSettingsPanelVisible() {
        return !!elements.readerSettingsPanel && !elements.readerSettingsPanel.classList.contains('hidden');
    }

    function normalizePreferenceNumber(value, fallback, min, max) {
        if (typeof value !== 'number' || Number.isNaN(value)) {
            return fallback;
        }
        return Math.min(max, Math.max(min, value));
    }

    function normalizeReaderPreferences(raw) {
        const source = raw || {};
        const theme = Object.prototype.hasOwnProperty.call(READER_THEMES, source.theme)
            ? source.theme
            : DEFAULT_READER_PREFERENCES.theme;
        return {
            fontSize: normalizePreferenceNumber(
                parseFloat(source.fontSize),
                DEFAULT_READER_PREFERENCES.fontSize,
                1.0,
                1.5
            ),
            lineHeight: normalizePreferenceNumber(
                parseFloat(source.lineHeight),
                DEFAULT_READER_PREFERENCES.lineHeight,
                1.4,
                2.1
            ),
            columnGap: normalizePreferenceNumber(
                parseFloat(source.columnGap),
                DEFAULT_READER_PREFERENCES.columnGap,
                2.0,
                6.0
            ),
            theme
        };
    }

    function loadStoredReaderPreferences() {
        const defaults = detectMobileLayout()
            ? MOBILE_DEFAULT_READER_PREFERENCES
            : DEFAULT_READER_PREFERENCES;
        const raw = localStorage.getItem(STORAGE_KEYS.READER_PREFERENCES);
        if (!raw) {
            return { ...defaults };
        }
        try {
            return normalizeReaderPreferences(JSON.parse(raw));
        } catch (_error) {
            return { ...defaults };
        }
    }

    function saveReaderPreferences() {
        localStorage.setItem(STORAGE_KEYS.READER_PREFERENCES, JSON.stringify(state.readerPreferences));
        localStorage.setItem(STORAGE_KEYS.READER_PREFERENCES_UPDATED_AT, new Date().toISOString());
    }

    function applyReaderPreferences() {
        if (!state.readerPreferences) return;
        const theme = READER_THEMES[state.readerPreferences.theme] || READER_THEMES.warm;
        const rootStyle = document.documentElement.style;
        rootStyle.setProperty('--font-size-body', `${state.readerPreferences.fontSize.toFixed(2)}rem`);
        rootStyle.setProperty('--line-height', state.readerPreferences.lineHeight.toFixed(2));
        rootStyle.setProperty('--column-gap', `${state.readerPreferences.columnGap.toFixed(2)}rem`);
        rootStyle.setProperty('--text-color', theme.textColor);
        rootStyle.setProperty('--bg-color', theme.bgColor);
        rootStyle.setProperty('--muted-color', theme.mutedColor);
        rootStyle.setProperty('--border-color', theme.borderColor);
        rootStyle.setProperty('--highlight-color', theme.highlightColor);
    }

    function syncReaderPreferencesControls() {
        if (!state.readerPreferences) return;
        if (elements.readerFontSize) {
            elements.readerFontSize.value = state.readerPreferences.fontSize.toFixed(2);
        }
        if (elements.readerFontSizeValue) {
            elements.readerFontSizeValue.textContent = `${state.readerPreferences.fontSize.toFixed(2)}rem`;
        }
        if (elements.readerLineHeight) {
            elements.readerLineHeight.value = state.readerPreferences.lineHeight.toFixed(2);
        }
        if (elements.readerLineHeightValue) {
            elements.readerLineHeightValue.textContent = state.readerPreferences.lineHeight.toFixed(2);
        }
        if (elements.readerColumnGap) {
            elements.readerColumnGap.value = state.readerPreferences.columnGap.toFixed(2);
        }
        if (elements.readerColumnGapValue) {
            elements.readerColumnGapValue.textContent = `${state.readerPreferences.columnGap.toFixed(2)}rem`;
        }
        if (elements.readerTheme) {
            elements.readerTheme.value = state.readerPreferences.theme;
        }
    }

    function repaginateFromCurrentParagraph() {
        if (elements.readerView.classList.contains('hidden')) return;
        if (!Array.isArray(state.paragraphs) || state.paragraphs.length === 0) return;

        const targetParagraph = Math.max(0, Math.min(state.currentParagraphIndex, state.paragraphs.length - 1));
        calculatePages();
        let targetPage = state.pagesData.findIndex((page) =>
            targetParagraph >= page.startParagraph && targetParagraph <= page.endParagraph
        );
        if (targetPage < 0) {
            targetPage = Math.max(0, Math.min(state.currentPage, state.totalPages - 1));
        }
        state.currentPage = targetPage;
        state.currentParagraphIndex = targetParagraph;
        renderPage();
    }

    function setReaderPreferences(nextPartial, options = {}) {
        const repaginate = options.repaginate !== false;
        state.readerPreferences = normalizeReaderPreferences({
            ...state.readerPreferences,
            ...nextPartial
        });
        applyReaderPreferences();
        syncReaderPreferencesControls();
        saveReaderPreferences();
        if (repaginate) {
            repaginateFromCurrentParagraph();
        }
    }

    function openReaderSettingsPanel() {
        if (!elements.readerSettingsPanel || !elements.readerSettingsToggle) return;
        if (elements.searchResults) {
            elements.searchResults.classList.add('hidden');
        }
        elements.readerSettingsPanel.classList.remove('hidden');
        elements.readerSettingsToggle.classList.add('active');
    }

    function closeReaderSettingsPanel() {
        if (!elements.readerSettingsPanel || !elements.readerSettingsToggle) return;
        elements.readerSettingsPanel.classList.add('hidden');
        elements.readerSettingsToggle.classList.remove('active');
    }

    function toggleReaderSettingsPanel() {
        if (isReaderSettingsPanelVisible()) {
            closeReaderSettingsPanel();
            return;
        }
        syncReaderPreferencesControls();
        openReaderSettingsPanel();
    }

    // Initialize
    async function init() {
        installAuthAwareFetch();
        state.readerPreferences = loadStoredReaderPreferences();
        applyReaderPreferences();
        syncReaderPreferencesControls();
        setupEventListeners();
        setDesktopSearchExpanded(false);
        await loadClassroomContext();
        await loadLibrary();
        await accountCheckStatus();
        consumeAccountNotice();
        await authCheckStatus();
        await speedReadingCheckAvailability();
        applyLayoutCapabilities();
        await ttsCheckAvailability();
        await illustrationCheckAvailability();
        await characterCheckAvailability();
        await recapCheckAvailability();
        await quizCheckAvailability();

        // Load saved TTS speed preference
        const savedSpeed = parseFloat(localStorage.getItem(STORAGE_KEYS.TTS_SPEED));
        if (savedSpeed && [1.0, 1.25, 1.5, 1.75, 2.0].includes(savedSpeed)) {
            state.ttsPlaybackRate = savedSpeed;
        }

        // Load saved speed reading preference
        const savedWpm = parseInt(localStorage.getItem(STORAGE_KEYS.SPEED_READING_WPM), 10);
        if (!Number.isNaN(savedWpm) && savedWpm >= 150 && savedWpm <= 800) {
            state.speedReadingWpm = savedWpm;
        }

        // Load saved illustration mode preference
        const savedIllustrationMode = localStorage.getItem(STORAGE_KEYS.ILLUSTRATION_MODE);
        if (savedIllustrationMode === 'true' && state.illustrationAvailable) {
            state.illustrationMode = true;
            if (elements.illustrationToggle) {
                elements.illustrationToggle.classList.add('active');
            }
        }

        updateSpeedReadingControls();

        updateAnnotationControls();
        updateFavoriteUi();
        await openBookFromUrlIfPresent();
    }

    function readInitialReaderRoute() {
        const url = new URL(window.location.href);
        const bookId = (url.searchParams.get('book') || '').trim();
        const chapterId = (url.searchParams.get('chapter') || '').trim();
        const paragraphRaw = (url.searchParams.get('paragraph') || '').trim();
        const parsedParagraph = Number.parseInt(paragraphRaw, 10);
        const paragraphIndex = Number.isInteger(parsedParagraph) && parsedParagraph > 0
            ? parsedParagraph - 1
            : 0;

        if (!bookId) {
            return null;
        }

        return {
            bookId,
            chapterId,
            paragraphIndex
        };
    }

    async function openBookFromUrlIfPresent() {
        const route = readInitialReaderRoute();
        if (!route) {
            return false;
        }

        const book = (state.localBooks || []).find(item => item && item.id === route.bookId);
        if (!book) {
            return false;
        }

        let chapterIndex = 0;
        if (route.chapterId) {
            const matchedChapterIndex = (book.chapters || []).findIndex(chapter => chapter && chapter.id === route.chapterId);
            if (matchedChapterIndex >= 0) {
                chapterIndex = matchedChapterIndex;
            }
        }

        await selectBook(book, chapterIndex, 0, route.paragraphIndex);
        return true;
    }

    function setLibrarySearchBusy(busy, statusMessage = '') {
        if (elements.librarySearchContainer) {
            elements.librarySearchContainer.classList.toggle('is-loading', busy);
        }
        if (elements.bookList) {
            elements.bookList.classList.toggle('is-loading', busy);
        }
        if (elements.librarySearchSubmit) {
            elements.librarySearchSubmit.disabled = busy;
            elements.librarySearchSubmit.textContent = busy ? 'Searching...' : 'Search';
        }
        if (elements.librarySearchStatus) {
            if (busy && statusMessage) {
                elements.librarySearchStatus.textContent = statusMessage;
                elements.librarySearchStatus.classList.remove('hidden');
            } else {
                elements.librarySearchStatus.textContent = '';
                elements.librarySearchStatus.classList.add('hidden');
            }
        }
    }

    function showLibrarySearchError(message, onRetry = null) {
        if (!elements.librarySearchError || !elements.librarySearchErrorMessage || !message) {
            return;
        }
        state.librarySearchRetryHandler = typeof onRetry === 'function' ? onRetry : null;
        elements.librarySearchErrorMessage.textContent = message;
        elements.librarySearchError.classList.remove('hidden');
        if (elements.librarySearchRetry) {
            elements.librarySearchRetry.classList.toggle('hidden', !state.librarySearchRetryHandler);
        }
    }

    function clearLibrarySearchError() {
        state.librarySearchRetryHandler = null;
        if (elements.librarySearchError) {
            elements.librarySearchError.classList.add('hidden');
        }
        if (elements.librarySearchErrorMessage) {
            elements.librarySearchErrorMessage.textContent = '';
        }
        if (elements.librarySearchRetry) {
            elements.librarySearchRetry.classList.add('hidden');
        }
    }

    function submitLibrarySearch() {
        if (elements.librarySearchSubmit?.disabled) {
            return;
        }
        const query = (elements.librarySearch?.value || '').trim();
        void searchCatalog(query);
    }

    function normalizeClassroomFeatures(features) {
        return {
            quizEnabled: features?.quizEnabled !== false,
            recapEnabled: features?.recapEnabled !== false,
            ttsEnabled: features?.ttsEnabled !== false,
            illustrationEnabled: features?.illustrationEnabled !== false,
            characterEnabled: features?.characterEnabled !== false,
            chatEnabled: features?.chatEnabled !== false,
            speedReadingEnabled: features?.speedReadingEnabled !== false,
            citationEnabled: features?.citationEnabled !== false
        };
    }

    function normalizeClassroomContext(payload) {
        const enrolled = payload?.enrolled === true;
        if (!enrolled) {
            return {
                enrolled: false,
                classId: null,
                className: null,
                teacherName: null,
                features: { ...DEFAULT_CLASSROOM_FEATURES },
                assignments: []
            };
        }

        const assignments = Array.isArray(payload?.assignments)
            ? payload.assignments
                .filter(item => item && typeof item.bookId === 'string' && item.bookId.trim().length > 0)
                .map((item, index) => ({
                    assignmentId: typeof item.assignmentId === 'string' && item.assignmentId.trim().length > 0
                        ? item.assignmentId.trim()
                        : `assignment-${index + 1}`,
                    title: typeof item.title === 'string' ? item.title.trim() : '',
                    bookId: item.bookId.trim(),
                    bookTitle: typeof item.bookTitle === 'string' ? item.bookTitle.trim() : '',
                    bookAuthor: typeof item.bookAuthor === 'string' ? item.bookAuthor.trim() : '',
                    chapterId: typeof item.chapterId === 'string' && item.chapterId.trim().length > 0
                        ? item.chapterId.trim()
                        : null,
                    chapterIndex: Number.isInteger(item.chapterIndex) ? item.chapterIndex : null,
                    chapterTitle: typeof item.chapterTitle === 'string' ? item.chapterTitle.trim() : '',
                    dueAt: typeof item.dueAt === 'string' && item.dueAt.trim().length > 0
                        ? item.dueAt.trim()
                        : null,
                    quizRequired: item.quizRequired === true,
                    quizStatus: typeof item.quizStatus === 'string' ? item.quizStatus : 'UNKNOWN',
                    bookAvailable: item.bookAvailable !== false
                }))
            : [];

        return {
            enrolled: true,
            classId: typeof payload?.classId === 'string' ? payload.classId.trim() : null,
            className: typeof payload?.className === 'string' ? payload.className.trim() : null,
            teacherName: typeof payload?.teacherName === 'string' ? payload.teacherName.trim() : null,
            features: normalizeClassroomFeatures(payload?.features),
            assignments
        };
    }

    function isClassroomFeatureEnabled(featureName) {
        if (!state.classroomContext?.enrolled) {
            return true;
        }
        const features = state.classroomContext.features || DEFAULT_CLASSROOM_FEATURES;
        return features[featureName] !== false;
    }

    async function loadClassroomContext() {
        try {
            const response = await fetch('/api/classroom/context', { cache: 'no-store' });
            if (!response.ok) {
                throw new Error(`Classroom context request failed with status ${response.status}`);
            }
            const payload = await response.json();
            state.classroomContext = normalizeClassroomContext(payload);
            state.classroomAssignments = state.classroomContext.assignments || [];
        } catch (error) {
            console.debug('Failed to load classroom context:', error);
            state.classroomContext = normalizeClassroomContext(null);
            state.classroomAssignments = [];
        }
    }

    // Load library - both local books and popular from catalog
    async function loadLibrary() {
        const requestId = ++libraryLoadRequestId;
        setLibrarySearchBusy(true, 'Loading library...');
        clearLibrarySearchError();
        try {
            // Load local books and discover catalog in parallel.
            const [localResponse, catalogResponse] = await Promise.all([
                fetch('/api/library'),
                fetch('/api/import/popular')
            ]);

            if (!localResponse.ok) {
                throw new Error(`Local library request failed with status ${localResponse.status}`);
            }
            if (!catalogResponse.ok) {
                throw new Error(`Popular catalog request failed with status ${catalogResponse.status}`);
            }

            const [localBooks, popularCatalog] = await Promise.all([
                localResponse.json(),
                catalogResponse.json()
            ]);
            if (requestId !== libraryLoadRequestId) {
                return;
            }

            state.localBooks = localBooks;
            syncFavoriteBooksWithLocalBooks();
            syncBookActivityWithLocalBooks();
            state.achievementsLoaded = false;
            state.achievementsLoading = false;
            state.achievementsSummary = '';
            state.achievementsItems = [];
            state.achievementsAllItems = [];
            state.popularCatalogBooks = Array.isArray(popularCatalog) ? popularCatalog : [];
            state.catalogBooks = Array.isArray(popularCatalog) ? popularCatalog : [];
            state.librarySearchQuery = '';
            renderLibrary();
        } catch (error) {
            console.error('Failed to load library:', error);
            if (requestId !== libraryLoadRequestId) {
                return;
            }
            showLibrarySearchError(
                'Unable to load the library right now.',
                () => { void loadLibrary(); }
            );
        } finally {
            if (requestId === libraryLoadRequestId) {
                setLibrarySearchBusy(false);
            }
        }
    }

    async function speedReadingCheckAvailability() {
        try {
            const response = await fetch('/api/features');
            if (response.ok) {
                const features = await response.json();
                state.speedReadingEnabled = features.speedReadingEnabled !== false
                    && isClassroomFeatureEnabled('speedReadingEnabled');
                state.catalogMode = typeof features.catalogMode === 'string'
                    ? features.catalogMode.toLowerCase()
                    : null;
                state.catalogCuratedOnly = features.catalogCuratedOnly === true
                    || state.catalogMode === 'curated';
            }
        } catch (error) {
            console.warn('Speed reading feature check failed:', error);
        }

        state.speedReadingEnabled = state.speedReadingEnabled && isClassroomFeatureEnabled('speedReadingEnabled');
        updateLibraryCatalogModeBadge();

        applySpeedReadingAvailability();
    }

    function updateLibraryCatalogModeBadge() {
        if (!elements.libraryCatalogModeBadge) return;
        const showCuratedBadge = state.catalogCuratedOnly || state.catalogMode === 'curated';
        elements.libraryCatalogModeBadge.classList.toggle('hidden', !showCuratedBadge);
        if (showCuratedBadge) {
            elements.libraryCatalogModeBadge.textContent = 'curated';
        }
    }

    function applySpeedReadingAvailability() {
        if (state.speedReadingEnabled) {
            if (elements.speedReadingContainer) {
                elements.speedReadingContainer.style.display = '';
            }
            if (elements.speedReadingHint) {
                elements.speedReadingHint.style.display = '';
            }
            if (elements.speedReadingOverlay) {
                elements.speedReadingOverlay.style.display = '';
            }
            if (elements.speedReadingChapterOverlay) {
                elements.speedReadingChapterOverlay.style.display = '';
            }
            return;
        }

        exitSpeedReading(true);
        if (elements.speedReadingContainer) {
            elements.speedReadingContainer.style.display = 'none';
        }
        if (elements.speedReadingHint) {
            elements.speedReadingHint.style.display = 'none';
        }
        if (elements.speedReadingOverlay) {
            elements.speedReadingOverlay.classList.add('hidden');
            elements.speedReadingOverlay.style.display = 'none';
        }
        if (elements.speedReadingChapterOverlay) {
            elements.speedReadingChapterOverlay.classList.add('hidden');
            elements.speedReadingChapterOverlay.style.display = 'none';
        }
    }

    // Search the Gutenberg catalog
    async function searchCatalog(query) {
        const normalizedQuery = (query || '').trim();
        const requestId = ++catalogSearchRequestId;
        const searchingPopular = normalizedQuery.length < 2;

        if (catalogSearchAbortController) {
            catalogSearchAbortController.abort();
        }
        catalogSearchAbortController = new AbortController();
        const signal = catalogSearchAbortController.signal;
        clearLibrarySearchError();
        setLibrarySearchBusy(
            true,
            searchingPopular ? 'Refreshing discover books...' : `Searching for "${normalizedQuery}"...`
        );

        try {
            const response = await fetch(
                searchingPopular
                    ? '/api/import/popular'
                    : `/api/import/search?q=${encodeURIComponent(normalizedQuery)}`,
                { signal }
            );
            if (!response.ok) {
                throw new Error(`Catalog search request failed with status ${response.status}`);
            }
            const catalogBooks = await response.json();
            if (requestId !== catalogSearchRequestId) {
                return;
            }
            if (searchingPopular) {
                state.popularCatalogBooks = Array.isArray(catalogBooks) ? catalogBooks : [];
            }
            state.catalogBooks = Array.isArray(catalogBooks) ? catalogBooks : [];
            state.librarySearchQuery = normalizedQuery;
            renderLibrary(normalizedQuery);
        } catch (error) {
            if (signal.aborted || requestId !== catalogSearchRequestId) {
                return;
            }
            console.error('Catalog search failed:', error);
            showLibrarySearchError(
                searchingPopular
                    ? 'Unable to refresh discover books.'
                    : 'Search failed. Please try again.',
                () => { void searchCatalog(normalizedQuery); }
            );
        } finally {
            if (requestId === catalogSearchRequestId) {
                catalogSearchAbortController = null;
                setLibrarySearchBusy(false);
            }
        }
    }

    function clampNumber(value, min, max) {
        return Math.min(max, Math.max(min, value));
    }

    function toFiniteNumber(value, fallback = 0) {
        const parsed = Number(value);
        return Number.isFinite(parsed) ? parsed : fallback;
    }

    function clampInteger(value, min, max) {
        return Math.floor(clampNumber(toFiniteNumber(value, min), min, max));
    }

    function toTimestamp(value) {
        if (!value || typeof value !== 'string') {
            return 0;
        }
        const parsed = Date.parse(value);
        return Number.isFinite(parsed) ? parsed : 0;
    }

    // Get recently read book IDs
    function getRecentlyRead() {
        const stored = localStorage.getItem(STORAGE_KEYS.RECENTLY_READ);
        if (!stored) {
            return [];
        }
        try {
            const parsed = JSON.parse(stored);
            return Array.isArray(parsed) ? parsed : [];
        } catch (_error) {
            return [];
        }
    }

    // Add book to recently read
    function addToRecentlyRead(bookId) {
        let recent = getRecentlyRead();
        recent = recent.filter(id => id !== bookId);
        recent.unshift(bookId);
        recent = recent.slice(0, MAX_RECENTLY_READ);
        localStorage.setItem(STORAGE_KEYS.RECENTLY_READ, JSON.stringify(recent));
    }

    function readFavoriteBookIdsFromStorage() {
        const stored = localStorage.getItem(STORAGE_KEYS.FAVORITE_BOOKS);
        if (!stored) {
            return [];
        }
        try {
            const parsed = JSON.parse(stored);
            if (!Array.isArray(parsed)) {
                return [];
            }
            return parsed
                .filter(id => typeof id === 'string' && id.trim().length > 0)
                .map(id => id.trim());
        } catch (_error) {
            return [];
        }
    }

    function hydrateFavoriteState(favoriteIds) {
        const uniqueIds = [];
        const seen = new Set();
        (favoriteIds || []).forEach((bookId) => {
            if (!bookId || seen.has(bookId)) {
                return;
            }
            seen.add(bookId);
            uniqueIds.push(bookId);
        });
        state.favoriteBookOrder = uniqueIds;
        state.favoriteBookIds = new Set(uniqueIds);
    }

    function persistFavoriteState() {
        localStorage.setItem(STORAGE_KEYS.FAVORITE_BOOKS, JSON.stringify(state.favoriteBookOrder));
    }

    function syncFavoriteBooksWithLocalBooks() {
        const availableBookIds = new Set((state.localBooks || []).map(book => book.id));
        const filtered = readFavoriteBookIdsFromStorage().filter(bookId => availableBookIds.has(bookId));
        hydrateFavoriteState(filtered);
        persistFavoriteState();
    }

    function isBookFavorite(bookId) {
        return !!bookId && state.favoriteBookIds.has(bookId);
    }

    function getFavoriteOrderIndex(bookId) {
        const index = state.favoriteBookOrder.indexOf(bookId);
        return index >= 0 ? index : Number.MAX_SAFE_INTEGER;
    }

    function setBookFavorite(bookId, favorite) {
        if (!bookId) {
            return false;
        }
        const currentlyFavorite = isBookFavorite(bookId);
        if (currentlyFavorite === favorite) {
            return currentlyFavorite;
        }

        const nextOrder = state.favoriteBookOrder.filter(id => id !== bookId);
        if (favorite) {
            nextOrder.unshift(bookId);
        }
        hydrateFavoriteState(nextOrder);
        persistFavoriteState();
        return favorite;
    }

    function toggleBookFavorite(bookId, options = {}) {
        if (!bookId) {
            return null;
        }
        const nextValue = !isBookFavorite(bookId);
        const favorite = setBookFavorite(bookId, nextValue);
        if (favorite === null) {
            return null;
        }
        updateFavoriteUi();
        if (options.rerenderLibrary) {
            renderLibrary();
        }
        if (options.showToast) {
            showAppToast({
                title: favorite ? 'Saved to My List' : 'Removed from My List',
                message: favorite
                    ? 'This book will stay pinned in your My List section.'
                    : 'This book was removed from your My List section.',
                autoDismissMs: 3200
            });
        }
        return favorite;
    }

    function readBookActivityStore() {
        const raw = localStorage.getItem(STORAGE_KEYS.BOOK_ACTIVITY);
        if (!raw) {
            return {};
        }
        try {
            const parsed = JSON.parse(raw);
            if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
                return {};
            }
            return parsed;
        } catch (_error) {
            return {};
        }
    }

    function writeBookActivityStore(store) {
        localStorage.setItem(STORAGE_KEYS.BOOK_ACTIVITY, JSON.stringify(store));
    }

    function computeProgressRatio(chapterIndex, pageIndex, totalPages, chapterCount) {
        const safeChapterCount = Math.max(1, Math.round(toFiniteNumber(chapterCount, 1)));
        const safeTotalPages = Math.max(1, Math.round(toFiniteNumber(totalPages, 1)));
        const safeChapterIndex = clampInteger(chapterIndex, 0, safeChapterCount - 1);
        const safePageIndex = clampInteger(pageIndex, 0, safeTotalPages - 1);
        const chapterProgress = (safePageIndex + 1) / safeTotalPages;
        return clampNumber((safeChapterIndex + chapterProgress) / safeChapterCount, 0, 1);
    }

    function normalizeBookActivity(book, rawActivity) {
        const raw = rawActivity && typeof rawActivity === 'object' ? rawActivity : {};
        const chapterCount = Math.max(
            1,
            (Array.isArray(book?.chapters) && book.chapters.length > 0)
                ? book.chapters.length
                : Math.round(toFiniteNumber(raw.chapterCount, 1))
        );
        const totalPages = Math.max(1, Math.round(toFiniteNumber(raw.totalPages, 1)));
        const lastChapterIndex = clampInteger(raw.lastChapterIndex, 0, chapterCount - 1);
        const lastPage = clampInteger(raw.lastPage, 0, totalPages - 1);
        const hasProgressData = raw.progressRatio !== undefined
            || raw.maxProgressRatio !== undefined
            || raw.lastChapterIndex !== undefined
            || raw.lastPage !== undefined
            || raw.totalPages !== undefined;
        const fallbackProgress = hasProgressData
            ? computeProgressRatio(lastChapterIndex, lastPage, totalPages, chapterCount)
            : 0;
        const progressRatio = clampNumber(toFiniteNumber(raw.progressRatio, fallbackProgress), 0, 1);
        const maxProgressRatio = clampNumber(
            Math.max(progressRatio, toFiniteNumber(raw.maxProgressRatio, progressRatio)),
            0,
            1
        );
        const completed = Boolean(raw.completed) || maxProgressRatio >= 0.999;
        return {
            chapterCount,
            lastChapterIndex,
            lastPage,
            totalPages,
            progressRatio,
            maxProgressRatio,
            completed,
            openCount: Math.max(0, Math.round(toFiniteNumber(raw.openCount, 0))),
            lastOpenedAt: typeof raw.lastOpenedAt === 'string' ? raw.lastOpenedAt : null,
            lastReadAt: typeof raw.lastReadAt === 'string' ? raw.lastReadAt : null,
            completedAt: completed && typeof raw.completedAt === 'string' ? raw.completedAt : null
        };
    }

    function upsertBookActivity(bookId, updater) {
        if (!bookId || typeof updater !== 'function') {
            return;
        }
        const store = readBookActivityStore();
        const existing = store[bookId] && typeof store[bookId] === 'object' ? store[bookId] : {};
        const next = updater(existing);
        if (!next || typeof next !== 'object') {
            return;
        }
        store[bookId] = next;
        writeBookActivityStore(store);
    }

    function markBookOpened(book) {
        if (!book?.id) {
            return;
        }
        const now = new Date().toISOString();
        upsertBookActivity(book.id, (existing) => {
            const normalized = normalizeBookActivity(book, existing);
            return {
                ...normalized,
                chapterCount: Math.max(1, Array.isArray(book.chapters) ? book.chapters.length : normalized.chapterCount),
                openCount: normalized.openCount + 1,
                lastOpenedAt: now,
                lastReadAt: normalized.lastReadAt || now
            };
        });
    }

    function getBookResumePosition(book) {
        if (!book?.id) {
            return { chapterIndex: 0, pageIndex: 0 };
        }
        const store = readBookActivityStore();
        const normalized = normalizeBookActivity(book, store[book.id]);
        const hasMeaningfulProgress = normalized.progressRatio > 0
            || normalized.maxProgressRatio > 0
            || !!normalized.lastReadAt;
        if (!hasMeaningfulProgress) {
            return { chapterIndex: 0, pageIndex: 0 };
        }
        return {
            chapterIndex: normalized.lastChapterIndex,
            pageIndex: normalized.lastPage
        };
    }

    async function selectBookWithResume(book) {
        const resume = getBookResumePosition(book);
        await selectBook(book, resume.chapterIndex, resume.pageIndex);
    }

    function persistCurrentBookActivity() {
        if (!state.currentBook?.id || !Array.isArray(state.chapters) || state.chapters.length === 0) {
            return;
        }
        const chapterCount = Math.max(1, state.chapters.length);
        const totalPages = Math.max(1, state.totalPages || 1);
        const chapterIndex = clampInteger(state.currentChapterIndex, 0, chapterCount - 1);
        const pageIndex = clampInteger(state.currentPage, 0, totalPages - 1);
        const signature = `${state.currentBook.id}:${chapterIndex}:${pageIndex}:${totalPages}:${chapterCount}`;
        if (signature === state.lastBookActivitySignature) {
            return;
        }
        state.lastBookActivitySignature = signature;

        const now = new Date().toISOString();
        const progressRatio = computeProgressRatio(chapterIndex, pageIndex, totalPages, chapterCount);
        const reachedEnd = chapterIndex >= chapterCount - 1 && pageIndex >= totalPages - 1;

        upsertBookActivity(state.currentBook.id, (existing) => {
            const normalized = normalizeBookActivity(state.currentBook, existing);
            const maxProgressRatio = Math.max(normalized.maxProgressRatio, progressRatio);
            const completed = normalized.completed || reachedEnd || maxProgressRatio >= 0.999;
            return {
                ...normalized,
                chapterCount,
                lastChapterIndex: chapterIndex,
                lastPage: pageIndex,
                totalPages,
                progressRatio,
                maxProgressRatio,
                completed,
                completedAt: completed ? (normalized.completedAt || now) : null,
                lastOpenedAt: normalized.lastOpenedAt || now,
                lastReadAt: now,
                openCount: Math.max(1, normalized.openCount)
            };
        });
    }

    function syncBookActivityWithLocalBooks() {
        const localBooks = Array.isArray(state.localBooks) ? state.localBooks : [];
        const bookIds = new Set(localBooks.map(book => book.id));
        const recentIds = getRecentlyRead();
        const recentOrder = new Map();
        recentIds.forEach((bookId, index) => {
            recentOrder.set(bookId, index);
        });

        const now = Date.now();
        const store = readBookActivityStore();
        Object.keys(store).forEach((bookId) => {
            if (!bookIds.has(bookId)) {
                delete store[bookId];
            }
        });

        localBooks.forEach((book) => {
            const existing = store[book.id];
            const normalized = normalizeBookActivity(book, existing);
            const recencySeed = recentOrder.has(book.id)
                ? new Date(now - (recentOrder.get(book.id) * 60000)).toISOString()
                : null;
            const seededProgress = recencySeed && normalized.maxProgressRatio <= 0
                ? computeProgressRatio(0, 0, 1, normalized.chapterCount)
                : normalized.maxProgressRatio;
            const seededCompleted = Boolean(normalized.completed) || seededProgress >= 0.999;
            store[book.id] = {
                ...normalized,
                chapterCount: Math.max(1, Array.isArray(book.chapters) ? book.chapters.length : normalized.chapterCount),
                progressRatio: Math.max(normalized.progressRatio, seededProgress),
                maxProgressRatio: Math.max(normalized.maxProgressRatio, seededProgress),
                completed: seededCompleted,
                completedAt: seededCompleted ? normalized.completedAt : null,
                lastOpenedAt: normalized.lastOpenedAt || recencySeed,
                lastReadAt: normalized.lastReadAt || recencySeed
            };
        });

        writeBookActivityStore(store);
    }

    function normalizeAuthorName(author) {
        const raw = (author || '').trim();
        if (!raw) return '';

        const commaCount = (raw.match(/,/g) || []).length;
        if (commaCount !== 1) {
            return raw;
        }

        const parts = raw.split(',').map(part => part.trim()).filter(Boolean);
        if (parts.length !== 2) {
            return raw;
        }

        return `${parts[1]} ${parts[0]}`.trim();
    }

    function getLocalBookEntries() {
        const store = readBookActivityStore();
        return state.localBooks.map(book => ({
            book,
            activity: normalizeBookActivity(book, store[book.id]),
            favorite: isBookFavorite(book.id),
            favoriteOrderIndex: getFavoriteOrderIndex(book.id)
        }));
    }

    function getLastActivityTimestamp(entry) {
        if (libraryRankingHelpers && typeof libraryRankingHelpers.toTimestamp === 'function') {
            return Math.max(
                libraryRankingHelpers.toTimestamp(entry?.activity?.lastReadAt),
                libraryRankingHelpers.toTimestamp(entry?.activity?.lastOpenedAt),
                0
            );
        }
        return Math.max(
            toTimestamp(entry.activity.lastReadAt),
            toTimestamp(entry.activity.lastOpenedAt),
            0
        );
    }

    function isInProgressEntry(entry) {
        if (libraryRankingHelpers && typeof libraryRankingHelpers.isInProgress === 'function') {
            return libraryRankingHelpers.isInProgress(entry);
        }
        return !entry.activity.completed && entry.activity.maxProgressRatio > 0;
    }

    function isCompletedEntry(entry) {
        if (libraryRankingHelpers && typeof libraryRankingHelpers.isCompleted === 'function') {
            return libraryRankingHelpers.isCompleted(entry);
        }
        return entry.activity.completed;
    }

    function isUnreadEntry(entry) {
        return !entry.activity.completed && entry.activity.maxProgressRatio <= 0;
    }

    function compareByTitle(a, b) {
        return (a.book.title || '').localeCompare(b.book.title || '', undefined, { sensitivity: 'base' });
    }

    function compareByLibraryPriority(a, b) {
        if (libraryRankingHelpers) {
            return libraryRankingHelpers.compareForActiveQueue(a, b);
        }

        // Fallback tie-break order: reading state, recency, favorite, progress depth, title.
        const aState = isInProgressEntry(a) ? 2 : (isUnreadEntry(a) ? 1 : 0);
        const bState = isInProgressEntry(b) ? 2 : (isUnreadEntry(b) ? 1 : 0);
        if (aState !== bState) {
            return bState - aState;
        }

        const recencyDiff = getLastActivityTimestamp(b) - getLastActivityTimestamp(a);
        if (recencyDiff !== 0) {
            return recencyDiff;
        }

        const favoriteDiff = Number(Boolean(b.favorite)) - Number(Boolean(a.favorite));
        if (favoriteDiff !== 0) {
            return favoriteDiff;
        }

        const progressDiff = b.activity.maxProgressRatio - a.activity.maxProgressRatio;
        if (Math.abs(progressDiff) > 0.0001) {
            return progressDiff;
        }

        return compareByTitle(a, b);
    }

    function compareCompletedEntries(a, b) {
        if (libraryRankingHelpers) {
            return libraryRankingHelpers.compareForCompleted(a, b);
        }

        const completedDiff = toTimestamp(b.activity.completedAt) - toTimestamp(a.activity.completedAt);
        if (completedDiff !== 0) {
            return completedDiff;
        }

        const recencyDiff = getLastActivityTimestamp(b) - getLastActivityTimestamp(a);
        if (recencyDiff !== 0) {
            return recencyDiff;
        }

        const favoriteDiff = Number(Boolean(b.favorite)) - Number(Boolean(a.favorite));
        if (favoriteDiff !== 0) {
            return favoriteDiff;
        }

        return compareByLibraryPriority(a, b);
    }

    function formatRelativeActivityTime(value) {
        const timestamp = toTimestamp(value);
        if (!timestamp) {
            return '';
        }
        const elapsedMs = Date.now() - timestamp;
        if (elapsedMs < 60_000) {
            return 'just now';
        }
        if (elapsedMs < 3_600_000) {
            return `${Math.round(elapsedMs / 60_000)}m ago`;
        }
        if (elapsedMs < 86_400_000) {
            return `${Math.round(elapsedMs / 3_600_000)}h ago`;
        }
        return `${Math.round(elapsedMs / 86_400_000)}d ago`;
    }

    function buildBookProgressSnapshot(activity) {
        if (libraryProgressHelpers) {
            return libraryProgressHelpers.buildBookProgressSnapshot(activity);
        }

        const chapterCount = Math.max(1, clampInteger(activity?.chapterCount, 1, Number.MAX_SAFE_INTEGER));
        const rawProgress = clampNumber(toFiniteNumber(activity?.maxProgressRatio, 0), 0, 1);
        const completed = Boolean(activity?.completed) || rawProgress >= 0.999;

        let chapterNumber = 0;
        let percentComplete = clampInteger(Math.round(rawProgress * 100), 0, 100);
        let statusLabel = 'Not Started';
        let statusClass = 'not-started';

        if (completed) {
            chapterNumber = chapterCount;
            percentComplete = 100;
            statusLabel = 'Completed';
            statusClass = 'completed';
        } else if (rawProgress > 0) {
            chapterNumber = clampInteger(toFiniteNumber(activity?.lastChapterIndex, 0) + 1, 1, chapterCount);
            percentComplete = Math.max(1, percentComplete);
            statusLabel = 'In Progress';
            statusClass = 'in-progress';
        }

        return {
            chapterLabel: `Chapter ${chapterNumber}/${chapterCount}`,
            percentLabel: `${percentComplete}%`,
            statusLabel,
            statusClass
        };
    }

    function formatBookActivityLabel(activity, progressSnapshot) {
        if (progressSnapshot.statusClass === 'completed') {
            const completedAt = formatRelativeActivityTime(activity.completedAt || activity.lastReadAt || activity.lastOpenedAt);
            return completedAt ? `Finished ${completedAt}` : 'Finished';
        }

        const activeAt = formatRelativeActivityTime(activity.lastReadAt || activity.lastOpenedAt);
        return activeAt ? `Active ${activeAt}` : 'No recent activity';
    }

    function renderLocalBookItem(entry, options = {}) {
        const author = normalizeAuthorName(entry.book.author);
        const badge = options.badge ? `<span class="book-item-badge">${options.badge}</span>` : '';
        const progress = buildBookProgressSnapshot(entry.activity);
        const meta = formatBookActivityLabel(entry.activity, progress);
        const favoriteLabel = entry.favorite ? 'Saved' : 'Save';
        const favoriteTitle = entry.favorite ? 'Remove from My List' : 'Add to My List';
        const favoriteClass = entry.favorite ? ' active' : '';
        return `
            <div class="book-item" data-book-id="${entry.book.id}">
                <div class="book-item-title-row">
                    <div class="book-item-title">${entry.book.title}${badge}</div>
                    <button
                        class="book-item-favorite-btn${favoriteClass}"
                        type="button"
                        data-favorite-toggle="true"
                        data-book-id="${entry.book.id}"
                        aria-pressed="${entry.favorite ? 'true' : 'false'}"
                        title="${favoriteTitle}"
                    >
                        ${favoriteLabel}
                    </button>
                </div>
                <div class="book-item-author">${author}</div>
                <div class="book-item-progress">
                    <span class="book-progress-chip book-progress-chip-status status-${progress.statusClass}">${progress.statusLabel}</span>
                    <span class="book-progress-chip">${progress.chapterLabel}</span>
                    <span class="book-progress-chip">${progress.percentLabel}</span>
                </div>
                <div class="book-item-meta">${meta}</div>
            </div>
        `;
    }

    // Render a catalog book item (from Gutenberg)
    function renderCatalogBookItem(book) {
        const importedClass = book.alreadyImported ? ' imported' : '';
        const importedBadge = book.alreadyImported ? '<span class="imported-badge">✓</span>' : '';
        const author = normalizeAuthorName(book.author);
        const reason = (typeof book.discoverReason === 'string' && book.discoverReason.trim().length > 0)
            ? `<div class="book-item-discover-reason">${escapeHtml(book.discoverReason)}</div>`
            : '';
        return `
            <div class="book-item catalog-book${importedClass}" data-gutenberg-id="${book.gutenbergId}">
                <div class="book-item-title">${book.title}${importedBadge}</div>
                <div class="book-item-author">${author}</div>
                ${reason}
            </div>
        `;
    }

    function getDiscoverCatalogEntries() {
        const catalog = Array.isArray(state.popularCatalogBooks) && state.popularCatalogBooks.length > 0
            ? state.popularCatalogBooks
            : (Array.isArray(state.catalogBooks) ? state.catalogBooks : []);
        if (catalog.length === 0) {
            return [];
        }

        if (libraryDiscoverHelpers) {
            return libraryDiscoverHelpers.buildRecommendations(catalog, getLocalBookEntries());
        }

        return catalog.map(book => ({
            ...book,
            discoverReason: 'Popular with readers right now',
            discoverReasonType: 'popularity'
        }));
    }

    function renderLocalSection(sectionElement, listElement, entries, options = {}) {
        if (!sectionElement || !listElement) {
            return;
        }
        if (!entries || entries.length === 0) {
            listElement.innerHTML = '';
            sectionElement.classList.add('hidden');
            return;
        }
        listElement.innerHTML = entries.map((entry, index) => {
            const badge = typeof options.badge === 'function'
                ? options.badge(entry, index)
                : options.badge;
            return renderLocalBookItem(entry, { badge });
        }).join('');
        sectionElement.classList.remove('hidden');
    }

    function hideAchievementsShelf() {
        if (!elements.achievementsShelf || !elements.achievementsSummary || !elements.achievementsList) {
            return;
        }
        elements.achievementsSummary.textContent = '';
        elements.achievementsList.innerHTML = '';
        if (elements.achievementsViewAll) {
            elements.achievementsViewAll.classList.add('hidden');
        }
        elements.achievementsShelf.classList.add('hidden');
        closeAchievementsModal();
    }

    function summarizeAchievements(totalUnlocked, booksWithTrophies) {
        if (totalUnlocked <= 0) {
            return 'No trophies unlocked yet. Complete quizzes to start collecting achievements.';
        }
        const trophyWord = totalUnlocked === 1 ? 'trophy' : 'trophies';
        const bookWord = booksWithTrophies === 1 ? 'book' : 'books';
        return `${totalUnlocked} ${trophyWord} unlocked across ${booksWithTrophies} ${bookWord}.`;
    }

    function toAchievementRecord(book, trophy) {
        if (!book || !trophy) {
            return null;
        }
        const title = (typeof trophy.title === 'string' && trophy.title.trim())
            ? trophy.title.trim()
            : (typeof trophy.code === 'string' && trophy.code.trim() ? trophy.code.trim() : 'Trophy');
        return {
            bookId: book.id,
            bookTitle: book.title || 'Book',
            trophyTitle: title,
            trophyCode: typeof trophy.code === 'string' ? trophy.code : '',
            description: typeof trophy.description === 'string' ? trophy.description : '',
            unlockedAt: typeof trophy.unlockedAt === 'string' ? trophy.unlockedAt : null
        };
    }

    function compareAchievementsNewestFirst(a, b) {
        const timeDiff = toTimestamp(b.unlockedAt) - toTimestamp(a.unlockedAt);
        if (timeDiff !== 0) {
            return timeDiff;
        }
        const titleDiff = (a.trophyTitle || '').localeCompare(b.trophyTitle || '', undefined, { sensitivity: 'base' });
        if (titleDiff !== 0) {
            return titleDiff;
        }
        return (a.bookTitle || '').localeCompare(b.bookTitle || '', undefined, { sensitivity: 'base' });
    }

    function formatAchievementDetail(item) {
        const relativeTime = formatRelativeActivityTime(item.unlockedAt);
        return relativeTime
            ? `${item.bookTitle} • ${relativeTime}`
            : item.bookTitle;
    }

    function renderAchievementsShelf() {
        if (!elements.achievementsShelf || !elements.achievementsSummary || !elements.achievementsList) {
            return;
        }

        if (!state.quizAvailable || !Array.isArray(state.localBooks) || state.localBooks.length === 0) {
            hideAchievementsShelf();
            return;
        }

        elements.achievementsShelf.classList.remove('hidden');

        if (state.achievementsLoading && state.achievementsAllItems.length === 0) {
            elements.achievementsSummary.textContent = 'Loading achievements...';
            elements.achievementsList.innerHTML = '';
            if (elements.achievementsViewAll) {
                elements.achievementsViewAll.classList.add('hidden');
            }
            return;
        }

        elements.achievementsSummary.textContent = state.achievementsSummary
            || 'No trophies unlocked yet. Complete quizzes to start collecting achievements.';

        if (!Array.isArray(state.achievementsAllItems) || state.achievementsAllItems.length === 0) {
            elements.achievementsList.innerHTML = '';
            if (elements.achievementsViewAll) {
                elements.achievementsViewAll.classList.add('hidden');
            }
            return;
        }

        const shelfItems = state.achievementsAllItems.slice(0, 6);
        elements.achievementsList.innerHTML = shelfItems.map((item) => {
            const detail = formatAchievementDetail(item);
            return `
                <button
                    class="achievement-item"
                    type="button"
                    data-achievement-book-id="${item.bookId}"
                    title="${escapeHtml(item.description || item.trophyTitle)}"
                >
                    <span class="achievement-title">${escapeHtml(item.trophyTitle)}</span>
                    <span class="achievement-meta">${escapeHtml(detail)}</span>
                </button>
            `;
        }).join('');

        if (elements.achievementsViewAll) {
            elements.achievementsViewAll.classList.toggle('hidden', state.achievementsAllItems.length <= shelfItems.length);
        }

        if (isAchievementsModalVisible()) {
            renderAchievementsModal();
        }
    }

    async function loadAchievementsShelf(force = false) {
        if (state.achievementsLoading) {
            return;
        }
        if (!state.quizAvailable || !Array.isArray(state.localBooks) || state.localBooks.length === 0) {
            state.achievementsLoaded = true;
            state.achievementsSummary = '';
            state.achievementsItems = [];
            state.achievementsAllItems = [];
            renderAchievementsShelf();
            return;
        }
        if (state.achievementsLoaded && !force) {
            renderAchievementsShelf();
            return;
        }

        state.achievementsLoading = true;
        renderAchievementsShelf();

        try {
            const requests = state.localBooks.map(async (book) => {
                try {
                    const response = await fetch(`/api/quizzes/book/${encodeURIComponent(book.id)}/trophies`, {
                        cache: 'no-store'
                    });
                    if (response.status === 403 || response.status === 404) {
                        return [];
                    }
                    if (!response.ok) {
                        throw new Error(`Trophy fetch failed (${response.status})`);
                    }
                    const trophies = await response.json();
                    if (!Array.isArray(trophies)) {
                        return [];
                    }
                    return trophies
                        .map((trophy) => toAchievementRecord(book, trophy))
                        .filter(Boolean);
                } catch (error) {
                    console.debug('Failed to load trophy data for book:', book.id, error);
                    return [];
                }
            });

            const results = await Promise.all(requests);
            const combined = results.flat().sort(compareAchievementsNewestFirst);
            const totalUnlocked = combined.length;
            const booksWithTrophies = new Set(combined.map(item => item.bookId)).size;

            state.achievementsAllItems = combined;
            state.achievementsItems = combined.slice(0, 6);
            state.achievementsSummary = summarizeAchievements(totalUnlocked, booksWithTrophies);
            state.achievementsLoaded = true;
        } finally {
            state.achievementsLoading = false;
            renderAchievementsShelf();
        }
    }

    function parseDueTimestamp(value) {
        if (!value || typeof value !== 'string') {
            return 0;
        }
        const parsed = Date.parse(value);
        return Number.isFinite(parsed) ? parsed : 0;
    }

    function formatAssignmentDueLabel(value) {
        const dueTs = parseDueTimestamp(value);
        if (!dueTs) {
            return { label: '', overdue: false };
        }
        const delta = dueTs - Date.now();
        const absMs = Math.abs(delta);
        const dayMs = 86_400_000;
        const hourMs = 3_600_000;
        const overdue = delta < 0;

        if (absMs < hourMs) {
            return {
                label: overdue ? 'Due now (late)' : 'Due soon',
                overdue
            };
        }
        if (absMs < dayMs) {
            const hours = Math.max(1, Math.round(absMs / hourMs));
            return {
                label: overdue ? `${hours}h late` : `Due in ${hours}h`,
                overdue
            };
        }
        const days = Math.max(1, Math.round(absMs / dayMs));
        return {
            label: overdue ? `${days}d late` : `Due in ${days}d`,
            overdue
        };
    }

    function compareClassroomAssignments(a, b) {
        const aDue = parseDueTimestamp(a?.dueAt);
        const bDue = parseDueTimestamp(b?.dueAt);
        if (aDue && bDue && aDue !== bDue) {
            return aDue - bDue;
        }
        if (aDue && !bDue) {
            return -1;
        }
        if (!aDue && bDue) {
            return 1;
        }
        return (a.assignmentId || '').localeCompare(b.assignmentId || '', undefined, { sensitivity: 'base' });
    }

    function renderClassroomAssignmentItem(assignment, localEntry) {
        const title = localEntry?.book?.title || assignment.bookTitle || 'Assigned Book';
        const author = normalizeAuthorName(localEntry?.book?.author || assignment.bookAuthor || '');
        const progress = localEntry ? buildBookProgressSnapshot(localEntry.activity) : null;
        const meta = localEntry
            ? formatBookActivityLabel(localEntry.activity, progress)
            : (assignment.bookAvailable ? 'Open this book to start reading.' : 'Book not available in this library yet.');
        const due = formatAssignmentDueLabel(assignment.dueAt);
        const dueChip = due.label
            ? `<span class="book-progress-chip assignment-due${due.overdue ? ' overdue' : ''}">${escapeHtml(due.label)}</span>`
            : '';

        let quizChip = '<span class="book-progress-chip assignment-quiz-unknown">Quiz status unknown</span>';
        if (assignment.quizStatus === 'NOT_REQUIRED') {
            quizChip = '<span class="book-progress-chip assignment-quiz-unknown">Reading only</span>';
        } else if (assignment.quizStatus === 'COMPLETE') {
            quizChip = '<span class="book-progress-chip assignment-quiz-complete">Quiz complete</span>';
        } else if (assignment.quizStatus === 'PENDING') {
            quizChip = '<span class="book-progress-chip assignment-quiz-required">Quiz required</span>';
        }

        const assignmentLabel = assignment.title
            ? `<div class="book-item-assignment-label">${escapeHtml(assignment.title)}</div>`
            : '';
        const chapterLabel = assignment.chapterTitle
            ? `<span class="book-progress-chip">${escapeHtml(assignment.chapterTitle)}</span>`
            : '';
        const progressChips = progress
            ? `
                <span class="book-progress-chip book-progress-chip-status status-${progress.statusClass}">${progress.statusLabel}</span>
                <span class="book-progress-chip">${progress.chapterLabel}</span>
                <span class="book-progress-chip">${progress.percentLabel}</span>
            `
            : '<span class="book-progress-chip book-progress-chip-status status-not-started">Not Started</span>';

        const bookIdAttr = localEntry?.book?.id
            ? ` data-book-id="${escapeHtml(localEntry.book.id)}"`
            : '';
        return `
            <div class="book-item"${bookIdAttr}>
                <div class="book-item-title-row">
                    <div class="book-item-title">${escapeHtml(title)}</div>
                </div>
                <div class="book-item-author">${escapeHtml(author)}</div>
                ${assignmentLabel}
                <div class="book-item-progress">
                    ${chapterLabel}
                    ${dueChip}
                    ${quizChip}
                </div>
                <div class="book-item-progress">
                    ${progressChips}
                </div>
                <div class="book-item-meta">${escapeHtml(meta)}</div>
            </div>
        `;
    }

    function hideClassroomLandingSections() {
        if (elements.classroomBanner) {
            elements.classroomBanner.classList.add('hidden');
        }
        if (elements.classroomLabel) {
            elements.classroomLabel.textContent = '';
        }
        if (elements.classroomFeatureSummary) {
            elements.classroomFeatureSummary.textContent = '';
        }
        if (elements.classroomAssignments) {
            elements.classroomAssignments.classList.add('hidden');
        }
        if (elements.classroomAssignmentsList) {
            elements.classroomAssignmentsList.innerHTML = '';
        }
        if (elements.classroomAssignmentsLabel) {
            elements.classroomAssignmentsLabel.textContent = '';
        }
        if (elements.classroomAssignmentsEmpty) {
            elements.classroomAssignmentsEmpty.classList.add('hidden');
        }
    }

    function renderClassroomLandingSections() {
        if (!state.classroomContext?.enrolled) {
            hideClassroomLandingSections();
            return;
        }

        const className = state.classroomContext.className || 'Classroom';
        const teacherName = state.classroomContext.teacherName || '';
        if (elements.classroomBanner) {
            elements.classroomBanner.classList.remove('hidden');
        }
        if (elements.classroomLabel) {
            elements.classroomLabel.textContent = teacherName
                ? `${className} with ${teacherName}`
                : className;
        }

        const disabled = [];
        if (!isClassroomFeatureEnabled('quizEnabled')) disabled.push('quizzes');
        if (!isClassroomFeatureEnabled('recapEnabled')) disabled.push('recaps');
        if (!isClassroomFeatureEnabled('ttsEnabled')) disabled.push('read-aloud');
        if (!isClassroomFeatureEnabled('illustrationEnabled')) disabled.push('illustrations');
        if (!isClassroomFeatureEnabled('characterEnabled')) disabled.push('characters');
        if (!isClassroomFeatureEnabled('chatEnabled')) disabled.push('chat');
        if (!isClassroomFeatureEnabled('citationEnabled')) disabled.push('citations');
        if (elements.classroomFeatureSummary) {
            elements.classroomFeatureSummary.textContent = disabled.length > 0
                ? `Teacher controls active: ${disabled.join(', ')} off for this class.`
                : 'Teacher controls active for this class.';
        }

        const assignments = Array.isArray(state.classroomAssignments)
            ? [...state.classroomAssignments].sort(compareClassroomAssignments)
            : [];
        const localEntriesByBookId = new Map(getLocalBookEntries().map(entry => [entry.book.id, entry]));

        if (elements.classroomAssignmentsLabel) {
            const count = assignments.length;
            elements.classroomAssignmentsLabel.textContent = count === 1 ? '1 active' : `${count} active`;
        }
        if (elements.classroomAssignments) {
            elements.classroomAssignments.classList.remove('hidden');
        }

        if (assignments.length === 0) {
            if (elements.classroomAssignmentsList) {
                elements.classroomAssignmentsList.innerHTML = '';
            }
            if (elements.classroomAssignmentsEmpty) {
                elements.classroomAssignmentsEmpty.classList.remove('hidden');
            }
            return;
        }

        if (elements.classroomAssignmentsEmpty) {
            elements.classroomAssignmentsEmpty.classList.add('hidden');
        }
        if (elements.classroomAssignmentsList) {
            elements.classroomAssignmentsList.innerHTML = assignments.map((assignment) =>
                renderClassroomAssignmentItem(assignment, localEntriesByBookId.get(assignment.bookId))
            ).join('');
        }
    }

    function hidePersonalizedSections() {
        renderLocalSection(elements.continueReading, elements.continueReadingList, []);
        renderLocalSection(elements.upNext, elements.upNextList, []);
        renderLocalSection(elements.inProgress, elements.inProgressList, []);
        renderLocalSection(elements.completedBooks, elements.completedBooksList, []);
        renderLocalSection(elements.myList, elements.myListList, []);
    }

    function renderPersonalizedSections() {
        const entries = getLocalBookEntries();
        if (entries.length === 0) {
            hidePersonalizedSections();
            return;
        }

        const activeQueue = [...entries]
            .filter(entry => !isCompletedEntry(entry))
            .sort(compareByLibraryPriority);
        const inProgress = activeQueue.filter(isInProgressEntry);
        const completed = entries.filter(isCompletedEntry).sort(compareCompletedEntries);
        const continueEntry = activeQueue[0] || null;
        const upNext = activeQueue.slice(1, 5);
        const myListEntries = [...entries]
            .filter(entry => entry.favorite)
            .sort((a, b) => a.favoriteOrderIndex - b.favoriteOrderIndex);

        renderLocalSection(
            elements.continueReading,
            elements.continueReadingList,
            continueEntry ? [continueEntry] : [],
            { badge: 'Now' }
        );
        renderLocalSection(
            elements.upNext,
            elements.upNextList,
            upNext,
            { badge: (_entry, index) => `Next ${index + 1}` }
        );
        renderLocalSection(elements.inProgress, elements.inProgressList, inProgress);
        renderLocalSection(elements.completedBooks, elements.completedBooksList, completed);
        renderLocalSection(elements.myList, elements.myListList, myListEntries);
    }

    // Render library view
    function renderLibrary(filter = state.librarySearchQuery) {
        const searchTerm = (filter || '').toLowerCase().trim();

        if (searchTerm) {
            hideClassroomLandingSections();
            hideAchievementsShelf();
            hidePersonalizedSections();
        } else {
            renderClassroomLandingSections();
            renderAchievementsShelf();
            void loadAchievementsShelf();
            renderPersonalizedSections();
        }

        const discoverCatalog = searchTerm ? state.catalogBooks : getDiscoverCatalogEntries();

        if (discoverCatalog.length > 0) {
            elements.bookList.innerHTML = discoverCatalog.map(renderCatalogBookItem).join('');
            elements.allBooks.classList.remove('hidden');
            elements.noResults.classList.add('hidden');
        } else if (searchTerm) {
            elements.allBooks.classList.add('hidden');
            elements.noResults.classList.remove('hidden');
        } else {
            elements.bookList.innerHTML = '';
            elements.allBooks.classList.add('hidden');
            elements.noResults.classList.add('hidden');
        }
    }

    // Select a book
    async function selectBook(book, chapterIndex = 0, pageIndex = 0, paragraphIndex = 0) {
        state.currentBook = book;
        state.chapters = book.chapters;
        state.currentChapterIndex = chapterIndex;
        state.newCharacterQueue = [];
        state.currentToastCharacter = null;
        state.discoveredCharacterIds = loadDiscoveredCharacters(book.id);
        state.discoveredCharacterDetails = loadDiscoveredCharacterDetails(book.id);
        state.ttsVoiceSettings = null;
        state.illustrationSettings = null;
        state.annotationsByKey = new Map();
        state.bookmarks = [];
        state.noteModalParagraphIndex = null;
        state.currentBookMlaCitation = '';
        state.currentBookCitationKey = null;
        state.currentBookCitationPromise = null;
        state.searchChapterFilter = '';
        state.searchLastQuery = '';
        clearSearchHighlightState();
        stopCharacterPolling();

        // Save to localStorage and recently read
        localStorage.setItem(STORAGE_KEYS.LAST_BOOK, book.id);
        addToRecentlyRead(book.id);
        markBookOpened(book);
        state.lastBookActivitySignature = '';

        // Switch to reader view
        elements.libraryView.classList.add('hidden');
        elements.readerView.classList.remove('hidden');
        applyLayoutCapabilities();

        // Update title
        elements.bookTitle.textContent = book.title;
        const author = normalizeAuthorName(book.author);
        if (elements.bookAuthor) {
            elements.bookAuthor.textContent = author;
            elements.bookAuthor.classList.toggle('hidden', author.length === 0);
        }
        void ensureCurrentBookMlaCitation();
        updateFavoriteUi();
        renderSearchChapterFilterOptions();

        await ttsCheckAvailability();
        await illustrationCheckAvailability();
        await characterCheckAvailability();
        await recapCheckAvailability();
        await quizCheckAvailability();

        state.recapOptOut = getRecapOptOut(book.id);
        if (elements.chapterRecapOptout) {
            elements.chapterRecapOptout.checked = state.recapOptOut;
        }
        updateRecapOptOutControl();
        closeChapterRecapOverlay(false);

        const savedIllustrationMode = localStorage.getItem(STORAGE_KEYS.ILLUSTRATION_MODE);
        if (!state.illustrationMode && savedIllustrationMode === 'true' && state.illustrationAvailable) {
            state.illustrationMode = true;
            if (elements.illustrationToggle) {
                elements.illustrationToggle.classList.add('active');
            }
            updateColumnLayout();
        }
        if (state.illustrationMode) {
            updateColumnLayout();
        }

        await loadAnnotationsForCurrentBook();

        // Load chapter
        await loadChapter(chapterIndex, pageIndex, paragraphIndex);

        // Analyze book for voice settings (async, don't block)
        if (state.ttsAvailable && !state.ttsVoiceSettings) {
            ttsAnalyzeBook();
        }

        // Analyze book for illustration style (async, don't block)
        if (state.illustrationAvailable && !state.illustrationSettings) {
            illustrationAnalyzeBook();
        }

        // Prefetch main characters for the book (async, don't block)
        if (state.characterAvailable) {
            nativeFetch(`/api/characters/book/${book.id}/prefetch`, { method: 'POST' })
                .catch(error => {
                    console.debug('Book character prefetch request failed:', error);
                });
        }
    }

    // Load chapter content
    async function loadChapter(chapterIndex, pageIndex = 0, paragraphIndex = 0, suppressTts = false) {
        if (chapterIndex < 0 || chapterIndex >= state.chapters.length) {
            return false;
        }

        const loadRequestId = ++state.chapterLoadRequestId;

        state.ttsWaitingForChapter = true;
        state.currentChapterIndex = chapterIndex;
        localStorage.setItem(STORAGE_KEYS.LAST_CHAPTER, chapterIndex);

        const chapter = state.chapters[chapterIndex];
        elements.chapterTitle.textContent = chapter.title;

        try {
            const response = await fetch(`/api/library/${state.currentBook.id}/chapters/${chapter.id}`);
            if (!response.ok) {
                throw new Error(`Failed to load chapter content (${response.status})`);
            }
            const content = await response.json();
            if (loadRequestId !== state.chapterLoadRequestId) {
                return false;
            }
            state.paragraphs = content.paragraphs || [];

            // Calculate pages and render
            calculatePages();
            state.currentPage = Math.min(pageIndex, state.totalPages - 1);
            state.currentPage = Math.max(0, state.currentPage);
            state.currentParagraphIndex = Math.min(paragraphIndex, state.paragraphs.length - 1);
            state.currentParagraphIndex = Math.max(0, state.currentParagraphIndex);
            renderPage();

            // Continue TTS if it was enabled
            state.ttsWaitingForChapter = false;
            if (state.ttsEnabled && !suppressTts) {
                ttsSpeakCurrent();
            }

            // Load illustration if mode is enabled
            if (state.illustrationMode) {
                loadChapterIllustration();
            }

            // Analyze chapter for characters
            loadChapterCharacters();

            // Queue recap generation asynchronously (no-op when disabled or already generated)
            requestChapterRecapGeneration(chapter.id);
            requestChapterQuizGeneration(chapter.id);
            return true;
        } catch (error) {
            if (loadRequestId !== state.chapterLoadRequestId || error?.name === 'AbortError') {
                return false;
            }
            state.ttsWaitingForChapter = false;
            console.error('Failed to load chapter:', error);
            state.paragraphs = [];
            elements.columnLeft.innerHTML = '<p class="no-content">Content not available</p>';
            elements.columnRight.innerHTML = '';
            updateAnnotationControls();
            updateTouchNavigationControls();
            return false;
        }
    }

    async function loadAnnotationsForCurrentBook() {
        if (!state.currentBook?.id) {
            state.annotationsByKey = new Map();
            state.bookmarks = [];
            updateAnnotationControls();
            return;
        }

        try {
            const [annotationsResponse, bookmarksResponse] = await Promise.all([
                fetch(`/api/library/${state.currentBook.id}/annotations`, { cache: 'no-store' }),
                fetch(`/api/library/${state.currentBook.id}/bookmarks`, { cache: 'no-store' })
            ]);

            if (annotationsResponse.ok) {
                const annotations = await annotationsResponse.json();
                state.annotationsByKey = new Map();
                if (Array.isArray(annotations)) {
                    annotations.forEach(setParagraphAnnotation);
                }
            } else {
                state.annotationsByKey = new Map();
            }

            if (bookmarksResponse.ok) {
                const bookmarks = await bookmarksResponse.json();
                state.bookmarks = Array.isArray(bookmarks) ? bookmarks : [];
            } else {
                state.bookmarks = [];
            }
        } catch (error) {
            console.error('Failed to load annotations:', error);
            state.annotationsByKey = new Map();
            state.bookmarks = [];
        } finally {
            renderBookmarkList();
            updateAnnotationControls();
        }
    }

    async function refreshBookmarksForCurrentBook() {
        if (!state.currentBook?.id) {
            state.bookmarks = [];
            renderBookmarkList();
            return;
        }
        try {
            const response = await fetch(`/api/library/${state.currentBook.id}/bookmarks`, { cache: 'no-store' });
            if (!response.ok) {
                return;
            }
            const bookmarks = await response.json();
            state.bookmarks = Array.isArray(bookmarks) ? bookmarks : [];
            renderBookmarkList();
            updateAnnotationControls();
        } catch (error) {
            console.debug('Failed to refresh bookmarks:', error);
        }
    }

    async function upsertParagraphAnnotation(chapterId, paragraphIndex, nextAnnotation) {
        if (!state.currentBook?.id || !chapterId || !Number.isInteger(paragraphIndex)) {
            return false;
        }
        try {
            const response = await fetch(
                `/api/library/${state.currentBook.id}/annotations/${chapterId}/${paragraphIndex}`,
                {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(nextAnnotation)
                }
            );

            if (response.status === 204) {
                removeParagraphAnnotation(chapterId, paragraphIndex);
                await refreshBookmarksForCurrentBook();
                renderPage();
                return true;
            }
            if (!response.ok) {
                return false;
            }

            const savedAnnotation = await response.json();
            setParagraphAnnotation(savedAnnotation);
            await refreshBookmarksForCurrentBook();
            renderPage();
            return true;
        } catch (error) {
            console.error('Failed to save annotation:', error);
            return false;
        }
    }

    function toggleAnnotationMenu() {
        if (!elements.annotationMenuPanel) return;
        if (isAnnotationMenuVisible()) {
            closeAnnotationMenu();
            return;
        }
        elements.annotationMenuPanel.classList.remove('hidden');
        elements.annotationMenuToggle?.classList.add('active');
    }

    function closeAnnotationMenu() {
        if (!elements.annotationMenuPanel) return;
        elements.annotationMenuPanel.classList.add('hidden');
        elements.annotationMenuToggle?.classList.remove('active');
        updateAnnotationControls();
    }

    function showShortcutsOverlay() {
        if (!elements.shortcutsOverlay) return;
        closeAnnotationMenu();
        elements.shortcutsOverlay.classList.remove('hidden');
        ttsPauseForModal();
    }

    function hideShortcutsOverlay(shouldResumeTts = true) {
        if (!elements.shortcutsOverlay) return;
        elements.shortcutsOverlay.classList.add('hidden');
        if (shouldResumeTts) {
            ttsResumeAfterModal();
        }
    }

    function updateAnnotationControls() {
        const chapterId = getCurrentChapterId();
        const hasCurrentParagraph = !!state.currentBook
            && state.paragraphs.length > 0
            && Number.isInteger(state.currentParagraphIndex)
            && state.currentParagraphIndex >= 0
            && state.currentParagraphIndex < state.paragraphs.length
            && !!chapterId;

        const annotation = hasCurrentParagraph
            ? getParagraphAnnotation(chapterId, state.currentParagraphIndex)
            : null;
        const hasNote = !!(annotation && typeof annotation.noteText === 'string' && annotation.noteText.trim().length > 0);
        const hasBookmarks = Array.isArray(state.bookmarks) && state.bookmarks.length > 0;
        const hasCurrentAnnotation = !!annotation && (!!annotation.highlighted || !!annotation.bookmarked || hasNote);

        if (elements.annotationMenuToggle) {
            elements.annotationMenuToggle.disabled = !state.currentBook;
            if (!isAnnotationMenuVisible()) {
                elements.annotationMenuToggle.classList.toggle('active', hasCurrentAnnotation);
            }
        }

        if (elements.highlightToggle) {
            elements.highlightToggle.disabled = !hasCurrentParagraph;
            elements.highlightToggle.classList.toggle('active', !!annotation?.highlighted);
        }
        if (elements.noteToggle) {
            elements.noteToggle.disabled = !hasCurrentParagraph;
            elements.noteToggle.classList.toggle('active', hasNote);
        }
        if (elements.bookmarkToggle) {
            elements.bookmarkToggle.disabled = !hasCurrentParagraph;
            elements.bookmarkToggle.classList.toggle('active', !!annotation?.bookmarked);
        }
        if (elements.bookmarksToggle) {
            elements.bookmarksToggle.disabled = !state.currentBook;
            elements.bookmarksToggle.classList.toggle('active', hasBookmarks);
        }
    }

    function renderBookmarkList() {
        if (!elements.bookmarkList) return;
        if (!Array.isArray(state.bookmarks) || state.bookmarks.length === 0) {
            bookmarkListSelectedIndex = 0;
            elements.bookmarkList.innerHTML = '<li class="bookmark-list-empty">No bookmarks yet.</li>';
            return;
        }

        const maxIndex = state.bookmarks.length - 1;
        bookmarkListSelectedIndex = Math.max(0, Math.min(bookmarkListSelectedIndex, maxIndex));
        elements.bookmarkList.innerHTML = state.bookmarks.map((bookmark, index) => {
            const chapterLabel = escapeHtml(bookmark.chapterTitle || `Chapter ${bookmark.chapterId || ''}`);
            const snippet = escapeHtml(bookmark.snippet || '');
            const selectedClass = index === bookmarkListSelectedIndex ? ' selected' : '';
            return `
                <li class="chapter-list-item bookmark-list-item${selectedClass}"
                    data-bookmark-index="${index}"
                    data-chapter-id="${bookmark.chapterId}"
                    data-paragraph-index="${bookmark.paragraphIndex}">
                    <div class="bookmark-item-top">
                        <span class="chapter-number">${index + 1}.</span>
                        <span class="chapter-name">${chapterLabel}</span>
                    </div>
                    <div class="bookmark-item-snippet">${snippet || '<em>No snippet available.</em>'}</div>
                </li>
            `;
        }).join('');
    }

    function showBookmarksOverlay() {
        if (!elements.bookmarksOverlay || !state.currentBook) return;
        closeAnnotationMenu();
        bookmarkListSelectedIndex = 0;
        renderBookmarkList();
        elements.bookmarksOverlay.classList.remove('hidden');
        ttsPauseForModal();
        if (state.bookmarks.length > 0) {
            const first = elements.bookmarkList.querySelector('.bookmark-list-item');
            first?.scrollIntoView({ block: 'nearest' });
        }
    }

    function hideBookmarksOverlay(shouldResumeTts = true) {
        if (!elements.bookmarksOverlay) return;
        elements.bookmarksOverlay.classList.add('hidden');
        if (shouldResumeTts) {
            ttsResumeAfterModal();
        }
    }

    function bookmarkListNavigate(direction) {
        if (!Array.isArray(state.bookmarks) || state.bookmarks.length === 0) return;
        const maxIndex = state.bookmarks.length - 1;
        bookmarkListSelectedIndex = Math.max(0, Math.min(maxIndex, bookmarkListSelectedIndex + direction));
        renderBookmarkList();
        const selected = elements.bookmarkList.querySelector(`[data-bookmark-index="${bookmarkListSelectedIndex}"]`);
        selected?.scrollIntoView({ block: 'nearest' });
    }

    function selectBookmarkFromList(index) {
        if (!Array.isArray(state.bookmarks)) return;
        const bookmark = state.bookmarks[index];
        if (!bookmark) return;
        hideBookmarksOverlay(false);
        navigateToChapterParagraph(bookmark.chapterId, bookmark.paragraphIndex);
    }

    function openNoteModal() {
        const chapterId = getCurrentChapterId();
        if (!chapterId || !state.currentBook || state.paragraphs.length === 0) {
            return;
        }
        closeAnnotationMenu();
        state.noteModalParagraphIndex = state.currentParagraphIndex;
        const annotation = getParagraphAnnotation(chapterId, state.noteModalParagraphIndex);
        if (elements.noteTextarea) {
            elements.noteTextarea.value = annotation?.noteText || '';
        }
        if (elements.noteDelete) {
            elements.noteDelete.disabled = !(annotation?.noteText && annotation.noteText.trim().length > 0);
        }
        if (elements.noteModalLocation) {
            const chapterTitle = state.chapters[state.currentChapterIndex]?.title || chapterId;
            elements.noteModalLocation.textContent =
                `${chapterTitle}, paragraph ${state.noteModalParagraphIndex + 1}`;
        }
        if (elements.noteModal) {
            elements.noteModal.classList.remove('hidden');
        }
        ttsPauseForModal();
        elements.noteTextarea?.focus();
        elements.noteTextarea?.setSelectionRange(elements.noteTextarea.value.length, elements.noteTextarea.value.length);
    }

    function closeNoteModal(shouldResumeTts = true) {
        if (elements.noteModal) {
            elements.noteModal.classList.add('hidden');
        }
        state.noteModalParagraphIndex = null;
        if (shouldResumeTts) {
            ttsResumeAfterModal();
        }
    }

    async function saveNoteFromModal() {
        const chapterId = getCurrentChapterId();
        const paragraphIndex = state.noteModalParagraphIndex;
        if (!chapterId || !Number.isInteger(paragraphIndex)) return;

        const existing = getParagraphAnnotation(chapterId, paragraphIndex);
        const payload = {
            highlighted: !!existing?.highlighted,
            bookmarked: !!existing?.bookmarked,
            noteText: elements.noteTextarea?.value || ''
        };

        const saved = await upsertParagraphAnnotation(chapterId, paragraphIndex, payload);
        if (saved) {
            closeNoteModal();
        }
    }

    async function deleteNoteFromModal() {
        const chapterId = getCurrentChapterId();
        const paragraphIndex = state.noteModalParagraphIndex;
        if (!chapterId || !Number.isInteger(paragraphIndex)) return;

        const existing = getParagraphAnnotation(chapterId, paragraphIndex);
        if (!existing?.noteText) {
            closeNoteModal();
            return;
        }

        const payload = {
            highlighted: !!existing.highlighted,
            bookmarked: !!existing.bookmarked,
            noteText: ''
        };
        const saved = await upsertParagraphAnnotation(chapterId, paragraphIndex, payload);
        if (saved) {
            closeNoteModal();
        }
    }

    async function toggleHighlightForCurrentParagraph() {
        const chapterId = getCurrentChapterId();
        const paragraphIndex = state.currentParagraphIndex;
        if (!chapterId || !Number.isInteger(paragraphIndex)) return;

        const existing = getParagraphAnnotation(chapterId, paragraphIndex);
        await upsertParagraphAnnotation(chapterId, paragraphIndex, {
            highlighted: !existing?.highlighted,
            bookmarked: !!existing?.bookmarked,
            noteText: existing?.noteText || ''
        });
    }

    async function toggleBookmarkForCurrentParagraph() {
        const chapterId = getCurrentChapterId();
        const paragraphIndex = state.currentParagraphIndex;
        if (!chapterId || !Number.isInteger(paragraphIndex)) return;

        const existing = getParagraphAnnotation(chapterId, paragraphIndex);
        await upsertParagraphAnnotation(chapterId, paragraphIndex, {
            highlighted: !!existing?.highlighted,
            bookmarked: !existing?.bookmarked,
            noteText: existing?.noteText || ''
        });
    }

    // Calculate which paragraphs fit on each page
    function calculatePages() {
        state.pagesData = [];

        if (state.paragraphs.length === 0) {
            state.totalPages = 1;
            return;
        }

        // Get available height for content
        const contentArea = document.querySelector('.reader-content');
        const columnHeight = contentArea.clientHeight;
        const columnWidth = elements.columnLeft.clientWidth;

        // In illustration mode and mobile layout, use single reading column.
        const useSecondColumn = !state.illustrationMode && !state.isMobileLayout;

        // Create a temporary measurement container
        const measureContainer = document.createElement('div');
        measureContainer.style.cssText = `
            position: absolute;
            visibility: hidden;
            width: ${columnWidth}px;
            font-family: var(--font-serif);
            font-size: var(--font-size-body);
            line-height: var(--line-height);
        `;
        document.body.appendChild(measureContainer);

        let currentPageStart = 0;
        let currentHeight = 0;
        let columnCount = 0; // 0 = first column, 1 = second column

        for (let i = 0; i < state.paragraphs.length; i++) {
            const para = state.paragraphs[i];

            // Measure paragraph height
            measureContainer.innerHTML = `<p class="paragraph" style="text-indent: ${i === currentPageStart || (state.pagesData.length > 0 && i === state.pagesData[state.pagesData.length - 1].startParagraph) ? '0' : '1.5em'}; text-align: justify;">${para.content}</p>`;
            const paraHeight = measureContainer.firstChild.offsetHeight;

            // Check if paragraph fits in current column
            if (currentHeight + paraHeight > columnHeight) {
                if (columnCount === 0 && useSecondColumn) {
                    // Move to second column
                    columnCount = 1;
                    currentHeight = paraHeight;
                } else {
                    // Ensure we never emit an empty page when the first paragraph exceeds available height.
                    if (i === currentPageStart) {
                        currentHeight = paraHeight;
                        continue;
                    }
                    // Start new page
                    state.pagesData.push({
                        startParagraph: currentPageStart,
                        endParagraph: i - 1
                    });
                    currentPageStart = i;
                    columnCount = 0;
                    currentHeight = paraHeight;
                }
            } else {
                currentHeight += paraHeight;
            }
        }

        // Add final page
        state.pagesData.push({
            startParagraph: currentPageStart,
            endParagraph: state.paragraphs.length - 1
        });

        state.totalPages = state.pagesData.length;
        document.body.removeChild(measureContainer);
    }

    // Render current page
    function renderPage() {
        localStorage.setItem(STORAGE_KEYS.LAST_PAGE, state.currentPage);
        localStorage.setItem(STORAGE_KEYS.LAST_PARAGRAPH, state.currentParagraphIndex);
        persistCurrentBookActivity();

        if (state.paragraphs.length === 0) {
            elements.columnLeft.innerHTML = '<p class="no-content">No content available</p>';
            elements.columnRight.innerHTML = '';
            elements.pageIndicator.textContent = '';
            updateAnnotationControls();
            updateTouchNavigationControls();
            updateMobileHeaderMenuState();
            return;
        }

        const pageData = state.pagesData[state.currentPage];
        if (!pageData) {
            updateTouchNavigationControls();
            updateMobileHeaderMenuState();
            return;
        }

        const pageParagraphs = state.paragraphs.slice(pageData.startParagraph, pageData.endParagraph + 1);

        // Get column dimensions
        const contentArea = document.querySelector('.reader-content');
        const columnHeight = contentArea.clientHeight;
        const columnWidth = elements.columnLeft.clientWidth;

        // In illustration mode and mobile layout, only use left column.
        const useSecondColumn = !state.illustrationMode && !state.isMobileLayout;

        // Build HTML for both columns
        let leftHtml = '';
        let rightHtml = '';
        let currentHeight = 0;
        let inRightColumn = false;
        const chapterId = getCurrentChapterId();

        // Temporary container for measurement
        const measureContainer = document.createElement('div');
        measureContainer.style.cssText = `
            position: absolute;
            visibility: hidden;
            width: ${columnWidth}px;
            font-family: var(--font-serif);
            font-size: var(--font-size-body);
            line-height: var(--line-height);
        `;
        document.body.appendChild(measureContainer);

        for (let i = 0; i < pageParagraphs.length; i++) {
            const para = pageParagraphs[i];
            const globalIndex = pageData.startParagraph + i;
            const isFirst = i === 0 || (inRightColumn && rightHtml === '');
            const isHighlighted = globalIndex === state.currentParagraphIndex;
            const annotation = getParagraphAnnotation(chapterId, globalIndex);
            const hasNote = !!(annotation && typeof annotation.noteText === 'string' && annotation.noteText.trim().length > 0);
            const classes = ['paragraph'];
            if (isHighlighted) classes.push('highlighted');
            if (annotation?.highlighted) classes.push('annotation-highlight');
            if (annotation?.bookmarked) classes.push('annotation-bookmarked');
            if (hasNote) classes.push('annotation-noted');
            const isSearchHighlighted = chapterId === state.searchHighlightChapterId
                && globalIndex === state.searchHighlightParagraphIndex
                && Array.isArray(state.searchHighlightTerms)
                && state.searchHighlightTerms.length > 0;
            if (isSearchHighlighted) {
                classes.push('search-match');
            }
            const paraContent = isSearchHighlighted
                ? highlightTermsInHtml(para.content, state.searchHighlightTerms)
                : para.content;

            const paraHtml = `<p class="${classes.join(' ')}" data-index="${globalIndex}" style="text-indent: ${isFirst ? '0' : '1.5em'}">${paraContent}</p>`;

            // Measure
            measureContainer.innerHTML = `<p class="paragraph" style="text-indent: ${isFirst ? '0' : '1.5em'}; text-align: justify;">${para.content}</p>`;
            const paraHeight = measureContainer.firstChild.offsetHeight;

            if (!inRightColumn && currentHeight + paraHeight > columnHeight && useSecondColumn) {
                inRightColumn = true;
                currentHeight = 0;
            }

            if (inRightColumn && useSecondColumn) {
                rightHtml += paraHtml;
            } else {
                leftHtml += paraHtml;
            }
            currentHeight += paraHeight;
        }

        document.body.removeChild(measureContainer);

        elements.columnLeft.innerHTML = leftHtml || '';
        elements.columnRight.innerHTML = useSecondColumn ? (rightHtml || '') : '';

        // Update page indicator
        elements.pageIndicator.textContent = `Page ${state.currentPage + 1} of ${state.totalPages}`;

        // Ensure current paragraph is valid
        if (state.currentParagraphIndex < pageData.startParagraph || state.currentParagraphIndex > pageData.endParagraph) {
            state.currentParagraphIndex = pageData.startParagraph;
        }

        updateAnnotationControls();
        scheduleCharacterDiscoveryCheck();
        updateTouchNavigationControls();
        updateMobileHeaderMenuState();
    }

    // Navigation functions
    function nextPage() {
        if (state.currentPage < state.totalPages - 1) {
            state.currentPage++;
            state.currentParagraphIndex = state.pagesData[state.currentPage].startParagraph;
            renderPage();
        } else if (state.currentChapterIndex < state.chapters.length - 1) {
            goToNextChapter(true);
        }
    }

    function prevPage() {
        if (state.currentPage > 0) {
            state.currentPage--;
            state.currentParagraphIndex = state.pagesData[state.currentPage].startParagraph;
            renderPage();
        } else if (state.currentChapterIndex > 0) {
            // Go to previous chapter, last page
            loadChapter(state.currentChapterIndex - 1).then(applied => {
                if (!applied) return;
                state.currentPage = state.totalPages - 1;
                if (state.pagesData[state.currentPage]) {
                    state.currentParagraphIndex = state.pagesData[state.currentPage].startParagraph;
                }
                renderPage();
            });
        }
    }

    function nextParagraph() {
        if (state.currentParagraphIndex < state.paragraphs.length - 1) {
            state.currentParagraphIndex++;

            // Check if we need to change page
            const pageData = state.pagesData[state.currentPage];
            if (state.currentParagraphIndex > pageData.endParagraph) {
                nextPage();
            } else {
                renderPage();
            }
        } else if (state.currentChapterIndex < state.chapters.length - 1) {
            goToNextChapter(true);
        }
    }

    function prevParagraph() {
        if (state.currentParagraphIndex > 0) {
            state.currentParagraphIndex--;

            // Check if we need to change page
            const pageData = state.pagesData[state.currentPage];
            if (state.currentParagraphIndex < pageData.startParagraph) {
                prevPage();
                state.currentParagraphIndex = state.pagesData[state.currentPage].endParagraph;
                renderPage();
            } else {
                renderPage();
            }
        } else if (state.currentChapterIndex > 0) {
            // Go to previous chapter, last paragraph
            loadChapter(state.currentChapterIndex - 1).then(applied => {
                if (!applied) return;
                state.currentPage = state.totalPages - 1;
                state.currentParagraphIndex = state.paragraphs.length - 1;
                renderPage();
            });
        }
    }

    function nextChapter() {
        if (state.currentChapterIndex < state.chapters.length - 1) {
            goToNextChapter(true);
        }
    }

    function prevChapter() {
        if (state.currentChapterIndex > 0) {
            loadChapter(state.currentChapterIndex - 1, 0);
        }
    }

    function shouldShowChapterRecapOnTransition() {
        return (state.recapAvailable || state.quizAvailable) &&
            !state.recapOptOut &&
            !state.ttsEnabled &&
            !state.speedReadingActive;
    }

    async function goToNextChapter(showRecap) {
        const nextChapterIndex = state.currentChapterIndex + 1;
        if (nextChapterIndex >= state.chapters.length) return;

        if (showRecap && shouldShowChapterRecapOnTransition()) {
            if (state.recapCacheOnly || state.quizCacheOnly) {
                const transitionDataReady = await isCurrentChapterPauseReady();
                if (!transitionDataReady) {
                    loadChapter(nextChapterIndex, 0);
                    return;
                }
            }
            openChapterRecapOverlay(nextChapterIndex);
            return;
        }

        loadChapter(nextChapterIndex, 0);
    }

    async function openChapterRecapOverlay(nextChapterIndex) {
        if (!state.currentBook) {
            loadChapter(nextChapterIndex, 0);
            return;
        }

        const currentChapter = state.chapters[state.currentChapterIndex];
        if (!currentChapter) {
            loadChapter(nextChapterIndex, 0);
            return;
        }

        state.recapPendingChapterIndex = nextChapterIndex;
        state.recapChatChapterIndex = state.currentChapterIndex;
        state.quizChapterId = currentChapter.id;
        state.quizQuestions = [];
        state.quizSelectedAnswers = [];
        state.quizSubmitting = false;
        state.quizResult = null;
        state.quizDifficultyLevel = 0;
        await recapCheckAvailability();
        await quizCheckAvailability();
        clearRecapOverlayError();
        clearRecapChatError();
        if (elements.chapterRecapOptout) {
            elements.chapterRecapOptout.checked = state.recapOptOut;
        }
        setChapterRecapTab(state.recapAvailable ? 'recap' : 'quiz');
        elements.chapterRecapChapterTitle.textContent = currentChapter.title || 'Current chapter';
        elements.chapterRecapStatus.textContent = 'Loading recap...';
        elements.chapterRecapSummary.textContent = 'Preparing chapter recap.';
        if (elements.chapterQuizStatus) {
            elements.chapterQuizStatus.textContent = 'Loading quiz...';
        }
        renderRecapList(elements.chapterRecapEvents, []);
        renderRecapList(elements.chapterRecapCharacters, []);
        renderChapterQuizQuestions();
        renderChapterQuizFeedback(null);
        state.recapChatHistory = loadRecapChatHistory(state.recapChatChapterIndex);
        renderRecapChatMessages();
        setRecapChatControls();
        setQuizControls();
        if (elements.chapterRecapChatInput) {
            elements.chapterRecapChatInput.value = '';
        }
        elements.chapterRecapOverlay.classList.remove('hidden');
        ttsPauseForModal();
        trackRecapAnalytics('viewed');

        stopRecapOverlayPolling();
        const shouldPoll = await refreshChapterRecapOverlay(currentChapter.id);
        if (shouldPoll && isChapterRecapVisible()) {
            startRecapOverlayPolling(currentChapter.id);
        }
    }

    function populateChapterRecapOverlay(recap) {
        const payload = recap && recap.payload ? recap.payload : {};
        const summary = (payload.shortSummary || '').trim();
        const events = Array.isArray(payload.keyEvents) ? payload.keyEvents : [];
        const deltas = Array.isArray(payload.characterDeltas) ? payload.characterDeltas : [];

        if (recap && recap.status) {
            if (recap.status === 'COMPLETED') {
                elements.chapterRecapStatus.textContent = 'Recap ready';
            } else if (recap.status === 'MISSING' || recap.status === 'PENDING' || recap.status === 'GENERATING') {
                elements.chapterRecapStatus.textContent = 'Recap is still generating.';
            } else if (recap.status === 'FAILED') {
                elements.chapterRecapStatus.textContent = 'Recap generation failed.';
            } else {
                elements.chapterRecapStatus.textContent = `Recap status: ${recap.status}`;
            }
        } else {
            elements.chapterRecapStatus.textContent = 'Recap status unknown.';
        }

        elements.chapterRecapSummary.textContent = summary ||
            'Recap details are not ready yet. You can skip recap and continue.';

        renderRecapList(elements.chapterRecapEvents, events);
        renderRecapList(
            elements.chapterRecapCharacters,
            deltas.map(d => `${d.characterName}: ${d.delta}`)
        );
    }

    function setChapterRecapTab(tab) {
        const validTab = tab === 'chat' || tab === 'quiz' || tab === 'recap' ? tab : 'recap';
        let nextTab = validTab;
        if (nextTab === 'recap' && !state.recapAvailable && state.quizAvailable) {
            nextTab = 'quiz';
        }
        if (nextTab === 'quiz' && !state.quizAvailable && state.recapAvailable) {
            nextTab = 'recap';
        }
        if (nextTab === 'chat' && !state.recapChatAvailable) {
            if (state.recapAvailable) {
                nextTab = 'recap';
            } else if (state.quizAvailable) {
                nextTab = 'quiz';
            }
        }

        const recapActive = nextTab === 'recap';
        const chatActive = nextTab === 'chat';
        const quizActive = nextTab === 'quiz';
        state.recapActiveTab = nextTab;

        if (elements.chapterRecapTabRecap) {
            elements.chapterRecapTabRecap.classList.toggle('active', recapActive);
            elements.chapterRecapTabRecap.setAttribute('aria-selected', recapActive ? 'true' : 'false');
        }
        if (elements.chapterRecapTabChat) {
            elements.chapterRecapTabChat.classList.toggle('active', chatActive);
            elements.chapterRecapTabChat.setAttribute('aria-selected', chatActive ? 'true' : 'false');
        }
        if (elements.chapterRecapTabQuiz) {
            elements.chapterRecapTabQuiz.classList.toggle('active', quizActive);
            elements.chapterRecapTabQuiz.setAttribute('aria-selected', quizActive ? 'true' : 'false');
        }
        if (elements.chapterRecapPanelRecap) {
            elements.chapterRecapPanelRecap.classList.toggle('hidden', !recapActive);
        }
        if (elements.chapterRecapPanelChat) {
            elements.chapterRecapPanelChat.classList.toggle('hidden', !chatActive);
        }
        if (elements.chapterRecapPanelQuiz) {
            elements.chapterRecapPanelQuiz.classList.toggle('hidden', !quizActive);
        }
    }

    function shouldPollRecapStatus(status) {
        return status === 'MISSING' || status === 'PENDING' || status === 'GENERATING';
    }

    async function refreshChapterRecapOverlay(chapterId) {
        const pollStates = [];
        try {
            clearRecapOverlayError();
            const response = await fetch(`/api/recaps/chapter/${chapterId}`, { cache: 'no-store' });
            if (!response.ok) {
                const payload = await readErrorPayload(response);
                const mapped = mapRecapError({
                    status: response.status,
                    message: firstMessageFromPayload(payload)
                });
                elements.chapterRecapStatus.textContent = mapped.message;
                elements.chapterRecapSummary.textContent = 'You can continue to the next chapter now, and recap data will populate once generation completes.';
                setRecapOverlayError(
                    mapped.message,
                    mapped.retryable ? () => refreshChapterRecapOverlay(chapterId) : null
                );
            } else {
                const recap = await response.json();
                clearRecapOverlayError();
                populateChapterRecapOverlay(recap);
                pollStates.push(recap && shouldPollRecapStatus(recap.status));
            }
        } catch (error) {
            console.debug('Failed to load chapter recap:', error);
            const mapped = mapRecapError({ network: true });
            elements.chapterRecapStatus.textContent = mapped.message;
            elements.chapterRecapSummary.textContent = 'You can continue to the next chapter now, and recap data will populate once generation completes.';
            setRecapOverlayError(
                mapped.message,
                mapped.retryable ? () => refreshChapterRecapOverlay(chapterId) : null
            );
        }

        const shouldPollQuiz = await refreshChapterQuizOverlay(chapterId);
        pollStates.push(shouldPollQuiz);
        return pollStates.some(Boolean);
    }

    function startRecapOverlayPolling(chapterId) {
        stopRecapOverlayPolling();
        state.recapPollingChapterId = chapterId;
        state.recapPollingInterval = setInterval(async () => {
            if (!isChapterRecapVisible() || state.recapPollingChapterId !== chapterId) {
                stopRecapOverlayPolling();
                return;
            }
            if (state.recapPollingInFlight) {
                return;
            }

            state.recapPollingInFlight = true;
            try {
                const shouldContinue = await refreshChapterRecapOverlay(chapterId);
                if (!shouldContinue) {
                    stopRecapOverlayPolling();
                }
            } finally {
                state.recapPollingInFlight = false;
            }
        }, 3000);
    }

    function stopRecapOverlayPolling() {
        if (state.recapPollingInterval) {
            clearInterval(state.recapPollingInterval);
        }
        state.recapPollingInterval = null;
        state.recapPollingChapterId = null;
        state.recapPollingInFlight = false;
    }

    function renderRecapList(listElement, values) {
        if (!listElement) return;
        listElement.innerHTML = '';
        if (!Array.isArray(values) || values.length === 0) {
            const li = document.createElement('li');
            li.textContent = 'Not available yet.';
            listElement.appendChild(li);
            return;
        }
        values.forEach(value => {
            const li = document.createElement('li');
            li.textContent = value;
            listElement.appendChild(li);
        });
    }

    function loadRecapChatHistory(chapterIndex = state.recapChatChapterIndex) {
        const key = getRecapChatStorageKey(chapterIndex);
        if (!key) return [];
        const stored = localStorage.getItem(key);
        if (!stored) return [];
        try {
            const parsed = JSON.parse(stored);
            if (!Array.isArray(parsed)) return [];
            return parsed
                .filter(msg => msg && typeof msg.content === 'string' && msg.content.trim())
                .map(msg => ({
                    role: msg.role === 'user' ? 'user' : 'assistant',
                    content: msg.content.trim(),
                    timestamp: Number.isFinite(msg.timestamp) ? msg.timestamp : Date.now()
                }));
        } catch (_error) {
            return [];
        }
    }

    function saveRecapChatHistory(history, chapterIndex = state.recapChatChapterIndex) {
        const key = getRecapChatStorageKey(chapterIndex);
        if (!key) return;
        const limited = Array.isArray(history) ? history.slice(-80) : [];
        localStorage.setItem(key, JSON.stringify(limited));
    }

    function getRecapChatStorageKey(chapterIndex) {
        if (!state.currentBook?.id || !Number.isInteger(chapterIndex) || chapterIndex < 0) {
            return null;
        }
        return `${STORAGE_KEYS.RECAP_CHAT_PREFIX}${state.currentBook.id}_ch${chapterIndex}`;
    }

    function setRecapChatControls() {
        const canUseChat = state.recapChatAvailable && !!state.currentBook;
        const inputDisabled = !canUseChat || state.recapChatLoading;
        const sendDisabled = !canUseChat || state.recapChatLoading;
        const chatUnavailable = !state.recapChatAvailable;
        const recapUnavailable = !state.recapAvailable;

        if (elements.chapterRecapChatInput) {
            elements.chapterRecapChatInput.disabled = inputDisabled;
        }
        if (elements.chapterRecapChatSend) {
            elements.chapterRecapChatSend.disabled = sendDisabled;
        }
        if (elements.chapterRecapTabChat) {
            elements.chapterRecapTabChat.classList.toggle('unavailable', chatUnavailable);
        }
        if (elements.chapterRecapTabRecap) {
            elements.chapterRecapTabRecap.classList.toggle('unavailable', recapUnavailable);
        }
        if (!canUseChat) {
            clearRecapChatError();
        }

        if (!elements.chapterRecapChatStatus) return;
        if (!state.recapAvailable) {
            elements.chapterRecapChatStatus.textContent = 'Recap chat is unavailable for this book.';
            return;
        }
        if (!state.recapChatEnabled) {
            elements.chapterRecapChatStatus.textContent = 'Recap chat is disabled in this environment.';
            return;
        }
        if (!state.recapChatAvailable) {
            elements.chapterRecapChatStatus.textContent = 'Recap chat provider is unavailable.';
            return;
        }
        elements.chapterRecapChatStatus.textContent = state.recapChatLoading
            ? 'Generating response...'
            : 'Spoiler-safe discussion through your current chapter.';
    }

    function renderRecapChatMessages() {
        if (!elements.chapterRecapChatMessages) return;
        if (!Array.isArray(state.recapChatHistory) || state.recapChatHistory.length === 0) {
            elements.chapterRecapChatMessages.innerHTML = '<div class="chapter-recap-chat-empty">Ask a question about what happened so far.</div>';
            return;
        }

        elements.chapterRecapChatMessages.innerHTML = state.recapChatHistory.map(msg => {
            const roleClass = msg.role === 'user' ? 'user' : 'character';
            return `
                <div class="chat-message ${roleClass}">
                    ${escapeHtml(msg.content)}
                </div>
            `;
        }).join('');

        elements.chapterRecapChatMessages.scrollTop = elements.chapterRecapChatMessages.scrollHeight;
    }

    async function refreshChapterQuizOverlay(chapterId) {
        try {
            const response = await fetch(`/api/quizzes/chapter/${chapterId}`, { cache: 'no-store' });
            if (!response.ok) {
                if (elements.chapterQuizStatus) {
                    elements.chapterQuizStatus.textContent = 'Quiz unavailable right now.';
                }
                state.quizQuestions = [];
                state.quizSelectedAnswers = [];
                renderChapterQuizQuestions();
                renderChapterQuizFeedback(null);
                setQuizControls();
                return false;
            }
            const quiz = await response.json();
            populateChapterQuizOverlay(quiz);
            return quiz && shouldPollRecapStatus(quiz.status);
        } catch (error) {
            console.debug('Failed to load chapter quiz:', error);
            if (elements.chapterQuizStatus) {
                elements.chapterQuizStatus.textContent = 'Quiz unavailable right now.';
            }
            state.quizQuestions = [];
            state.quizSelectedAnswers = [];
            renderChapterQuizQuestions();
            renderChapterQuizFeedback(null);
            setQuizControls();
            return false;
        }
    }

    function populateChapterQuizOverlay(quiz) {
        const payload = quiz && quiz.payload ? quiz.payload : {};
        const questions = Array.isArray(payload.questions) ? payload.questions : [];
        const previousSelections = Array.isArray(state.quizSelectedAnswers) ? state.quizSelectedAnswers : [];
        const sameQuestionCount = Array.isArray(state.quizQuestions) && state.quizQuestions.length === questions.length;
        const difficultyLevel = Number.isInteger(quiz?.difficultyLevel) ? quiz.difficultyLevel : 0;

        state.quizQuestions = questions;
        state.quizDifficultyLevel = difficultyLevel;
        state.quizSelectedAnswers = questions.map((_, index) =>
            Number.isInteger(previousSelections[index]) ? previousSelections[index] : null
        );
        if (!sameQuestionCount) {
            state.quizResult = null;
            renderChapterQuizFeedback(null);
        }

        if (elements.chapterQuizStatus) {
            if (quiz && quiz.status === 'COMPLETED') {
                elements.chapterQuizStatus.textContent = `Quiz ready (${formatQuizDifficulty(difficultyLevel)})`;
            } else if (quiz && (quiz.status === 'MISSING' || quiz.status === 'PENDING' || quiz.status === 'GENERATING')) {
                elements.chapterQuizStatus.textContent = 'Quiz is still generating.';
            } else if (quiz && quiz.status === 'FAILED') {
                elements.chapterQuizStatus.textContent = 'Quiz generation failed.';
            } else {
                elements.chapterQuizStatus.textContent = 'Quiz status unknown.';
            }
        }

        renderChapterQuizQuestions();
        setQuizControls();
    }

    function renderChapterQuizQuestions() {
        if (!elements.chapterQuizQuestions) return;

        const questions = Array.isArray(state.quizQuestions) ? state.quizQuestions : [];
        if (questions.length === 0) {
            elements.chapterQuizQuestions.innerHTML = '<div class="chapter-recap-chat-empty">Quiz questions are not ready yet.</div>';
            return;
        }

        elements.chapterQuizQuestions.innerHTML = questions.map((question, questionIndex) => {
            const prompt = escapeHtml(question.question || `Question ${questionIndex + 1}`);
            const options = Array.isArray(question.options) ? question.options : [];
            const optionsHtml = options.map((option, optionIndex) => {
                const checked = state.quizSelectedAnswers[questionIndex] === optionIndex ? 'checked' : '';
                return `
                    <li>
                        <label>
                            <input type="radio" name="chapter-quiz-q-${questionIndex}" data-question-index="${questionIndex}" value="${optionIndex}" ${checked} ${state.quizSubmitting ? 'disabled' : ''} />
                            <span>${escapeHtml(option)}</span>
                        </label>
                    </li>
                `;
            }).join('');
            return `
                <div class="chapter-quiz-question">
                    <div class="chapter-quiz-question-title">${questionIndex + 1}. ${prompt}</div>
                    <ul class="chapter-quiz-options">${optionsHtml}</ul>
                </div>
            `;
        }).join('');

        elements.chapterQuizQuestions.querySelectorAll('input[type="radio"]').forEach(input => {
            input.addEventListener('change', () => {
                const questionIndex = Number.parseInt(input.dataset.questionIndex, 10);
                const selectedIndex = Number.parseInt(input.value, 10);
                if (!Number.isInteger(questionIndex) || questionIndex < 0) return;
                if (!Number.isInteger(selectedIndex) || selectedIndex < 0) return;
                state.quizSelectedAnswers[questionIndex] = selectedIndex;
                setQuizControls();
            });
        });
    }

    function setQuizControls() {
        const quizUnavailable = !state.quizAvailable;
        if (elements.chapterRecapTabQuiz) {
            elements.chapterRecapTabQuiz.classList.toggle('unavailable', quizUnavailable);
            elements.chapterRecapTabQuiz.disabled = quizUnavailable;
            elements.chapterRecapTabQuiz.setAttribute('aria-disabled', quizUnavailable ? 'true' : 'false');
        }
        if (!elements.chapterQuizSubmit) return;

        const hasQuestions = Array.isArray(state.quizQuestions) && state.quizQuestions.length > 0;
        const answeredCount = Array.isArray(state.quizSelectedAnswers)
            ? state.quizSelectedAnswers.filter(value => Number.isInteger(value)).length
            : 0;
        elements.chapterQuizSubmit.disabled = quizUnavailable
            || state.quizSubmitting
            || !hasQuestions
            || answeredCount === 0;
        elements.chapterQuizSubmit.textContent = state.quizSubmitting
            ? 'Checking...'
            : 'Check Answers';
    }

    function renderChapterQuizFeedback(result) {
        if (!elements.chapterQuizFeedback) return;
        if (!result) {
            elements.chapterQuizFeedback.classList.add('hidden');
            elements.chapterQuizFeedback.innerHTML = '';
            return;
        }

        const missed = Array.isArray(result.results)
            ? result.results.filter(item => item && item.correct === false)
            : [];
        const score = Number.isFinite(result.scorePercent) ? result.scorePercent : 0;
        const correctAnswers = Number.isFinite(result.correctAnswers) ? result.correctAnswers : 0;
        const totalQuestions = Number.isFinite(result.totalQuestions) ? result.totalQuestions : 0;
        const difficultyLabel = formatQuizDifficulty(result.difficultyLevel);
        const progress = result.progress || {};
        const totalAttempts = Number.isFinite(progress.totalAttempts) ? progress.totalAttempts : 0;
        const perfectAttempts = Number.isFinite(progress.perfectAttempts) ? progress.perfectAttempts : 0;
        const streak = Number.isFinite(progress.currentPerfectStreak) ? progress.currentPerfectStreak : 0;
        const unlockedTrophies = Array.isArray(result.unlockedTrophies) ? result.unlockedTrophies : [];

        const missedItems = missed.map(item => {
            const qNum = Number.isFinite(item.questionIndex) ? item.questionIndex + 1 : '?';
            const correctAnswer = escapeHtml(item.correctAnswer || '');
            const citation = escapeHtml(item.citationSnippet || '');
            const citationLine = citation
                ? `<div><em>Citation:</em> ${citation}</div>`
                : '';
            return `<li><strong>Q${qNum}</strong> correct answer: ${correctAnswer}${citationLine}</li>`;
        }).join('');

        const details = missed.length > 0
            ? `<ul class="chapter-quiz-feedback-list">${missedItems}</ul>`
            : '<div class="chapter-quiz-feedback-list">Perfect score. Nice recall.</div>';

        const unlockedBlock = unlockedTrophies.length > 0
            ? `<div class="chapter-quiz-trophies"><strong>New trophy unlocked:</strong> ${unlockedTrophies.map(t => escapeHtml(t.title || t.code || 'Trophy')).join(', ')}</div>`
            : '';

        elements.chapterQuizFeedback.innerHTML = `
            <div class="chapter-quiz-feedback-score">Score: ${correctAnswers}/${totalQuestions} (${score}%) • ${difficultyLabel}</div>
            <div class="chapter-quiz-feedback-meta">Attempts: ${totalAttempts} • Perfect: ${perfectAttempts} • Current streak: ${streak}</div>
            ${unlockedBlock}
            ${details}
        `;
        elements.chapterQuizFeedback.classList.remove('hidden');
    }

    function formatQuizDifficulty(level) {
        const numeric = Number.isInteger(level) ? level : 0;
        if (numeric <= 0) return 'Easy';
        if (numeric === 1) return 'Medium';
        if (numeric === 2) return 'Hard';
        return `Expert ${numeric}`;
    }

    async function submitChapterQuiz() {
        if (!state.quizChapterId || !Array.isArray(state.quizQuestions) || state.quizQuestions.length === 0) {
            return;
        }
        if (state.quizSubmitting || !state.quizAvailable) {
            return;
        }

        state.quizSubmitting = true;
        setQuizControls();

        try {
            const payload = {
                selectedOptionIndexes: state.quizQuestions.map((_, index) =>
                    Number.isInteger(state.quizSelectedAnswers[index]) ? state.quizSelectedAnswers[index] : -1
                )
            };
            const response = await fetch(`/api/quizzes/chapter/${state.quizChapterId}/grade`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if (!response.ok) {
                if (elements.chapterQuizStatus) {
                    elements.chapterQuizStatus.textContent = response.status === 409
                        ? 'Quiz is still generating.'
                        : 'Unable to grade quiz right now.';
                }
                return;
            }

            const result = await response.json();
            state.quizResult = result;
            renderChapterQuizFeedback(result);
        } catch (error) {
            console.debug('Quiz grading failed:', error);
            if (elements.chapterQuizStatus) {
                elements.chapterQuizStatus.textContent = 'Unable to grade quiz right now.';
            }
        } finally {
            state.quizSubmitting = false;
            setQuizControls();
        }
    }

    async function sendRecapChatMessage(options = {}) {
        const retryMessage = typeof options.retryMessage === 'string' ? options.retryMessage : '';
        const appendUserMessage = options.appendUser !== false;
        const message = (retryMessage || elements.chapterRecapChatInput?.value || '').trim();
        if (!message || !state.currentBook || state.recapChatLoading || !state.recapChatAvailable) return;
        clearRecapChatError();
        const chatChapterIndex = Number.isInteger(state.recapChatChapterIndex)
            ? state.recapChatChapterIndex
            : state.currentChapterIndex;

        if (appendUserMessage) {
            const userMsg = { role: 'user', content: message, timestamp: Date.now() };
            state.recapChatHistory.push(userMsg);
            saveRecapChatHistory(state.recapChatHistory, chatChapterIndex);
            renderRecapChatMessages();
        }

        if (appendUserMessage && elements.chapterRecapChatInput) {
            elements.chapterRecapChatInput.value = '';
        }

        state.recapChatLoading = true;
        setRecapChatControls();

        const loadingDiv = document.createElement('div');
        loadingDiv.className = 'chat-message character loading';
        loadingDiv.textContent = 'Thinking';
        elements.chapterRecapChatMessages?.appendChild(loadingDiv);
        if (elements.chapterRecapChatMessages) {
            elements.chapterRecapChatMessages.scrollTop = elements.chapterRecapChatMessages.scrollHeight;
        }

        try {
            const response = await fetch(`/api/recaps/book/${state.currentBook.id}/chat`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    message,
                    conversationHistory: state.recapChatHistory.slice(-10),
                    readerChapterIndex: chatChapterIndex
                })
            });

            if (!response.ok) {
                const payload = await readErrorPayload(response);
                const mapped = mapChatError({
                    status: response.status,
                    message: firstMessageFromPayload(payload)
                });
                loadingDiv.remove();
                setRecapChatError(
                    mapped.message,
                    mapped.retryable ? () => {
                        sendRecapChatMessage({ retryMessage: message, appendUser: false });
                    } : null
                );
                return;
            }

            const data = await response.json().catch(() => ({}));
            const reply = (data && typeof data.response === 'string') ? data.response : '';
            loadingDiv.remove();
            const assistantMsg = {
                role: 'assistant',
                content: reply.trim() || "I don't have enough context to answer that yet.",
                timestamp: Date.now()
            };
            state.recapChatHistory.push(assistantMsg);
            saveRecapChatHistory(state.recapChatHistory, chatChapterIndex);
            renderRecapChatMessages();
        } catch (error) {
            console.debug('Recap chat failed:', error);
            loadingDiv.remove();
            const mapped = mapChatError({ network: true });
            setRecapChatError(
                mapped.message,
                mapped.retryable ? () => {
                    sendRecapChatMessage({ retryMessage: message, appendUser: false });
                } : null
            );
        } finally {
            state.recapChatLoading = false;
            setRecapChatControls();
            elements.chapterRecapChatInput?.focus();
        }
    }

    function closeChapterRecapOverlay(restoreAudio = true) {
        if (!elements.chapterRecapOverlay) return;
        stopRecapOverlayPolling();
        elements.chapterRecapOverlay.classList.add('hidden');
        state.recapPendingChapterIndex = null;
        state.recapChatChapterIndex = null;
        state.quizChapterId = null;
        state.quizQuestions = [];
        state.quizSelectedAnswers = [];
        state.quizSubmitting = false;
        state.quizResult = null;
        state.quizDifficultyLevel = 0;
        setChapterRecapTab('recap');
        state.recapChatLoading = false;
        clearRecapOverlayError();
        clearRecapChatError();
        if (elements.chapterRecapChatInput) {
            elements.chapterRecapChatInput.value = '';
        }
        renderChapterQuizFeedback(null);
        renderChapterQuizQuestions();
        setRecapChatControls();
        setQuizControls();
        if (restoreAudio) {
            ttsResumeAfterModal();
        }
    }

    async function isCurrentChapterPauseReady() {
        if (!state.currentBook || !state.chapters || state.currentChapterIndex < 0) {
            return false;
        }
        const currentChapter = state.chapters[state.currentChapterIndex];
        if (!currentChapter) {
            return false;
        }
        try {
            let recapReady = false;
            let quizReady = false;

            if (state.recapAvailable) {
                const recapResponse = await fetch(`/api/recaps/chapter/${currentChapter.id}/status`, { cache: 'no-store' });
                if (recapResponse.ok) {
                    const recapStatus = await recapResponse.json();
                    recapReady = recapStatus && recapStatus.ready === true;
                }
            }

            if (state.quizAvailable) {
                const quizResponse = await fetch(`/api/quizzes/chapter/${currentChapter.id}/status`, { cache: 'no-store' });
                if (quizResponse.ok) {
                    const quizStatus = await quizResponse.json();
                    quizReady = quizStatus && quizStatus.ready === true;
                }
            }
            return recapReady || quizReady;
        } catch (error) {
            console.debug('Failed to check chapter pause cache status:', error);
            return false;
        }
    }

    async function continueFromChapterRecap(eventType = 'continued') {
        const nextChapterIndex = state.recapPendingChapterIndex;
        if (eventType) {
            trackRecapAnalytics(eventType);
        }
        closeChapterRecapOverlay(false);
        if (nextChapterIndex == null) return;
        await loadChapter(nextChapterIndex, 0);
    }

    async function skipChapterRecap() {
        await continueFromChapterRecap('skipped');
    }

    // Search
    let searchTimeout = null;

    function setDesktopSearchExpanded(expanded, options = {}) {
        if (!elements.searchContainer || !elements.searchInput || !elements.searchToggle) return;

        const shouldExpand = expanded === true;
        elements.searchContainer.classList.toggle('expanded', shouldExpand);
        elements.searchToggle.setAttribute('aria-expanded', shouldExpand ? 'true' : 'false');
        elements.searchInput.tabIndex = shouldExpand ? 0 : -1;

        if (shouldExpand && options.focus === true) {
            requestAnimationFrame(() => {
                elements.searchInput.focus();
            });
        }
    }

    function setSearchInputValues(value, options = {}) {
        const nextValue = typeof value === 'string' ? value : '';
        const skipDesktop = options.skipDesktop === true;
        const skipMobile = options.skipMobile === true;
        if (!skipDesktop && elements.searchInput && elements.searchInput.value !== nextValue) {
            elements.searchInput.value = nextValue;
        }
        if (!skipMobile && elements.mobileMenuSearchInput && elements.mobileMenuSearchInput.value !== nextValue) {
            elements.mobileMenuSearchInput.value = nextValue;
        }
        if (!skipDesktop && elements.searchInput && !state.isMobileLayout) {
            const hasQuery = nextValue.trim().length > 0;
            const focused = document.activeElement === elements.searchInput;
            setDesktopSearchExpanded(hasQuery || focused);
        }
    }

    function scheduleSearch(query) {
        clearTimeout(searchTimeout);
        searchTimeout = setTimeout(() => {
            performSearch(query);
        }, 300);
    }

    function submitMobileMenuSearch() {
        if (!elements.mobileMenuSearchInput) return;
        const query = elements.mobileMenuSearchInput.value || '';
        setSearchInputValues(query, { skipMobile: true });
        closeMobileHeaderMenu();
        performSearch(query);
    }

    function escapeRegExp(value) {
        return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    function extractSearchTerms(query) {
        if (!query || typeof query !== 'string') {
            return [];
        }
        const normalized = query.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
        if (!normalized) {
            return [];
        }
        const unique = new Set();
        normalized.split(/\s+/)
            .filter(term => term.length >= 2)
            .forEach(term => unique.add(term));
        return Array.from(unique);
    }

    function highlightTermsInText(text, terms) {
        if (!text || !Array.isArray(terms) || terms.length === 0) {
            return text || '';
        }
        const pattern = terms.map(escapeRegExp).join('|');
        if (!pattern) {
            return text;
        }
        const regex = new RegExp(`(${pattern})`, 'gi');
        return text.replace(regex, '<mark class="search-hit">$1</mark>');
    }

    function highlightTermsInHtml(html, terms) {
        if (!html || !Array.isArray(terms) || terms.length === 0) {
            return html || '';
        }
        const parts = html.split(/(<[^>]+>)/g);
        return parts.map(part => {
            if (part.startsWith('<') && part.endsWith('>')) {
                return part;
            }
            return highlightTermsInText(part, terms);
        }).join('');
    }

    function clearSearchHighlightState() {
        state.searchHighlightTerms = [];
        state.searchHighlightChapterId = null;
        state.searchHighlightParagraphIndex = null;
    }

    function chapterTitleForSearch(chapterId) {
        const chapter = state.chapters.find(c => c.id === chapterId);
        return chapter?.title || chapterId || 'Unknown chapter';
    }

    function renderSearchChapterFilterOptions() {
        if (!elements.searchChapterFilter) return;
        const previous = state.searchChapterFilter;
        const options = ['<option value="">All chapters</option>'];
        options.push(...state.chapters.map(chapter => (
            `<option value="${chapter.id}">${escapeHtml(chapter.title)}</option>`
        )));
        elements.searchChapterFilter.innerHTML = options.join('');

        const stillExists = !previous || state.chapters.some(chapter => chapter.id === previous);
        state.searchChapterFilter = stillExists ? previous : '';
        elements.searchChapterFilter.value = state.searchChapterFilter;
    }

    function renderSearchResults(results, query) {
        if (!elements.searchResultsList) return;
        clearSearchError();
        if (!Array.isArray(results) || results.length === 0) {
            elements.searchResultsList.innerHTML = '<div class="search-result-empty"><em>No results found</em></div>';
            return;
        }

        const terms = extractSearchTerms(query);
        const grouped = new Map();
        for (const result of results) {
            const chapterId = result.chapterId || 'unknown-chapter';
            if (!grouped.has(chapterId)) {
                grouped.set(chapterId, []);
            }
            grouped.get(chapterId).push(result);
        }

        const html = Array.from(grouped.entries()).map(([chapterId, groupResults]) => {
            const chapterTitle = escapeHtml(chapterTitleForSearch(chapterId));
            const items = groupResults.map(result => {
                const snippetRaw = escapeHtml(result.snippet || '');
                const snippet = highlightTermsInText(snippetRaw, terms);
                return `
                    <div class="search-result-item" data-chapter-id="${result.chapterId}" data-paragraph-index="${result.paragraphIndex}">
                        <div class="search-result-snippet">${snippet || '<em>No snippet available</em>'}</div>
                    </div>
                `;
            }).join('');

            return `
                <section class="search-group">
                    <div class="search-group-title">${chapterTitle}</div>
                    ${items}
                </section>
            `;
        }).join('');

        elements.searchResultsList.innerHTML = html;
    }

    async function performSearch(query) {
        if (!state.currentBook?.id) return;
        const normalizedQuery = (query || '').trim();
        state.searchLastQuery = normalizedQuery;
        if (!normalizedQuery || normalizedQuery.length < 2) {
            clearSearchError();
            elements.searchResults.classList.add('hidden');
            if (elements.searchResultsList) {
                elements.searchResultsList.innerHTML = '';
            }
            return;
        }

        try {
            clearSearchError();
            const params = new URLSearchParams({
                q: normalizedQuery,
                bookId: state.currentBook.id,
                limit: '20'
            });
            if (state.searchChapterFilter) {
                params.set('chapterId', state.searchChapterFilter);
            }
            const response = await fetch(`/api/search?${params.toString()}`);
            if (!response.ok) {
                const payload = await readErrorPayload(response);
                const mapped = mapSearchError({
                    status: response.status,
                    message: firstMessageFromPayload(payload)
                });
                if (elements.searchResultsList) {
                    elements.searchResultsList.innerHTML = '';
                }
                setSearchError(
                    mapped.message,
                    mapped.retryable ? () => performSearch(normalizedQuery) : null
                );
                elements.searchResults.classList.remove('hidden');
                return;
            }
            const results = await response.json();
            renderSearchResults(results, normalizedQuery);
            elements.searchResults.classList.remove('hidden');
        } catch (error) {
            console.error('Search failed:', error);
            const mapped = mapSearchError({ network: true });
            if (elements.searchResultsList) {
                elements.searchResultsList.innerHTML = '';
            }
            setSearchError(
                mapped.message,
                mapped.retryable ? () => performSearch(normalizedQuery) : null
            );
            elements.searchResults.classList.remove('hidden');
        }
    }

    function navigateToSearchResult(chapterId, paragraphIndex) {
        const query = (state.searchLastQuery || elements.searchInput.value || '').trim();
        navigateToChapterParagraph(chapterId, paragraphIndex, query);
    }

    function navigateToChapterParagraph(chapterId, paragraphIndex, highlightQuery = '') {
        const chapterIndex = state.chapters.findIndex(c => c.id === chapterId);
        if (chapterIndex === -1) return;

        const terms = extractSearchTerms(highlightQuery);
        if (terms.length > 0) {
            state.searchHighlightTerms = terms;
            state.searchHighlightChapterId = chapterId;
            state.searchHighlightParagraphIndex = paragraphIndex;
        } else {
            clearSearchHighlightState();
        }

        elements.searchResults.classList.add('hidden');
        setSearchInputValues('');

        const loadPromise = loadChapter(chapterIndex, 0, paragraphIndex, true);
        if (state.ttsEnabled) {
            ttsStopPlayback();
        }

        loadPromise.then(applied => {
            if (!applied) return;
            // Find which page contains this paragraph
            for (let i = 0; i < state.pagesData.length; i++) {
                const pageData = state.pagesData[i];
                if (paragraphIndex >= pageData.startParagraph && paragraphIndex <= pageData.endParagraph) {
                    state.currentPage = i;
                    state.currentParagraphIndex = paragraphIndex;
                    renderPage();
                    if (state.ttsEnabled) {
                        ttsSpeakCurrent();
                    }
                    break;
                }
            }
        });
    }

    // Back to library
    function backToLibrary(event) {
        if (event && typeof event.preventDefault === 'function') {
            event.preventDefault();
        }
        persistCurrentBookActivity();
        state.lastBookActivitySignature = '';
        ttsStop();
        closeMobileHeaderMenu();
        closeChapterRecapOverlay(false);
        if (state.speedReadingActive) {
            exitSpeedReading(true);
        }
        stopCharacterPolling();
        state.newCharacterQueue = [];
        state.currentToastCharacter = null;
        state.ttsVoiceSettings = null;  // Clear voice settings for next book
        state.recapChatHistory = [];
        state.recapChatChapterIndex = null;
        state.recapChatLoading = false;
        state.quizChapterId = null;
        state.quizQuestions = [];
        state.quizSelectedAnswers = [];
        state.quizSubmitting = false;
        state.quizResult = null;
        state.searchChapterFilter = '';
        state.searchLastQuery = '';
        clearSearchHighlightState();
        closeAnnotationMenu();
        closeReaderSettingsPanel();
        hideShortcutsOverlay(false);
        hideBookmarksOverlay();
        closeNoteModal(false);
        state.annotationsByKey = new Map();
        state.bookmarks = [];
        state.noteModalParagraphIndex = null;
        state.currentBookMlaCitation = '';
        state.currentBookCitationKey = null;
        state.currentBookCitationPromise = null;
        updateRecapOptOutControl();
        elements.readerView.classList.add('hidden');
        elements.libraryView.classList.remove('hidden');
        updateTouchNavigationControls();
        elements.searchResults.classList.add('hidden');
        setSearchInputValues('');
        if (elements.searchResultsList) {
            elements.searchResultsList.innerHTML = '';
        }
        if (elements.searchChapterFilter) {
            elements.searchChapterFilter.value = '';
        }
        elements.librarySearch.value = '';
        state.librarySearchQuery = '';
        clearLibrarySearchError();
        state.achievementsLoaded = false;
        state.achievementsAllItems = [];
        renderLibrary();
        void loadClassroomContext().then(() => {
            if (!state.currentBook && elements.libraryView && !elements.libraryView.classList.contains('hidden')) {
                renderLibrary();
            }
        });
        updateFavoriteUi();
        elements.librarySearch.focus();
    }

    // Chapter list
    function showChapterList() {
        closeMobileHeaderMenu();
        closeAnnotationMenu();
        ttsPauseForModal();
        chapterListSelectedIndex = state.currentChapterIndex;
        renderChapterList();
        elements.chapterListOverlay.classList.remove('hidden');
        scrollChapterIntoView(chapterListSelectedIndex);
    }

    function hideChapterList() {
        elements.chapterListOverlay.classList.add('hidden');
        ttsResumeAfterModal();
    }

    function isChapterListVisible() {
        return !elements.chapterListOverlay.classList.contains('hidden');
    }

    function renderChapterList() {
        elements.chapterList.innerHTML = state.chapters.map((chapter, index) => {
            const isCurrent = index === state.currentChapterIndex;
            const isSelected = index === chapterListSelectedIndex;
            return `
                <li class="chapter-list-item${isCurrent ? ' current' : ''}${isSelected ? ' selected' : ''}"
                    data-chapter-index="${index}">
                    <span class="chapter-number">${index + 1}</span>
                    <span class="chapter-name">${chapter.title}</span>
                </li>
            `;
        }).join('');
    }

    function scrollChapterIntoView(index) {
        const item = elements.chapterList.querySelector(`[data-chapter-index="${index}"]`);
        if (item) {
            item.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        }
    }

    function selectChapterFromList(index) {
        if (index >= 0 && index < state.chapters.length) {
            hideChapterList();
            loadChapter(index, 0);
        }
    }

    function chapterListNavigate(direction) {
        const newIndex = chapterListSelectedIndex + direction;
        if (newIndex >= 0 && newIndex < state.chapters.length) {
            chapterListSelectedIndex = newIndex;
            renderChapterList();
            scrollChapterIntoView(chapterListSelectedIndex);
        }
    }

    // Text-to-Speech functions (using backend OpenAI TTS with browser fallback)
    async function ttsCheckAvailability() {
        state.cacheOnly = false;
        // Check browser speech synthesis support
        state.ttsBrowserAvailable = 'speechSynthesis' in window;

        // Check OpenAI TTS availability
        try {
            const response = await fetch('/api/tts/status');
            const status = await response.json();
            state.ttsOpenAIConfigured = status.openaiConfigured === true;
            state.ttsCachedAvailable = status.cachedAvailable === true;
            state.ttsOpenAIAvailable = state.ttsOpenAIConfigured || state.ttsCachedAvailable;
            state.cacheOnly = status.cacheOnly === true;
        } catch (error) {
            console.warn('OpenAI TTS not available:', error);
            state.ttsOpenAIAvailable = false;
            state.ttsOpenAIConfigured = false;
            state.ttsCachedAvailable = false;
        }

        // TTS is available if either OpenAI or browser is available and the book allows it
        state.ttsAvailable = isBookFeatureEnabled('ttsEnabled')
            && isClassroomFeatureEnabled('ttsEnabled')
            && (state.ttsOpenAIAvailable || state.ttsBrowserAvailable);
        if (!state.ttsAvailable) {
            ttsStop();
        }

        if (elements.ttsToggle) {
            elements.ttsToggle.style.display = state.ttsAvailable ? '' : 'none';
        }
        if (elements.ttsHint) {
            elements.ttsHint.style.display = state.ttsAvailable ? '' : 'none';
        }
        if (elements.ttsSpeedHint) {
            elements.ttsSpeedHint.style.display = state.ttsAvailable ? '' : 'none';
        }

        console.log('TTS availability:', {
            openai: state.ttsOpenAIAvailable,
            cached: state.ttsCachedAvailable,
            browser: state.ttsBrowserAvailable,
            available: state.ttsAvailable
        });
        updateCacheOnlyIndicator();
        updateMobileHeaderMenuState();

        return {
            openaiConfigured: state.ttsOpenAIConfigured,
            cachedAvailable: state.ttsCachedAvailable,
            browserAvailable: state.ttsBrowserAvailable
        };
    }

    async function ttsAnalyzeBook() {
        if (!state.currentBook || !state.ttsAvailable) return;

        try {
            // First check if settings are already saved
            const savedResponse = await fetch(`/api/tts/settings/${state.currentBook.id}`);
            if (savedResponse.ok && savedResponse.status === 200) {
                state.ttsVoiceSettings = await savedResponse.json();
                console.log('Loaded saved voice settings:', state.ttsVoiceSettings);
                return;
            }
        } catch (error) {
            console.warn('Voice analysis failed:', error);
        }
        state.ttsVoiceSettings = { voice: 'fable', speed: 1.0, instructions: null };
    }

    function showVoiceRecommendation() {
        if (!state.ttsVoiceSettings || !state.ttsVoiceSettings.reasoning) return;

        // Show a subtle notification about the voice recommendation
        const notification = document.createElement('div');
        notification.className = 'voice-notification';
        notification.innerHTML = `
            <div class="voice-notification-content">
                <strong>AI Voice Selection:</strong> ${state.ttsVoiceSettings.voice}
                <br><small>${state.ttsVoiceSettings.reasoning}</small>
            </div>
        `;
        document.body.appendChild(notification);

        // Auto-dismiss after 5 seconds
        setTimeout(() => {
            notification.classList.add('fade-out');
            setTimeout(() => notification.remove(), 500);
        }, 5000);
    }

    function ttsToggle() {
        if (state.speedReadingActive) {
            return;
        }
        if (!state.ttsAvailable) {
            console.warn('TTS not available');
            return;
        }

        if (state.ttsEnabled) {
            ttsStop();
        } else {
            state.ttsEnabled = true;
            elements.ttsToggle.classList.add('active');
            updateSpeedIndicator();
            updateModeIndicator();
            ttsSpeakCurrent();
        }
        updateMobileHeaderMenuState();
    }

    function ttsStop() {
        state.ttsEnabled = false;
        // Abort any in-flight request
        if (state.ttsAbortController) {
            state.ttsAbortController.abort();
            state.ttsAbortController = null;
        }
        if (state.ttsAudio) {
            state.ttsAudio.pause();
            // Clean up blob URL if exists
            if (state.ttsAudio.src && state.ttsAudio.src.startsWith('blob:')) {
                URL.revokeObjectURL(state.ttsAudio.src);
            }
            state.ttsAudio = null;
        }
        // Cancel browser speech synthesis if active
        if (state.ttsBrowserAvailable) {
            speechSynthesis.cancel();
        }
        // Clear any prefetched audio
        ttsClearPrefetch();
        state.ttsUsingBrowser = false;
        if (elements.ttsToggle) {
            elements.ttsToggle.classList.remove('active');
        }
        updateSpeedIndicator();
        updateModeIndicator();
        updateMobileHeaderMenuState();
    }

    async function ttsSpeakCurrent() {
        if (!state.ttsEnabled || state.paragraphs.length === 0 || state.ttsWaitingForChapter) {
            return;
        }

        const paragraph = state.paragraphs[state.currentParagraphIndex];
        if (!paragraph) {
            ttsStop();
            return;
        }

        // Extract plain text to check if empty
        const tempDiv = document.createElement('div');
        tempDiv.innerHTML = paragraph.content;
        const text = tempDiv.textContent || tempDiv.innerText || '';

        // Skip empty paragraphs
        if (!text.trim()) {
            ttsAdvanceAndContinue();
            return;
        }

        // In public mode, skip sensitive server TTS when collaborator auth is unavailable.
        if (state.authPublicMode && !state.authCanAccessSensitive) {
            if (state.ttsBrowserAvailable) {
                ttsSpeakBrowser(text);
            } else {
                ttsStop();
            }
            return;
        }

        // If OpenAI is not available, use browser TTS directly
        if (!state.ttsOpenAIAvailable) {
            ttsSpeakBrowser(text);
            return;
        }

        // Try OpenAI TTS first (includes server-side cache check)
        state.ttsUsingBrowser = false;
        updateModeIndicator();

        // Cancel any browser TTS that might still be playing
        if (state.ttsBrowserAvailable) {
            speechSynthesis.cancel();
        }

        // Abort any previous in-flight request
        if (state.ttsAbortController) {
            state.ttsAbortController.abort();
            state.ttsAbortController = null;
        }

        // Stop previous audio if any
        if (state.ttsAudio) {
            state.ttsAudio.pause();
            // Clean up blob URL if it exists
            if (state.ttsAudio.src && state.ttsAudio.src.startsWith('blob:')) {
                URL.revokeObjectURL(state.ttsAudio.src);
            }
        }

        const chapter = state.chapters[state.currentChapterIndex];
        let audio;
        let blobUrl = null;

        // Check if we have prefetched audio for this paragraph
        if (state.ttsPrefetchedAudio &&
            state.ttsPrefetchedIndex === state.currentParagraphIndex &&
            state.ttsPrefetchedChapter === chapter.id) {
            // Use prefetched audio (already has blob URL set)
            audio = state.ttsPrefetchedAudio;
            blobUrl = audio.src;  // Track for cleanup
            state.ttsPrefetchedAudio = null;
            state.ttsPrefetchedIndex = -1;
            state.ttsPrefetchedChapter = null;
            state.ttsPrefetchAbortController = null;  // Clear prefetch controller
            console.log('Using prefetched audio for paragraph', state.currentParagraphIndex);
        } else if (state.ttsCachedAvailable && !state.ttsOpenAIConfigured) {
            const params = new URLSearchParams();
            if (state.ttsVoiceSettings) {
                if (state.ttsVoiceSettings.voice) params.set('voice', state.ttsVoiceSettings.voice);
                if (state.ttsVoiceSettings.speed) params.set('speed', state.ttsVoiceSettings.speed);
                if (state.ttsVoiceSettings.instructions) params.set('instructions', state.ttsVoiceSettings.instructions);
            }
            let url = `/api/tts/speak/${state.currentBook.id}/${chapter.id}/${state.currentParagraphIndex}`;
            if (params.toString()) url += '?' + params.toString();
            audio = new Audio(url);
        } else {
            // Fetch audio with AbortController
            const controller = new AbortController();
            state.ttsAbortController = controller;

            // Build the URL with voice settings
            let url = `/api/tts/speak/${state.currentBook.id}/${chapter.id}/${state.currentParagraphIndex}`;

            const params = new URLSearchParams();
            if (state.ttsVoiceSettings) {
                if (state.ttsVoiceSettings.voice) params.set('voice', state.ttsVoiceSettings.voice);
                if (state.ttsVoiceSettings.speed) params.set('speed', state.ttsVoiceSettings.speed);
                if (state.ttsVoiceSettings.instructions) params.set('instructions', state.ttsVoiceSettings.instructions);
            }
            if (params.toString()) url += '?' + params.toString();

            try {
                const response = await fetch(url, { signal: controller.signal });
                if (!response.ok) {
                    if (response.status === 401 || response.status === 403) {
                        state.authCanAccessSensitive = false;
                        updateAuthUi();
                        throw new Error(`TTS auth required: ${response.status}`);
                    }
                    throw new Error(`TTS request failed: ${response.status}`);
                }
                const blob = await response.blob();
                blobUrl = URL.createObjectURL(blob);
                audio = new Audio(blobUrl);
            } catch (error) {
                if (error.name === 'AbortError') {
                    console.log('TTS request aborted for paragraph', state.currentParagraphIndex);
                    return;  // Request was cancelled, don't continue
                }
                if (typeof error?.message === 'string' && error.message.startsWith('TTS auth required')) {
                    console.warn('Sensitive TTS requires collaborator sign-in in public mode; using browser speech.');
                } else {
                    console.error('OpenAI TTS fetch error, falling back to browser:', error);
                }
                if (state.ttsEnabled && state.ttsBrowserAvailable) {
                    ttsSpeakBrowser(text);
                }
                return;
            }
        }

        state.ttsAudio = audio;

        // Set playbackRate both immediately and after load to ensure it sticks
        audio.playbackRate = state.ttsPlaybackRate;
        audio.onloadeddata = () => {
            audio.playbackRate = state.ttsPlaybackRate;
        };

        audio.onended = () => {
            // Only handle if this is still the current audio (not replaced by navigation)
            if (state.ttsAudio !== audio) return;

            // Clean up blob URL
            if (blobUrl) {
                URL.revokeObjectURL(blobUrl);
            }

            if (state.ttsEnabled) {
                ttsAdvanceAndContinue();
            }
        };

        audio.onerror = (event) => {
            // Only handle error if this is still the current audio (not replaced by navigation)
            if (state.ttsAudio !== audio) return;

            // Clean up blob URL
            if (blobUrl) {
                URL.revokeObjectURL(blobUrl);
            }

            console.error('OpenAI TTS audio error, falling back to browser:', event);
            // Fall back to browser TTS
            if (state.ttsEnabled && state.ttsBrowserAvailable) {
                ttsSpeakBrowser(text);
            } else if (state.ttsEnabled) {
                setTimeout(() => ttsAdvanceAndContinue(), 500);
            }
        };

        try {
            await audio.play();
            // Also set after play starts to be extra sure
            audio.playbackRate = state.ttsPlaybackRate;
            // Start prefetching next paragraph once playback begins
            ttsPrefetchNext();
        } catch (error) {
            // Only fall back if this is still the current audio (not replaced by navigation)
            if (state.ttsAudio !== audio) return;

            // Clean up blob URL
            if (blobUrl) {
                URL.revokeObjectURL(blobUrl);
            }

            console.error('OpenAI TTS playback error, falling back to browser:', error);
            // Fall back to browser TTS
            if (state.ttsEnabled && state.ttsBrowserAvailable) {
                ttsSpeakBrowser(text);
            }
        }
    }

    function ttsAdvanceAndContinue() {
        const wasLastParagraph = state.currentParagraphIndex >= state.paragraphs.length - 1;
        const wasLastChapter = state.currentChapterIndex >= state.chapters.length - 1;

        if (wasLastParagraph && wasLastChapter) {
            // End of book
            ttsStop();
            return;
        }

        if (wasLastParagraph) {
            // Keep TTS chapter transitions uninterrupted by recap overlay.
            goToNextChapter(false);
        } else {
            nextParagraph();
            ttsSpeakCurrent();
        }
    }

    async function ttsPrefetchNext() {
        // Don't prefetch if OpenAI TTS is not available (browser TTS doesn't benefit from prefetch)
        if (!state.ttsOpenAIAvailable || !state.ttsEnabled) return;
        if (state.ttsCachedAvailable && !state.ttsOpenAIConfigured) return;
        if (state.authPublicMode && !state.authCanAccessSensitive) return;

        const nextIndex = state.currentParagraphIndex + 1;
        const chapter = state.chapters[state.currentChapterIndex];

        // Don't prefetch if at end of chapter (cross-chapter prefetch is complex)
        if (nextIndex >= state.paragraphs.length) return;

        // Don't prefetch if already have this one cached
        if (state.ttsPrefetchedIndex === nextIndex &&
            state.ttsPrefetchedChapter === chapter.id &&
            state.ttsPrefetchedAudio) {
            return;
        }

        const nextParagraph = state.paragraphs[nextIndex];
        if (!nextParagraph) return;

        // Extract plain text to check if empty
        const tempDiv = document.createElement('div');
        tempDiv.innerHTML = nextParagraph.content;
        const text = tempDiv.textContent || tempDiv.innerText || '';

        // Don't prefetch empty paragraphs
        if (!text.trim()) return;

        // Abort any existing prefetch request
        if (state.ttsPrefetchAbortController) {
            state.ttsPrefetchAbortController.abort();
        }

        // Clean up any existing prefetched audio blob URL
        if (state.ttsPrefetchedAudio && state.ttsPrefetchedAudio.src &&
            state.ttsPrefetchedAudio.src.startsWith('blob:')) {
            URL.revokeObjectURL(state.ttsPrefetchedAudio.src);
        }

        // Build the URL with voice settings
        let url = `/api/tts/speak/${state.currentBook.id}/${chapter.id}/${nextIndex}`;

        const params = new URLSearchParams();
        if (state.ttsVoiceSettings) {
            if (state.ttsVoiceSettings.voice) params.set('voice', state.ttsVoiceSettings.voice);
            if (state.ttsVoiceSettings.speed) params.set('speed', state.ttsVoiceSettings.speed);
            if (state.ttsVoiceSettings.instructions) params.set('instructions', state.ttsVoiceSettings.instructions);
        }
        if (params.toString()) url += '?' + params.toString();

        // Create AbortController for this prefetch
        const controller = new AbortController();
        state.ttsPrefetchAbortController = controller;

        console.log('Prefetching audio for paragraph', nextIndex);

        try {
            const response = await fetch(url, { signal: controller.signal });
            if (!response.ok) {
                if (response.status === 401 || response.status === 403) {
                    state.authCanAccessSensitive = false;
                    updateAuthUi();
                    return;
                }
                throw new Error(`Prefetch request failed: ${response.status}`);
            }
            const blob = await response.blob();
            const blobUrl = URL.createObjectURL(blob);

            // Only store if this prefetch wasn't cancelled
            if (state.ttsPrefetchAbortController === controller) {
                const prefetchAudio = new Audio(blobUrl);
                state.ttsPrefetchedAudio = prefetchAudio;
                state.ttsPrefetchedIndex = nextIndex;
                state.ttsPrefetchedChapter = chapter.id;
                console.log('Prefetch complete for paragraph', nextIndex);
            } else {
                // Prefetch was superseded, clean up
                URL.revokeObjectURL(blobUrl);
            }
        } catch (error) {
            if (error.name === 'AbortError') {
                console.log('Prefetch aborted for paragraph', nextIndex);
            } else {
                console.warn('Prefetch failed for paragraph', nextIndex, error);
            }
        }
    }

    function ttsClearPrefetch() {
        // Abort any in-flight prefetch request
        if (state.ttsPrefetchAbortController) {
            state.ttsPrefetchAbortController.abort();
            state.ttsPrefetchAbortController = null;
        }
        // Clean up blob URL if exists
        if (state.ttsPrefetchedAudio) {
            if (state.ttsPrefetchedAudio.src && state.ttsPrefetchedAudio.src.startsWith('blob:')) {
                URL.revokeObjectURL(state.ttsPrefetchedAudio.src);
            }
            state.ttsPrefetchedAudio = null;
        }
        state.ttsPrefetchedIndex = -1;
        state.ttsPrefetchedChapter = null;
    }

    function ttsSpeakBrowser(text) {
        // Use browser's built-in speech synthesis as fallback
        if (!state.ttsBrowserAvailable) {
            console.warn('Browser speech synthesis not available');
            ttsStop();
            return;
        }

        state.ttsUsingBrowser = true;
        updateModeIndicator();

        // Cancel any pending speech
        speechSynthesis.cancel();

        const utterance = new SpeechSynthesisUtterance(text);

        // Map playback rate (our 1.0-2.0 range works well with utterance.rate)
        utterance.rate = state.ttsPlaybackRate;

        // Try to find a reasonable voice (prefer English)
        const voices = speechSynthesis.getVoices();
        const englishVoice = voices.find(v => v.lang.startsWith('en') && v.localService) ||
                             voices.find(v => v.lang.startsWith('en')) ||
                             voices[0];
        if (englishVoice) {
            utterance.voice = englishVoice;
        }

        utterance.onend = () => {
            // Only advance if still using browser TTS (not switched to OpenAI)
            if (state.ttsEnabled && state.ttsUsingBrowser) {
                ttsAdvanceAndContinue();
            }
        };

        utterance.onerror = (event) => {
            console.error('Browser TTS error:', event);
            // Only handle if still using browser TTS and not interrupted by cancel
            if (state.ttsEnabled && state.ttsUsingBrowser && event.error !== 'interrupted') {
                setTimeout(() => ttsAdvanceAndContinue(), 500);
            }
        };

        speechSynthesis.speak(utterance);
    }

    function ttsStopPlayback() {
        if (!state.ttsEnabled) {
            return;
        }

        // Abort any in-flight request
        if (state.ttsAbortController) {
            state.ttsAbortController.abort();
            state.ttsAbortController = null;
        }
        if (state.ttsAudio) {
            state.ttsAudio.pause();
            // Clean up blob URL if exists
            if (state.ttsAudio.src && state.ttsAudio.src.startsWith('blob:')) {
                URL.revokeObjectURL(state.ttsAudio.src);
            }
            state.ttsAudio = null;
        }
        // Cancel browser speech synthesis if active
        if (state.ttsBrowserAvailable) {
            speechSynthesis.cancel();
        }
        // Clear prefetch since user navigated away
        ttsClearPrefetch();
    }

    function ttsInterrupt() {
        // Called when user navigates manually while TTS is active
        if (state.ttsEnabled) {
            ttsStopPlayback();
            ttsSpeakCurrent();
        }
    }

    function ttsCycleSpeed() {
        const speeds = [1.0, 1.25, 1.5, 1.75, 2.0];
        const currentIndex = speeds.indexOf(state.ttsPlaybackRate);
        const nextIndex = (currentIndex + 1) % speeds.length;
        state.ttsPlaybackRate = speeds[nextIndex];

        // Save preference
        localStorage.setItem(STORAGE_KEYS.TTS_SPEED, state.ttsPlaybackRate);
        console.log('Saved TTS speed:', state.ttsPlaybackRate);

        // Update current audio if playing (OpenAI)
        if (state.ttsAudio) {
            state.ttsAudio.playbackRate = state.ttsPlaybackRate;
        }

        // For browser TTS, restart with new speed (can't change rate mid-utterance)
        if (state.ttsUsingBrowser && state.ttsEnabled) {
            ttsInterrupt();
        }

        updateSpeedIndicator();
        showSpeedNotification();
        updateMobileHeaderMenuState();
    }

    function updateSpeedIndicator() {
        if (elements.ttsSpeed) {
            if (state.ttsEnabled && state.ttsPlaybackRate !== 1.0) {
                elements.ttsSpeed.textContent = state.ttsPlaybackRate + 'x';
            } else {
                elements.ttsSpeed.textContent = '';
            }
        }
    }

    function updateModeIndicator() {
        if (elements.ttsMode) {
            if (state.ttsEnabled) {
                if (state.ttsUsingBrowser) {
                    elements.ttsMode.textContent = 'Browser';
                    elements.ttsMode.className = 'tts-mode browser';
                } else {
                    elements.ttsMode.textContent = 'AI';
                    elements.ttsMode.className = 'tts-mode openai';
                }
            } else {
                elements.ttsMode.textContent = '';
                elements.ttsMode.className = 'tts-mode';
            }
        }
    }

    function showSpeedNotification() {
        // Remove existing notification
        const existing = document.querySelector('.speed-notification');
        if (existing) existing.remove();

        const notification = document.createElement('div');
        notification.className = 'speed-notification';
        notification.textContent = state.ttsPlaybackRate + 'x';
        document.body.appendChild(notification);

        // Auto-dismiss after 1 second
        setTimeout(() => {
            notification.classList.add('fade-out');
            setTimeout(() => notification.remove(), 300);
        }, 1000);
    }

    // Pause TTS when opening modals/overlays (saves state for resume)
    function ttsPauseForModal() {
        if (state.ttsEnabled) {
            state.ttsWasPlayingBeforeModal = true;
            // Pause without fully stopping (don't clear ttsEnabled)
            if (state.ttsAudio) {
                state.ttsAudio.pause();
            }
            if (state.ttsBrowserAvailable) {
                speechSynthesis.cancel();
            }
        } else {
            state.ttsWasPlayingBeforeModal = false;
        }
    }

    // Resume TTS after closing modals/overlays (if it was playing before)
    function ttsResumeAfterModal() {
        if (state.ttsWasPlayingBeforeModal && state.ttsEnabled) {
            state.ttsWasPlayingBeforeModal = false;
            ttsSpeakCurrent();
        }
    }

    // ========================================
    // Speed Reading Mode Functions
    // ========================================

    function speedReadingToggle() {
        if (!state.speedReadingEnabled) {
            return;
        }
        if (state.speedReadingActive) {
            exitSpeedReading();
        } else {
            enterSpeedReading();
        }
    }

    function enterSpeedReading() {
        if (!state.speedReadingEnabled) {
            return;
        }
        if (!state.currentBook || state.paragraphs.length === 0) return;

        if (state.ttsEnabled) {
            ttsStop();
        }

        if (state.illustrationMode) {
            state.speedReadingRestoreIllustration = true;
            illustrationToggle();
        } else {
            state.speedReadingRestoreIllustration = false;
        }

        state.speedReadingActive = true;
        state.speedReadingPlaying = true;
        elements.readerView.classList.add('speed-reading-active');
        elements.speedReadingOverlay.classList.remove('hidden');
        elements.speedReadingChapterOverlay.classList.add('hidden');
        elements.speedReadingToggle.classList.add('active');
        if (elements.ttsToggle) {
            elements.ttsToggle.disabled = true;
        }
        if (elements.illustrationToggle) {
            elements.illustrationToggle.disabled = true;
        }

        updateTouchNavigationControls();
        updateSpeedReadingControls();
        prepareSpeedReadingTokens(state.currentParagraphIndex);
        state.speedReadingTokenIndex = 0;
        speedReadingStep();
    }

    function exitSpeedReading(skipRender = false) {
        clearSpeedReadingTimer();
        state.speedReadingActive = false;
        state.speedReadingPlaying = false;
        updateSpeedReadingControls();
        elements.readerView.classList.remove('speed-reading-active');
        elements.speedReadingOverlay.classList.add('hidden');
        elements.speedReadingChapterOverlay.classList.add('hidden');
        elements.speedReadingToggle.classList.remove('active');
        if (elements.ttsToggle) {
            elements.ttsToggle.disabled = false;
        }
        if (elements.illustrationToggle) {
            elements.illustrationToggle.disabled = false;
        }
        updateTouchNavigationControls();

        let restoredIllustration = false;
        if (state.speedReadingRestoreIllustration && !state.illustrationMode && state.illustrationAvailable) {
            illustrationToggle();
            restoredIllustration = true;
        }
        state.speedReadingRestoreIllustration = false;

        if (!skipRender && !restoredIllustration) {
            syncPageToParagraph();
        }
    }

    function speedReadingPause() {
        state.speedReadingPlaying = false;
        clearSpeedReadingTimer();
        updateSpeedReadingControls();
    }

    function speedReadingStart() {
        if (state.speedReadingPlaying) return;
        state.speedReadingPlaying = true;
        updateSpeedReadingControls();
        speedReadingStep();
    }

    function speedReadingStep() {
        if (!state.speedReadingActive || !state.speedReadingPlaying) return;

        const token = state.speedReadingTokens[state.speedReadingTokenIndex];
        if (!token) {
            handleSpeedReadingParagraphEnd();
            return;
        }

        renderSpeedReadingToken(token.text);
        state.speedReadingTokenIndex += 1;

        const delay = getSpeedReadingDelay(token);
        state.speedReadingTimer = setTimeout(speedReadingStep, delay);
    }

    function clearSpeedReadingTimer() {
        if (state.speedReadingTimer) {
            clearTimeout(state.speedReadingTimer);
            state.speedReadingTimer = null;
        }
    }

    function getSpeedReadingDelay(token) {
        const baseDelay = Math.max(40, Math.round(60000 / state.speedReadingWpm));
        return token.hasPunctuation ? baseDelay * 2 : baseDelay;
    }

    function handleSpeedReadingParagraphEnd() {
        clearSpeedReadingTimer();

        if (state.currentParagraphIndex < state.paragraphs.length - 1) {
            const nextIndex = findNextReadableParagraph(state.currentParagraphIndex + 1);
            if (nextIndex === -1) {
                showSpeedReadingChapterPause();
                return;
            }
            state.currentParagraphIndex = nextIndex;
            localStorage.setItem(STORAGE_KEYS.LAST_PARAGRAPH, state.currentParagraphIndex);
            scheduleCharacterDiscoveryCheck();
            prepareSpeedReadingTokens(state.currentParagraphIndex);
            state.speedReadingTokenIndex = 0;
            speedReadingStep();
        } else {
            showSpeedReadingChapterPause();
        }
    }

    function findNextReadableParagraph(startIndex) {
        for (let i = startIndex; i < state.paragraphs.length; i++) {
            const tokens = tokenizeParagraph(state.paragraphs[i]);
            if (tokens.length > 0) {
                state.speedReadingTokens = tokens;
                return i;
            }
        }
        return -1;
    }

    function prepareSpeedReadingTokens(paragraphIndex) {
        const paragraph = state.paragraphs[paragraphIndex];
        state.speedReadingTokens = tokenizeParagraph(paragraph);
        if (state.speedReadingTokens.length === 0) {
            const nextIndex = findNextReadableParagraph(paragraphIndex + 1);
            if (nextIndex !== -1) {
                state.currentParagraphIndex = nextIndex;
                localStorage.setItem(STORAGE_KEYS.LAST_PARAGRAPH, state.currentParagraphIndex);
                scheduleCharacterDiscoveryCheck();
            }
        }
    }

    function tokenizeParagraph(paragraph) {
        if (!paragraph) return [];
        const tempDiv = document.createElement('div');
        tempDiv.innerHTML = paragraph.content || '';
        const text = (tempDiv.textContent || tempDiv.innerText || '').trim();
        if (!text) return [];
        return text.split(/\s+/).map(token => ({
            text: token,
            hasPunctuation: /[.,;:!?]+$/.test(token)
        }));
    }

    function renderSpeedReadingToken(tokenText) {
        if (!elements.speedReadingWord) return;
        elements.speedReadingWord.innerHTML = '';

        const pivotIndex = findPivotIndex(tokenText);
        if (pivotIndex === -1) {
            elements.speedReadingWord.textContent = tokenText;
            return;
        }

        const left = document.createElement('span');
        left.textContent = tokenText.slice(0, pivotIndex);

        const pivot = document.createElement('span');
        pivot.className = 'pivot';
        pivot.textContent = tokenText[pivotIndex];

        const right = document.createElement('span');
        right.textContent = tokenText.slice(pivotIndex + 1);

        elements.speedReadingWord.append(left, pivot, right);
    }

    function findPivotIndex(tokenText) {
        const letterIndices = [];
        for (let i = 0; i < tokenText.length; i++) {
            if (/[A-Za-z]/.test(tokenText[i])) {
                letterIndices.push(i);
            }
        }
        if (letterIndices.length === 0) {
            return -1;
        }
        const pivotOffset = Math.floor((letterIndices.length - 1) * 0.4);
        return letterIndices[pivotOffset];
    }

    function updateSpeedReadingControls() {
        if (elements.speedReadingSlider) {
            elements.speedReadingSlider.value = state.speedReadingWpm;
        }
        if (elements.speedReadingWpm) {
            elements.speedReadingWpm.textContent = `${state.speedReadingWpm} wpm`;
        }
        if (elements.speedReadingPlay) {
            elements.speedReadingPlay.textContent = state.speedReadingPlaying ? 'Pause' : 'Play';
        }
    }

    function showSpeedReadingChapterPause() {
        speedReadingPause();

        const nextChapterIndex = state.currentChapterIndex + 1;
        if (nextChapterIndex < state.chapters.length) {
            elements.speedReadingChapterTitle.textContent = `Next Chapter: ${state.chapters[nextChapterIndex].title}`;
            elements.speedReadingContinue.disabled = false;
        } else {
            elements.speedReadingChapterTitle.textContent = 'End of book';
            elements.speedReadingContinue.disabled = true;
        }

        elements.speedReadingChapterOverlay.classList.remove('hidden');
    }

    async function continueSpeedReading() {
        const nextChapterIndex = state.currentChapterIndex + 1;
        if (nextChapterIndex >= state.chapters.length) {
            exitSpeedReading();
            return;
        }

        elements.speedReadingChapterOverlay.classList.add('hidden');
        const loaded = await loadChapter(nextChapterIndex, 0, 0);
        if (!loaded) {
            return;
        }
        prepareSpeedReadingTokens(state.currentParagraphIndex);
        state.speedReadingTokenIndex = 0;
        speedReadingStart();
    }

    function syncPageToParagraph() {
        if (state.pagesData.length === 0) return;
        const index = state.currentParagraphIndex;
        for (let i = 0; i < state.pagesData.length; i++) {
            const page = state.pagesData[i];
            if (index >= page.startParagraph && index <= page.endParagraph) {
                state.currentPage = i;
                break;
            }
        }
        renderPage();
    }

    // ========================================
    // Illustration Mode Functions
    // ========================================

    async function illustrationCheckAvailability() {
        try {
            const response = await fetch('/api/illustrations/status');
            const status = await response.json();
            state.illustrationAvailable = isBookFeatureEnabled('illustrationEnabled')
                && isClassroomFeatureEnabled('illustrationEnabled');
            state.allowPromptEditing = state.illustrationAvailable
                && !status.cacheOnly
                && (status.allowPromptEditing || false);
            state.illustrationCacheOnly = status.cacheOnly === true;
            state.cacheOnly = state.cacheOnly || status.cacheOnly === true;
        } catch (error) {
            console.warn('Illustration service not available:', error);
            state.illustrationAvailable = false;
            state.allowPromptEditing = false;
            state.illustrationCacheOnly = false;
        }

        if (elements.illustrationToggle) {
            elements.illustrationToggle.style.display = state.illustrationAvailable ? '' : 'none';
        }
        if (elements.illustrationHint) {
            elements.illustrationHint.style.display = state.illustrationAvailable ? '' : 'none';
        }
        if (elements.illustrationImage && !state.allowPromptEditing) {
            elements.illustrationImage.classList.remove('editable');
        }

        if (!state.illustrationAvailable && state.illustrationMode) {
            state.illustrationMode = false;
            if (elements.illustrationToggle) {
                elements.illustrationToggle.classList.remove('active');
            }
            updateColumnLayout();
        }

        console.log('Illustration availability:', state.illustrationAvailable, 'Prompt editing:', state.allowPromptEditing);
        updateCacheOnlyIndicator();
        updateMobileHeaderMenuState();
    }

    async function illustrationAnalyzeBook() {
        if (!state.currentBook || !state.illustrationAvailable) return;

        try {
            // First check if settings are already saved
            const savedResponse = await fetch(`/api/illustrations/settings/${state.currentBook.id}`);
            if (savedResponse.ok && savedResponse.status === 200) {
                state.illustrationSettings = await savedResponse.json();
                console.log('Loaded saved illustration settings:', state.illustrationSettings);
            }
        } catch (error) {
            console.warn('Illustration style analysis failed:', error);
        }
    }

    function showStyleNotification() {
        if (!state.illustrationSettings || !state.illustrationSettings.reasoning) return;

        const notification = document.createElement('div');
        notification.className = 'style-notification';
        notification.innerHTML = `
            <div class="style-notification-content">
                <strong>AI Illustration Style:</strong> ${state.illustrationSettings.style}
                <br><small>${state.illustrationSettings.reasoning}</small>
            </div>
        `;
        document.body.appendChild(notification);

        // Auto-dismiss after 5 seconds
        setTimeout(() => {
            notification.classList.add('fade-out');
            setTimeout(() => notification.remove(), 500);
        }, 5000);
    }

    function illustrationToggle() {
        if (state.speedReadingActive) {
            return;
        }
        if (!state.illustrationAvailable) {
            console.warn('Illustration mode not available');
            return;
        }

        state.illustrationMode = !state.illustrationMode;
        localStorage.setItem(STORAGE_KEYS.ILLUSTRATION_MODE, state.illustrationMode);

        if (elements.illustrationToggle) {
            elements.illustrationToggle.classList.toggle('active', state.illustrationMode);
        }

        updateColumnLayout();

        if (state.illustrationMode) {
            loadChapterIllustration();
        } else {
            // Stop any polling
            if (state.illustrationPolling) {
                clearInterval(state.illustrationPolling);
                state.illustrationPolling = null;
            }
        }

        // Recalculate pages for the new layout and keep paragraph position.
        calculatePages();
        syncPageToParagraph();
    }

    function updateColumnLayout() {
        const contentArea = document.querySelector('.reader-content');
        if (!contentArea || !elements.columnRight || !elements.illustrationColumn) {
            return;
        }
        const showSecondReadingColumn = !state.illustrationMode && !state.isMobileLayout;

        if (state.illustrationMode) {
            contentArea.classList.add('illustration-mode');
            elements.columnRight.classList.add('hidden');
            elements.illustrationColumn.classList.remove('hidden');
        } else {
            contentArea.classList.remove('illustration-mode');
            elements.columnRight.classList.toggle('hidden', !showSecondReadingColumn);
            elements.illustrationColumn.classList.add('hidden');
            // Hide all illustration states
            elements.illustrationSkeleton.classList.add('hidden');
            elements.illustrationImage.classList.add('hidden');
            clearIllustrationError();
        }
    }

    async function loadChapterIllustration() {
        if (!state.illustrationMode || !state.currentBook) return;

        const chapter = state.chapters[state.currentChapterIndex];
        if (!chapter) return;

        if (state.currentIllustrationChapterId === chapter.id
            && elements.illustrationImage?.src
            && !elements.illustrationImage.classList.contains('hidden')) {
            return;
        }

        // Show skeleton placeholder
        showIllustrationSkeleton();

        try {
            // Check status
            const statusResponse = await fetch(`/api/illustrations/chapter/${chapter.id}/status`);
            if (!statusResponse.ok) {
                const payload = await readErrorPayload(statusResponse);
                const mapped = mapGenerationError({
                    status: statusResponse.status,
                    message: firstMessageFromPayload(payload)
                });
                showIllustrationError(
                    mapped.message,
                    mapped.retryable ? () => loadChapterIllustration() : null
                );
                return;
            }
            const status = await statusResponse.json();

            if (status.ready) {
                // Load the image
                displayIllustration(chapter.id);
            } else if (status.status === 'FAILED' || status.status === 'DISABLED' || status.status === 'NOT_FOUND') {
                const mapped = mapGenerationError({ generationState: status.status });
                showIllustrationError(
                    mapped.message,
                    mapped.retryable ? () => loadChapterIllustration() : null
                );
            } else if (state.illustrationCacheOnly) {
                const mapped = mapGenerationError({ status: 409 });
                showIllustrationError(mapped.message, null);
            } else {
                // Always call request first - backend handles duplicates gracefully
                // and will re-queue stuck PENDING illustrations older than 5 minutes
                const requestResponse = await nativeFetch(`/api/illustrations/chapter/${chapter.id}/request`, { method: 'POST' });
                if (!requestResponse.ok) {
                    const payload = await readErrorPayload(requestResponse);
                    const mapped = mapGenerationError({
                        status: requestResponse.status,
                        message: firstMessageFromPayload(payload)
                    });
                    showIllustrationError(
                        mapped.message,
                        mapped.retryable ? () => loadChapterIllustration() : null
                    );
                    return;
                }
                // Still generating, poll for completion
                pollForIllustration(chapter.id);
            }

            // Pre-fetch next chapter
            if (!state.illustrationCacheOnly) {
                nativeFetch(`/api/illustrations/chapter/${chapter.id}/prefetch-next`, { method: 'POST' });
            }

        } catch (error) {
            console.error('Failed to load illustration:', error);
            const mapped = mapGenerationError({ network: true });
            showIllustrationError(
                mapped.message,
                mapped.retryable ? () => loadChapterIllustration() : null
            );
        }
    }

    function pollForIllustration(chapterId) {
        // Clear any existing polling
        if (state.illustrationPolling) {
            clearInterval(state.illustrationPolling);
        }

        let attempts = 0;
        const maxAttempts = 90; // 3 minutes at 2-second intervals

        state.illustrationPolling = setInterval(async () => {
            attempts++;

            // Check if we're still on the same chapter and illustration mode is on
            const currentChapter = state.chapters[state.currentChapterIndex];
            if (!currentChapter || currentChapter.id !== chapterId || !state.illustrationMode) {
                clearInterval(state.illustrationPolling);
                state.illustrationPolling = null;
                return;
            }

            if (attempts >= maxAttempts) {
                clearInterval(state.illustrationPolling);
                state.illustrationPolling = null;
                const mapped = mapGenerationError({ timeout: true });
                showIllustrationError(
                    mapped.message,
                    mapped.retryable ? () => loadChapterIllustration() : null
                );
                return;
            }

            try {
                const response = await fetch(`/api/illustrations/chapter/${chapterId}/status`);
                const status = await response.json();

                if (status.ready) {
                    clearInterval(state.illustrationPolling);
                    state.illustrationPolling = null;
                    displayIllustration(chapterId);
                } else if (status.status === 'FAILED' || status.status === 'DISABLED' || status.status === 'NOT_FOUND') {
                    clearInterval(state.illustrationPolling);
                    state.illustrationPolling = null;
                    const mapped = mapGenerationError({ generationState: status.status });
                    showIllustrationError(
                        mapped.message,
                        mapped.retryable ? () => loadChapterIllustration() : null
                    );
                }
                // Otherwise keep polling
            } catch (error) {
                console.error('Polling error:', error);
                // Continue polling on network errors
            }
        }, 2000);
    }

    function displayIllustration(chapterId, options = {}) {
        hideIllustrationSkeleton();
        clearIllustrationError();
        const imageUrl = options.forceRefresh
            ? `/api/illustrations/chapter/${chapterId}?t=${Date.now()}`
            : `/api/illustrations/chapter/${chapterId}`;
        if (elements.illustrationImage.src !== new URL(imageUrl, window.location.href).href) {
            elements.illustrationImage.src = imageUrl;
        }
        state.currentIllustrationChapterId = chapterId;
        elements.illustrationImage.classList.remove('hidden');

        // Make image clickable if prompt editing is enabled
        if (state.allowPromptEditing) {
            elements.illustrationImage.classList.add('editable');
        } else {
            elements.illustrationImage.classList.remove('editable');
        }
    }

    function showIllustrationSkeleton() {
        elements.illustrationSkeleton.classList.remove('hidden');
        elements.illustrationImage.classList.add('hidden');
        clearIllustrationError();
    }

    function hideIllustrationSkeleton() {
        elements.illustrationSkeleton.classList.add('hidden');
    }

    function showIllustrationError(message = 'Illustration unavailable', onRetry = null) {
        hideIllustrationSkeleton();
        elements.illustrationImage.classList.add('hidden');
        state.currentIllustrationChapterId = null;
        setIllustrationError(message, onRetry);
    }

    // ========================================
    // Prompt Editing Modal Functions
    // ========================================

    async function openPromptModal() {
        if (!state.allowPromptEditing || state.illustrationCacheOnly || !state.illustrationMode) return;

        const chapter = state.chapters[state.currentChapterIndex];
        if (!chapter) return;

        // Pause TTS while modal is open
        ttsPauseForModal();

        // Store chapter ID
        state.promptModalChapterId = chapter.id;

        // Show modal in edit mode
        clearPromptError();
        showPromptEditMode();
        elements.promptModal.classList.remove('hidden');
        elements.promptTextarea.value = 'Loading prompt...';
        elements.promptTextarea.disabled = true;
        elements.promptRegenerate.disabled = true;

        try {
            const response = await fetch(`/api/illustrations/chapter/${chapter.id}/prompt`);
            if (response.ok) {
                const data = await response.json();
                elements.promptTextarea.value = data.prompt || '';
                state.promptModalLastPrompt = data.prompt || '';
                elements.promptTextarea.disabled = false;
                elements.promptRegenerate.disabled = false;
            } else if (response.status === 404) {
                elements.promptTextarea.value = 'No prompt available for this illustration.';
            } else {
                elements.promptTextarea.value = 'Failed to load prompt.';
            }
        } catch (error) {
            console.error('Failed to load prompt:', error);
            elements.promptTextarea.value = 'Failed to load prompt.';
        }
    }

    function closePromptModal() {
        // Stop any polling
        if (state.promptModalPolling) {
            clearInterval(state.promptModalPolling);
            state.promptModalPolling = null;
        }

        clearPromptError();
        elements.promptModal.classList.add('hidden');
        elements.promptTextarea.value = '';
        state.promptModalChapterId = null;
        state.promptModalLastPrompt = '';
        ttsResumeAfterModal();
    }

    function isPromptModalVisible() {
        return !elements.promptModal.classList.contains('hidden');
    }

    function showPromptEditMode() {
        elements.promptModalTitle.textContent = 'Edit Illustration Prompt';
        elements.promptEditMode.classList.remove('hidden');
        elements.promptGeneratingMode.classList.add('hidden');
        elements.promptPreviewMode.classList.add('hidden');
        elements.promptEditButtons.classList.remove('hidden');
        elements.promptPreviewButtons.classList.add('hidden');
    }

    function showPromptGeneratingMode() {
        clearPromptError();
        elements.promptModalTitle.textContent = 'Generating Illustration';
        elements.promptEditMode.classList.add('hidden');
        elements.promptGeneratingMode.classList.remove('hidden');
        elements.promptPreviewMode.classList.add('hidden');
        elements.promptEditButtons.classList.add('hidden');
        elements.promptPreviewButtons.classList.add('hidden');
    }

    function showPromptPreviewMode(imageUrl) {
        elements.promptModalTitle.textContent = 'Preview Illustration';
        elements.promptPreviewImage.src = imageUrl;
        elements.promptEditMode.classList.add('hidden');
        elements.promptGeneratingMode.classList.add('hidden');
        elements.promptPreviewMode.classList.remove('hidden');
        elements.promptEditButtons.classList.add('hidden');
        elements.promptPreviewButtons.classList.remove('hidden');
    }

    async function regenerateIllustration() {
        const chapterId = state.promptModalChapterId;
        if (!chapterId) return;

        const newPrompt = elements.promptTextarea.value.trim();
        clearPromptError();
        if (!newPrompt) {
            setPromptError('Enter a prompt before regenerating.', null);
            return;
        }

        // Save the prompt for "try again"
        state.promptModalLastPrompt = newPrompt;

        // Disable button while submitting
        elements.promptRegenerate.disabled = true;

        try {
            const response = await fetch(`/api/illustrations/chapter/${chapterId}/regenerate`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ prompt: newPrompt })
            });

            if (response.ok) {
                // Switch to generating mode and poll for completion
                showPromptGeneratingMode();
                pollForRegeneratedIllustration(chapterId);
            } else {
                const payload = await readErrorPayload(response);
                const mapped = mapGenerationError({
                    status: response.status,
                    message: firstMessageFromPayload(payload)
                });
                restorePromptEditModeForRetry();
                setPromptError(
                    mapped.message,
                    mapped.retryable ? () => regenerateIllustration() : null
                );
            }
        } catch (error) {
            console.error('Failed to regenerate:', error);
            const mapped = mapGenerationError({ network: true });
            restorePromptEditModeForRetry();
            setPromptError(
                mapped.message,
                mapped.retryable ? () => regenerateIllustration() : null
            );
        }
    }

    function restorePromptEditModeForRetry() {
        showPromptEditMode();
        elements.promptTextarea.value = state.promptModalLastPrompt;
        elements.promptTextarea.disabled = false;
        elements.promptRegenerate.disabled = false;
    }

    function pollForRegeneratedIllustration(chapterId) {
        // Clear any existing polling
        if (state.promptModalPolling) {
            clearInterval(state.promptModalPolling);
        }

        let attempts = 0;
        const maxAttempts = 90; // 3 minutes at 2-second intervals

        state.promptModalPolling = setInterval(async () => {
            attempts++;

            // Check if modal was closed
            if (!isPromptModalVisible() || state.promptModalChapterId !== chapterId) {
                clearInterval(state.promptModalPolling);
                state.promptModalPolling = null;
                return;
            }

            if (attempts >= maxAttempts) {
                clearInterval(state.promptModalPolling);
                state.promptModalPolling = null;
                const mapped = mapGenerationError({ timeout: true });
                restorePromptEditModeForRetry();
                setPromptError(
                    mapped.message,
                    mapped.retryable ? () => regenerateIllustration() : null
                );
                return;
            }

            try {
                const response = await fetch(`/api/illustrations/chapter/${chapterId}/status`);
                const status = await response.json();

                if (status.ready) {
                    clearInterval(state.promptModalPolling);
                    state.promptModalPolling = null;
                    // Show preview with cache-busting timestamp
                    const imageUrl = `/api/illustrations/chapter/${chapterId}?t=${Date.now()}`;
                    showPromptPreviewMode(imageUrl);
                } else if (status.status === 'FAILED') {
                    clearInterval(state.promptModalPolling);
                    state.promptModalPolling = null;
                    const mapped = mapGenerationError({ generationState: status.status });
                    restorePromptEditModeForRetry();
                    setPromptError(
                        mapped.message,
                        mapped.retryable ? () => regenerateIllustration() : null
                    );
                }
                // Otherwise keep polling (PENDING or GENERATING)
            } catch (error) {
                console.error('Polling error:', error);
                // Continue polling on network errors
            }
        }, 2000);
    }

    function acceptRegeneration() {
        const chapterId = state.promptModalChapterId;
        if (!chapterId) return;

        // Update the main illustration display
        displayIllustration(chapterId, { forceRefresh: true });

        // Close the modal
        closePromptModal();
    }

    function tryAgainRegeneration() {
        // Switch back to edit mode with the last prompt
        clearPromptError();
        showPromptEditMode();
        elements.promptTextarea.value = state.promptModalLastPrompt;
        elements.promptTextarea.disabled = false;
        elements.promptRegenerate.disabled = false;
    }

    // ========================================
    // End Illustration Mode Functions
    // ========================================

    // ========================================
    // Character Feature Functions
    // ========================================

    function getDiscoveredCharactersKey(bookId) {
        return STORAGE_KEYS.DISCOVERED_CHARACTERS_PREFIX + bookId;
    }

    function getDiscoveredCharacterDetailsKey(bookId) {
        return STORAGE_KEYS.DISCOVERED_CHARACTER_DETAILS_PREFIX + bookId;
    }

    function loadDiscoveredCharacters(bookId) {
        if (!bookId) return new Set();
        const stored = localStorage.getItem(getDiscoveredCharactersKey(bookId));
        if (!stored) return new Set();
        try {
            const parsed = JSON.parse(stored);
            if (Array.isArray(parsed)) {
                return new Set(parsed);
            }
        } catch (error) {
            console.debug('Failed to parse discovered characters', error);
        }
        return new Set();
    }

    function loadDiscoveredCharacterDetails(bookId) {
        if (!bookId) return new Map();
        const stored = localStorage.getItem(getDiscoveredCharacterDetailsKey(bookId));
        if (!stored) return new Map();
        try {
            const parsed = JSON.parse(stored);
            if (Array.isArray(parsed)) {
                const map = new Map();
                parsed.forEach(character => {
                    if (character && character.id) {
                        map.set(character.id, character);
                    }
                });
                return map;
            }
        } catch (error) {
            console.debug('Failed to parse discovered character details', error);
        }
        return new Map();
    }

    function saveDiscoveredCharacters() {
        if (!state.currentBook) return;
        const key = getDiscoveredCharactersKey(state.currentBook.id);
        localStorage.setItem(key, JSON.stringify(Array.from(state.discoveredCharacterIds)));
    }

    function saveDiscoveredCharacterDetails() {
        if (!state.currentBook) return;
        const key = getDiscoveredCharacterDetailsKey(state.currentBook.id);
        localStorage.setItem(key, JSON.stringify(Array.from(state.discoveredCharacterDetails.values())));
    }

    function recordDiscoveredCharacter(character) {
        if (!character || !character.id) return;
        state.discoveredCharacterIds.add(character.id);
        state.discoveredCharacterDetails.set(character.id, character);
    }

    function isCharacterWithinCurrentPosition(character) {
        if (!character) return false;
        if (character.firstChapterIndex < state.currentChapterIndex) {
            return true;
        }
        if (character.firstChapterIndex > state.currentChapterIndex) {
            return false;
        }
        return character.firstParagraphIndex <= state.currentParagraphIndex;
    }

    function sortCharacters(characters) {
        return characters.slice().sort((a, b) => {
            if (a.characterType !== b.characterType) {
                return a.characterType.localeCompare(b.characterType);
            }
            if (a.firstChapterIndex !== b.firstChapterIndex) {
                return a.firstChapterIndex - b.firstChapterIndex;
            }
            return a.firstParagraphIndex - b.firstParagraphIndex;
        });
    }

    function mergeCharacterLists(primaryList, secondaryList) {
        const byId = new Map();
        primaryList.forEach(character => byId.set(character.id, character));
        secondaryList.forEach(character => byId.set(character.id, character));
        return Array.from(byId.values());
    }

    function scheduleCharacterDiscoveryCheck() {
        if (!state.characterAvailable || !state.currentBook) return;
        if (state.characterDiscoveryTimeout) {
            clearTimeout(state.characterDiscoveryTimeout);
        }
        state.characterDiscoveryTimeout = setTimeout(() => {
            state.characterDiscoveryTimeout = null;
            checkForDiscoveredCharacters();
        }, 300);
    }

    async function checkForDiscoveredCharacters() {
        if (!state.currentBook || !state.characterAvailable) return;

        const chapterIndex = state.currentChapterIndex;
        const paragraphIndex = state.currentParagraphIndex;

        try {
            const response = await fetch(
                `/api/characters/book/${state.currentBook.id}/up-to?chapterIndex=${chapterIndex}&paragraphIndex=${paragraphIndex}`
            );
            const characters = await response.json();
            const newDiscoveries = characters.filter(c =>
                c.portraitReady && !state.discoveredCharacterIds.has(c.id)
            );

            if (newDiscoveries.length > 0) {
                newDiscoveries.forEach(recordDiscoveredCharacter);
                state.newCharacterQueue.push(...newDiscoveries);
                saveDiscoveredCharacters();
                saveDiscoveredCharacterDetails();
                if (!state.currentToastCharacter) {
                    showNextCharacterToast();
                }
            }
        } catch (error) {
            console.debug('Failed to check discovered characters:', error);
        }
    }

    async function characterCheckAvailability() {
        try {
            const response = await fetch('/api/characters/status');
            const status = await response.json();
            state.characterAvailable = status.enabled
                && isBookFeatureEnabled('characterEnabled')
                && isClassroomFeatureEnabled('characterEnabled');
            state.characterCacheOnly = status.cacheOnly === true;
            // Chat is available if enabled in config and the provider is reachable
            state.characterChatAvailable = state.characterAvailable
                && status.chatEnabled === true
                && isClassroomFeatureEnabled('chatEnabled')
                && status.chatProviderAvailable === true;
            state.cacheOnly = state.cacheOnly || status.cacheOnly === true;

            console.log('Character status response:', status);
            console.log('Character toggle element:', elements.characterToggle);

            if (elements.characterToggle) {
                elements.characterToggle.style.display = state.characterAvailable ? '' : 'none';
                console.log('Character toggle display set to:', elements.characterToggle.style.display);
            }
            if (elements.characterHint) {
                elements.characterHint.style.display = state.characterAvailable ? '' : 'none';
            }
            console.log('Character feature available:', state.characterAvailable);
        } catch (error) {
            console.error('Failed to check character availability:', error);
            state.characterAvailable = false;
            state.characterCacheOnly = false;
            state.characterChatAvailable = false;
        }

        if (!state.characterAvailable) {
            stopCharacterPolling();
            state.newCharacterQueue = [];
            state.currentToastCharacter = null;
        }
        if (!state.characterAvailable) {
            state.characterChatAvailable = false;
        }
        updateCacheOnlyIndicator();
        updateMobileHeaderMenuState();
    }

    async function recapCheckAvailability() {
        try {
            const statusUrl = state.currentBook?.id
                ? `/api/recaps/book/${state.currentBook.id}/status`
                : '/api/recaps/status';
            const response = await fetch(statusUrl, { cache: 'no-store' });
            const status = await response.json();
            state.recapGenerationAvailable = status.enabled === true
                && status.reasoningEnabled === true
                && isClassroomFeatureEnabled('recapEnabled')
                && status.cacheOnly !== true;
            state.recapAvailable = status.available === true
                && isClassroomFeatureEnabled('recapEnabled');
            state.recapCacheOnly = status.cacheOnly === true;
            state.recapChatEnabled = status.chatEnabled === true
                && isClassroomFeatureEnabled('chatEnabled');
            state.recapChatAvailable = state.recapAvailable &&
                state.recapChatEnabled &&
                status.chatProviderAvailable === true;
            state.cacheOnly = state.cacheOnly || status.cacheOnly === true;
        } catch (error) {
            console.debug('Failed to check recap availability:', error);
            state.recapGenerationAvailable = false;
            state.recapAvailable = false;
            state.recapCacheOnly = false;
            state.recapChatEnabled = false;
            state.recapChatAvailable = false;
        }
        updateRecapOptOutControl();
        updateCacheOnlyIndicator();
        setRecapChatControls();
        updateMobileHeaderMenuState();
    }

    async function quizCheckAvailability() {
        const wasQuizAvailable = state.quizAvailable;
        try {
            const statusUrl = state.currentBook?.id
                ? `/api/quizzes/book/${state.currentBook.id}/status`
                : '/api/quizzes/status';
            const response = await fetch(statusUrl, { cache: 'no-store' });
            const status = await response.json();
            state.quizCacheOnly = status.cacheOnly === true;
            state.quizAvailable = status.available === true
                && isClassroomFeatureEnabled('quizEnabled');
            state.quizGenerationAvailable = (status.generationAvailable === true
                || (status.enabled === true && status.reasoningEnabled === true && status.cacheOnly !== true))
                && isClassroomFeatureEnabled('quizEnabled');
            state.cacheOnly = state.cacheOnly || status.cacheOnly === true;
        } catch (error) {
            console.debug('Failed to check quiz availability:', error);
            state.quizAvailable = false;
            state.quizGenerationAvailable = false;
            state.quizCacheOnly = false;
        }
        if (state.quizAvailable && !wasQuizAvailable) {
            // Force a fresh trophy read when quiz support becomes available after startup checks.
            state.achievementsLoaded = false;
            state.achievementsLoading = false;
            state.achievementsSummary = '';
            state.achievementsItems = [];
            state.achievementsAllItems = [];
        }
        if (!state.quizAvailable) {
            state.achievementsLoaded = true;
            state.achievementsLoading = false;
            state.achievementsSummary = '';
            state.achievementsItems = [];
            state.achievementsAllItems = [];
        }
        updateRecapOptOutControl();
        updateCacheOnlyIndicator();
        setQuizControls();
        updateMobileHeaderMenuState();
        if (!state.currentBook && elements.libraryView && !elements.libraryView.classList.contains('hidden')) {
            renderLibrary();
        }
    }

    function trackRecapAnalytics(eventType) {
        if (!state.currentBook?.id || !eventType) return;
        const currentChapter = state.chapters?.[state.currentChapterIndex];
        fetch('/api/recaps/analytics', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                bookId: state.currentBook.id,
                chapterId: currentChapter?.id || null,
                event: eventType
            })
        }).catch(error => {
            console.debug('Failed to track recap analytics event:', error);
        });
    }

    function getRecapOptOut(bookId) {
        if (!bookId) return false;
        return localStorage.getItem(`${STORAGE_KEYS.RECAP_OPTOUT_PREFIX}${bookId}`) === 'true';
    }

    function setRecapOptOut(bookId, enabled) {
        if (!bookId) return;
        localStorage.setItem(`${STORAGE_KEYS.RECAP_OPTOUT_PREFIX}${bookId}`, enabled ? 'true' : 'false');
        state.recapOptOut = enabled;
        updateRecapOptOutControl();
    }

    function updateRecapOptOutControl() {
        if (!elements.recapEnableBtn) return;
        const showControl = !!state.currentBook && (state.recapAvailable || state.quizAvailable) && state.recapOptOut;
        elements.recapEnableBtn.classList.toggle('hidden', !showControl);
        updateMobileHeaderMenuState();
    }

    function isChapterRecapVisible() {
        return elements.chapterRecapOverlay && !elements.chapterRecapOverlay.classList.contains('hidden');
    }

    function isCharacterBrowserVisible() {
        return elements.characterBrowserModal && !elements.characterBrowserModal.classList.contains('hidden');
    }

    function isCharacterChatVisible() {
        return elements.characterChatModal && !elements.characterChatModal.classList.contains('hidden');
    }

    async function loadChapterCharacters() {
        console.log('loadChapterCharacters called, available:', state.characterAvailable, 'book:', state.currentBook?.id);
        if (!state.characterAvailable || !state.currentBook) return;
        if (state.characterCacheOnly) return;

        const chapter = state.chapters[state.currentChapterIndex];
        if (!chapter) return;

        console.log('Requesting character analysis for chapter:', chapter.id);
        try {
            // Request character analysis for current chapter
            await nativeFetch(`/api/characters/chapter/${chapter.id}/analyze`, { method: 'POST' });

            // Prefetch next chapter analysis
            nativeFetch(`/api/characters/chapter/${chapter.id}/prefetch-next`, { method: 'POST' })
                .catch(error => {
                    console.debug('Next chapter character prefetch request failed:', error);
                });

            // Start polling for new characters
            startCharacterPolling();
        } catch (error) {
            console.error('Failed to request character analysis:', error);
        }
    }

    async function requestChapterRecapGeneration(chapterId) {
        if (!chapterId || !state.recapGenerationAvailable || state.cacheOnly || state.recapCacheOnly) return;
        try {
            await nativeFetch(`/api/recaps/chapter/${chapterId}/generate`, { method: 'POST' });
        } catch (error) {
            console.debug('Failed to request chapter recap generation:', error);
        }
    }

    async function requestChapterQuizGeneration(chapterId) {
        if (!chapterId || !state.quizGenerationAvailable || state.cacheOnly || state.quizCacheOnly) return;
        try {
            await nativeFetch(`/api/quizzes/chapter/${chapterId}/generate`, { method: 'POST' });
        } catch (error) {
            console.debug('Failed to request chapter quiz generation:', error);
        }
    }

    function startCharacterPolling() {
        // Clear existing polling
        if (state.characterPollingInterval) {
            clearInterval(state.characterPollingInterval);
        }

        state.characterLastCheck = Date.now();

        // Poll every 3 seconds for new characters
        state.characterPollingInterval = setInterval(async () => {
            if (!state.currentBook) {
                clearInterval(state.characterPollingInterval);
                return;
            }

            try {
                const response = await fetch(
                    `/api/characters/book/${state.currentBook.id}/new-since?sinceTimestamp=${state.characterLastCheck}`
                );
                const newCharacters = await response.json();

                if (newCharacters.length > 0) {
                    state.characterLastCheck = Date.now();
                }

                const filtered = newCharacters.filter(c =>
                    c.portraitReady &&
                    isCharacterWithinCurrentPosition(c) &&
                    !state.discoveredCharacterIds.has(c.id)
                );

                if (filtered.length > 0) {
                    // Add to queue
                    filtered.forEach(recordDiscoveredCharacter);
                    state.newCharacterQueue.push(...filtered);
                    saveDiscoveredCharacters();
                    saveDiscoveredCharacterDetails();
                    // Show next toast if not already showing one
                    if (!state.currentToastCharacter) {
                        showNextCharacterToast();
                    }
                }
            } catch (error) {
                console.debug('Character poll failed:', error);
            }
        }, 3000);
    }

    function stopCharacterPolling() {
        if (state.characterPollingInterval) {
            clearInterval(state.characterPollingInterval);
            state.characterPollingInterval = null;
        }
    }

    function showNextCharacterToast() {
        if (state.newCharacterQueue.length === 0) {
            state.currentToastCharacter = null;
            return;
        }

        const character = state.newCharacterQueue.shift();
        state.currentToastCharacter = character;

        elements.characterToastName.textContent = character.name;
        elements.characterToastDesc.textContent = character.description || '';
        elements.characterToastImage.src = `/api/characters/${character.id}/portrait?t=${Date.now()}`;
        elements.characterToast.classList.remove('hidden', 'fade-out');

        // Auto-dismiss after 8 seconds
        setTimeout(() => {
            dismissCharacterToast();
        }, 8000);
    }

    function dismissCharacterToast() {
        elements.characterToast.classList.add('fade-out');
        setTimeout(() => {
            elements.characterToast.classList.add('hidden');
            elements.characterToast.classList.remove('fade-out');
            state.currentToastCharacter = null;
            // Show next if queued
            showNextCharacterToast();
        }, 400);
    }

    function characterBrowserToggle() {
        console.log('characterBrowserToggle called, available:', state.characterAvailable);
        if (!state.characterAvailable) {
            console.log('Character feature not available, returning');
            return;
        }

        if (isCharacterBrowserVisible()) {
            closeCharacterBrowser();
        } else {
            openCharacterBrowser();
        }
    }

    async function openCharacterBrowser() {
        if (!state.currentBook) return;

        ttsPauseForModal();
        state.characterBrowserOpen = true;

        // Load characters up to current reading position
        const chapterIndex = state.currentChapterIndex;
        const paragraphIndex = state.currentParagraphIndex;

        try {
            const response = await fetch(
                `/api/characters/book/${state.currentBook.id}/up-to?chapterIndex=${chapterIndex}&paragraphIndex=${paragraphIndex}`
            );
            const upToCharacters = await response.json();
            let mergedCharacters = upToCharacters;

            if (state.discoveredCharacterIds.size > 0) {
                const cachedDiscovered = Array.from(state.discoveredCharacterDetails.values());
                const cachedIds = new Set(cachedDiscovered.map(c => c.id));
                const missingIds = Array.from(state.discoveredCharacterIds).filter(id => !cachedIds.has(id));
                let discoveredCharacters = cachedDiscovered;

                if (missingIds.length > 0) {
                    const allResponse = await fetch(`/api/characters/book/${state.currentBook.id}`);
                    const allCharacters = await allResponse.json();
                    const missingCharacters = allCharacters.filter(c => missingIds.includes(c.id));
                    missingCharacters.forEach(recordDiscoveredCharacter);
                    saveDiscoveredCharacterDetails();
                    discoveredCharacters = mergeCharacterLists(cachedDiscovered, missingCharacters);
                }

                mergedCharacters = mergeCharacterLists(upToCharacters, discoveredCharacters);
            }

            state.characters = sortCharacters(mergedCharacters);
        } catch (error) {
            console.error('Failed to load characters:', error);
            state.characters = [];
        }

        showCharacterListView();
        elements.characterBrowserModal.classList.remove('hidden');
    }

    function openCharacterBrowserToCharacter(characterId) {
        if (!characterId) return;
        dismissCharacterToast();
        openCharacterBrowser().then(() => {
            showCharacterDetail(characterId);
        });
    }

    function closeCharacterBrowser(skipAudioResume = false) {
        elements.characterBrowserModal.classList.add('hidden');
        state.characterBrowserOpen = false;
        state.selectedCharacterId = null;
        if (!skipAudioResume) {
            ttsResumeAfterModal();
        }
    }

    function showCharacterListView() {
        elements.characterDetailView.classList.add('hidden');
        elements.characterListView.classList.remove('hidden');
        renderCharacterList();
    }

    function renderCharacterCard(char, isLarge) {
        const sizeClass = isLarge ? 'character-card-large' : 'character-card-small';
        const iconSize = isLarge ? 32 : 24;
        return `
            <div class="character-card ${sizeClass}" data-character-id="${char.id}">
                <div class="character-card-portrait ${char.portraitReady ? '' : 'pending'}">
                    ${char.portraitReady
                        ? `<img src="/api/characters/${char.id}/portrait" alt="${char.name}" />`
                        : `<svg width="${iconSize}" height="${iconSize}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>
                            <circle cx="12" cy="7" r="4"/>
                          </svg>`
                    }
                </div>
                <div class="character-card-name">${char.name}</div>
            </div>
        `;
    }

    function renderCharacterList() {
        if (state.characters.length === 0) {
            elements.characterListEmpty.classList.remove('hidden');
            elements.characterList.innerHTML = '';
            return;
        }

        elements.characterListEmpty.classList.add('hidden');

        // Separate characters by type - primary first, then secondary
        const primaryCharacters = state.characters.filter(c => c.characterType === 'PRIMARY');
        const secondaryCharacters = state.characters.filter(c => c.characterType === 'SECONDARY');

        // Single flowing layout: primary (large) cards first, then secondary (small) cards
        const html = `<div class="character-flow">
            ${primaryCharacters.map(char => renderCharacterCard(char, true)).join('')}
            ${secondaryCharacters.map(char => renderCharacterCard(char, false)).join('')}
        </div>`;

        elements.characterList.innerHTML = html;

        // Add click handlers
        elements.characterList.querySelectorAll('.character-card').forEach(card => {
            card.addEventListener('click', () => {
                showCharacterDetail(card.dataset.characterId);
            });
        });
    }

    function showCharacterDetail(characterId) {
        const character = state.characters.find(c => c.id === characterId);
        if (!character) return;

        state.selectedCharacterId = characterId;

        elements.characterDetailName.textContent = character.name;
        elements.characterDetailDesc.textContent = character.description || '';
        elements.characterDetailChapter.textContent = character.firstChapterTitle || `Chapter ${character.firstChapterIndex + 1}`;

        if (character.portraitReady) {
            elements.characterDetailPortrait.src = `/api/characters/${characterId}/portrait?t=${Date.now()}`;
        } else {
            elements.characterDetailPortrait.src = '';
        }

        // Show/hide chat button based on character type (only PRIMARY can chat)
        if (elements.characterChatBtn) {
            if (character.characterType === 'PRIMARY' && state.characterChatAvailable) {
                elements.characterChatBtn.classList.remove('hidden');
            } else {
                elements.characterChatBtn.classList.add('hidden');
            }
        }

        elements.characterListView.classList.add('hidden');
        elements.characterDetailView.classList.remove('hidden');
    }

    function navigateToCharacterAppearance() {
        const character = state.characters.find(c => c.id === state.selectedCharacterId);
        if (!character) return;

        closeCharacterBrowser();

        // Find chapter by id
        const chapterIndex = state.chapters.findIndex(c => c.id === character.firstChapterId);
        if (chapterIndex >= 0) {
            loadChapter(chapterIndex, character.firstParagraphIndex);
        }
    }

    async function openCharacterChat(characterId) {
        if (!characterId) return;
        if (!state.characterChatAvailable) return;

        const character = state.characters.find(c => c.id === characterId);
        if (!character) return;

        state.chatCharacterId = characterId;
        state.chatCharacter = character;
        clearCharacterChatError();

        // Load chat history from localStorage
        state.chatHistory = loadChatHistory(characterId);

        // Update UI
        elements.chatCharacterName.textContent = character.name;
        if (character.portraitReady) {
            elements.chatCharacterPortrait.src = `/api/characters/${characterId}/portrait`;
        }

        // Render existing messages
        renderChatMessages();

        // Close browser, open chat (skip audio resume since chat modal keeps it paused)
        closeCharacterBrowser(true);
        elements.characterChatModal.classList.remove('hidden');
        state.characterChatOpen = true;

        // Focus input
        elements.chatInput.focus();
    }

    function closeCharacterChat() {
        elements.characterChatModal.classList.add('hidden');
        state.characterChatOpen = false;
        state.chatCharacterId = null;
        state.chatCharacter = null;
        state.chatHistory = [];
        clearCharacterChatError();
        elements.chatMessages.innerHTML = '';
        elements.chatInput.value = '';
        ttsResumeAfterModal();
    }

    function loadChatHistory(characterId) {
        const key = STORAGE_KEYS.CHARACTER_CHAT_PREFIX + state.currentBook.id + '_' + characterId;
        const stored = localStorage.getItem(key);
        return stored ? JSON.parse(stored) : [];
    }

    function saveChatHistory(characterId, history) {
        const key = STORAGE_KEYS.CHARACTER_CHAT_PREFIX + state.currentBook.id + '_' + characterId;
        // Limit history to last 50 messages
        const limited = history.slice(-50);
        localStorage.setItem(key, JSON.stringify(limited));
    }

    function renderChatMessages() {
        elements.chatMessages.innerHTML = state.chatHistory.map(msg => `
            <div class="chat-message ${msg.role}">
                ${escapeHtml(msg.content)}
            </div>
        `).join('');

        // Scroll to bottom
        elements.chatMessages.scrollTop = elements.chatMessages.scrollHeight;
    }

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    async function sendChatMessage(options = {}) {
        const retryMessage = typeof options.retryMessage === 'string' ? options.retryMessage : '';
        const appendUserMessage = options.appendUser !== false;
        const message = (retryMessage || elements.chatInput.value || '').trim();
        if (!message || state.chatLoading || !state.chatCharacterId) return;
        clearCharacterChatError();

        if (appendUserMessage) {
            // Add user message to history
            const userMsg = { role: 'user', content: message, timestamp: Date.now() };
            state.chatHistory.push(userMsg);
            renderChatMessages();
        }

        if (appendUserMessage) {
            // Clear input
            elements.chatInput.value = '';
        }

        // Show loading
        state.chatLoading = true;
        elements.chatSendBtn.disabled = true;

        // Add loading message
        const loadingDiv = document.createElement('div');
        loadingDiv.className = 'chat-message character loading';
        loadingDiv.textContent = 'Thinking';
        elements.chatMessages.appendChild(loadingDiv);
        elements.chatMessages.scrollTop = elements.chatMessages.scrollHeight;

        try {
            const response = await fetch(`/api/characters/${state.chatCharacterId}/chat`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    message: message,
                    conversationHistory: state.chatHistory.slice(-10),
                    readerChapterIndex: state.currentChapterIndex,
                    readerParagraphIndex: state.currentParagraphIndex
                })
            });

            if (!response.ok) {
                const payload = await readErrorPayload(response);
                const mapped = mapChatError({
                    status: response.status,
                    message: firstMessageFromPayload(payload)
                });
                loadingDiv.remove();
                setCharacterChatError(
                    mapped.message,
                    mapped.retryable
                        ? () => sendChatMessage({ retryMessage: message, appendUser: false })
                        : null
                );
                return;
            }

            const data = await response.json().catch(() => ({}));

            // Remove loading message
            loadingDiv.remove();

            // Add character response
            const reply = (data && typeof data.response === 'string') ? data.response.trim() : '';
            const charMsg = {
                role: 'character',
                content: reply || "I don't have enough context to answer that yet.",
                timestamp: Date.now()
            };
            state.chatHistory.push(charMsg);
            renderChatMessages();

            // Save history
            saveChatHistory(state.chatCharacterId, state.chatHistory);

        } catch (error) {
            console.error('Chat failed:', error);
            loadingDiv.remove();
            const mapped = mapChatError({ network: true });
            setCharacterChatError(
                mapped.message,
                mapped.retryable
                    ? () => sendChatMessage({ retryMessage: message, appendUser: false })
                    : null
            );
        } finally {
            state.chatLoading = false;
            elements.chatSendBtn.disabled = false;
            elements.chatInput.focus();
        }
    }

    // ========================================
    // End Character Feature Functions
    // ========================================

    // Import a book from Gutenberg and open it
    async function importAndOpenBook(gutenbergId) {
        if (state.isImporting) return;

        state.isImporting = true;
        showImportingOverlay();

        try {
            const response = await fetch(`/api/import/gutenberg/${gutenbergId}`, {
                method: 'POST'
            });
            const result = await response.json().catch(() => ({}));

            if (result.success || result.message === 'Book already imported') {
                // Fetch the full book from library
                const bookResponse = await fetch(`/api/library/${result.bookId}`);
                if (!bookResponse.ok) {
                    throw new Error(`Book lookup failed with status ${bookResponse.status}`);
                }
                const book = await bookResponse.json();

                // Update local books list
                if (!state.localBooks.find(b => b.id === book.id)) {
                    state.localBooks.push(book);
                }

                // Mark as imported in catalog
                const catalogBook = state.catalogBooks.find(b => b.gutenbergId === gutenbergId);
                if (catalogBook) {
                    catalogBook.alreadyImported = true;
                }
                const popularBook = state.popularCatalogBooks.find(b => b.gutenbergId === gutenbergId);
                if (popularBook) {
                    popularBook.alreadyImported = true;
                }

                hideImportingOverlay();
                await selectBookWithResume(book);
            } else {
                hideImportingOverlay();
                const mapped = mapImportError({
                    status: response.status,
                    message: firstMessageFromPayload(result)
                });
                showAppToast({
                    title: 'Import Failed',
                    message: mapped.message,
                    actionLabel: mapped.retryable ? 'Retry Import' : null,
                    onAction: mapped.retryable ? () => importAndOpenBook(gutenbergId) : null
                });
            }
        } catch (error) {
            console.error('Import failed:', error);
            hideImportingOverlay();
            const mapped = mapImportError({ network: true });
            showAppToast({
                title: 'Import Failed',
                message: mapped.message,
                actionLabel: mapped.retryable ? 'Retry Import' : null,
                onAction: mapped.retryable ? () => importAndOpenBook(gutenbergId) : null
            });
        } finally {
            state.isImporting = false;
        }
    }

    // Show/hide importing overlay
    function showImportingOverlay() {
        let overlay = document.getElementById('importing-overlay');
        if (!overlay) {
            overlay = document.createElement('div');
            overlay.id = 'importing-overlay';
            overlay.innerHTML = '<div class="importing-message">Importing book...</div>';
            document.body.appendChild(overlay);
        }
        overlay.classList.add('visible');
    }

    function hideImportingOverlay() {
        const overlay = document.getElementById('importing-overlay');
        if (overlay) {
            overlay.classList.remove('visible');
        }
    }

    // Event listeners
    function setupEventListeners() {
        // Library search - explicit submit only (Enter or Search button)
        if (elements.librarySearch) {
            elements.librarySearch.addEventListener('keydown', (e) => {
                if (e.key !== 'Enter') {
                    return;
                }
                e.preventDefault();
                submitLibrarySearch();
            });
            elements.librarySearch.addEventListener('input', () => {
                clearLibrarySearchError();
            });
        }
        if (elements.librarySearchSubmit) {
            elements.librarySearchSubmit.addEventListener('click', () => {
                submitLibrarySearch();
            });
        }
        if (elements.librarySearchRetry) {
            elements.librarySearchRetry.addEventListener('click', () => {
                if (typeof state.librarySearchRetryHandler === 'function') {
                    state.librarySearchRetryHandler();
                }
            });
        }

        // Personalized/local book selection
        elements.libraryView.addEventListener('click', async (e) => {
            const achievementItem = e.target.closest('[data-achievement-book-id]');
            if (achievementItem && elements.libraryView.contains(achievementItem)) {
                const achievementBookId = achievementItem.dataset.achievementBookId;
                const achievementBook = state.localBooks.find(b => b.id === achievementBookId);
                if (achievementBook) {
                    await selectBookWithResume(achievementBook);
                }
                return;
            }

            const favoriteToggle = e.target.closest('[data-favorite-toggle="true"]');
            if (favoriteToggle && elements.libraryView.contains(favoriteToggle)) {
                e.preventDefault();
                e.stopPropagation();
                const favoriteBookId = favoriteToggle.dataset.bookId;
                toggleBookFavorite(favoriteBookId, { rerenderLibrary: true, showToast: true });
                return;
            }

            const bookItem = e.target.closest('.book-item[data-book-id]');
            if (!bookItem || !elements.libraryView.contains(bookItem)) {
                return;
            }
            const bookId = bookItem.dataset.bookId;
            const book = state.localBooks.find(b => b.id === bookId);
            if (book) {
                await selectBookWithResume(book);
            }
        });
        if (elements.achievementsViewAll) {
            elements.achievementsViewAll.addEventListener('click', () => {
                openAchievementsModal();
            });
        }
        if (elements.achievementsModalClose) {
            elements.achievementsModalClose.addEventListener('click', closeAchievementsModal);
        }
        if (elements.achievementsModalBackdrop) {
            elements.achievementsModalBackdrop.addEventListener('click', closeAchievementsModal);
        }
        if (elements.achievementsModalList) {
            elements.achievementsModalList.addEventListener('click', async (e) => {
                const item = e.target.closest('[data-achievement-book-id]');
                if (!item) return;
                const achievementBookId = item.dataset.achievementBookId;
                const achievementBook = state.localBooks.find(b => b.id === achievementBookId);
                if (achievementBook) {
                    closeAchievementsModal();
                    await selectBookWithResume(achievementBook);
                }
            });
        }

        // Catalog book selection (may need import)
        elements.bookList.addEventListener('click', async (e) => {
            const bookItem = e.target.closest('.book-item');
            if (bookItem && bookItem.dataset.gutenbergId) {
                const gutenbergId = parseInt(bookItem.dataset.gutenbergId);
                // Resolve through the import endpoint even for imported catalog entries.
                // Title/author matching is ambiguous when sample books or alternate metadata exist.
                await importAndOpenBook(gutenbergId);
            }
        });

        // Back to library
        elements.backToLibrary.addEventListener('click', backToLibrary);
        if (elements.favoriteToggle) {
            elements.favoriteToggle.addEventListener('click', () => {
                if (!state.currentBook?.id) return;
                toggleBookFavorite(state.currentBook.id, { rerenderLibrary: true, showToast: true });
            });
        }
        if (elements.annotationMenuToggle) {
            elements.annotationMenuToggle.addEventListener('click', (e) => {
                e.stopPropagation();
                toggleAnnotationMenu();
            });
        }

        if (elements.highlightToggle) {
            elements.highlightToggle.addEventListener('click', () => {
                closeAnnotationMenu();
                toggleHighlightForCurrentParagraph();
            });
        }
        if (elements.noteToggle) {
            elements.noteToggle.addEventListener('click', () => {
                closeAnnotationMenu();
                openNoteModal();
            });
        }
        if (elements.bookmarkToggle) {
            elements.bookmarkToggle.addEventListener('click', () => {
                closeAnnotationMenu();
                toggleBookmarkForCurrentParagraph();
            });
        }
        if (elements.bookmarksToggle) {
            elements.bookmarksToggle.addEventListener('click', () => {
                closeAnnotationMenu();
                showBookmarksOverlay();
            });
        }
        if (elements.readerSettingsToggle) {
            elements.readerSettingsToggle.addEventListener('click', (e) => {
                e.stopPropagation();
                toggleReaderSettingsPanel();
            });
        }
        if (elements.readerSettingsPanel) {
            elements.readerSettingsPanel.addEventListener('click', (e) => {
                e.stopPropagation();
            });
        }
        if (elements.readerFontSize) {
            elements.readerFontSize.addEventListener('input', (e) => {
                const value = parseFloat(e.target.value);
                if (!Number.isNaN(value)) {
                    setReaderPreferences({ fontSize: value });
                }
            });
        }
        if (elements.readerLineHeight) {
            elements.readerLineHeight.addEventListener('input', (e) => {
                const value = parseFloat(e.target.value);
                if (!Number.isNaN(value)) {
                    setReaderPreferences({ lineHeight: value });
                }
            });
        }
        if (elements.readerColumnGap) {
            elements.readerColumnGap.addEventListener('input', (e) => {
                const value = parseFloat(e.target.value);
                if (!Number.isNaN(value)) {
                    setReaderPreferences({ columnGap: value });
                }
            });
        }
        if (elements.readerTheme) {
            elements.readerTheme.addEventListener('change', (e) => {
                setReaderPreferences({ theme: e.target.value });
            });
        }
        if (elements.readerSettingsReset) {
            elements.readerSettingsReset.addEventListener('click', () => {
                const defaults = detectMobileLayout()
                    ? MOBILE_DEFAULT_READER_PREFERENCES
                    : DEFAULT_READER_PREFERENCES;
                setReaderPreferences({ ...defaults });
            });
        }
        if (elements.mobileHeaderMenuToggle) {
            elements.mobileHeaderMenuToggle.addEventListener('click', (e) => {
                e.stopPropagation();
                toggleMobileHeaderMenu();
            });
        }
        if (elements.mobileHeaderMenuPanel) {
            elements.mobileHeaderMenuPanel.addEventListener('click', (e) => {
                e.stopPropagation();
            });
        }
        if (elements.mobileMenuTtsToggle) {
            elements.mobileMenuTtsToggle.addEventListener('click', () => {
                closeMobileHeaderMenu();
                ttsToggle();
            });
        }
        if (elements.mobileMenuTtsSpeed) {
            elements.mobileMenuTtsSpeed.addEventListener('click', () => {
                ttsCycleSpeed();
            });
        }
        if (elements.mobileMenuSpeedReadingToggle) {
            elements.mobileMenuSpeedReadingToggle.addEventListener('click', () => {
                closeMobileHeaderMenu();
                speedReadingToggle();
            });
        }
        if (elements.mobileMenuIllustrationToggle) {
            elements.mobileMenuIllustrationToggle.addEventListener('click', () => {
                closeMobileHeaderMenu();
                illustrationToggle();
            });
        }
        if (elements.mobileMenuCharacterToggle) {
            elements.mobileMenuCharacterToggle.addEventListener('click', () => {
                closeMobileHeaderMenu();
                characterBrowserToggle();
            });
        }
        if (elements.mobileMenuReaderSettings) {
            elements.mobileMenuReaderSettings.addEventListener('click', (e) => {
                e.stopPropagation();
                closeMobileHeaderMenu();
                syncReaderPreferencesControls();
                openReaderSettingsPanel();
            });
        }
        if (elements.mobileMenuHighlight) {
            elements.mobileMenuHighlight.addEventListener('click', () => {
                closeMobileHeaderMenu();
                toggleHighlightForCurrentParagraph();
            });
        }
        if (elements.mobileMenuNote) {
            elements.mobileMenuNote.addEventListener('click', () => {
                closeMobileHeaderMenu();
                openNoteModal();
            });
        }
        if (elements.mobileMenuBookmark) {
            elements.mobileMenuBookmark.addEventListener('click', () => {
                closeMobileHeaderMenu();
                toggleBookmarkForCurrentParagraph();
            });
        }
        if (elements.mobileMenuBookmarks) {
            elements.mobileMenuBookmarks.addEventListener('click', () => {
                closeMobileHeaderMenu();
                showBookmarksOverlay();
            });
        }
        if (elements.mobileMenuCitation) {
            elements.mobileMenuCitation.addEventListener('click', () => {
                closeMobileHeaderMenu();
                void copyCurrentBookMlaCitation();
            });
        }
        if (elements.mobileMenuFavorite) {
            elements.mobileMenuFavorite.addEventListener('click', () => {
                closeMobileHeaderMenu();
                if (!state.currentBook?.id) return;
                toggleBookFavorite(state.currentBook.id, { rerenderLibrary: true, showToast: true });
            });
        }
        if (elements.mobileMenuRecapEnable) {
            elements.mobileMenuRecapEnable.addEventListener('click', () => {
                closeMobileHeaderMenu();
                if (state.currentBook?.id) {
                    setRecapOptOut(state.currentBook.id, false);
                    if (elements.chapterRecapOptout) {
                        elements.chapterRecapOptout.checked = false;
                    }
                }
            });
        }
        if (elements.mobileMenuAccount) {
            elements.mobileMenuAccount.addEventListener('click', () => {
                closeMobileHeaderMenu();
                openAccountModal();
            });
        }
        if (elements.mobileMenuAuth) {
            elements.mobileMenuAuth.addEventListener('click', () => {
                closeMobileHeaderMenu();
                openAuthModal();
            });
        }
        document.addEventListener('click', (e) => {
            if (!isAnnotationMenuVisible()) return;
            const menuHost = e.target.closest('.annotation-menu');
            if (!menuHost) {
                closeAnnotationMenu();
            }
        });
        document.addEventListener('click', (e) => {
            if (!isReaderSettingsPanelVisible()) return;
            const settingsHost = e.target.closest('.reader-settings-menu');
            if (!settingsHost) {
                closeReaderSettingsPanel();
            }
        });
        document.addEventListener('click', (e) => {
            if (!isMobileHeaderMenuVisible()) return;
            const menuHost = e.target.closest('.mobile-header-menu');
            if (!menuHost) {
                closeMobileHeaderMenu();
            }
        });
        if (elements.annotationMenuPanel) {
            elements.annotationMenuPanel.addEventListener('click', (e) => {
                e.stopPropagation();
            });
        }
        if (elements.shortcutsToggle) {
            elements.shortcutsToggle.addEventListener('click', () => {
                showShortcutsOverlay();
            });
        }
        if (elements.shortcutsOverlay) {
            elements.shortcutsOverlay.addEventListener('click', (e) => {
                if (e.target === elements.shortcutsOverlay) {
                    hideShortcutsOverlay();
                }
            });
        }
        if (elements.bookmarkList) {
            elements.bookmarkList.addEventListener('click', (e) => {
                const item = e.target.closest('.bookmark-list-item');
                if (!item) return;
                const index = parseInt(item.dataset.bookmarkIndex, 10);
                if (!Number.isInteger(index)) return;
                selectBookmarkFromList(index);
            });
        }
        if (elements.bookmarksOverlay) {
            elements.bookmarksOverlay.addEventListener('click', (e) => {
                if (e.target === elements.bookmarksOverlay) {
                    hideBookmarksOverlay();
                }
            });
        }
        if (elements.noteModalClose) {
            elements.noteModalClose.addEventListener('click', () => closeNoteModal());
        }
        if (elements.noteModalBackdrop) {
            elements.noteModalBackdrop.addEventListener('click', () => closeNoteModal());
        }
        if (elements.noteCancel) {
            elements.noteCancel.addEventListener('click', () => closeNoteModal());
        }
        if (elements.noteSave) {
            elements.noteSave.addEventListener('click', saveNoteFromModal);
        }
        if (elements.noteDelete) {
            elements.noteDelete.addEventListener('click', deleteNoteFromModal);
        }
        if (elements.noteTextarea) {
            elements.noteTextarea.addEventListener('keydown', (e) => {
                if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
                    e.preventDefault();
                    saveNoteFromModal();
                }
            });
        }

        if (elements.authToggle) {
            elements.authToggle.addEventListener('click', () => {
                if (state.authAuthenticated) {
                    openAuthModal('You are signed in. You can sign out here.');
                } else {
                    openAuthModal();
                }
            });
        }
        if (elements.accountToggle) {
            elements.accountToggle.addEventListener('click', () => {
                openAccountModal();
            });
        }
        if (elements.accountToggleLibrary) {
            elements.accountToggleLibrary.addEventListener('click', () => {
                openAccountModal();
            });
        }
        if (elements.accountModalClose) {
            elements.accountModalClose.addEventListener('click', closeAccountModal);
        }
        if (elements.accountModalBackdrop) {
            elements.accountModalBackdrop.addEventListener('click', closeAccountModal);
        }
        if (elements.accountSignIn) {
            elements.accountSignIn.addEventListener('click', submitAccountLogin);
        }
        if (elements.accountGoogleSignIn) {
            elements.accountGoogleSignIn.addEventListener('click', startGoogleAccountAuth);
        }
        if (elements.accountRegister) {
            elements.accountRegister.addEventListener('click', submitAccountRegister);
        }
        if (elements.accountSignOut) {
            elements.accountSignOut.addEventListener('click', submitAccountLogout);
        }
        if (elements.accountPassword) {
            elements.accountPassword.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' && !state.accountAuthenticated) {
                    e.preventDefault();
                    submitAccountLogin();
                }
            });
        }
        if (elements.authModalClose) {
            elements.authModalClose.addEventListener('click', closeAuthModal);
        }
        if (elements.authModalBackdrop) {
            elements.authModalBackdrop.addEventListener('click', closeAuthModal);
        }
        if (elements.authSignIn) {
            elements.authSignIn.addEventListener('click', submitAuthLogin);
        }
        if (elements.authSignOut) {
            elements.authSignOut.addEventListener('click', submitAuthLogout);
        }
        if (elements.authPassword) {
            elements.authPassword.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    submitAuthLogin();
                }
            });
        }

        // Paragraph click navigation
        if (elements.readerContent) {
            elements.readerContent.addEventListener('click', (e) => {
                const paragraph = e.target.closest('.paragraph');
                if (!paragraph || state.speedReadingActive) return;
                if (elements.readerView.classList.contains('hidden')) return;

                const index = parseInt(paragraph.dataset.index, 10);
                if (Number.isNaN(index) || index === state.currentParagraphIndex) return;

                state.currentParagraphIndex = index;
                renderPage();
                ttsInterrupt();
            });
        }

        // Page gutters
        if (elements.gutterLeft) {
            elements.gutterLeft.addEventListener('click', () => {
                if (!elements.readerView.classList.contains('hidden') && !state.speedReadingActive) {
                    prevPage();
                    ttsInterrupt();
                }
            });
        }
        if (elements.gutterRight) {
            elements.gutterRight.addEventListener('click', () => {
                if (!elements.readerView.classList.contains('hidden') && !state.speedReadingActive) {
                    nextPage();
                    ttsInterrupt();
                }
            });
        }

        if (elements.mobileChapterList) {
            elements.mobileChapterList.addEventListener('click', () => {
                if (elements.readerView.classList.contains('hidden')) return;
                showChapterList();
            });
        }
        if (elements.mobilePrevPage) {
            elements.mobilePrevPage.addEventListener('click', () => {
                if (elements.readerView.classList.contains('hidden') || state.speedReadingActive) return;
                prevPage();
                ttsInterrupt();
            });
        }
        if (elements.mobileNextPage) {
            elements.mobileNextPage.addEventListener('click', () => {
                if (elements.readerView.classList.contains('hidden') || state.speedReadingActive) return;
                nextPage();
                ttsInterrupt();
            });
        }

        // TTS toggle
        if (elements.ttsToggle) {
            elements.ttsToggle.addEventListener('click', ttsToggle);
        }

        // Speed reading toggle
        if (state.speedReadingEnabled) {
            if (elements.speedReadingToggle) {
                elements.speedReadingToggle.addEventListener('click', speedReadingToggle);
            }
            if (elements.speedReadingPlay) {
                elements.speedReadingPlay.addEventListener('click', () => {
                    if (state.speedReadingPlaying) {
                        speedReadingPause();
                    } else {
                        speedReadingStart();
                    }
                });
            }
            if (elements.speedReadingExitInline) {
                elements.speedReadingExitInline.addEventListener('click', () => exitSpeedReading());
            }
            if (elements.speedReadingSlider) {
                elements.speedReadingSlider.addEventListener('input', (e) => {
                    const value = parseInt(e.target.value, 10);
                    if (!Number.isNaN(value)) {
                        state.speedReadingWpm = value;
                        localStorage.setItem(STORAGE_KEYS.SPEED_READING_WPM, value);
                        updateSpeedReadingControls();
                        if (state.speedReadingActive && state.speedReadingPlaying) {
                            clearSpeedReadingTimer();
                            speedReadingStep();
                        }
                    }
                });
            }
            if (elements.speedReadingContinue) {
                elements.speedReadingContinue.addEventListener('click', continueSpeedReading);
            }
            if (elements.speedReadingExit) {
                elements.speedReadingExit.addEventListener('click', () => exitSpeedReading());
            }
        }

        if (elements.chapterRecapClose) {
            elements.chapterRecapClose.addEventListener('click', () => closeChapterRecapOverlay(true));
        }
        if (elements.chapterRecapBackdrop) {
            elements.chapterRecapBackdrop.addEventListener('click', () => closeChapterRecapOverlay(true));
        }
        if (elements.chapterRecapSkip) {
            elements.chapterRecapSkip.addEventListener('click', skipChapterRecap);
        }
        if (elements.chapterRecapContinue) {
            elements.chapterRecapContinue.addEventListener('click', () => continueFromChapterRecap('continued'));
        }
        if (elements.chapterRecapTabRecap) {
            elements.chapterRecapTabRecap.addEventListener('click', () => setChapterRecapTab('recap'));
        }
        if (elements.chapterRecapTabChat) {
            elements.chapterRecapTabChat.addEventListener('click', () => {
                setChapterRecapTab('chat');
                if (state.recapChatAvailable && !state.recapChatLoading) {
                    elements.chapterRecapChatInput?.focus();
                }
            });
        }
        if (elements.chapterRecapTabQuiz) {
            elements.chapterRecapTabQuiz.addEventListener('click', () => setChapterRecapTab('quiz'));
        }
        if (elements.chapterRecapRetry) {
            elements.chapterRecapRetry.addEventListener('click', () => {
                if (typeof state.recapRetryHandler === 'function') {
                    state.recapRetryHandler();
                }
            });
        }
        if (elements.chapterRecapChatSend) {
            elements.chapterRecapChatSend.addEventListener('click', sendRecapChatMessage);
        }
        if (elements.chapterRecapChatRetry) {
            elements.chapterRecapChatRetry.addEventListener('click', () => {
                if (typeof state.recapChatRetryHandler === 'function') {
                    state.recapChatRetryHandler();
                }
            });
        }
        if (elements.chapterQuizSubmit) {
            elements.chapterQuizSubmit.addEventListener('click', submitChapterQuiz);
        }
        if (elements.chapterRecapChatInput) {
            elements.chapterRecapChatInput.addEventListener('input', () => {
                clearRecapChatError();
            });
            elements.chapterRecapChatInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    e.stopPropagation();
                    sendRecapChatMessage();
                }
            });
        }
        if (elements.chapterRecapOptout) {
            elements.chapterRecapOptout.addEventListener('change', (e) => {
                setRecapOptOut(state.currentBook?.id, !!e.target.checked);
            });
        }
        if (elements.recapEnableBtn) {
            elements.recapEnableBtn.addEventListener('click', () => {
                if (!state.currentBook?.id) return;
                setRecapOptOut(state.currentBook.id, false);
                if (elements.chapterRecapOptout) {
                    elements.chapterRecapOptout.checked = false;
                }
            });
        }

        // Illustration toggle
        if (elements.illustrationToggle) {
            elements.illustrationToggle.addEventListener('click', illustrationToggle);
        }

        // Illustration image click (opens prompt modal)
        if (elements.illustrationImage) {
            elements.illustrationImage.addEventListener('click', () => {
                if (state.allowPromptEditing && elements.illustrationImage.classList.contains('editable')) {
                    openPromptModal();
                }
            });
        }
        if (elements.illustrationErrorRetry) {
            elements.illustrationErrorRetry.addEventListener('click', () => {
                if (typeof state.illustrationRetryHandler === 'function') {
                    state.illustrationRetryHandler();
                }
            });
        }

        // Prompt modal event listeners
        if (elements.promptModalClose) {
            elements.promptModalClose.addEventListener('click', closePromptModal);
        }
        if (elements.promptCancel) {
            elements.promptCancel.addEventListener('click', closePromptModal);
        }
        if (elements.promptRegenerate) {
            elements.promptRegenerate.addEventListener('click', regenerateIllustration);
        }
        if (elements.promptTextarea) {
            elements.promptTextarea.addEventListener('input', () => {
                clearPromptError();
            });
        }
        if (elements.promptModalBackdrop) {
            elements.promptModalBackdrop.addEventListener('click', closePromptModal);
        }
        if (elements.promptTryAgain) {
            elements.promptTryAgain.addEventListener('click', tryAgainRegeneration);
        }
        if (elements.promptAccept) {
            elements.promptAccept.addEventListener('click', acceptRegeneration);
        }
        if (elements.promptErrorRetry) {
            elements.promptErrorRetry.addEventListener('click', () => {
                if (typeof state.promptRetryHandler === 'function') {
                    state.promptRetryHandler();
                }
            });
        }

        // Search input
        if (elements.searchToggle) {
            elements.searchToggle.addEventListener('click', () => {
                const expanded = !!elements.searchContainer?.classList.contains('expanded');
                const hasQuery = !!(elements.searchInput?.value || '').trim();
                if (!expanded) {
                    setDesktopSearchExpanded(true, { focus: true });
                    return;
                }
                if (!hasQuery) {
                    elements.searchResults.classList.add('hidden');
                    setDesktopSearchExpanded(false);
                    return;
                }
                elements.searchInput?.focus();
            });
        }
        elements.searchInput.addEventListener('input', (e) => {
            const value = e.target.value || '';
            setSearchInputValues(value, { skipDesktop: true });
            scheduleSearch(value);
        });
        if (elements.mobileMenuSearchInput) {
            elements.mobileMenuSearchInput.addEventListener('input', (e) => {
                const value = e.target.value || '';
                setSearchInputValues(value, { skipMobile: true });
            });
        }
        if (elements.mobileMenuSearchSubmit) {
            elements.mobileMenuSearchSubmit.addEventListener('click', () => {
                submitMobileMenuSearch();
            });
        }
        if (elements.searchChapterFilter) {
            elements.searchChapterFilter.addEventListener('change', (e) => {
                state.searchChapterFilter = e.target.value || '';
                performSearch(elements.searchInput.value);
            });
        }
        if (elements.searchResultsRetry) {
            elements.searchResultsRetry.addEventListener('click', () => {
                if (typeof state.searchRetryHandler === 'function') {
                    state.searchRetryHandler();
                }
            });
        }

        elements.searchInput.addEventListener('focus', () => {
            setDesktopSearchExpanded(true);
            ttsPauseForModal();
            if (elements.searchInput.value.trim().length >= 2) {
                performSearch(elements.searchInput.value);
            }
        });
        if (elements.mobileMenuSearchInput) {
            elements.mobileMenuSearchInput.addEventListener('focus', () => {
                ttsPauseForModal();
            });
        }

        elements.searchInput.addEventListener('blur', () => {
            ttsResumeAfterModal();
            if (elements.searchInput.value.trim().length === 0) {
                setDesktopSearchExpanded(false);
            }
        });
        if (elements.mobileMenuSearchInput) {
            elements.mobileMenuSearchInput.addEventListener('blur', () => {
                ttsResumeAfterModal();
            });
        }

        elements.searchInput.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                e.stopPropagation();
                elements.searchResults.classList.add('hidden');
                if (elements.searchInput.value.trim().length === 0) {
                    setDesktopSearchExpanded(false);
                }
                elements.searchInput.blur();
            }
        });
        if (elements.mobileMenuSearchInput) {
            elements.mobileMenuSearchInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    e.stopPropagation();
                    submitMobileMenuSearch();
                    return;
                }
                if (e.key === 'Escape') {
                    e.stopPropagation();
                    elements.searchResults.classList.add('hidden');
                    elements.mobileMenuSearchInput.blur();
                }
            });
        }

        // Search results click
        elements.searchResults.addEventListener('click', (e) => {
            const resultItem = e.target.closest('.search-result-item');
            if (resultItem && resultItem.dataset.chapterId) {
                const chapterId = resultItem.dataset.chapterId;
                const parsed = parseInt(resultItem.dataset.paragraphIndex, 10);
                const paragraphIndex = Number.isInteger(parsed) ? parsed : 0;
                navigateToSearchResult(chapterId, paragraphIndex);
            }
        });

        // Chapter list click handler
        elements.chapterList.addEventListener('click', (e) => {
            const item = e.target.closest('.chapter-list-item');
            if (item) {
                const index = parseInt(item.dataset.chapterIndex);
                selectChapterFromList(index);
            }
        });

        // Close chapter list when clicking overlay background
        elements.chapterListOverlay.addEventListener('click', (e) => {
            if (e.target === elements.chapterListOverlay) {
                hideChapterList();
            }
        });

        // Character feature event listeners
        if (elements.characterToggle) {
            elements.characterToggle.addEventListener('click', characterBrowserToggle);
        }
        if (elements.characterToastClose) {
            elements.characterToastClose.addEventListener('click', dismissCharacterToast);
        }
        if (elements.characterToast) {
            elements.characterToast.addEventListener('click', (e) => {
                if (e.target !== elements.characterToastClose) {
                    openCharacterBrowserToCharacter(state.currentToastCharacter?.id);
                }
            });
        }
        if (elements.characterBrowserClose) {
            elements.characterBrowserClose.addEventListener('click', closeCharacterBrowser);
        }
        if (elements.characterBrowserModal) {
            elements.characterBrowserModal.querySelector('.character-modal-backdrop')?.addEventListener('click', closeCharacterBrowser);
        }
        if (elements.characterBackBtn) {
            elements.characterBackBtn.addEventListener('click', showCharacterListView);
        }
        if (elements.characterChatBtn) {
            elements.characterChatBtn.addEventListener('click', () => openCharacterChat(state.selectedCharacterId));
        }
        if (elements.characterDetailLink) {
            elements.characterDetailLink.addEventListener('click', (e) => {
                e.preventDefault();
                navigateToCharacterAppearance();
            });
        }
        if (elements.characterChatClose) {
            elements.characterChatClose.addEventListener('click', closeCharacterChat);
        }
        if (elements.characterChatModal) {
            elements.characterChatModal.querySelector('.character-modal-backdrop')?.addEventListener('click', closeCharacterChat);
        }
        if (elements.chatSendBtn) {
            elements.chatSendBtn.addEventListener('click', sendChatMessage);
        }
        if (elements.chatErrorRetry) {
            elements.chatErrorRetry.addEventListener('click', () => {
                if (typeof state.characterChatRetryHandler === 'function') {
                    state.characterChatRetryHandler();
                }
            });
        }
        if (elements.chatInput) {
            elements.chatInput.addEventListener('input', () => {
                clearCharacterChatError();
            });
        }

        // Keyboard navigation
        document.addEventListener('keydown', (e) => {
            if (isAccountModalVisible()) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    closeAccountModal();
                }
                return;
            }

            if (isAuthModalVisible()) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    closeAuthModal();
                }
                return;
            }

            if (isAchievementsModalVisible()) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    closeAchievementsModal();
                }
                return;
            }

            if (isMobileHeaderMenuVisible()) {
                const activeElement = document.activeElement;
                const typingInMenuInput = !!elements.mobileHeaderMenuPanel
                    && elements.mobileHeaderMenuPanel.contains(activeElement)
                    && (activeElement instanceof HTMLInputElement
                        || activeElement instanceof HTMLTextAreaElement);

                if (e.key === 'Escape' || (!state.isMobileLayout && e.key.toLowerCase() === 'o')) {
                    e.preventDefault();
                    closeMobileHeaderMenu({ restoreToggleFocus: true });
                    return;
                }

                if (!typingInMenuInput) {
                    if (e.key === 'ArrowDown' || e.key === 'j') {
                        e.preventDefault();
                        moveMobileHeaderMenuFocus(1);
                        return;
                    }
                    if (e.key === 'ArrowUp' || e.key === 'k') {
                        e.preventDefault();
                        moveMobileHeaderMenuFocus(-1);
                        return;
                    }
                    if (e.key === 'Enter') {
                        e.preventDefault();
                        activateMobileHeaderMenuFocusedAction();
                        return;
                    }
                }
                return;
            }

            if (isShortcutsOverlayVisible()) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    hideShortcutsOverlay();
                }
                return;
            }

            if (isAnnotationMenuVisible()) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    closeAnnotationMenu();
                }
                return;
            }

            if (isReaderSettingsPanelVisible()) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    closeReaderSettingsPanel();
                }
                return;
            }

            if (isNoteModalVisible()) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    closeNoteModal();
                } else if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
                    e.preventDefault();
                    saveNoteFromModal();
                }
                return;
            }

            // Handle prompt modal keyboard
            if (isPromptModalVisible()) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    closePromptModal();
                }
                // Don't process other shortcuts when modal is open
                return;
            }

            if (isChapterRecapVisible()) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    closeChapterRecapOverlay(true);
                } else if (e.key === 'Enter'
                    && state.recapActiveTab === 'recap'
                    && document.activeElement !== elements.chapterRecapChatInput
                    && document.activeElement !== elements.chapterRecapTabRecap
                    && document.activeElement !== elements.chapterRecapTabChat
                    && document.activeElement !== elements.chapterRecapTabQuiz) {
                    e.preventDefault();
                    continueFromChapterRecap('continued');
                }
                return;
            }

            // Handle character browser modal keyboard
            if (isCharacterBrowserVisible()) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    closeCharacterBrowser();
                }
                return;
            }

            // Handle character chat modal keyboard
            if (isCharacterChatVisible()) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    closeCharacterChat();
                }
                // Allow Enter to send chat if not shift+enter
                if (e.key === 'Enter' && !e.shiftKey && document.activeElement === elements.chatInput) {
                    e.preventDefault();
                    sendChatMessage();
                }
                return;
            }

            // Skip if typing in search
            if (document.activeElement === elements.searchInput) {
                return;
            }

            // Skip if not in reader view
            if (elements.readerView.classList.contains('hidden')) {
                return;
            }

            if (state.speedReadingEnabled && state.speedReadingActive) {
                if (!elements.speedReadingChapterOverlay.classList.contains('hidden')) {
                    if (e.key === 'Escape') {
                        e.preventDefault();
                        exitSpeedReading();
                    } else if (e.key === 'Enter') {
                        e.preventDefault();
                        continueSpeedReading();
                    }
                    return;
                }

                if (e.key === 'Escape') {
                    e.preventDefault();
                    exitSpeedReading();
                    return;
                }
                if (e.key === 'r') {
                    e.preventDefault();
                    speedReadingToggle();
                    return;
                }
                if (e.key === ' ') {
                    e.preventDefault();
                    if (state.speedReadingPlaying) {
                        speedReadingPause();
                    } else {
                        speedReadingStart();
                    }
                    return;
                }
                return;
            }

            if (isBookmarksOverlayVisible()) {
                switch (e.key) {
                    case 'ArrowDown':
                    case 'j':
                        e.preventDefault();
                        bookmarkListNavigate(1);
                        break;
                    case 'ArrowUp':
                    case 'k':
                        e.preventDefault();
                        bookmarkListNavigate(-1);
                        break;
                    case 'Enter':
                        e.preventDefault();
                        selectBookmarkFromList(bookmarkListSelectedIndex);
                        break;
                    case 'Escape':
                        e.preventDefault();
                        hideBookmarksOverlay();
                        break;
                }
                return;
            }

            // Handle chapter list keyboard navigation
            if (isChapterListVisible()) {
                switch (e.key) {
                    case 'ArrowDown':
                    case 'j':
                        e.preventDefault();
                        chapterListNavigate(1);
                        break;
                    case 'ArrowUp':
                    case 'k':
                        e.preventDefault();
                        chapterListNavigate(-1);
                        break;
                    case 'Enter':
                        e.preventDefault();
                        selectChapterFromList(chapterListSelectedIndex);
                        break;
                    case 'Escape':
                        e.preventDefault();
                        hideChapterList();
                        break;
                }
                return;
            }

            // Allow native shortcuts (Cmd-C, Ctrl-V, etc.)
            if (e.metaKey || e.ctrlKey) {
                return;
            }

            switch (e.key) {
                case '?':
                    e.preventDefault();
                    showShortcutsOverlay();
                    break;
                case '/':
                    e.preventDefault();
                    if (state.isMobileLayout && elements.mobileMenuSearchInput) {
                        openMobileHeaderMenu();
                        elements.mobileMenuSearchInput.focus();
                    } else {
                        setDesktopSearchExpanded(true, { focus: true });
                    }
                    break;
                case 'c':
                    e.preventDefault();
                    showChapterList();
                    break;
                case 'u':
                    e.preventDefault();
                    toggleHighlightForCurrentParagraph();
                    break;
                case 'n':
                    e.preventDefault();
                    openNoteModal();
                    break;
                case 'b':
                    e.preventDefault();
                    toggleBookmarkForCurrentParagraph();
                    break;
                case 'B':
                    e.preventDefault();
                    showBookmarksOverlay();
                    break;
                case 'x':
                case 'X':
                    e.preventDefault();
                    void copyCurrentBookMlaCitation();
                    break;
                case 'j':
                    e.preventDefault();
                    nextParagraph();
                    ttsInterrupt();
                    break;
                case 'k':
                    e.preventDefault();
                    prevParagraph();
                    ttsInterrupt();
                    break;
                case 'l':
                    e.preventDefault();
                    nextPage();
                    ttsInterrupt();
                    break;
                case 'h':
                    e.preventDefault();
                    prevPage();
                    ttsInterrupt();
                    break;
                case 'L':
                    e.preventDefault();
                    if (state.ttsEnabled) speechSynthesis.cancel();
                    nextChapter();
                    break;
                case 'H':
                    e.preventDefault();
                    if (state.ttsEnabled) speechSynthesis.cancel();
                    prevChapter();
                    break;
                case 'p':
                    e.preventDefault();
                    ttsToggle();
                    break;
                case 's':
                    e.preventDefault();
                    ttsCycleSpeed();
                    break;
                case 'r':
                    if (state.speedReadingEnabled) {
                        e.preventDefault();
                        speedReadingToggle();
                    }
                    break;
                case 'i':
                    e.preventDefault();
                    illustrationToggle();
                    break;
                case 'm':
                    console.log('M key pressed');
                    e.preventDefault();
                    characterBrowserToggle();
                    break;
                case 'o':
                    if (!state.isMobileLayout) {
                        e.preventDefault();
                        toggleMobileHeaderMenu();
                    }
                    break;
            }
        });

        // Handle window resize
        let resizeTimeout;
        window.addEventListener('resize', () => {
            clearTimeout(resizeTimeout);
            resizeTimeout = setTimeout(() => {
                applyLayoutCapabilities({ repaginate: true });
            }, 250);
        });
    }

    // Start
    document.addEventListener('DOMContentLoaded', init);
})();
