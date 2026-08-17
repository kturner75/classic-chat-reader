const test = require('node:test');
const assert = require('node:assert/strict');

const {
    clampIndex,
    cycleFocusIndex,
    describeKey,
    isNavigationKey,
    moveGridIndex,
    moveIndex,
    moveLinearIndex
} = require('../../main/resources/static/js/character-browser-keyboard.js');

function twoRowRects() {
    return [
        { index: 0, top: 0, left: 0, width: 130, height: 160 },
        { index: 1, top: 0, left: 150, width: 130, height: 160 },
        { index: 2, top: 180, left: 0, width: 90, height: 120 },
        { index: 3, top: 180, left: 110, width: 90, height: 120 },
        { index: 4, top: 180, left: 220, width: 90, height: 120 }
    ];
}

test('clampIndex keeps a valid selection inside the list', () => {
    assert.equal(clampIndex(1, 3), 1);
    assert.equal(clampIndex(-2, 3), 0);
    assert.equal(clampIndex(9, 3), 2);
    assert.equal(clampIndex(0, 0), -1);
});

test('moveLinearIndex treats arrows as a one-dimensional list', () => {
    assert.equal(moveLinearIndex(0, 'ArrowDown', 4), 1);
    assert.equal(moveLinearIndex(1, 'ArrowUp', 4), 0);
    assert.equal(moveLinearIndex(0, 'ArrowLeft', 4), 0);
    assert.equal(moveLinearIndex(3, 'ArrowRight', 4), 3);
    assert.equal(moveLinearIndex(2, 'Home', 4), 0);
    assert.equal(moveLinearIndex(1, 'End', 4), 3);
});

test('moveGridIndex uses visual rows for wrapping character cards', () => {
    const rects = twoRowRects();

    assert.equal(moveGridIndex(rects, 0, 'ArrowRight'), 1);
    assert.equal(moveGridIndex(rects, 1, 'ArrowRight'), 2);
    assert.equal(moveGridIndex(rects, 0, 'ArrowDown'), 2);
    assert.equal(moveGridIndex(rects, 1, 'ArrowDown'), 3);
    assert.equal(moveGridIndex(rects, 3, 'ArrowUp'), 1);
    assert.equal(moveGridIndex(rects, 2, 'ArrowLeft'), 1);
    assert.equal(moveGridIndex(rects, 4, 'Home'), 0);
    assert.equal(moveGridIndex(rects, 0, 'End'), 4);
});

test('moveIndex falls back to linear movement for a single visual row', () => {
    const oneRow = [
        { index: 0, top: 10, left: 0, width: 90, height: 120 },
        { index: 1, top: 12, left: 100, width: 90, height: 120 },
        { index: 2, top: 8, left: 200, width: 90, height: 120 }
    ];

    assert.equal(moveIndex(oneRow, 0, 'ArrowDown'), 1);
    assert.equal(moveIndex(oneRow, 2, 'ArrowUp'), 1);
    assert.equal(moveIndex(twoRowRects(), 0, 'ArrowDown'), 2);
});

test('cycleFocusIndex wraps at the ends of the modal tab order', () => {
    assert.equal(cycleFocusIndex(0, 1, 3), 1);
    assert.equal(cycleFocusIndex(2, 1, 3), 0);
    assert.equal(cycleFocusIndex(0, -1, 3), 2);
    assert.equal(cycleFocusIndex(-1, 1, 3), 0);
});

test('describeKey opens a listbox keyboard model and blocks reader shortcuts', () => {
    const listContext = { listVisible: true, cardFocused: true, characterCount: 3 };

    assert.deepEqual(describeKey({ key: 'Escape' }, listContext), {
        action: 'close',
        preventDefault: true,
        stopPropagation: true
    });
    assert.equal(describeKey({ key: 'ArrowDown' }, listContext).action, 'move');
    assert.equal(describeKey({ key: 'ArrowUp' }, listContext).action, 'move');
    assert.equal(describeKey({ key: 'Enter' }, listContext).action, 'select');
    assert.equal(describeKey({ key: ' ' }, listContext).action, 'select');
    assert.equal(describeKey({ key: 'Tab' }, listContext).action, 'trap-tab-forward');
    assert.equal(describeKey({ key: 'Tab', shiftKey: true }, listContext).action, 'trap-tab-backward');
    assert.equal(isNavigationKey('ArrowLeft'), true);

    const unfocusedClose = describeKey({ key: 'Enter' }, {
        listVisible: true,
        cardFocused: false,
        characterCount: 3
    });
    assert.equal(unfocusedClose.action, 'consume');
    assert.equal(unfocusedClose.preventDefault, false);

    const emptyList = describeKey({ key: 'ArrowDown' }, {
        listVisible: true,
        cardFocused: false,
        characterCount: 0
    });
    assert.equal(emptyList.action, 'block-reader');
    assert.equal(emptyList.preventDefault, true);

    const detailView = describeKey({ key: 'j' }, {
        listVisible: false,
        cardFocused: false,
        characterCount: 3
    });
    assert.equal(detailView.action, 'block-reader');
    assert.equal(detailView.stopPropagation, true);
});
