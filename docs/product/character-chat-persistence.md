# Character chat persistence model

BL-049 introduces account-owned `character_chat_conversations` and ordered
`character_chat_messages`.

## Model assumptions

- A conversation belongs to exactly one authenticated `users` row and one
  `characters` row. Anonymous chat remains client-local until an API explicitly
  claims it into an authenticated account.
- The current reader UI has one local history per book/character. The database
  deliberately permits multiple conversations for the same user and character
  so a future My Chats experience can start a new thread without rewriting the
  schema. The API may initially resume the most recently updated thread.
- Book identity is derived from `characters.book_id`; it is not duplicated on a
  conversation, avoiding a character/book mismatch.
- Messages are immutable transcript events. `sequence_number` is the canonical
  order within a conversation; `created_at` is audit/display metadata and is not
  used to break ordering ties. A unique constraint prevents duplicate sequence
  positions.
- `client_message_id` is optional. When supplied by the client or a localStorage
  claim-sync operation, it makes retries idempotent within one conversation.
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
