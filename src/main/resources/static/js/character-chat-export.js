(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
        return;
    }
    root.CharacterChatExport = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    'use strict';

    function asText(value) {
        if (value == null) {
            return '';
        }
        return String(value).trim();
    }

    function slugify(value, fallback) {
        const base = asText(value)
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, '-')
            .replace(/^-+|-+$/g, '');
        const clipped = base.slice(0, 48).replace(/-+$/g, '');
        return clipped || fallback || 'export';
    }

    function formatTimestamp(value) {
        if (value == null || value === '') {
            return '';
        }
        const date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) {
            return '';
        }
        return date.toISOString();
    }

    function speakerLabel(role, characterName) {
        const normalized = asText(role).toLowerCase();
        if (normalized === 'user') {
            return 'You';
        }
        if (normalized === 'character' || normalized === 'assistant') {
            return characterName || 'Character';
        }
        if (normalized === 'system') {
            return 'System';
        }
        return role ? String(role) : 'Speaker';
    }

    function hasDownloadableTranscript(messages) {
        if (!Array.isArray(messages) || messages.length === 0) {
            return false;
        }
        return messages.some((message) => asText(message && message.content).length > 0);
    }

    /** "October 21, 2026, 9:14 AM" in the reader's own time zone (or {@code timeZone}, for tests). */
    function formatReadableDate(value, timeZone) {
        const date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) {
            return '';
        }
        try {
            return date.toLocaleString('en-US', {
                dateStyle: 'long',
                timeStyle: 'short',
                ...(timeZone ? { timeZone } : {})
            });
        } catch (error) {
            return formatTimestamp(date);
        }
    }

    /** Header lines and speaker-labelled messages shared by every export format. */
    function buildTranscriptModel(options) {
        const opts = options && typeof options === 'object' ? options : {};
        const bookTitle = asText(opts.bookTitle) || 'Unknown book';
        const bookAuthor = asText(opts.bookAuthor);
        const characterName = asText(opts.characterName) || 'Character';
        const exportedAt = formatReadableDate(opts.exportedAt || Date.now(), opts.timeZone)
            || formatReadableDate(Date.now(), opts.timeZone);
        const chapterLabel = asText(opts.chapterLabel);
        const messages = Array.isArray(opts.messages) ? opts.messages : [];

        const details = [
            { label: 'Book', value: `${bookTitle}${bookAuthor ? ` (${bookAuthor})` : ''}` },
            { label: 'Character', value: characterName },
            { label: 'Date', value: exportedAt }
        ];
        if (chapterLabel) {
            details.push({ label: 'Reader position', value: chapterLabel });
        }
        const entries = [];
        for (const message of messages) {
            const content = asText(message && message.content);
            if (content) {
                entries.push({ speaker: speakerLabel(message && message.role, characterName), text: content });
            }
        }
        return {
            title: 'Character conversation (Classic Chat Reader)',
            documentTitle: `${characterName} - ${bookTitle}`,
            details,
            entries
        };
    }

    // Advance widths (1/1000 em) of Times-Roman and Times-Bold for WinAnsi codes 32-255, from
    // Adobe's Core 14 AFM files. Every PDF reader ships these fonts, so nothing is embedded.
    const TIMES_ROMAN_WIDTHS = [250,333,408,500,500,833,778,180,333,333,500,564,250,333,250,278,500,500,500,500,500,500,500,500,500,500,278,278,564,564,564,444,921,722,667,667,722,611,556,722,722,333,389,722,611,889,722,722,556,722,667,556,611,722,722,944,722,722,611,333,278,333,469,500,333,444,500,444,500,444,333,500,500,278,278,500,278,778,500,500,500,500,333,389,278,500,500,722,500,500,444,480,200,480,541,0,500,0,333,500,444,1000,500,500,333,1000,556,333,889,0,611,0,0,333,333,444,444,350,500,1000,333,980,389,333,722,0,444,722,250,333,500,500,500,500,200,500,333,760,276,500,564,333,760,333,400,564,300,300,333,500,453,250,333,300,310,500,750,750,750,444,722,722,722,722,722,722,889,667,611,611,611,611,333,333,333,333,722,722,722,722,722,722,722,564,722,722,722,722,722,722,556,500,444,444,444,444,444,444,667,444,444,444,444,444,278,278,278,278,500,500,500,500,500,500,500,564,500,500,500,500,500,500,500,500];
    const TIMES_BOLD_WIDTHS = [250,333,555,500,500,1000,833,278,333,333,500,570,250,333,250,278,500,500,500,500,500,500,500,500,500,500,333,333,570,570,570,500,930,722,667,722,722,667,611,778,778,389,500,778,667,944,722,778,611,778,722,556,667,722,722,1000,722,722,667,333,278,333,581,500,333,500,556,444,556,444,333,500,556,278,333,556,278,833,556,500,556,556,444,389,333,556,500,722,500,500,444,394,220,394,520,0,500,0,333,500,500,1000,500,500,333,1000,556,333,1000,0,667,0,0,333,333,500,500,350,500,1000,333,1000,389,333,722,0,444,722,250,333,500,500,500,500,220,500,333,747,300,500,570,333,747,333,400,570,300,300,333,556,540,250,333,300,330,500,750,750,750,500,722,722,722,722,722,722,1000,722,667,667,667,667,389,389,389,389,722,722,778,778,778,778,778,570,778,722,722,722,722,722,611,556,500,500,500,500,500,500,722,444,444,444,444,444,278,278,278,278,500,556,500,500,500,500,500,570,500,556,556,556,556,500,556,500];

    // Windows-1252 bytes 0x80-0x9F that hold typographic characters (curly quotes, dashes, ...).
    const WIN_ANSI_EXTRAS = {
        0x20AC: 0x80, 0x201A: 0x82, 0x0192: 0x83, 0x201E: 0x84, 0x2026: 0x85, 0x2020: 0x86,
        0x2021: 0x87, 0x02C6: 0x88, 0x2030: 0x89, 0x0160: 0x8A, 0x2039: 0x8B, 0x0152: 0x8C,
        0x017D: 0x8E, 0x2018: 0x91, 0x2019: 0x92, 0x201C: 0x93, 0x201D: 0x94, 0x2022: 0x95,
        0x2013: 0x96, 0x2014: 0x97, 0x02DC: 0x98, 0x2122: 0x99, 0x0161: 0x9A, 0x203A: 0x9B,
        0x0153: 0x9C, 0x017E: 0x9E, 0x0178: 0x9F
    };
    const LOOKALIKES = {
        0x2010: '-', 0x2011: '-', 0x2012: '-', 0x2015: '—', 0x2032: "'", 0x2033: '"',
        0x2002: ' ', 0x2003: ' ', 0x2009: ' ', 0x202F: ' ', 0x2212: '-'
    };

    function isDroppedCodePoint(cp) {
        // Emoji, pictographs, variation selectors and joiners: leave them out rather than print "?".
        return cp >= 0x1F000
            || (cp >= 0x2600 && cp <= 0x27BF)
            || (cp >= 0xFE00 && cp <= 0xFE0F)
            || cp === 0x200B || cp === 0x200C || cp === 0x200D || cp === 0xFEFF;
    }

    function winAnsiCode(cp) {
        if ((cp >= 0x20 && cp <= 0x7E) || (cp >= 0xA0 && cp <= 0xFF)) {
            return cp;
        }
        return WIN_ANSI_EXTRAS[cp] || 0;
    }

    /**
     * The text as a string of WinAnsi bytes (one char per byte) for the built-in PDF fonts.
     * Accented letters outside that set lose their accent; other unsupported characters become "?".
     */
    function toWinAnsi(value) {
        let out = '';
        for (const ch of String(value == null ? '' : value).replace(/\t/g, ' ')) {
            const cp = ch.codePointAt(0);
            if (cp < 0x20 || (cp >= 0x7F && cp < 0xA0) || isDroppedCodePoint(cp)) {
                continue;
            }
            const replacement = LOOKALIKES[cp];
            const direct = winAnsiCode(replacement ? replacement.codePointAt(0) : cp);
            if (direct) {
                out += String.fromCharCode(direct);
                continue;
            }
            const stripped = ch.normalize('NFKD').replace(/[̀-ͯ]/g, '');
            const fallback = stripped.length === 1 ? winAnsiCode(stripped.codePointAt(0)) : 0;
            out += String.fromCharCode(fallback || 0x3F);
        }
        return out;
    }

    function textWidth(winAnsi, widths, fontSize) {
        let units = 0;
        for (let i = 0; i < winAnsi.length; i += 1) {
            units += widths[winAnsi.charCodeAt(i) - 32] || 0;
        }
        return units * fontSize / 1000;
    }

    /**
     * Splits WinAnsi text into lines no wider than {@code maxWidth}; the first line has
     * {@code firstIndent} less room (for a bold speaker label). Overlong words are broken.
     */
    function wrapText(winAnsi, widths, fontSize, maxWidth, firstIndent) {
        const lines = [];
        let line = '';
        let room = maxWidth - (firstIndent || 0);
        const pushLine = () => {
            lines.push(line);
            line = '';
            room = maxWidth;
        };
        for (const word of winAnsi.split(/ +/).filter(Boolean)) {
            const candidate = line ? `${line} ${word}` : word;
            if (textWidth(candidate, widths, fontSize) <= room) {
                line = candidate;
                continue;
            }
            if (line) {
                pushLine();
            }
            let rest = word;
            while (textWidth(rest, widths, fontSize) > room) {
                let cut = 1;
                while (cut < rest.length && textWidth(rest.slice(0, cut + 1), widths, fontSize) <= room) {
                    cut += 1;
                }
                line = rest.slice(0, cut);
                pushLine();
                rest = rest.slice(cut);
            }
            line = rest;
        }
        lines.push(line);
        return lines;
    }

    function pdfHex(winAnsi) {
        let hex = '';
        for (let i = 0; i < winAnsi.length; i += 1) {
            hex += winAnsi.charCodeAt(i).toString(16).padStart(2, '0');
        }
        return `<${hex}>`;
    }

    /** A PDF text string (UTF-16BE with byte-order mark) for document metadata. */
    function pdfUnicodeString(value) {
        let hex = 'feff';
        const text = String(value == null ? '' : value);
        for (let i = 0; i < text.length; i += 1) {
            hex += text.charCodeAt(i).toString(16).padStart(4, '0');
        }
        return `<${hex}>`;
    }

    function fixed(n) {
        return Number(n.toFixed(2)).toString();
    }

    const PAGE_WIDTH = 612;
    const PAGE_HEIGHT = 792;
    const MARGIN = 72;
    const BODY_SIZE = 11;
    const BODY_LEADING = 15;
    const TITLE_SIZE = 15;

    /** Lays the transcript out on US Letter pages; returns each page's content-stream operators. */
    function layoutPdfPages(model) {
        const maxWidth = PAGE_WIDTH - 2 * MARGIN;
        const bottom = MARGIN;
        const pages = [];
        let ops = [];
        let y = PAGE_HEIGHT - MARGIN;
        const newPage = () => {
            pages.push(ops);
            ops = [];
            y = PAGE_HEIGHT - MARGIN;
        };
        const ensureRoom = (height) => {
            if (y - height < bottom && ops.length) {
                newPage();
            }
        };
        // One line: an optional bold run followed by a regular run.
        const writeLine = (boldRun, regularRun, size) => {
            ensureRoom(size === TITLE_SIZE ? TITLE_SIZE + 6 : BODY_LEADING);
            y -= size === TITLE_SIZE ? TITLE_SIZE + 6 : BODY_LEADING;
            let line = `BT ${fixed(MARGIN)} ${fixed(y)} Td`;
            if (boldRun) {
                line += ` /F2 ${size} Tf ${pdfHex(boldRun)} Tj`;
            }
            if (regularRun) {
                line += ` /F1 ${size} Tf ${pdfHex(regularRun)} Tj`;
            }
            ops.push(`${line} ET`);
        };
        const writeLabelled = (label, text) => {
            const boldLabel = toWinAnsi(`${label}: `);
            const indent = textWidth(boldLabel, TIMES_BOLD_WIDTHS, BODY_SIZE);
            const paragraphs = String(text).split(/\r?\n/);
            paragraphs.forEach((paragraph, index) => {
                const lines = wrapText(toWinAnsi(paragraph), TIMES_ROMAN_WIDTHS, BODY_SIZE, maxWidth,
                    index === 0 ? indent : 0);
                lines.forEach((line, lineIndex) => {
                    writeLine(index === 0 && lineIndex === 0 ? boldLabel : '', line, BODY_SIZE);
                });
            });
        };

        for (const line of wrapText(toWinAnsi(model.title), TIMES_BOLD_WIDTHS, TITLE_SIZE, maxWidth, 0)) {
            writeLine(line, '', TITLE_SIZE);
        }
        y -= 6;
        for (const detail of model.details) {
            writeLabelled(detail.label, detail.value);
        }
        y -= 8;
        ops.push(`0.6 G 0.75 w ${MARGIN} ${fixed(y)} m ${PAGE_WIDTH - MARGIN} ${fixed(y)} l S 0 G`);
        y -= 6;
        if (model.entries.length === 0) {
            writeLine('', toWinAnsi('(No messages in this conversation yet.)'), BODY_SIZE);
        }
        for (const entry of model.entries) {
            ensureRoom(BODY_LEADING * 2);
            y -= BODY_LEADING / 2;
            writeLabelled(entry.speaker, entry.text);
        }
        pages.push(ops);

        return pages.map((pageOps, index) => {
            const footer = toWinAnsi(`Page ${index + 1} of ${pages.length}`);
            const x = (PAGE_WIDTH - textWidth(footer, TIMES_ROMAN_WIDTHS, 9)) / 2;
            return pageOps.concat(`0.4 g BT /F1 9 Tf ${fixed(x)} ${MARGIN / 2} Td ${pdfHex(footer)} Tj ET 0 g`);
        });
    }

    /**
     * The conversation as a PDF (Uint8Array) for students to upload to a course dropbox and quote
     * in a paper. Uses the built-in Times fonts, so the text stays selectable and the file opens in
     * any browser without embedding fonts or loading a library.
     */
    function buildCharacterChatPdf(options) {
        const model = buildTranscriptModel(options);
        const pages = layoutPdfPages(model);

        // Objects: 1 catalog, 2 page tree, 3 regular font, 4 bold font, 5 info, then page/content pairs.
        const objects = [];
        const pageIds = pages.map((_, index) => 6 + index * 2);
        objects[1] = '<< /Type /Catalog /Pages 2 0 R >>';
        objects[2] = `<< /Type /Pages /Kids [${pageIds.map((id) => `${id} 0 R`).join(' ')}] /Count ${pages.length} >>`;
        objects[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding >>';
        objects[4] = '<< /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >>';
        objects[5] = `<< /Title ${pdfUnicodeString(model.documentTitle)} /Producer (Classic Chat Reader) >>`;
        pages.forEach((ops, index) => {
            const pageId = pageIds[index];
            const stream = ops.join('\n');
            objects[pageId] = `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${PAGE_WIDTH} ${PAGE_HEIGHT}]`
                + ` /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${pageId + 1} 0 R >>`;
            objects[pageId + 1] = `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`;
        });

        // Every char is a single byte (content uses hex strings), so string length is byte length.
        let pdf = '%PDF-1.4\n%\xE2\xE3\xCF\xD3\n';
        const offsets = [];
        for (let id = 1; id < objects.length; id += 1) {
            offsets[id] = pdf.length;
            pdf += `${id} 0 obj\n${objects[id]}\nendobj\n`;
        }
        const xrefOffset = pdf.length;
        pdf += `xref\n0 ${objects.length}\n0000000000 65535 f \n`;
        for (let id = 1; id < objects.length; id += 1) {
            pdf += `${String(offsets[id]).padStart(10, '0')} 00000 n \n`;
        }
        pdf += `trailer\n<< /Size ${objects.length} /Root 1 0 R /Info 5 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`;

        const bytes = new Uint8Array(pdf.length);
        for (let i = 0; i < pdf.length; i += 1) {
            bytes[i] = pdf.charCodeAt(i);
        }
        return bytes;
    }

    function buildCharacterChatFilename(options) {
        const opts = options && typeof options === 'object' ? options : {};
        const bookSlug = slugify(opts.bookTitle, 'book');
        const characterSlug = slugify(opts.characterName, 'character');
        const date = opts.exportedAt instanceof Date
            ? opts.exportedAt
            : new Date(opts.exportedAt || Date.now());
        const day = Number.isNaN(date.getTime())
            ? new Date().toISOString().slice(0, 10)
            : date.toISOString().slice(0, 10);
        return `character-chat_${bookSlug}_${characterSlug}_${day}.pdf`;
    }

    /** Saves bytes or text as a file through a temporary object URL. */
    function downloadFile(filename, data, mimeType) {
        const safeName = asText(filename) || 'download';
        const blob = new Blob([data == null ? '' : data], { type: mimeType || 'application/octet-stream' });
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = safeName;
        anchor.rel = 'noopener';
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        // Delay revoke so the browser can start the download.
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        return safeName;
    }

    /** Builds the conversation PDF and saves it; returns the filename. */
    function downloadCharacterChatPdf(options) {
        return downloadFile(buildCharacterChatFilename(options), buildCharacterChatPdf(options), 'application/pdf');
    }

    return {
        asText,
        slugify,
        speakerLabel,
        hasDownloadableTranscript,
        formatReadableDate,
        buildTranscriptModel,
        toWinAnsi,
        wrapText,
        buildCharacterChatPdf,
        buildCharacterChatFilename,
        downloadFile,
        downloadCharacterChatPdf
    };
});
