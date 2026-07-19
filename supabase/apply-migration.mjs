// Apply the SQL migrations to a Postgres database — a lightweight alternative to the
// Supabase CLI. Runs every supabase/migrations/*.sql in order.
//
// Usage (from supabase/):
//   npm install
//   DATABASE_URL="postgresql://postgres:<PASSWORD>@<host>:5432/postgres" npm run migrate
//
// Get the connection string (with your password) from:
//   Supabase → Project Settings → Database → Connection string (URI).
// First enable the `pg_cron` and `pgcrypto` extensions in Database → Extensions.
//
// NOTE: this is a first-run applier (the migration uses `create type`/`create table`,
// which error if the objects already exist). For iterative schema changes, use the
// Supabase CLI (`supabase db push`) instead.

import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import pg from 'pg'

const url = process.env.DATABASE_URL
if (!url) {
  console.error(
    'ERROR: set DATABASE_URL to your Supabase Postgres connection string (with password).',
  )
  process.exit(1)
}

const migrationsDir = path.join(path.dirname(fileURLToPath(import.meta.url)), 'migrations')
const files = readdirSync(migrationsDir)
  .filter((f) => f.endsWith('.sql'))
  .sort()

if (files.length === 0) {
  console.error('No .sql files found in supabase/migrations/')
  process.exit(1)
}

const client = new pg.Client({ connectionString: url })

try {
  await client.connect()
  for (const file of files) {
    process.stdout.write(`applying ${file} ... `)
    const sql = readFileSync(path.join(migrationsDir, file), 'utf8')
    await client.query(sql)
    console.log('done')
  }
  console.log(`\nApplied ${files.length} migration(s) successfully.`)
} catch (err) {
  console.error('\nMigration failed:', err.message)
  process.exitCode = 1
} finally {
  await client.end()
}
