(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
        return;
    }
    root.CharacterIdentity = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    function identityKey(name) {
        if (typeof name !== 'string') {
            return '';
        }
        return name
            .toLowerCase()
            .replace(/[^a-z\s-]/g, ' ')
            .replace(/-/g, ' ')
            .replace(/\s+/g, ' ')
            .trim();
    }

    function prefer(left, right) {
        if (!left) {
            return right;
        }
        if (!right) {
            return left;
        }
        const typeRank = (character) => character.characterType === 'PRIMARY' ? 1 : 0;
        const statusRank = (character) => {
            if (character.status === 'COMPLETED' || character.portraitReady === true) {
                return 4;
            }
            if (character.status === 'GENERATING') {
                return 3;
            }
            if (character.status === 'PENDING') {
                return 2;
            }
            if (character.status === 'FAILED') {
                return 1;
            }
            return 0;
        };
        const leftKey = [
            typeRank(left),
            statusRank(left),
            left.portraitReady ? 1 : 0,
            -(left.firstChapterIndex ?? 0),
            -(left.firstParagraphIndex ?? 0),
            left.id || ''
        ];
        const rightKey = [
            typeRank(right),
            statusRank(right),
            right.portraitReady ? 1 : 0,
            -(right.firstChapterIndex ?? 0),
            -(right.firstParagraphIndex ?? 0),
            right.id || ''
        ];
        for (let i = 0; i < leftKey.length; i++) {
            if (leftKey[i] > rightKey[i]) {
                return left;
            }
            if (leftKey[i] < rightKey[i]) {
                return right;
            }
        }
        return left;
    }

    function dedupeByIdentity(characters) {
        const source = Array.isArray(characters) ? characters : [];
        const winners = new Map();
        source.forEach((character) => {
            if (!character) {
                return;
            }
            const key = identityKey(character.name) || `id:${character.id || ''}`;
            const existing = winners.get(key);
            winners.set(key, prefer(existing, character));
        });
        const chosen = new Set(winners.values());
        return source.filter((character) => chosen.has(character));
    }

    function mergeAndDedupe(primaryList, secondaryList) {
        const byId = new Map();
        (Array.isArray(primaryList) ? primaryList : []).forEach((character) => {
            if (character && character.id) {
                byId.set(character.id, character);
            }
        });
        (Array.isArray(secondaryList) ? secondaryList : []).forEach((character) => {
            if (character && character.id) {
                byId.set(character.id, character);
            }
        });
        return dedupeByIdentity(Array.from(byId.values()));
    }

    function retainLiveDiscoveries(discoveredIds, discoveredDetails, liveCharacters) {
        const liveIds = new Set((Array.isArray(liveCharacters) ? liveCharacters : [])
            .map((character) => character && character.id)
            .filter(Boolean));
        const nextIds = [];
        (discoveredIds || []).forEach((id) => {
            if (liveIds.has(id)) {
                nextIds.push(id);
            }
        });
        const nextDetails = [];
        (discoveredDetails || []).forEach((character) => {
            if (character && liveIds.has(character.id)) {
                nextDetails.push(character);
            }
        });
        return { ids: nextIds, details: nextDetails };
    }

    return {
        identityKey,
        prefer,
        dedupeByIdentity,
        mergeAndDedupe,
        retainLiveDiscoveries
    };
});
