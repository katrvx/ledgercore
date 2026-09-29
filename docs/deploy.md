# Deploying to Google Cloud Run

This is how I deploy LedgerCore to Google Cloud: the service on Cloud Run, the database on Cloud SQL for PostgreSQL 18. Every step is a command you run yourself. Nothing here is automated, and the commands that cost money are marked.

I deployed it this way on 2026-09-27: `/ready` answered `UP` with the database `UP` and Redis `DISABLED`, a request without a token got 403, and a deposit and a transfer worked. The commands work in bash and in zsh, the default shell on macOS (see "Problems I hit on the first deploy" at the end).

## What runs where

| Part | Where | Why |
|---|---|---|
| The service | Cloud Run, private (no public access) | scales to zero, pay per request, and the API has no login of its own |
| PostgreSQL 18 | Cloud SQL, edition Enterprise, `db-f1-micro` | the smallest managed Postgres, and 18 is the default version there |
| Database password | Secret Manager | never in the repo, never in the image, never in a command line |
| Docker image | Artifact Registry | next to Cloud Run, in the same project |
| Redis | not used | the service works without it: idempotency and the velocity rule use the database, and `/ready` shows `"redis": "DISABLED"` |

## What it costs

Prices from Google's pricing pages for Iowa (`us-central1`), one month counted as 730 hours:

| Resource | Setting | Price on the page | Per month |
|---|---|---|---|
| Cloud Run | 1 vCPU, 1 GiB, 0 to 2 instances | free up to 2 million requests, 180,000 vCPU-seconds and 360,000 GiB-seconds per month | $0 for a demo |
| Cloud SQL instance | `db-f1-micro`, no high availability | $0.0105 per hour | $7.67 |
| Cloud SQL storage | 10 GiB SSD | $0.000232877 per GiB-hour | $1.70 |
| Cloud SQL backups | daily, the database is tiny, about 1 GiB | $0.000109589 per GiB-hour | about $0.08 |
| Artifact Registry | one or two images of about 190 MB | free up to 0.5 GB | $0 |
| Secret Manager | one secret | free up to 6 active versions and 10,000 accesses | $0 |
| Budget alert | | free | $0 |
| **Total** | | | **about $9.45** |

Things that surprised me:
- A stopped Cloud SQL instance still costs money: the storage, and its IPv4 address "while idle" at $0.01 per hour ($7.30 a month). Stopping saves almost nothing, so I delete the instance when I'm done (step 9).
- `db-f1-micro` only exists in the Enterprise edition. Enterprise Plus has no shared-core machines and costs many times more, so the create command sets `--edition=ENTERPRISE`. Check that the console shows Enterprise and `db-f1-micro` before you confirm.
- Shared-core machines are not covered by the Cloud SQL SLA. That is fine for a demo, not for real money.
- New Google Cloud accounts get $300 of credit for 90 days, which covers this many times over.

## 0. Before you start

You need the gcloud CLI, logged in (`gcloud auth login`), a project, and a billing account. Creating the project and the billing account are done in the console, by hand.

Every variable is written with braces, like `${REGION}`. In zsh, `$REGION:l` means "REGION in lowercase", so without braces a colon after a variable can silently eat the next letter.

```bash
PROJECT_ID=your-project-id
REGION=us-central1
BILLING_ACCOUNT_ID=XXXXXX-XXXXXX-XXXXXX   # gcloud billing accounts list
IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/ledgercore/ledgercore:1.0.0"
SERVICE_ACCOUNT="ledgercore-run@${PROJECT_ID}.iam.gserviceaccount.com"

gcloud config set project "${PROJECT_ID}"
```

Link the billing account to the project, and check it. Without this, every paid API fails to turn on with `UREQ_PROJECT_BILLING_NOT_FOUND`:

```bash
gcloud billing projects link "${PROJECT_ID}" --billing-account="${BILLING_ACCOUNT_ID}"
gcloud billing projects describe "${PROJECT_ID}" --format='value(billingEnabled)'
# must print: True
```

## 1. A budget alert, before anything else

The amount must be in the currency of your billing account. Mine is in GBP, so I used 12 GBP:

