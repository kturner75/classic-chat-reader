const test = require('node:test');
const assert = require('node:assert/strict');

const {
    identityKey,
    dedupeByIdentity,
    mergeAndDedupe,
    retainLiveDiscoveries
} = require('../../main/resources/static/js/character-identity.js');

test('identityKey collapses case, punctuation, and whitespace', () => {
    assert.equal(identityKey('Sally'), 'sally');
    assert.equal(identityKey('  SALLY.  '), 'sally');
    assert.equal(identityKey('Henry-Tilney'), 'henry tilney');
});

test('identityKey preserves Unicode letters and digits and keeps distinct people apart', () => {
    assert.equal(identityKey('José'), 'josé');
    assert.equal(identityKey('Jos'), 'jos');
    assert.equal(identityKey('Émile'), 'émile');
    assert.equal(identityKey('Mile'), 'mile');
    assert.equal(identityKey('Agent 47'), 'agent 47');
    assert.equal(identityKey('Agent'), 'agent');
    assert.notEqual(identityKey('Mrs. Bennet'), identityKey('Elizabeth Bennet'));
    assert.notEqual(identityKey('Mr. Allen'), identityKey('Mrs. Allen'));
});

test('dedupeByIdentity keeps the stronger Sally and drops residual copies', () => {
    const pending = { id: 'a', name: 'Sally', characterType: 'SECONDARY', status: 'PENDING', portraitReady: false };
    const completed = { id: 'b', name: 'Sally.', characterType: 'SECONDARY', status: 'COMPLETED', portraitReady: true };
    const tilney = { id: 'c', name: 'Henry Tilney', characterType: 'PRIMARY', status: 'COMPLETED', portraitReady: true };

    const deduped = dedupeByIdentity([pending, tilney, completed]);
    assert.deepEqual(deduped.map((item) => item.id), ['c', 'b']);
});

test('mergeAndDedupe collapses same identity from cached and live lists', () => {
    const live = [{ id: 'new', name: 'Sally', characterType: 'PRIMARY', status: 'COMPLETED', portraitReady: true }];
    const cached = [{ id: 'old', name: 'sally', characterType: 'SECONDARY', status: 'PENDING', portraitReady: false }];
    const merged = mergeAndDedupe(live, cached);
    assert.deepEqual(merged.map((item) => item.id), ['new']);
});

test('retainLiveDiscoveries drops stale ids from earlier generation runs', () => {
    const retained = retainLiveDiscoveries(
        ['old-sally', 'live-tilney'],
        [
            { id: 'old-sally', name: 'Sally' },
            { id: 'live-tilney', name: 'Henry Tilney' }
        ],
        [{ id: 'live-tilney', name: 'Henry Tilney' }]
    );
    assert.deepEqual(retained.ids, ['live-tilney']);
    assert.deepEqual(retained.details.map((item) => item.id), ['live-tilney']);
});
