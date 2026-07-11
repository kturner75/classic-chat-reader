# Classic Chat Reader — Classroom Pilot Pitch

| Field | Value |
| --- | --- |
| **Audience** | Educator partners, department/AI-grant reviewers |
| **Product** | [Classic Chat Reader](https://classicchatreader.com) |
| **Date** | 2026-07-11 |
| **Status** | Partner-facing draft (update after each Thursday touchpoint) |
| **Primary partner** | Jessica Evans (literature faculty) |
| **Internal tracking** | `BL-025` (classroom), `BL-042` (usage/cost), `BL-043`/`BL-044` (FERPA/ADA), `BL-045` (onboarding docs) |

---

## One-sentence pitch

**Classic Chat Reader** is a distraction-free reader for public-domain literature that uses **optional, teacher-controlled AI** to deepen student engagement with the text—through quizzes, a reading companion, and character conversation—without turning the course into a generic chatbot class.

---

## The opportunity

Many AI classroom tools are chat-first. Literature students need **text-first** experiences: assigned chapters, comprehension checks, discussion sparks, and teacher control over which AI surfaces are allowed.

Classic Chat Reader already provides:

- A clean digital reading experience for classics
- Chapter quizzes with citation-backed feedback
- Optional AI surfaces: chapter recap, character chat, **character voice calls**, and **Reading Buddy** (meta-persona companion, not an in-story character)
- Classroom-oriented controls (feature toggles, assignment-aware landing in demo/pilot paths)

A **small funded pilot** (one or two college classes for a term or multi-week unit) would validate pedagogy, cost, and teacher workflow—and give the college a concrete AI-in-the-humanities initiative to point to.

---

## Proposed pilot

### Scope

| Item | Proposal |
| --- | --- |
| **Participants** | 1–2 literature instructors (Jessica + peer teacher of interest) |
| **Students** | One or two college class sections |
| **Duration** | One unit or full term (recommend **4–8 weeks minimum**) |
| **Texts** | Public-domain works already in (or addable to) the catalog; choose 1–3 assigned titles |
| **Primary student path** | Read assigned chapters → complete quiz where required → optional AI enrichment |
| **Teacher goals** | Control features, assign work, see engagement without running a full LMS gradebook |

### What teachers can emphasize

- **Quiz on, recap off** (or any independent mix)—AI is not all-or-nothing  
- **Reading Buddy** as optional enrichment (historian / close reader / etc.), sparse and dismissible  
- **Character chat / voice call** as discussion sparks or optional activities  
- Later: required character-chat assignments as a fun in-class share (tracked as follow-on work)

### What the pilot is *not* (v1 honesty)

- Not a full LMS or SIS integration  
- Not a promise of complete FERPA certification on day one—**college-age pilot with explicit data practices and a compliance roadmap**  
- Not unmoderated open-ended AI chat about anything; context is **position-bounded to the book**  
- Not a multi-school SaaS rollout; this is a **supervised couple-of-classes pilot**

---

## Student experience (demo storyboard)

Use this as a 10–15 minute live walkthrough.

1. **Landing / class context** — Student sees class banner and assignments (pilot/demo classroom mode).  
2. **Read** — Open assigned book/chapter; page/verse-style reading without clutter.  
3. **Quiz** — Complete chapter quiz; wrong answers show citation snippets (text-grounded).  
4. **Reading Buddy (optional)** — Enable a persona; show a short companion comment or Talk modal; emphasize off-by-default / dismissible.  
5. **Character call (optional)** — Brief voice conversation with a major character; frame as oral discussion tool, not assessment v1.  
6. **Teacher control narrative** — “In pilot, recap can be off while quiz stays on; features are independent.”

**Props to prepare before the call:** one short assigned chapter, quiz generated/cached, buddy/call credentials working in the demo environment.

---

## Why this fits an institutional AI grant

| Grant theme | How the pilot maps |
| --- | --- |
| **AI literacy in the disciplines** | Students use AI *with* a literary text, not instead of it |
| **Faculty-led innovation** | Designed with a literature professor; second teacher expands validity |
| **Responsible use** | Teacher feature gates; spoiler-safe / position-bounded AI where applicable |
| **Measurable engagement** | Reading progress, quiz outcomes; usage/cost instrumentation on the roadmap |
| **Replicable humanities use case** | Public-domain curriculum; low content-licensing friction |

---

## Pilot phases (realistic)

### Phase 0 — Partner readiness (now → first touchpoints)
- Weekly educator touchpoint (Thursday evenings)  
- Choose books/chapters, feature policy (e.g. recap off / quiz on)  
- Demo student path + agree pilot window  

### Phase 1 — Classroom foundation (in progress)
- Multi-class domain model: teachers, terms, enrollments, join links  
- Independent class feature toggles  
- Assignment v1 (book/chapter, due window, quiz completion)  

### Phase 2 — Pilot operations
- Teacher onboarding + student join flow  
- Usage visibility and cost awareness (per-student / per-class estimates)  
- FERPA/ADA checklist appropriate to college pilot  
- Short teacher/student guide  

### Phase 3 — Expand carefully
- Quiz question overrides for a class  
- Dashboard drill-down  
- Optional character-chat assignment activities  
- Second section / second teacher  

---

## Success criteria (draft — finalize with Jessica)

**Pedagogy**
- Students complete assigned reading + required quizzes at rates acceptable to the instructor  
- Instructor rates the tool as **worth repeating** next term  
- At least one AI surface (quiz feedback, buddy, or character conversation) is judged **helpful for discussion or comprehension**

**Operations**
- Join/roster works without engineer-in-the-room for routine class sessions  
- No severity-1 outages during core class windows  
- Cost per student stays within an agreed envelope (or is measured well enough to set one)

**Learning for product**
- Clear list of must-fix teacher workflows  
- Written feature policy used in the class (what is on/off and why)  
- Notes for second-teacher onboarding  

---

## Support & responsibilities

| Role | Responsibility |
| --- | --- |
| **Jessica (lead educator)** | Curriculum choices, classroom norms, grant/internal pitch, weekly product feedback |
| **Peer teacher (optional)** | Second section; validates multi-class needs |
| **Product/engineering (Kevin)** | Build pilot foundation, demo env, reliability, cost instrumentation, compliance roadmap |
| **Institution** | Account/email norms, any required IT/security review, grant administration |

---

## Open questions for the next Thursday call

1. Target term / start week for the pilot?  
2. Which books and chapters first?  
3. Feature policy for her class: quiz, recap, Reading Buddy, character chat/call—on or off?  
4. Will the second teacher join the same term or later?  
5. What does the grant need as a written artifact (one-pager, budget line, student count, outcomes)?  
6. Any campus constraints (SSO, data residency, accessibility review)?  

---

## Links (internal)

- Live site: https://classicchatreader.com  
- Classroom domain design: `docs/product/bl-025-classroom-data-model.md`  
- Classroom demo landing guide: `docs/product/classroom-landing-usage.md`  
- Reading Buddy design: `docs/product/reading-buddy-mode.md`  
- Product backlog: `docs/product/backlog.md` (`BL-025`, `BL-042`–`BL-045`)  

---

## One-paragraph abstract (copy/paste for email or grant form)

> We propose a small classroom pilot of Classic Chat Reader, a public-domain literature reading application with optional, teacher-controlled AI features (chapter quizzes with text citations, Reading Buddy companion commentary, and character conversation including voice). Led by literature faculty with interest from a second instructor, the pilot would run in one or two college sections for a multi-week unit or term. Goals are to improve engagement with assigned texts, keep AI text-grounded and instructor-gated (for example quizzes on while recap remains off), and measure operational cost and teacher workflow needs. The pilot is intentionally scoped: not a full LMS replacement, but a supervised, fundable path for AI in the humanities with a clear compliance and multi-class roadmap.
