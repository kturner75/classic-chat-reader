const test = require('node:test');
const assert = require('node:assert/strict');

const {
    hasDownloadableTranscript,
    formatCharacterChatMarkdown,
    buildCharacterChatFilename,
    speakerLabel,
    slugify
} = require('../../main/resources/static/js/character-chat-export.js');

test('hasDownloadableTranscript is false for empty history', () => {
    assert.equal(hasDownloadableTranscript([]), false);
    assert.equal(hasDownloadableTranscript([{ role: 'user', content: '   ' }]), false);
});

test('hasDownloadableTranscript is true when any message has content', () => {
    assert.equal(hasDownloadableTranscript([
        { role: 'user', content: 'Hello' },
        { role: 'character', content: 'Hi there' }
    ]), true);
});

test('formatCharacterChatMarkdown builds readable show-and-tell transcript', () => {
    const markdown = formatCharacterChatMarkdown({
        bookTitle: 'Pride and Prejudice',
        bookAuthor: 'Austen, Jane',
        characterName: 'Elizabeth Bennet',
        chapterLabel: 'Chapter I.',
        exportedAt: '2026-07-17T12:00:00.000Z',
        messages: [
            { role: 'user', content: 'What do you think of Mr. Darcy?' },
            { role: 'character', content: 'He is proud, to be sure.' },
            { role: 'assistant', content: 'Also reserved.' }
        ]
    });

    assert.match(markdown, /^# Character conversation\n/);
    assert.match(markdown, /- Book: Pride and Prejudice \(Austen, Jane\)/);
    assert.match(markdown, /- Character: Elizabeth Bennet/);
    assert.match(markdown, /- Exported: 2026-07-17T12:00:00.000Z/);
    assert.match(markdown, /- Reader position: Chapter I\./);
    assert.match(markdown, /\*\*You:\*\* What do you think of Mr\. Darcy\?/);
    assert.match(markdown, /\*\*Elizabeth Bennet:\*\* He is proud, to be sure\./);
    assert.match(markdown, /\*\*Elizabeth Bennet:\*\* Also reserved\./);
});

test('buildCharacterChatFilename uses slugged book and character', () => {
    const name = buildCharacterChatFilename({
        bookTitle: 'A Christmas Carol in Prose; Being a Ghost Story of Christmas',
        characterName: 'Ebenezer Scrooge',
        exportedAt: '2026-07-17T15:30:00.000Z'
    });
    assert.equal(
        name,
        'character-chat_a-christmas-carol-in-prose-being-a-ghost-story-o_ebenezer-scrooge_2026-07-17.md'
    );
});

test('slugify falls back for empty input', () => {
    assert.equal(slugify('', 'book'), 'book');
    assert.equal(speakerLabel('user'), 'You');
    assert.equal(speakerLabel('character', 'Darcy'), 'Darcy');
});
