# Production Database Backup and Recovery

## Protection layers

Production uses two complementary recovery layers:

1. **DigitalOcean managed backups and point-in-time recovery (PITR).** Managed PostgreSQL clusters include automatic daily full backups and retained write-ahead logs. DigitalOcean supports recovery to a selected point within the previous seven days. A restore creates a new cluster; it does not overwrite the existing primary. Destroying the source cluster also destroys its retained managed backups.
2. **Portable logical backups.** `scripts/backup_production_db.sh` creates a local PostgreSQL custom-format archive before deployment. This copy is independent of the managed cluster lifecycle and can restore an individual database into PostgreSQL outside DigitalOcean.

DigitalOcean references:

- [Managed PostgreSQL features](https://docs.digitalocean.com/products/databases/postgresql/details/features/)
- [Restore a PostgreSQL cluster from backups](https://docs.digitalocean.com/products/databases/postgresql/how-to/restore-from-backups/)
- [Fork a PostgreSQL cluster from a point in time](https://docs.digitalocean.com/products/databases/postgresql/how-to/fork-clusters/)

## Create a verified logical backup

Prerequisites:

- SSH access to the production host (`pdr` by default).
- Production database access from the machine running the script.
- PostgreSQL client tools at least as new as the production server. Production is PostgreSQL 18 as of 2026-07-14; on macOS use `brew install postgresql@18`.
- The production environment file remains at `/opt/public-domain-reader/app.env`, or `--remote-env` is supplied.

Run:

```bash
scripts/backup_production_db.sh
```

Default output:

```text
~/Backups/classic-chat-reader/production-YYYYMMDDTHHMMSSZ.dump
~/Backups/classic-chat-reader/production-YYYYMMDDTHHMMSSZ.dump.sha256
```

The script:

- obtains database host, database name, user, and password over SSH from the protected production environment;
- keeps credentials only in process environment variables;
- requires TLS;
- uses a transactionally consistent PostgreSQL custom-format dump;
- excludes source ownership and ACLs so the archive is portable;
- rejects a PostgreSQL client older than the server;
- verifies the archive with `pg_restore --list` and requires table-data entries;
- writes restrictive directory/file permissions and a SHA-256 checksum;
- leaves no final archive if dumping or verification fails.

The archive contains account and classroom records and must be treated as sensitive education/application data. Keep the destination on encrypted storage, do not commit it, and do not upload it to an unapproved personal cloud account.

## Deployment gate

`scripts/deploy_remote.sh` runs the verified backup automatically before building or uploading the JAR:

```bash
scripts/deploy_remote.sh --ssh-target pdr --ssh-key ~/.ssh/kevin
```

If the backup fails, deployment stops. `--skip-backup` is available for an explicit emergency opt-out; it should not be used for schema-changing releases.

## Validate an archive

Verify its checksum from the containing directory:

```bash
shasum -a 256 -c production-YYYYMMDDTHHMMSSZ.dump.sha256
```

Inspect its contents without restoring:

```bash
/opt/homebrew/opt/postgresql@18/bin/pg_restore \
  --list ~/Backups/classic-chat-reader/production-YYYYMMDDTHHMMSSZ.dump
```

## Restore test

Never test a restore against production. Create an empty, isolated PostgreSQL database and restore into it:

```bash
createdb classic_chat_reader_restore_test

/opt/homebrew/opt/postgresql@18/bin/pg_restore \
  --exit-on-error \
  --no-owner \
  --no-privileges \
  --dbname classic_chat_reader_restore_test \
  ~/Backups/classic-chat-reader/production-YYYYMMDDTHHMMSSZ.dump
```

Then verify at minimum:

- latest `flyway_schema_history` version;
- row counts for `books`, `chapters`, `users`, `class_sections`, `terms`, `enrollments`, and `assignments`;
- application startup against the restored database with `spring.jpa.hibernate.ddl-auto=validate`.

Do a restore test periodically and before relying on the process for a high-risk migration. Archive verification proves that PostgreSQL can read the dump catalog; only a restore test verifies the full recovery path.

## DigitalOcean recovery choice

Use **PITR/restore to a new managed cluster** when the production cluster needs to be reconstructed quickly at a recent transaction boundary. Use the **logical archive** when an independently retained, portable pre-deploy copy is needed or when restoring into a non-DigitalOcean PostgreSQL environment.

After a managed restore, update application connection settings only after validating the new cluster. Keep the original cluster intact until application smoke checks and data comparisons succeed.
