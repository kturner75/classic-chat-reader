# BL-023 QA Checklist (Mobile + Desktop Regression)

Last updated: 2026-08-17

This checklist validates BL-023 acceptance criteria for adaptive mobile behavior while protecting desktop keyboard workflows. It also covers core reader features: character chat, voice call, and My Chats.

## Test Matrix

- iOS Safari (iPhone simulator, narrow viewport)
- Android Chrome (phone viewport)
- Desktop Chrome or Safari (>= 1280px width)

## Preconditions

- Run latest local app build.
- Use a book with long title and enough chapters/paragraphs (for search + navigation checks).
- Ensure chapter has searchable text and at least one expected search hit.
- Sign in with a reader account (My Chats is account-only; signed-out landing hides the shelf).
- Use a book with at least one PRIMARY character and chat enabled.
- For voice: `voiceCallEnabled` / `voiceCallAvailable` on, and a browser that can grant the microphone (`getUserMedia` + AudioWorklet). Safari needs an explicit mic grant.

## Mobile Checklist (iOS Safari + Android Chrome)

- Open reader on phone viewport and confirm title remains visible in header with hamburger present.
- Open hamburger menu and verify panel is fully visible on-screen (not clipped off-left/off-right).
- Confirm desktop icon action cluster is hidden on mobile.
- In hamburger search, type text and verify menu does not close while typing.
- Tap `Search` (or press `Enter`) and verify the menu closes.
- Verify the search results panel appears after submit.
- Select a search result and verify reader jumps to the expected paragraph.
- Re-open hamburger and tap `Reader Preferences`; verify preferences panel opens every time.
- In `Reader Preferences`, verify no paragraph/search content overlays slider rows.
- Adjust font size, line height, and column gap; verify values update and content repaginates without breaking navigation.
- Change theme and verify immediate visual update.
- From hamburger, verify `Read Aloud` toggle and speed control both work.
- Verify `Speed Reading` action appears at the bottom of the hamburger menu.
- Verify highlight/note/bookmark actions from hamburger operate on current paragraph.
- Verify chapter list remains usable from touch controls.
- Verify bottom touch navigation (chapter/page/paragraph) works and buttons disable appropriately at bounds.
- Verify orientation change (portrait <-> landscape) keeps controls usable and content readable.

## Desktop Keyboard Regression Checklist

- Confirm desktop header search remains visible and usable.
- Verify `h`/`l` page navigation.
- Verify `j`/`k` paragraph navigation.
- Verify `H`/`L` chapter navigation.
- Verify `/` focuses search.
- Verify `c` opens chapter list.
- Verify `u`, `n`, `b`, `B` annotation flows.
- Verify `?` shortcuts overlay.
- Verify chapter list keyboard behavior still works (`ArrowUp`, `ArrowDown`, `Enter`, `Escape`).
- Verify opening/closing overlays does not leave reader in broken focus state.
- Verify search result navigation still highlights expected terms and lands on target paragraph.

## Core Features (Chat, Voice Call, My Chats)

Run on the same matrix: iOS Safari, Android Chrome, Desktop Chrome/Safari.

### Character chat (in-reader)

- Open a PRIMARY character and tap `Chat with this character`; modal is fully on-screen (not clipped, composer not under the keyboard).
- Send a message; user turn and character reply both appear; composer stays usable.
- Press `Enter` to send on desktop; `Shift+Enter` inserts a newline.
- Force a send failure (airplane mode or stop the server mid-send); verify Retry appears and a retry does **not** duplicate the user message.
- Close the modal (`×` / backdrop / `Esc` on desktop) and reopen; prior turns still show for the signed-in account.
- Download is disabled until there is a conversation, then produces a transcript.
- Closing chat does not break reader pagination, hamburger, or keyboard shortcuts.

### Voice call (Call Character)

- From the chat header, the voice-call button is visible for a PRIMARY character when voice is available; hidden when the browser cannot support calls.
- Start a call; Connecting → live status; portrait and name are visible; captions area is readable on a narrow viewport.
- Speak and confirm two-way audio plus captions updating.
- Mute toggles and stays obvious; End call hangs up and returns to chat (or My Chats conversation) without a stuck overlay.
- Deny microphone permission; error is visible and the reader is still usable.
- After a successful call, reopen chat and confirm persisted call turns appear (failed persist shows a retryable error, not a silent drop).
- Orientation change mid-call keeps mute/end reachable.

### My Chats

- Signed out: landing has **no** My Chats shelf; `/my-chats` shows sign-in and returns to `/my-chats` after login.
- Signed in, empty: landing empty copy + “Find a character”; dedicated page: “You haven’t started any character chats yet.”
- After at least one user chat message: landing shows up to 4 cards (portrait, character, book, preview, relative time) and **View all**.
- Open `/my-chats`: list newest-first; search; book/character filters (type-ahead, no-results state); date filters; Clear filters; Load more if needed.
- Resume a card: full-page conversation (not the in-reader modal); history oldest-first; spoiler/chapter label before send is enabled.
- Send from the conversation page; failed reply keeps the user turn and Retry does not duplicate it.
- **Open book** lands on the stored chapter/paragraph, not generic last-read progress.
- Voice call from the My Chats conversation works the same as in-reader (mic, mute, end, captions).
- Browser Back from a conversation returns to the list (filters/scroll preserved if possible).
- Error: “My Chats couldn’t load.” + Retry; other landing modules stay usable.

My Chats v1 is text character chats only. Voice-call-only sessions do not get their own list items; call turns show up on the existing `(user, book, character)` thread after a user message exists.

## Sign-off Template

- iOS Safari: Pass/Fail
- Android Chrome: Pass/Fail
- Desktop regression: Pass/Fail
- Character chat: Pass/Fail
- Voice call: Pass/Fail
- My Chats: Pass/Fail
- Notes / defects:
