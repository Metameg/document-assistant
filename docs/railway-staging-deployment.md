# Staging deployment on Railway

This setup deploys `development` into a new `staging` environment. Production keeps
tracking `main`. Staging gets its own database, R2 bucket, domain, and Access application.
The instructions below use the case-sensitive database service name **Postgres**.

## 1. Create an empty environment

In your existing Railway project, open the environment dropdown, choose **New
Environment**, select **Empty Environment**, and name it `staging`. Keep `staging`
selected while creating all services and variables below. Do not sync these changes
into production or reuse production database connection values.

## 2. Create the staging database

Add a service from the Docker image `pgvector/pgvector:0.8.6-pg17-bookworm` and name it
`Postgres`. This is the same PostgreSQL/pgvector image tested in local development.
Attach a new Railway volume mounted at `/var/lib/postgresql/data`.

In this **Postgres service's** Variables tab, set:

```dotenv
POSTGRES_DB=document_assistant_staging
POSTGRES_USER=rag_staging
POSTGRES_PASSWORD=REPLACE_WITH_A_NEW_RANDOM_DATABASE_PASSWORD
PGDATA=/var/lib/postgresql/data/pgdata
```

Generate a password locally with `openssl rand -hex 32` and save the output as
`POSTGRES_PASSWORD`. Apply the configuration and deploy the database. Leave its image's
start command unchanged. It listens on port 5432. The app connects through Railway's
private network; a public TCP proxy is not required for app access.

This database needs a volume even though the app's research files use R2. The database
contains vectors, searchable text, corpus records, and job history. Its initialization
variables apply when the data directory is first created; changing a password variable
later does not change an already-created database user's password.

## 3. Create private staging storage

In Cloudflare R2, create a private bucket such as `document-assistant-staging`. Leave
public `r2.dev` access disabled and do not attach a public bucket domain.

Create an R2 API token with **Object Read & Write** permission restricted to this bucket.
Save its S3 Access Key ID and Secret Access Key. Copy the bucket's S3 endpoint from
Cloudflare; for a standard bucket it is `https://<account-id>.r2.cloudflarestorage.com`.
Jurisdiction-specific buckets require their corresponding endpoint. This endpoint is
not a public file URL and does not include the bucket name.

## 4. Prepare private login

Use a staging subdomain on a domain you manage through Cloudflare, for example
`rag-staging.example.com`.

In Cloudflare Zero Trust / Access controls / Applications, create a **Self-hosted**
application for that hostname, covering all paths. Add an **Allow** policy that includes
only your email address. If using email codes, enable the **One-time PIN** identity
provider and make it available to this application. Do not add an Everyone or Bypass policy.

Save your team domain, such as `your-team.cloudflareaccess.com` (no scheme or trailing
slash), and this staging application's **Application Audience (AUD) Tag**. In the current
dashboard the AUD tag is under the application's Additional settings. It is not the
application's display name, UUID, or a custom label/tag.

## 5. Add the web service from GitHub

Still in Railway's `staging` environment, add a GitHub service for
`Metameg/document-assistant`. Configure its deployment branch as **development** before
the first successful deployment. Name it `document-assistant` if desired.

Use the repository root `/` and its `Dockerfile`. Leave custom build and start commands
empty so Railway uses the Dockerfile. Configure one replica and health-check path
`/actuator/health`. The web service does not need a Railway volume for research files.

If connecting GitHub starts an immediate deployment, it can fail until variables are
configured; set them below and deploy again. Do not disable the access filter to get
past missing Cloudflare settings.

## 6. Set the web service variables

Paste the following into the **web service's** Variables / Raw Editor and replace every
`REPLACE_...` value. Keep `${{...}}` expressions exactly as shown; they refer to your new
`Postgres` service in the same environment. These are Railway references, not shell commands.

