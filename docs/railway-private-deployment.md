# Private Railway deployment with Cloudflare R2

For the first deployment, follow the [staging setup guide](railway-staging-deployment.md)
to test `development` without deploying to production.

The web service runs Java and CPU-only Docling in one container. PostgreSQL with
pgvector holds the searchable passages, vectors, corpus registry, and job history.
A **private R2 bucket** holds uploaded PDFs, Docling outputs, versioned chunk files,
benchmark requests, research evaluation reports, and plots. Local disk is a disposable
working cache; no Railway filesystem volume is required for research corpus files.

Code deployment does not transfer the local development database or uploaded papers.
Upload and index them again in production, or migrate the database and the corresponding
files together. Keep the existing production PostgreSQL service and its persistent storage.

## Private access

1. Add a custom domain to the Railway web service and configure its DNS in Cloudflare.
2. Create a Cloudflare Access **self-hosted application** for that hostname, covering
   every path. Use an Allow policy restricted to your own email address. Enable your
   preferred login method, such as a one-time email code. Do not add a public Bypass policy.
3. Copy the Access team domain (for example `your-team.cloudflareaccess.com`, with no
   `https://` or trailing slash) and this application's audience tag into Railway variables.
4. The app validates Access JWT signatures, expiry, issuer, and audience itself. A request
   sent directly to the default Railway hostname without a valid Access token receives
   HTTP 401. The only unauthenticated endpoint is `GET /actuator/health`, for Railway's
   health check. Do not disable `document-assistant.access.enabled` in production.

An R2 account does not itself provide private website access. Cloudflare Access is a
separate setup. The bucket can and should remain private: disable its public `r2.dev`
URL and do not attach a public bucket domain. PDFs and reports are served through the
authenticated app; browser clients do not receive bucket credentials or public URLs.

## R2 setup and Railway variables

Create a bucket and a bucket-scoped R2 API token with **Object Read & Write** permission.
Use the S3 Access Key ID and Secret Access Key, not a Cloudflare management API token.
Set these variables on the Railway **web service**, not in a committed configuration file:

| Variable | Value |
| --- | --- |
| `R2_ENDPOINT` | Your bucket's S3 API endpoint, usually `https://<account-id>.r2.cloudflarestorage.com` |
| `R2_BUCKET` | Your private bucket name |
| `R2_ACCESS_KEY_ID` | R2 S3 Access Key ID |
| `R2_SECRET_ACCESS_KEY` | R2 S3 Secret Access Key |
| `R2_PREFIX` | Optional; defaults to `document-assistant-production` |
| `CF_ACCESS_TEAM_DOMAIN` | For example `your-team.cloudflareaccess.com` |
| `CF_ACCESS_AUDIENCE` | Access application's audience tag |
| `APP_INTERNAL_TOKEN` | A new random secret of at least 32 characters; generate with `openssl rand -hex 32` |

Keep the existing `OPENROUTER_API_KEY`, `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and
`PGPASSWORD`. Match the configured embedding model and dimensions to existing vectors.
The database user must be able to create and drop corpus tables and indexes.
Use a different R2 prefix **and database** for staging, so staging cleanup cannot delete
production files. Never configure an R2 lifecycle rule that expires live application data.

`APP_INTERNAL_TOKEN` allows the evaluation Python processes to call only the local
answer and evidence-pool endpoints. It is sent through their environment, not persisted
in reports. It cannot authenticate external requests or corpus-management operations.

## Build and validate before merging

```sh
docker build -t document-assistant:private-r2 .
```

The image installs pinned Docling/chunker/tokenizer dependencies and CPU-only PyTorch.
The build checks Python dependencies and imports. Docling downloads public processing
models on first use; its cache is disposable and separate from private corpus artifacts.
Allow outbound HTTPS for R2, the model provider, and those initial model downloads.

Start with a single web instance. Measure memory while processing a representative PDF:
Java and Docling share the container's memory. The image limits Java's maximum heap to
40% of container memory; that percentage alone does not guarantee enough memory for
large documents. Avoid deploying while an indexing or evaluation job is running.

Before enabling production autodeploys, test this in a separate Railway environment:

1. Open the Access hostname, sign in, and confirm direct unauthenticated Railway requests
   are denied while `/actuator/health` still works.
2. Upload a selectable-text PDF, process/index it, ask a question, and open a cited PDF page.
3. Generate, review, approve, and run a small benchmark; open its report and plots.
4. Redeploy to replace the container. Verify the PDF, benchmark sources, report, and plots
   still open. Add another paper and rebuild; cached completed processing is restored from R2.
5. On a disposable corpus, verify document and corpus deletion also removes their R2 objects.

Back up the production database before the first rollout. The app creates its new `rag_*`
registry tables on startup; it does not migrate local data or intentionally clear the old
specification index. Merge `development` into `main` only after the deployment settings
are ready: Railway can automatically deploy a push to its tracked branch.

## Storage and recovery behavior

- A PDF is registered only after its R2 upload succeeds. A new index is activated only
  after its versioned chunk files are stored successfully. Storage failure retains the
  previous active index.
- Missing PDFs and historical artifacts are fetched on demand into the working cache.
  Python jobs restore the input directories they need. Existing local files are not
  overwritten while a job is using them.
- A completed Python subprocess checkpoints its output directory to R2, including outputs
  from a normal failed subprocess. An abrupt container kill can lose work since the last
  completed subprocess/checkpoint; the job is marked interrupted on restart and can be retried.
- Document/corpus deletion commits the registry and vector changes first and queues
  remote/local cleanup durably in PostgreSQL. An R2 failure leaves cleanup pending, retried
  every 30 seconds and on startup. A pending remote cleanup does not restore deleted content
  to search. If R2 is unavailable before document removal can read its dependencies, removal
  fails without changing the active corpus.
- Only corpus UUID directories under the configured R2 prefix are managed. Shared Docling
  model caches are not uploaded. The built-in specification files remain bundled with the
  image; the older demo evaluation outputs are not covered by research-corpus R2 storage.
- Object storage is not a replacement for the database. Back up both, and keep credentials
  and the configured prefix stable across deployments. This version still assumes one
  running application instance; distributed workers require a separate change.

References: [Cloudflare R2 Java SDK](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-java/),
[Cloudflare Access origin token validation](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/self-hosted-public-app/),
[Railway GitHub autodeploys](https://docs.railway.com/deployments/github-autodeploys).
