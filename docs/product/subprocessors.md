# Subprocessors and the student AI hold (BL-043.3)

This page lists every third party that receives reader data, which of them see **student content**
(what an enrolled student types or says), and the contract terms that do or do not cover that
content. It is the engineering tracker for BL-043.3. It is not legal text: the DPA and privacy
notice wording come from counsel or the partner school (BL-043.11).

Vendor terms below were checked against the vendors' public pages on **2026-09-23**. Re-check them
before relying on this page for a school agreement.

## Current decision (2026-10-31): student chat on the OpenAI API, voice calls off

The pilot teacher can't use CCR with students until the FERPA gaps close. The subscription path
below was the biggest one, so on 2026-10-31 the owner moved student AI off it:

- **Chat goes to the OpenAI API.** Production defaults `ai.chat.provider` to `openai` and the model
  to `gpt-5.4-mini` (`application-prod.properties`). The owner funds the account personally:
  $50 prepaid, auto-recharge off, so spending stops at the balance. This covers character chat,
  recap chat and Reading Buddy (still off). With no `CCR_OPENAI_API_KEY`, chat reports unavailable
  and does not fall back to xAI.
- **OpenAI org data controls** (set by the owner): API call logging disabled, and every "share with
  OpenAI" option (feedback, evals and fine-tuning, inputs and outputs) disabled. Chat Completions
  calls are not stored unless `store=true` is sent, and the app never sends it. Abuse-monitoring
  retention (up to 30 days) still applies; Zero Data Retention needs OpenAI's approval.
- **Voice calls are off.** They only run on xAI realtime and would still stream student audio to
  the subscription. Production keeps `VOICE_CALL_ENABLED` unset (default false), which hides the call
  button and refuses `POST /api/characters/{id}/call-session` for everyone.
- `CLASSROOM_STUDENT_AI_COVERED=true` stays, so enrolled students get text chat.
- Still to do: accept OpenAI's DPA for the account, the school-side agreement and notice
  (BL-043.11), and BL-043.10.

## Earlier decision (2026-10-21, superseded): hold lifted, students chat on the subscription

The pilot has no school funding, and the owner will not pay for a pay-per-token xAI API account,
so the conditions under "Lifting the hold" below were **not** met. To let the Comp 2 pilot students
(Northanger Abbey) use character chat and voice, the owner chose on 2026-10-21 to lift the hold
anyway and run student AI on the **SuperGrok subscription**:

- Production sets `CLASSROOM_STUDENT_AI_COVERED=true`, so enrolled students get character chat,
  voice calls and recap chat. Reading Buddy stays off (`reading-buddy.enabled=false`).
- `XAI_API_KEY` is commented out on production (same day), so every xAI call uses the subscription
  OAuth token and nothing falls back to a billed key. When the subscription token fails or its
  allotment runs out, AI features error out instead.
- What this means: student chat text and voice audio reach xAI under the subscription's terms, which
  the API's no-training, 30-day-retention and DPA terms do not clearly cover (see "Vendor terms").
  No DPA is in place, and there is no school-side agreement (BL-043.11). Tell the pilot teacher.
- BL-043.10 (character chat and voice trust client-sent history) is still open.
- To put the hold back: remove `CLASSROOM_STUDENT_AI_COVERED` from `app.env` and restart. Enrolled
  students then lose chat and voice again, as described next.

## How the hold works

Production calls xAI with a **SuperGrok subscription OAuth token** (`ai.xai.oauth.*`), not a
pay-per-token API account. xAI's no-training, 30-day-retention and DPA terms are written for the
API and business services, and subscription traffic is not clearly covered by them. With the hold
on, **no student content goes to an AI provider**:

- `classroom.ferpa.student-ai-covered` (env `CLASSROOM_STUDENT_AI_COVERED`, default **false**).
- While it is false, any signed-in user with an `ACTIVE` enrollment in an `ACTIVE` term of a live
  class section is **held** (`ClassroomContextService.isStudentAiHeld`). The hold is per account:
  someone who teaches one class and is enrolled as a student in another is held everywhere, including
  when their teacher context is showing. Teachers with no student enrollment, and readers who are not
  in a class, are not held.
- Held students are refused with `403` (`CLASSROOM_AI_HELD`, "AI chat isn't available for classes
  yet.") **before** any provider call, on:
  - `POST /api/characters/{id}/chat`: character chat
  - `POST /api/characters/{id}/call-session`: voice call (no xAI realtime token is minted)
  - `POST /api/recaps/book/{bookId}/chat`: recap chat
  - `POST /api/reading-buddy/chat` and `/check-comment`: Reading Buddy
- The classroom context (`/api/classroom/context`) reports `chatEnabled` and `readingBuddyEnabled`
  as false for a held account, whichever membership it shows, so the reader and My Chats hide chat,
  voice, recap chat and Reading Buddy. My Chats' send and continue routes check these flags before
  calling the provider, so they refuse held accounts too (`CHAT_UNAVAILABLE`, reason
  `CLASSROOM_POLICY`). The class banner lists them under "Teacher controls active… off for this class", even
  though the teacher's own settings still show them on. Tell pilot teachers this.
- Everything else still works for students: reading, quizzes, cached recaps, read-aloud (cached
  audio), illustrations, character profiles, assignments.

An enrolled student who signs out and chats anonymously is not held. That chat is not linked to
their account or class, so it is not an education record.

### Lifting the hold

The intended bar for setting `CLASSROOM_STUDENT_AI_COVERED=true` (not met by the 2026-10-21
decision above) is that **all** of these are true:

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
| **xAI** (Grok API) | Live microphone audio during voice calls (the browser connects straight to xAI with a short-lived token); chat prompts when `ai.chat.provider=xai`; book text for read-aloud, character and image generation; teacher quiz authoring | Only if voice calls or xAI chat are turned on | Since 2026-10-31: **no student content**. Voice calls off, chat on OpenAI. Read-aloud generates audio live (`TTS_CACHE_ONLY=false` on production); image and other generation is cache-only (`generation.cache-only`). Reasoning stays on xAI |
| **OpenAI** (API) | Character chat, recap chat, Reading Buddy messages and memory summaries (`ai.chat.provider=openai`) | **Yes**: chat text | Since 2026-10-31: chat for everyone, enrolled students included, on `gpt-5.4-mini`. Reading Buddy off |
| **DigitalOcean** | App server, managed PostgreSQL (every account and classroom record), Spaces CDN (public book covers, portraits, illustrations only) | **Yes**: stores all education records | Yes |
| **Google** | Google sign-in (email, name, Google account id) when enabled; Google Fonts CSS and font files, which the browser fetches (IP address and user agent) | No education records | Sign-in only if `ACCOUNT_AUTH_GOOGLE_ENABLED`; fonts on every page |
| **Resend** | Password-reset emails (recipient address and the reset link), `TransactionalEmailSender` | Email address only | Yes, since #199 |
| Ollama | Local model server | n/a (no third party) | Not used |

No analytics or error-tracking vendors are in the app (checked 2026-09-23: no Sentry or analytics
SDK in `pom.xml`, server code or static JS). Resend was added later for password reset.

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

- BL-043.10: server-authoritative character chat history. Meant to land before lifting the hold;
  still open now that the hold is lifted.
- BL-043.11: privacy / FERPA notice that links this subprocessor list for the pilot.
- BL-043.9: keep provider error bodies (which can echo prompts) out of logs.
- Self-host EB Garamond to stop sending every reader's IP address to Google Fonts (optional).
