const test = require('node:test');
const assert = require('node:assert/strict');

const {
    hasDownloadableTranscript,
    formatCharacterChatTranscript,
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

test('formatCharacterChatTranscript builds a plain-text transcript a student can upload and quote', () => {
    const text = formatCharacterChatTranscript({
        bookTitle: 'Pride and Prejudice',
        bookAuthor: 'Austen, Jane',
        characterName: 'Elizabeth Bennet',
        chapterLabel: 'Chapter I.',
        exportedAt: '2026-07-17T17:05:00.000Z',
        timeZone: 'America/Chicago',
        messages: [
            { role: 'user', content: 'What do you think of Mr. Darcy?' },
            { role: 'character', content: 'He is proud, to be sure.' },
            { role: 'assistant', content: 'Also reserved.' },
            { role: 'user', content: '   ' }
        ]
    });

    assert.match(text, /^Character conversation \(Classic Chat Reader\)\n/);
    assert.match(text, /\nBook: Pride and Prejudice \(Austen, Jane\)\n/);
    assert.match(text, /\nCharacter: Elizabeth Bennet\n/);
    // Node's ICU data decides between "July 17, 2026 at 12:05 PM" and "July 17, 2026, 12:05 PM".
    assert.match(text, /\nDate: July 17, 2026(?: at|,) 12:05 PM\n/);
    assert.match(text, /\nReader position: Chapter I\.\n/);
    assert.match(text, /\nYou: What do you think of Mr\. Darcy\?\n/);
    assert.match(text, /\nElizabeth Bennet: He is proud, to be sure\.\n/);
    assert.match(text, /\nElizabeth Bennet: Also reserved\.\n/);
    assert.doesNotMatch(text, /\*\*|^#|^- /m, 'no Markdown marks');
});

test('formatCharacterChatTranscript says so when there are no messages', () => {
    assert.match(formatCharacterChatTranscript({ messages: [] }), /\(No messages in this conversation yet\.\)/);
});

test('buildCharacterChatFilename uses slugged book and character', () => {
    const name = buildCharacterChatFilename({
        bookTitle: 'A Christmas Carol in Prose; Being a Ghost Story of Christmas',
        characterName: 'Ebenezer Scrooge',
        exportedAt: '2026-07-17T15:30:00.000Z'
    });
    assert.equal(
        name,
        'character-chat_a-christmas-carol-in-prose-being-a-ghost-story-o_ebenezer-scrooge_2026-07-17.txt'
    );
});

test('slugify falls back for empty input', () => {
    assert.equal(slugify('', 'book'), 'book');
    assert.equal(speakerLabel('user'), 'You');
    assert.equal(speakerLabel('character', 'Darcy'), 'Darcy');
});