```bash
gcloud services enable billingbudgets.googleapis.com

gcloud billing budgets create \
  --billing-account="${BILLING_ACCOUNT_ID}" \
  --display-name="ledgercore" \
  --budget-amount=12GBP \
  --filter-projects="projects/${PROJECT_ID}" \
  --credit-types-treatment=exclude-all-credits \
  --threshold-rule=percent=0.5 \
  --threshold-rule=percent=0.9 \
  --threshold-rule=percent=1.0 \
  --threshold-rule=percent=1.0,basis=forecasted-spend
```

On my first try this command failed with `INVALID_ARGUMENT`. Billing was not linked to the project yet at that point, which is probably why, but I did not confirm it. I created the same budget in the console instead: Billing, Budgets and alerts, Create budget, scope set to this project, credits unticked, 12 GBP, alerts at 50%, 90% and 100% of actual spend and 100% of forecasted spend. Both ways give the same budget.

- The billing account administrators get an email at each threshold.
- Excluding credits makes the budget count the real cost. Otherwise the free trial credits pay for everything, the spend stays at 0 and no alert would ever come.
- **A budget only sends alerts. It does not stop spending.** Deleting the resources (step 9) is what stops it.

## 2. Turn on the APIs

```bash
gcloud services enable run.googleapis.com sqladmin.googleapis.com \
  artifactregistry.googleapis.com secretmanager.googleapis.com
```

## 3. Build and push the image

```bash
gcloud artifacts repositories create ledgercore --repository-format=docker --location=${REGION}
gcloud auth configure-docker ${REGION}-docker.pkg.dev

docker buildx build --platform linux/amd64 -t ${IMAGE} --push .
```

Cloud Run only runs `linux/amd64` images. On an Apple Silicon Mac the Dockerfile builds the jar natively and only the small final stage is amd64, so this takes about a minute.

## 4. The database (costs money from here)

```bash
gcloud sql instances create ledgercore-db \
  --database-version=POSTGRES_18 \
  --edition=ENTERPRISE \
  --tier=db-f1-micro \
  --region=${REGION} \
  --storage-type=SSD \
  --storage-size=10GB \
  --availability-type=zonal \
  --backup-start-time=03:00

gcloud sql databases create ledgercore --instance=ledgercore-db
```

The password is made up by the machine, goes straight into Cloud SQL and Secret Manager, and is never printed:

```bash
DB_PASSWORD="$(openssl rand -base64 24)"
gcloud sql users create ledgercore --instance=ledgercore-db --password="${DB_PASSWORD}"
printf '%s' "${DB_PASSWORD}" | gcloud secrets create ledgercore-db-password \
  --replication-policy=automatic --data-file=-
unset DB_PASSWORD
```

The service runs its Flyway migrations when it starts, so there is nothing else to set up in the database.

## 5. An identity for the service

```bash
gcloud iam service-accounts create ledgercore-run --display-name="ledgercore on cloud run"

# connect to cloud sql
gcloud projects add-iam-policy-binding ${PROJECT_ID} \
  --member=serviceAccount:${SERVICE_ACCOUNT} --role=roles/cloudsql.client

# read this one secret, and no other
gcloud secrets add-iam-policy-binding ledgercore-db-password \
  --member=serviceAccount:${SERVICE_ACCOUNT} --role=roles/secretmanager.secretAccessor
```

## 6. Deploy

The JDBC URL names the Cloud SQL instance, and Google's socket factory (a runtime dependency in `build.gradle.kts`) opens the connection with TLS, using the service account. The database port is never opened to the internet. The URL contains `?`, `&` and `=`, so it goes in a small file instead of the command line. The file has no secrets, only the project id, and `.gitignore` keeps it out of the repo.

The connection name is read from Cloud SQL instead of being put together by hand. My first deploy failed because zsh turned `$PROJECT_ID:$REGION:ledgercore-db` into `...:us-central1edgercore-db`.

