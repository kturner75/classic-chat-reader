# Subprocessors and the student AI hold (BL-043.3)

This page lists every third party that receives reader data, which of them see **student content**
(what an enrolled student types or says), and the contract terms that do or do not cover that
content. It is the engineering tracker for BL-043.3. It is not legal text: the DPA and privacy
notice wording come from counsel or the partner school (BL-043.11).

Vendor terms below were checked against the vendors' public pages on **2026-09-23**. Re-check them
before relying on this page for a school agreement.

## Current decision: enrolled students get no AI chat

Production calls xAI with a **SuperGrok subscription OAuth token** (`ai.xai.oauth.*`), not a
pay-per-token API account. xAI's no-training, 30-day-retention and DPA terms are written for the
API and business services. Subscription traffic is not clearly covered by them, and a paid API
account is not funded yet. Until one is, **no student content goes to an AI provider**:

- `classroom.ferpa.student-ai-covered` (env `CLASSROOM_STUDENT_AI_COVERED`, default **false**).
- While it is false, any signed-in user with an `ACTIVE` enrollment in an `ACTIVE` term of a live
  class section is **held** (`ClassroomContextService.isStudentAiHeld`). Teachers, and readers who
  are not in a class, are not held.
- Held students are refused with `403` (`CLASSROOM_AI_HELD`, "AI chat isn't available for classes
  yet.") **before** any provider call, on:
  - `POST /api/characters/{id}/chat`: character chat
  - `POST /api/characters/{id}/call-session`: voice call (no xAI realtime token is minted)
  - `POST /api/recaps/book/{bookId}/chat`: recap chat
  - `POST /api/reading-buddy/chat` and `/check-comment`: Reading Buddy
- The classroom context (`/api/classroom/context`) reports `chatEnabled` and `readingBuddyEnabled`
  as false for a held student, so the reader and My Chats hide chat, voice, recap chat and Reading
  Buddy. The class banner lists them under "Teacher controls active… off for this class", even
  though the teacher's own settings still show them on. Tell pilot teachers this.
- Everything else still works for students: reading, quizzes, cached recaps, read-aloud (cached
  audio), illustrations, character profiles, assignments.

An enrolled student who signs out and chats anonymously is not held. That chat is not linked to
their account or class, so it is not an education record.

### Lifting the hold

Only set `CLASSROOM_STUDENT_AI_COVERED=true` once **all** of these are true:

1. A pay-per-token **xAI API account** is funded, and production sends student traffic with its API
   key. Remove the OAuth refresh token from production (`XAI_OAUTH_REFRESH_TOKEN` unset, or
   `ai.xai.oauth.enabled=false`); otherwise the subscription token is still preferred.
2. The xAI DPA has been reviewed and accepted for that account. Decide whether to ask xAI for Zero
   Data Retention (enterprise, via sales).
3. The partner school has signed off (the school-side agreement and notice, BL-043.11).
4. This page is updated with the date and the account used.

## Subprocessors

| Vendor | What it receives | Student content? | In production |
| --- | --- | --- | --- |
| **xAI** (Grok API) | Character chat, recap chat, Reading Buddy messages and memory summaries; live microphone audio during voice calls (the browser connects straight to xAI with a short-lived token); book text for read-aloud, character and image generation | **Yes**: chat text and voice audio | Chat and voice **on**, held for enrolled students. Reading Buddy off (`reading-buddy.enabled=false`). Read-aloud and generation are cache-only (`tts.cache-only`, `generation.cache-only`), so no calls |
| **OpenAI** | Same chat and reasoning prompts, when configured as the provider (`ai.chat.provider=openai` / `ai.reasoning.provider=openai`) | Yes, if selected | **Not used**: both providers default to `xai` |
| **DigitalOcean** | App server, managed PostgreSQL (every account and classroom record), Spaces CDN (public book covers, portraits, illustrations only) | **Yes**: stores all education records | Yes |
| **Google** | Google sign-in (email, name, Google account id) when enabled; Google Fonts CSS and font files, which the browser fetches (IP address and user agent) | No education records | Sign-in only if `ACCOUNT_AUTH_GOOGLE_ENABLED`; fonts on every page |
| Ollama | Local model server | n/a (no third party) | Not used |

No analytics, error-tracking or email vendors are in the app (checked 2026-09-23: no Sentry,
analytics SDK, or mail sender in `pom.xml`, server code or static JS).

### Vendor terms (as published, 2026-09-23)

- **xAI API:** no training on API inputs or outputs without explicit permission; requests and
  responses are kept 30 days (encrypted) for abuse auditing, then deleted; Zero Data Retention is
  an enterprise option through sales, and with it voice conversations are not persisted. A Data
  Processing Addendum for API and business services is published at
  <https://x.ai/legal/data-processing-addendum>. Consumer Grok chats follow separate terms, where
  training depends on the user's setting. Source: <https://docs.x.ai/developers/faq/security>.
- **OpenAI API:** no training on API data by default; 30-day abuse-monitoring retention; Zero Data
  Retention / Modified Abuse Monitoring need OpenAI's approval; a DPA is offered to API customers.
  Source: <https://developers.openai.com/api/docs/guides/your-data>.
- **DigitalOcean:** the Data Processing Agreement is part of the customer Terms of Service. It is
  written for GDPR, UK and California law, and FERPA is not named, so the school may want its own
  terms. Source: <https://www.digitalocean.com/legal/data-processing-agreement>.

## Minimization: what goes into AI prompts

Checked in code on 2026-09-23 (`OpenAiLlmProvider`, `XaiLlmProvider`, `CharacterPersonaPromptBuilder`,
`ReadingBuddyPromptBuilder`, `ChapterRecapChatService`, `CharacterVoiceCallService`):

- Provider requests carry **no user id, email, name, class, term or school**. The request body is
  the model, sampling settings and the prompt. There is no OpenAI `user` or `safety_identifier` field.
- Prompts contain the book and character context, the reader's position, and a bounded slice of
  the conversation: the last 10 character-chat or voice messages (`character.chat.max-context-messages`,
  `voice.call.max-context-messages`); for Reading Buddy, the last 12 messages plus a rolling
  summary of up to 1,500 characters.
- Reading Buddy builds history from the server and ignores what the client sends. **Character chat
  and voice still trust client-sent history** (BL-043.10). This matters once the hold is lifted.
- Teacher quiz authoring sends teacher-written questions and book text, not student records.

## Follow-ups

- BL-043.10: server-authoritative character chat history, before lifting the hold.
- BL-043.11: privacy / FERPA notice that links this subprocessor list for the pilot.
- BL-043.9: keep provider error bodies (which can echo prompts) out of logs.
- Self-host EB Garamond to stop sending every reader's IP address to Google Fonts (optional).
