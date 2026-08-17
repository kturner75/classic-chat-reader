(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
        return;
    }
    root.CharacterBrowserKeyboard = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    const NAVIGATION_KEYS = new Set(['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End']);
    const READER_NAV_KEYS = new Set([
        'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight',
        'Home', 'End',
        'j', 'k', 'h', 'l', 'J', 'K', 'H', 'L'
    ]);
    const READER_SHORTCUT_KEYS = new Set([
        '?', '/', 'c', 'u', 'n', 'b', 'B', 'x', 'X',
        'j', 'k', 'l', 'h', 'H', 'L', 'p', 's', 'r', 'i', 'm', 'o'
    ]);

    function clampIndex(index, length) {
        if (!Number.isInteger(length) || length <= 0) {
            return -1;
        }
        if (!Number.isInteger(index) || index < 0) {
            return 0;
        }
        if (index >= length) {
            return length - 1;
        }
        return index;
    }

    function isNavigationKey(key) {
        return NAVIGATION_KEYS.has(key);
    }

    function isSelectKey(key) {
        return key === 'Enter' || key === ' ';
    }

    function cycleFocusIndex(currentIndex, delta, length) {
        if (!Number.isInteger(length) || length <= 0) {
            return -1;
        }
        const step = delta < 0 ? -1 : 1;
        if (!Number.isInteger(currentIndex) || currentIndex < 0 || currentIndex >= length) {
            return step > 0 ? 0 : length - 1;
        }
        return (currentIndex + step + length) % length;
    }

    function moveLinearIndex(currentIndex, key, length) {
        if (!Number.isInteger(length) || length <= 0) {
            return -1;
        }
        const current = clampIndex(currentIndex, length);
        switch (key) {
            case 'ArrowDown':
            case 'ArrowRight':
                return Math.min(length - 1, current + 1);
            case 'ArrowUp':
            case 'ArrowLeft':
                return Math.max(0, current - 1);
            case 'Home':
                return 0;
            case 'End':
                return length - 1;
            default:
                return current;
        }
    }

    function normalizeRects(rects) {
        if (!Array.isArray(rects)) {
            return [];
        }
        return rects.map((item, index) => ({
            index: Number.isInteger(item?.index) ? item.index : index,
            top: Number(item?.top),
            left: Number(item?.left),
            width: Number(item?.width) || 0,
            height: Number(item?.height) || 0
        })).filter((item) => Number.isFinite(item.top) && Number.isFinite(item.left));
    }

    function groupRectsIntoRows(rects, rowTolerance = 16) {
        const tolerance = Number.isFinite(rowTolerance) ? rowTolerance : 16;
        const rows = [];
        const sorted = [...rects].sort((a, b) => a.top - b.top || a.left - b.left);
        sorted.forEach((rect) => {
            const row = rows.find((candidate) => Math.abs(candidate[0].top - rect.top) <= tolerance);
            if (row) {
                row.push(rect);
                row.sort((a, b) => a.left - b.left);
                return;
            }
            rows.push([rect]);
        });
        rows.sort((a, b) => a[0].top - b[0].top);
        return rows;
    }

    function findRowPosition(rows, index) {
        for (let row = 0; row < rows.length; row += 1) {
            const col = rows[row].findIndex((item) => item.index === index);
            if (col >= 0) {
                return { row, col };
            }
        }
        return { row: 0, col: 0 };
    }

    function moveGridIndex(rects, currentIndex, key, options = {}) {
        const items = normalizeRects(rects);
        if (items.length === 0) {
            return -1;
        }
        if (key === 'Home') {
            return 0;
        }
        if (key === 'End') {
            return items.length - 1;
        }
        if (!isNavigationKey(key)) {
            return clampIndex(currentIndex, items.length);
        }

        const rows = groupRectsIntoRows(items, options.rowTolerance);
        if (rows.length === 0) {
            return moveLinearIndex(currentIndex, key, items.length);
        }

        const current = clampIndex(currentIndex, items.length);
        const { row, col } = findRowPosition(rows, current);

        if (key === 'ArrowRight') {
            if (col < rows[row].length - 1) {
                return rows[row][col + 1].index;
            }
            if (row < rows.length - 1) {
                return rows[row + 1][0].index;
            }
            return current;
        }
        if (key === 'ArrowLeft') {
            if (col > 0) {
                return rows[row][col - 1].index;
            }
            if (row > 0) {
                return rows[row - 1][rows[row - 1].length - 1].index;
            }
            return current;
        }
        if (key === 'ArrowDown') {
            if (row >= rows.length - 1) {
                return current;
            }
            const nextRow = rows[row + 1];
            return nextRow[Math.min(col, nextRow.length - 1)].index;
        }
        if (key === 'ArrowUp') {
            if (row <= 0) {
                return current;
            }
            const previousRow = rows[row - 1];
            return previousRow[Math.min(col, previousRow.length - 1)].index;
        }
        return current;
    }

    function moveIndex(rects, currentIndex, key, options = {}) {
        const items = normalizeRects(rects);
        if (items.length === 0) {
            return moveLinearIndex(currentIndex, key, Array.isArray(rects) ? rects.length : 0);
        }
        if (items.length === 1 || groupRectsIntoRows(items, options.rowTolerance).length <= 1) {
            return moveLinearIndex(currentIndex, key, items.length);
        }
        return moveGridIndex(items, currentIndex, key, options);
    }

    function describeKey(event, context = {}) {
        const key = event && typeof event.key === 'string' ? event.key : '';
        const shiftKey = !!(event && event.shiftKey);
        const listVisible = context.listVisible !== false;
        const cardFocused = context.cardFocused === true;
        const characterCount = Number.isInteger(context.characterCount) ? context.characterCount : 0;

        if (key === 'Escape') {
            return { action: 'close', preventDefault: true, stopPropagation: true };
        }
        if (key === 'Tab') {
            return {
                action: shiftKey ? 'trap-tab-backward' : 'trap-tab-forward',
                preventDefault: true,
                stopPropagation: true
            };
        }
        if (listVisible && characterCount > 0 && isNavigationKey(key)) {
            return { action: 'move', preventDefault: true, stopPropagation: true };
        }
        if (listVisible && cardFocused && isSelectKey(key)) {
            return { action: 'select', preventDefault: true, stopPropagation: true };
        }
        if (READER_NAV_KEYS.has(key) || READER_SHORTCUT_KEYS.has(key)) {
            return { action: 'block-reader', preventDefault: true, stopPropagation: true };
        }
        return { action: 'consume', preventDefault: false, stopPropagation: true };
    }

    return {
        clampIndex,
        cycleFocusIndex,
        describeKey,
        isNavigationKey,
        isSelectKey,
        moveGridIndex,
        moveIndex,
        moveLinearIndex
    };
});