```dotenv
PORT=8080
PGHOST=${{Postgres.RAILWAY_PRIVATE_DOMAIN}}
PGPORT=5432
PGDATABASE=${{Postgres.POSTGRES_DB}}
PGUSER=${{Postgres.POSTGRES_USER}}
PGPASSWORD=${{Postgres.POSTGRES_PASSWORD}}
OPENROUTER_API_KEY=REPLACE_WITH_YOUR_OPENROUTER_KEY
R2_ENDPOINT=https://REPLACE_WITH_YOUR_ACCOUNT_ID.r2.cloudflarestorage.com
R2_BUCKET=document-assistant-staging
R2_ACCESS_KEY_ID=REPLACE_WITH_STAGING_R2_ACCESS_KEY_ID
R2_SECRET_ACCESS_KEY=REPLACE_WITH_STAGING_R2_SECRET_ACCESS_KEY
R2_PREFIX=document-assistant-staging
CF_ACCESS_TEAM_DOMAIN=REPLACE_WITH_YOUR_TEAM.cloudflareaccess.com
CF_ACCESS_AUDIENCE=REPLACE_WITH_STAGING_APPLICATION_AUD_TAG
APP_INTERNAL_TOKEN=REPLACE_WITH_A_SEPARATE_NEW_RANDOM_SECRET
```

Generate another value with `openssl rand -hex 32` for `APP_INTERNAL_TOKEN`. It must be
at least 32 characters; use a different value from the database password.

`PORT=8080` makes routing explicit; the app also defaults to 8080. Explicitly set
`R2_PREFIX` for staging because the configuration's fallback prefix is for production.
If you chose a different bucket name or jurisdiction, replace the bucket and endpoint.
If you renamed the database service, replace `Postgres` in all four reference expressions.

These are the required connection/access inputs plus an explicit port and staging prefix.
No `DATABASE_URL`, `OPENAI_API_KEY`, AWS region, separate Python path, or Spring profile
is needed for this Dockerfile. Its Railway configuration already supplies those runtime
settings. Remove conflicting inherited Spring datasource overrides if any were added.

Current model defaults are `openrouter/free` for answers, `openai/text-embedding-3-small`
with 1536 dimensions for embeddings, and `openai/gpt-oss-120b` for benchmark generation
and judging. Staging model calls use provider credits. If production uses explicit model
overrides, copy those model choices deliberately; do not copy its database/storage secrets.

## 7. Deploy and connect the staging domain

Deploy the web service after the database is ready and variables are saved. The app
creates its registry/vector tables on startup. It does not import your local research
corpora. Use sufficient memory for Java and Docling together and monitor the first PDF
run; a successful web startup alone does not establish PDF-processing capacity.

In the staging web service's Networking settings, add `rag-staging.example.com` as a
custom domain, with target port **8080**. Add the DNS records Railway shows in Cloudflare.
Enable Cloudflare proxying for the hostname so Access can protect it. Wait for DNS and
Railway's certificate provisioning; use HTTPS with Full (strict) SSL/TLS once the origin
certificate is ready. Use the exact same hostname configured in the Access application.

Open that hostname, complete the Cloudflare login, and check the app loads. Direct
unauthenticated access to a generated Railway URL should return 401; that is expected.
The direct `/actuator/health` endpoint remains available to Railway and should return 200.

## 8. Test before touching production

1. Create a disposable research corpus and upload a small selectable-text PDF.
2. Process/index it, ask a question, and open the cited PDF page.
3. Generate a small benchmark (for example two questions), review/approve it, and evaluate it.
4. Redeploy the web service. Confirm the PDF, benchmark sources, report, and plots still open.
5. Upload another paper and rebuild. Verify both papers appear in answers when relevant.
6. Remove a paper and delete the disposable corpus; verify the corresponding R2 objects
   disappear. This exercises the staging bucket and database only.

Once staging is configured, pushes to `development` can autodeploy this web service.
Production should remain configured for `main`. Do not merge into `main` or sync staging
configuration into production during this testing phase.

References: [Railway environments](https://docs.railway.com/environments),
[branch deployment settings](https://docs.railway.com/deployments/github-autodeploys),
[R2 credentials](https://developers.cloudflare.com/r2/api/tokens/),
[Cloudflare Access setup](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/),
[pgvector](https://github.com/pgvector/pgvector).
