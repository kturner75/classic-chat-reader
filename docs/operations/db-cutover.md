# Database Cutover Runbook

## Scope
This runbook covers migration from local-style H2 runtime to a production database engine.
Default target is PostgreSQL (`prod` profile), with MariaDB supported via `mariadb` profile.

## Prerequisites
- Current app build is green (`mvn test`)
- Production backup window is approved
- Target DB instance is reachable from app runtime
- DB credentials are available as environment variables

## Profiles and Drivers
- PostgreSQL profile: `prod`
- MariaDB profile: `mariadb`

Required env vars:
- `SPRING_PROFILES_ACTIVE=prod` or `SPRING_PROFILES_ACTIVE=mariadb`
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

## Pre-Cutover Checklist
1. Take a full backup/snapshot of the current production database.
2. Confirm backup restore works in a non-production environment.
3. Confirm target DB has network access, TLS settings, and sufficient storage.
4. Confirm app image/JAR contains Flyway migrations.

## Cutover Steps
1. Deploy app version containing Flyway baseline migration and profile-specific datasource config.
2. Set production env vars for selected DB profile and datasource credentials.
3. Start one app instance and verify startup logs:
- Flyway initializes without errors.
- Schema validation passes (`spring.jpa.hibernate.ddl-auto=validate`).
4. Run API smoke checks:
- `/api/health`
- `/api/library`
- one generation-status endpoint (`/api/generation/status`)
5. Scale remaining instances only after the first instance passes checks.

## One-time Data Migration (H2 -> PostgreSQL/MariaDB)
Use this when bootstrapping an existing H2 dataset into a new production DB.

1. Run a dry-run first:
```bash
mvn -q -DskipTests exec:java \
  -Dexec.mainClass=com.classicchatreader.cli.DbMigrationRunner \
  -Dexec.args="--source-url jdbc:h2:file:./data/library --source-user sa --target-url jdbc:postgresql://localhost:5432/classic_chat_reader --target-user pdr_app"
```

2. Apply the copy after validating dry-run counts:
```bash
mvn -q -DskipTests exec:java \
  -Dexec.mainClass=com.classicchatreader.cli.DbMigrationRunner \
  -Dexec.args="--apply --source-url jdbc:h2:file:./data/library --source-user sa --target-url jdbc:postgresql://localhost:5432/classic_chat_reader --target-user pdr_app --target-password your-password"
```

3. If re-running against a previously populated target, include `--truncate-target` with `--apply`.

## Verification Queries
Run against the target DB after cutover:
1. Confirm migration history table exists and latest version is applied.
2. Confirm expected core tables exist:
- `books`, `chapters`, `paragraphs`, `illustrations`, `characters`, `chapter_analyses`, `chapter_recaps`, `chapter_quizzes`, `quiz_attempts`, `quiz_trophies`
3. Compare row counts for critical tables before vs after cutover.

## Rollback Plan
If verification fails:
1. Stop new app instances using the target DB.
2. Restore previous application version and datasource configuration.
3. Restore DB from backup snapshot if data integrity is in question.
4. Re-run smoke checks on rolled-back environment.
5. Capture failure logs and Flyway state before retrying cutover.

## Notes
- Local dev can continue using H2 defaults.
- Startup seed data is now profile-gated (`dev`, `test`, `smoke`) and does not run in production profiles.
