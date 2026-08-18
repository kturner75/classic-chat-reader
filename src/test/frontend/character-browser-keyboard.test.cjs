const test = require('node:test');
const assert = require('node:assert/strict');

const {
    characterCardSemantics,
    clampIndex,
    cycleFocusIndex,
    describeKey,
    hasButtonOptionDualRole,
    isNavigationKey,
    isVisibleFocusable,
    moveGridIndex,
    moveIndex,
    moveLinearIndex,
    pickReturnFocus
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

test('describeKey does not preventDefault Ctrl/Cmd+C/R/S/P or Alt+Arrow, but still traps Shift+Tab', () => {
    const listContext = { listVisible: true, cardFocused: true, characterCount: 3 };
    const ignoreChords = [
        { key: 'c', ctrlKey: true },
        { key: 'c', metaKey: true },
        { key: 'r', ctrlKey: true },
        { key: 'r', metaKey: true },
        { key: 's', ctrlKey: true },
        { key: 's', metaKey: true },
        { key: 'p', ctrlKey: true },
        { key: 'p', metaKey: true },
        { key: 'ArrowDown', altKey: true },
        { key: 'ArrowUp', altKey: true },
        { key: 'ArrowLeft', altKey: true },
        { key: 'ArrowRight', altKey: true }
    ];

    ignoreChords.forEach((event) => {
        const decision = describeKey(event, listContext);
        assert.equal(decision.action, 'ignore', `${event.key} chord should be ignored`);
        assert.equal(decision.preventDefault, false, `${event.key} chord must not preventDefault`);
        assert.equal(decision.stopPropagation, false);
    });

    const shiftTab = describeKey({ key: 'Tab', shiftKey: true }, listContext);
    assert.equal(shiftTab.action, 'trap-tab-backward');
    assert.equal(shiftTab.preventDefault, true);
    assert.equal(shiftTab.stopPropagation, true);

    const plainTab = describeKey({ key: 'Tab' }, listContext);
    assert.equal(plainTab.action, 'trap-tab-forward');
    assert.equal(plainTab.preventDefault, true);

    const ctrlTab = describeKey({ key: 'Tab', ctrlKey: true }, listContext);
    assert.equal(ctrlTab.action, 'ignore');
    assert.equal(ctrlTab.preventDefault, false);
});

function createFocusableControl(overrides = {}) {
    const classList = {
        contains(name) {
            return (overrides.classes || []).includes(name);
        }
    };
    const attributes = { ...(overrides.attributes || {}) };
    return {
        id: overrides.id || 'control',
        disabled: overrides.disabled === true,
        hidden: overrides.hidden === true,
        style: { ...(overrides.style || {}) },
        classList,
        offsetParent: Object.prototype.hasOwnProperty.call(overrides, 'offsetParent')
            ? overrides.offsetParent
            : {},
        closest(selector) {
            if (selector === '.hidden' && (overrides.hiddenAncestor || (overrides.classes || []).includes('hidden'))) {
                return { className: 'hidden' };
            }
            return null;
        },
        getAttribute(name) {
            return attributes[name] ?? null;
        },
        focus() {}
    };
}

test('isVisibleFocusable rejects hidden and non-focusable controls', () => {
    const documentMock = {
        contains() {
            return true;
        }
    };
    const env = { document: documentMock };

    assert.equal(isVisibleFocusable(createFocusableControl(), env), true);
    assert.equal(isVisibleFocusable(createFocusableControl({ style: { display: 'none' } }), env), false);
    assert.equal(isVisibleFocusable(createFocusableControl({ offsetParent: null }), env), false);
    assert.equal(isVisibleFocusable(createFocusableControl({ hiddenAncestor: true }), env), false);
    assert.equal(isVisibleFocusable(createFocusableControl({ classes: ['hidden'] }), env), false);
    assert.equal(isVisibleFocusable(createFocusableControl({ disabled: true }), env), false);
    assert.equal(isVisibleFocusable(null, env), false);

    const detached = createFocusableControl();
    assert.equal(isVisibleFocusable(detached, {
        document: {
            contains() {
                return false;
            }
        }
    }), false);
});

test('pickReturnFocus skips a hidden opener and uses a visible reader fallback', () => {
    const documentMock = {
        contains() {
            return true;
        }
    };
    const env = { document: documentMock };
    const hiddenMobileCharacters = createFocusableControl({
        id: 'mobile-menu-character-toggle',
        hiddenAncestor: true,
        offsetParent: null
    });
    const hiddenDesktopToggle = createFocusableControl({
        id: 'character-toggle',
        style: { display: 'none' },
        offsetParent: null
    });
    const visibleMenuToggle = createFocusableControl({
        id: 'mobile-header-menu-toggle'
    });

    const fallback = pickReturnFocus({
        remembered: hiddenMobileCharacters,
        launchers: [hiddenDesktopToggle, hiddenMobileCharacters, visibleMenuToggle]
    }, env);

    assert.equal(fallback.id, 'mobile-header-menu-toggle');

    const visibleOpener = createFocusableControl({ id: 'character-toggle' });
    const remembered = pickReturnFocus({
        remembered: visibleOpener,
        launchers: [hiddenDesktopToggle, visibleMenuToggle]
    }, env);
    assert.equal(remembered.id, 'character-toggle');

    assert.equal(pickReturnFocus({
        remembered: hiddenMobileCharacters,
        launchers: [hiddenDesktopToggle, hiddenMobileCharacters]
    }, env), null);
});

test('character cards are option-only, not native buttons inside a listbox', () => {
    const semantics = characterCardSemantics();
    assert.equal(semantics.tagName, 'div');
    assert.equal(semantics.role, 'option');
    assert.equal(semantics.containerRole, 'listbox');
    assert.equal(hasButtonOptionDualRole(semantics), false);
    assert.equal(hasButtonOptionDualRole({ tagName: 'button', role: 'option' }), true);
});
