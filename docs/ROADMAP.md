# ChurnPredictor — Roadmap

Planned integrations and improvements, beyond the current working proof of concept. Nothing
in this document is built yet; it describes the intended direction of the project.

**Current state:** a company can upload customer data, the model scores every customer's
churn risk, and an agent works the at-risk list from a dashboard (review, unflag). Two
services (Spring Boot + FastAPI/scikit-learn) over MySQL, with a documented REST API and
Swagger.

---

## 1. Retention workflow (LLM-assisted)

The core missing half of the product: acting on the predictions, not just surfacing them.

- **LLM-drafted retention messages** — for a HIGH-risk customer, an LLM drafts a
  personalized retention offer/message based on the customer's profile and churn reasons.
- **Human-in-the-loop approval** — the drafted message is stored as pending; an agent
  reviews, edits, approves, or rejects it before anything is sent. No message goes out
  without human sign-off.
- **Activate the "Take Action via LLM" button** — currently a disabled placeholder on the
  customer review page; this becomes the entry point to the flow above.
- **Retention offers & campaigns** — `RetentionOffer` and `Campaign` entities with CRUD, so
  actions are tracked and grouped rather than one-off.

## 2. Model improvements

- **Retrain on real outcomes** — replace the static Kaggle-trained model with one retrained
  periodically on the company's own accumulated churn outcomes.
- **Model versioning** — keep previous model artifacts and record which model version scored
  each customer, so results are reproducible and comparisons are possible.
- **Tunable risk thresholds** — move the LOW/MEDIUM/HIGH band cut-points (currently
  0.30 / 0.60) into configuration and tune them against the business cost of an offer vs. a
  lost customer.
- **Richer models** — re-evaluate gradient boosting and other models as the dataset grows;
  the head-to-head selection process is already in place.

## 3. Data & scoring

- **Scheduled batch scoring** — a background job that re-scores all customers on a schedule,
  rather than only on upload.
- **Prediction history** — persist each scoring run so a customer's risk trend over time is
  visible, not just their latest score.
- **Incremental uploads** — support merging/updating customers by ID, instead of the current
  replace-everything semantics.
- **Preserve action state across rescans** — keep an agent's unflag/action decisions when
  predictions are re-run (currently a rescan resets statuses to pending).

## 4. Security & hardening

- **Authentication** — Spring Security login for agents accessing the dashboard and API.
- **Network isolation** — restrict the ML service to internal access only; it should never be
  publicly reachable.
- **Environment configuration** — separate dev/prod configuration and secrets management.
- **Automated tests** — JUnit tests for the service layer and MockMvc tests for the
  controllers.

## 5. Cloud deployment

The target: a full, cloud-deployed application rather than a local proof of concept. The
specific cloud provider (AWS, Azure, GCP, or similar) is not yet decided; the plan is
provider-agnostic and containerized so it can run on any of them.

- **Containerization** — Docker images for both the Spring app and the ML service, so the
  deployment target stays portable across providers.
- **Cloud hosting** — deploy the services to a container platform (e.g. AWS ECS, Azure
  Container Apps, or equivalent), with the database on a managed MySQL service (e.g. Amazon
  RDS or Azure Database for MySQL).
- **CI/CD** — automated build, test, and deploy pipeline.
- **Managed retraining** — a repeatable pipeline for retraining and promoting new model
  versions.
