# Character chat persistence model

BL-049 introduces account-owned `character_chat_conversations` and ordered
`character_chat_messages`.

## Model assumptions

- A conversation belongs to exactly one authenticated `users` row and one
  `characters` row. The reader treats the authenticated API as the transcript's
  source of truth; character-chat history is no longer stored in `localStorage`.
- The current reader UI resumes the account's most recently updated conversation
  for a character. The database deliberately permits multiple conversations for
  the same user and character so a future My Chats experience can start a new
  thread without rewriting the schema.
- Book identity is derived from `characters.book_id`; it is not duplicated on a
  conversation, avoiding a character/book mismatch.
- Messages are immutable transcript events. `sequence_number` is the canonical
  order within a conversation; `created_at` is audit/display metadata and is not
  used to break ordering ties. A unique constraint prevents duplicate sequence
  positions.
- `client_message_id` is optional in the schema. The authenticated reader API
  requires an idempotency key for each user send, making retries idempotent
  within one conversation.
- `user_id` is repeated on messages so the composite foreign key
  `(conversation_id, user_id)` can reject a message attributed to a different
  account than its conversation. Repository reads also require the owner id.
- Deleting a user or character deletes its conversations; deleting a
  conversation deletes its messages. No teacher or bulk-read relationship is
  introduced in this schema.
- Application timestamps are written in UTC. Services that append a message
  must also advance the parent conversation's `updated_at`.
- V18 adds nullable chapter and paragraph context to each conversation so My
  Chats can resume the exact reader location. Rows created before V18 fall back
  to the character's first chapter until their next exchange captures context.

## Legacy browser cache decision

Legacy `reader_characterChat_*` entries contain book and character identifiers,
but no authenticated account identity. A browser can be shared or can sign in as
a different user after those entries were written, so automatically claiming the
cache could attach one person's transcript to another person's account. The
reader therefore does not migrate these entries. After an authenticated history
request succeeds, it removes all legacy character-chat keys while leaving other
reader preferences and local state untouched. If the server request fails, the
cache is left in place until a later successful sync, but it is never rendered or
used as chat context.

## Query shape

The V19 index `idx_ccc_user_character_activity` on `(user_id, character_id,
updated_at, created_at)` selects a user's threads for a character and covers the
repository's `updated_at DESC, created_at DESC` ordering. It is additive so the
checksum of the released V17 migration remains stable. The index
`(conversation_id, user_id, sequence_number)` then returns the selected
transcript in deterministic order. `(user_id, updated_at)` supports a paged
recent-thread list across characters.

## Migration and rollback

Flyway applies `db/migration/V17__character_chat_persistence.sql`,
`db/migration/V18__character_chat_resume_context.sql`, and
`db/migration/V19__character_chat_query_indexes.sql` in that order. Flyway
Community does not run undo migrations, so rollback is an explicit operator
step after a database backup:

1. Stop application writes.
2. Back up/export the two character chat tables if they contain data.
3. Execute `db/rollback/U18__character_chat_resume_context.sql`, then
   `db/rollback/U17__character_chat_persistence.sql`.
4. Remove or repair the V19, V18, and V17 rows in `flyway_schema_history` only
   as part of the coordinated deployment rollback; do not edit migration
   history on a live forward-moving deployment. Dropping the conversation table
   also removes V19's index.

The migration integration test executes the migrations and rollback against H2,
removes the coordinated V19/V18/V17 history entries, and re-applies the migrations.
It verifies ownership, ordering, exact index columns, cascade behavior, table
removal, and successful re-application.
