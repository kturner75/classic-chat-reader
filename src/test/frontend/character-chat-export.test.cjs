const test = require('node:test');
const assert = require('node:assert/strict');

const {
    hasDownloadableTranscript,
    buildCharacterChatPdf,
    buildCharacterChatFilename,
    toWinAnsi,
    wrapText,
    speakerLabel,
    slugify
} = require('../../main/resources/static/js/character-chat-export.js');

const latin1 = (bytes) => Buffer.from(bytes).toString('latin1');

/** The text a reader would select on each page: one string per text line (BT ... ET), in order. */
function pdfTextLines(bytes) {
    const decoder = new TextDecoder('windows-1252');
    const lines = [];
    for (const [, stream] of latin1(bytes).matchAll(/stream\n([\s\S]*?)\nendstream/g)) {
        for (const [, block] of stream.matchAll(/BT([\s\S]*?)ET/g)) {
            const runs = [...block.matchAll(/<([0-9a-f]*)> Tj/g)]
                .map(([, hex]) => decoder.decode(Buffer.from(hex, 'hex')));
            lines.push(runs.join(''));
        }
    }
    return lines;
}

const SAMPLE = {
    bookTitle: 'Pride and Prejudice',
    bookAuthor: 'Austen, Jane',
    characterName: 'Elizabeth Bennet',
    chapterLabel: 'Chapter I.',
    exportedAt: '2026-07-17T17:05:00.000Z',
    timeZone: 'America/Chicago',
    messages: [
        { role: 'user', content: 'What do you think of Mr. Darcy?' },
        { role: 'character', content: 'He is “proud,” to be sure — and I’m not sorry.' },
        { role: 'assistant', content: 'Also reserved.' },
        { role: 'user', content: '   ' }
    ]
};

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

test('buildCharacterChatPdf is a well-formed PDF with a correct cross-reference table', () => {
    const pdf = latin1(buildCharacterChatPdf(SAMPLE));
    assert.match(pdf, /^%PDF-1\.4\n/);
    assert.match(pdf, /%%EOF\n$/);
    const startxref = Number(pdf.match(/startxref\n(\d+)\n/)[1]);
    assert.equal(pdf.slice(startxref, startxref + 4), 'xref');
    const entries = [...pdf.slice(startxref).matchAll(/^(\d{10}) 00000 n $/gm)].map((m) => Number(m[1]));
    assert.ok(entries.length >= 7);
    entries.forEach((offset, index) => {
        assert.equal(pdf.slice(offset, offset + `${index + 1} 0 obj`.length), `${index + 1} 0 obj`);
    });
    for (const [, length, body] of pdf.matchAll(/<< \/Length (\d+) >>\nstream\n([\s\S]*?)\nendstream/g)) {
        assert.equal(body.length, Number(length));
    }
    assert.match(pdf, /\/BaseFont \/Times-Roman \/Encoding \/WinAnsiEncoding/);
    assert.doesNotMatch(pdf, /\/Image|\/XObject/, 'text, not a picture of text');
});

test('buildCharacterChatPdf holds the book, character, date and every message with speaker labels', () => {
    const lines = pdfTextLines(buildCharacterChatPdf(SAMPLE));
    assert.equal(lines[0], 'Character conversation (Classic Chat Reader)');
    assert.ok(lines.includes('Book: Pride and Prejudice (Austen, Jane)'));
    assert.ok(lines.includes('Character: Elizabeth Bennet'));
    // Node's ICU data decides between "July 17, 2026 at 12:05 PM" and "July 17, 2026, 12:05 PM".
    assert.ok(lines.some((line) => /^Date: July 17, 2026(?: at|,) 12:05 PM$/.test(line)), lines.join('\n'));
    assert.ok(lines.includes('Reader position: Chapter I.'));
    assert.ok(lines.includes('You: What do you think of Mr. Darcy?'));
    assert.ok(lines.includes('Elizabeth Bennet: He is “proud,” to be sure — and I’m not sorry.'));
    assert.ok(lines.includes('Elizabeth Bennet: Also reserved.'));
    assert.equal(lines.filter((line) => line.startsWith('You:')).length, 1, 'blank messages are skipped');
    assert.equal(lines.at(-1), 'Page 1 of 1');
});

test('buildCharacterChatPdf wraps long messages and continues onto numbered pages', () => {
    const long = Array.from({ length: 60 }, (_, i) => `Sentence number ${i + 1} about Bath and the Pump Room.`).join(' ');
    const messages = Array.from({ length: 8 }, (_, i) => ({ role: i % 2 ? 'character' : 'user', content: long }));
    const pdf = buildCharacterChatPdf({ ...SAMPLE, messages });
    const lines = pdfTextLines(pdf);
    const pageCount = Number(latin1(pdf).match(/\/Type \/Pages .* \/Count (\d+)/)[1]);
    assert.ok(pageCount > 1);
    assert.ok(lines.includes(`Page ${pageCount} of ${pageCount}`));
    const bodyText = lines.filter((line) => !/^Page \d+ of \d+$/.test(line)).join(' ');
    assert.ok(bodyText.includes('Sentence number 60 about Bath and the Pump Room.'));
});

test('buildCharacterChatPdf says so when there are no messages', () => {
    assert.ok(pdfTextLines(buildCharacterChatPdf({ messages: [] }))
        .includes('(No messages in this conversation yet.)'));
});

test('toWinAnsi keeps typographic punctuation, strips unsupported accents and drops emoji', () => {
    const decode = (text) => new TextDecoder('windows-1252').decode(Buffer.from(toWinAnsi(text), 'latin1'));
    assert.equal(decode('café ‘hi’ … €5'), 'café ‘hi’ … €5');
    assert.equal(decode('Dvořák'), 'Dvorák');
    assert.equal(decode('thanks \u{1F60A}️!'), 'thanks !');
    assert.equal(decode('Ω'), '?');
    assert.equal(decode('a\tb\u0007c'), 'a bc');
});

test('wrapText breaks lines at the width limit and splits words longer than a line', () => {
    const widths = new Array(224).fill(500);
    assert.deepEqual(wrapText('aa bb cc', widths, 10, 24, 0), ['aa', 'bb', 'cc']);
    assert.deepEqual(wrapText('aa bb cc', widths, 10, 35, 0), ['aa bb', 'cc']);
    assert.deepEqual(wrapText('abcdefghij', widths, 10, 20, 0), ['abcd', 'efgh', 'ij']);
    assert.deepEqual(wrapText('aa bb', widths, 10, 50, 30), ['aa', 'bb']);
});

test('buildCharacterChatFilename uses slugged book and character', () => {
    const name = buildCharacterChatFilename({
        bookTitle: 'A Christmas Carol in Prose; Being a Ghost Story of Christmas',
        characterName: 'Ebenezer Scrooge',
        exportedAt: '2026-07-17T15:30:00.000Z'
    });
    assert.equal(
        name,
        'character-chat_a-christmas-carol-in-prose-being-a-ghost-story-o_ebenezer-scrooge_2026-07-17.pdf'
    );
});

test('slugify falls back for empty input', () => {
    assert.equal(slugify('', 'book'), 'book');
    assert.equal(speakerLabel('user'), 'You');
    assert.equal(speakerLabel('character', 'Darcy'), 'Darcy');
});
