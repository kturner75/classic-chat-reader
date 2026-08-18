const test = require('node:test');
const assert = require('node:assert/strict');

const {
    gutenbergEbookId,
    gutenbergEbookUrl,
    gutenbergSourceLabel,
    gutenbergSourceAccessibleName,
    renderGutenbergSourceLink
} = require('../../main/resources/static/js/library-gutenberg-source.js');

test('resolves a positive Gutenberg ebook number', () => {
    assert.equal(gutenbergEbookId({ gutenbergId: 1513 }), '1513');
    assert.equal(gutenbergEbookId({ gutenbergId: '1513' }), '1513');
});

test('ignores missing or non-Gutenberg ebook numbers', () => {
    assert.equal(gutenbergEbookId(null), '');
    assert.equal(gutenbergEbookId({}), '');
    assert.equal(gutenbergEbookId({ gutenbergId: 0 }), '');
    assert.equal(gutenbergEbookId({ gutenbergId: -3 }), '');
    assert.equal(gutenbergEbookId({ gutenbergId: 'romeo' }), '');
});

test('builds the Project Gutenberg ebook URL', () => {
    assert.equal(gutenbergEbookUrl(1513), 'https://www.gutenberg.org/ebooks/1513');
    assert.equal(gutenbergEbookUrl(''), '');
});

test('uses compact visible label and a longer accessible name', () => {
    assert.equal(gutenbergSourceLabel('1513'), 'Gutenberg #1513');
    assert.equal(gutenbergSourceLabel('64317'), 'Gutenberg #64317');
    assert.equal(
        gutenbergSourceAccessibleName('1513', 'Romeo and Juliet'),
        'Gutenberg #1513. Project Gutenberg source for Romeo and Juliet.'
    );
    assert.equal(
        gutenbergSourceAccessibleName('64317', 'The Great Gatsby'),
        'Gutenberg #64317. Project Gutenberg source for The Great Gatsby.'
    );
    assert.equal(
        gutenbergSourceAccessibleName('1513', '  '),
        'Gutenberg #1513. Project Gutenberg source.'
    );
    assert.ok(gutenbergSourceAccessibleName('64317', 'The Great Gatsby').includes('Gutenberg #64317'));
});

test('renders a library-card source link for Gutenberg titles', () => {
    const html = renderGutenbergSourceLink({
        title: 'Romeo and Juliet',
        gutenbergId: 1513
    });

    assert.match(html, /class="book-item-gutenberg-source"/);
    assert.match(html, /href="https:\/\/www\.gutenberg\.org\/ebooks\/1513"/);
    assert.match(html, /target="_blank"/);
    assert.match(html, /rel="noopener noreferrer"/);
    assert.match(html, /aria-label="Gutenberg #1513. Project Gutenberg source for Romeo and Juliet."/);
    assert.match(html, />Gutenberg #1513</);
    assert.doesNotMatch(html, /\[Gutenberg /);
});

test('does not render a source link without a Gutenberg ebook id', () => {
    assert.equal(renderGutenbergSourceLink({ title: 'Manual Reader', source: 'manual' }), '');
    assert.equal(renderGutenbergSourceLink({ title: 'Unknown', gutenbergId: null }), '');
});

test('escapes title text in the accessible name', () => {
    const html = renderGutenbergSourceLink({
        title: 'Pride & Prejudice <script>',
        gutenbergId: 1342
    });

    assert.match(html, /aria-label="Gutenberg #1342. Project Gutenberg source for Pride &amp; Prejudice &lt;script&gt;."/);
    assert.doesNotMatch(html, /<script>/);
});