```bash
CONNECTION_NAME=$(gcloud sql instances describe ledgercore-db --format='value(connectionName)')
echo "${CONNECTION_NAME}"
# must print: your-project-id:us-central1:ledgercore-db

cat > cloud-run-env.yaml <<EOF
DATABASE_URL: "jdbc:postgresql:///ledgercore?cloudSqlInstance=${CONNECTION_NAME}&socketFactory=com.google.cloud.sql.postgres.SocketFactory"
DATABASE_USER: "ledgercore"
EOF
cat cloud-run-env.yaml

gcloud run deploy ledgercore \
  --image=${IMAGE} \
  --region=${REGION} \
  --service-account=${SERVICE_ACCOUNT} \
  --no-allow-unauthenticated \
  --env-vars-file=cloud-run-env.yaml \
  --set-secrets=DATABASE_PASSWORD=ledgercore-db-password:latest \
  --cpu=1 \
  --memory=1Gi \
  --min-instances=0 \
  --max-instances=2 \
  --cpu-boost
```

- `--no-allow-unauthenticated`: the API has no login of its own, so a public URL would let anyone open accounts and make deposits. Only Google accounts with the Cloud Run Invoker role (or Admin, like the project owner) can call it.
- `--max-instances=2`: each instance keeps up to 10 database connections (`DATABASE_POOL_SIZE`), and `db-f1-micro` allows 25 by default. Two instances use 20.
- `--min-instances=0`: no cost while nobody calls it. The first request after a pause waits for the JVM to start and for Flyway, a few seconds. `--cpu-boost` gives more CPU during that start.
- `REDIS_URL` is not set, so the service runs on the database alone.
- Cloud Run sends SIGTERM before it stops an instance, and the service then closes the HTTP server and the database pool.

## 7. Call it

The easiest way is the proxy, which adds your identity to every request:

```bash
gcloud run services proxy ledgercore --region=${REGION} --port=8080
# in another terminal:
curl -s localhost:8080/ready
```

Or directly with an identity token:

```bash
URL=$(gcloud run services describe ledgercore --region=${REGION} --format='value(status.url)')
TOKEN=$(gcloud auth print-identity-token)

curl -s -H "Authorization: Bearer ${TOKEN}" ${URL}/ready
# {"status":"UP","database":"UP","redis":"DISABLED"}

A=$(curl -s -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
  -X POST ${URL}/accounts -d '{"ownerName":"Alice","currency":"EUR"}')
echo ${A}
```

A request without a token is refused by Cloud Run itself with 403 and never reaches the service. I checked this after deploying.

## 8. Logs

The service writes one JSON object per line with `severity`, `message`, `time` and `requestId`, which Cloud Logging understands. In the Logs Explorer:

```
resource.type="cloud_run_revision"
resource.labels.service_name="ledgercore"
jsonPayload.requestId="the id from the X-Request-Id header"
```

## 9. Delete everything

```bash
gcloud run services delete ledgercore --region=${REGION}
gcloud sql instances delete ledgercore-db
gcloud secrets delete ledgercore-db-password
gcloud artifacts repositories delete ledgercore --location=${REGION}
gcloud iam service-accounts delete ${SERVICE_ACCOUNT}
rm cloud-run-env.yaml
```

The budget costs nothing and can stay, or you can delete it in the console under Billing, Budgets and alerts.

## What I would change for real money

- IAM database authentication instead of a password, so there is no secret to leak at all.
- A private IP for Cloud SQL, reached through the VPC, instead of the public IP the socket factory uses.
- Redis on Memorystore for the fast idempotency path and velocity counting (about $36 more per month for 1 GiB).
- An edition with an SLA and high availability instead of `db-f1-micro`.
- A real login for the API.

## Problems I hit on the first deploy

1. **Billing was not linked to the project.** `gcloud services enable` failed with `UREQ_PROJECT_BILLING_NOT_FOUND`. Step 0 now links it and checks `billingEnabled`.
2. **`gcloud billing budgets create` failed with `INVALID_ARGUMENT`**, probably because billing was not linked yet. I created the budget in the console instead, 12 GBP, because my billing account is in GBP.
3. **zsh ate a letter of the Cloud SQL connection name.** `"$PROJECT_ID:$REGION:ledgercore-db"` became `your-project-id:us-central1edgercore-db`, because in zsh `:l` after a variable means lowercase. The deploy failed until I fixed it. Now every variable has braces, and the connection name comes from `gcloud sql instances describe`.
