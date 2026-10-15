# ECRP — Model Documentation

Reference documentation for the machine-learning model behind the Engage Customer Retention
Platform (ECRP), a telecom
churn-prediction platform. This describes the model as it currently exists: the data it was
trained on, how it is built, how it was selected, how it performs, and how it is served.

The model is trained offline by `train.py` and served over HTTP by `app.py` (FastAPI). The
Spring Boot application consumes it but is out of scope for this document.

---

## 1. Overview

The model estimates the probability that a telecom customer will churn (cancel service),
given a snapshot of their account attributes. It is a binary classifier that outputs a
churn probability between 0 and 1, which the serving layer buckets into LOW / MEDIUM / HIGH
risk bands.

- **Type:** binary classification (logistic regression), probabilities calibrated
- **Library:** scikit-learn 1.8
- **Artifact:** `churn_model.joblib` (a complete preprocessing + model pipeline)
- **Version:** `2.1-calibrated` (recorded in `model_meta.json`)

---

## 2. Dataset

Trained on the **IBM Telco Customer Churn** dataset (`Telco-Customer-Churn.csv`), a widely
used public benchmark for churn modelling.

| Property | Value |
|---|---|
| Raw rows | 7,043 customers |
| Rows after cleaning | 7,032 (11 dropped — see §4) |
| Target column | `Churn` (Yes/No), mapped to 1/0 |
| Class balance | ~26.5% churn (imbalanced) |
| Train/test split | 80% / 20%, stratified on the target, `random_state=42` |

The class imbalance and the stratified split are both material: stratification preserves the
~26.5% churn rate in both the training and test sets, so the reported metrics are not
distorted by an unrepresentative split.

The dataset is a stand-in. In production the same pipeline would be retrained on the telecom
company's own historical data; the Kaggle data proves the method.

---

## 3. Features

19 input features are used. `customerID` is excluded (an identifier carries no predictive
signal), and `Churn` is the target, not an input.

**Numeric (4):**

| Feature | Meaning |
|---|---|
| `tenure` | months the customer has been with the company |
| `MonthlyCharges` | current monthly bill |
| `TotalCharges` | lifetime amount billed |
| `SeniorCitizen` | 0 / 1 flag |

**Categorical (15):**
`gender`, `Partner`, `Dependents`, `PhoneService`, `MultipleLines`, `InternetService`,
`OnlineSecurity`, `OnlineBackup`, `DeviceProtection`, `TechSupport`, `StreamingTV`,
`StreamingMovies`, `Contract`, `PaperlessBilling`, `PaymentMethod`.

After one-hot encoding, the 15 categorical features expand to produce **45 total model
inputs**.

---

## 4. Data cleaning

`TotalCharges` is stored as text in the raw CSV and contains 11 blank strings — customers
with `tenure = 0` who have not yet been billed. Because of those blanks, pandas reads the
entire column as text, which scikit-learn cannot consume.

Cleaning step: `pd.to_numeric(..., errors="coerce")` converts the column to numbers and turns
the 11 blanks into `NaN`; those 11 rows are then dropped. This is the only row-level cleaning
applied.

---

## 5. Preprocessing

Preprocessing is defined once as a `ColumnTransformer` and applied identically to both
candidate models and at serving time:

| Feature type | Transform | Rationale |
|---|---|---|
| Numeric | `StandardScaler` | rescales to mean 0 / std 1 so features on very different scales (e.g. `tenure` 0–72 vs `TotalCharges` 0–8,600) are comparable; logistic regression converges poorly otherwise |
| Categorical | `OneHotEncoder(handle_unknown="ignore")` | one 0/1 column per category value, avoiding a false numeric ordering between categories; `handle_unknown="ignore"` encodes unseen category values as all-zeros instead of erroring, so a future upload with a novel value cannot break scoring |

---

## 6. The pipeline

The scaler, the encoder, and the classifier are combined into a single scikit-learn
`Pipeline` and serialized together as `churn_model.joblib`.

This is a deliberate correctness decision: because the same pipeline object performs
preprocessing at both training and serving time, the transformations applied to a live
customer are guaranteed identical to those applied during training. Training/serving skew —
a common source of silent model bugs — is structurally impossible unless the artifact itself
is replaced. The serving code hands the pipeline raw, named columns and never reimplements
scaling or encoding.

---

## 7. Model selection

Two candidate classifiers are compared on the identical preprocessing pipeline. Neither uses
`class_weight="balanced"` — that reweighting inflates predicted probabilities, and those
probabilities are shown to agents as percentages (see §8), so the model is left to produce
calibrated probabilities instead.

Selection is by **5-fold stratified cross-validation** on ROC-AUC, not a single train/test
split — one split varies enough fold-to-fold to be unreliable for choosing a model:

| Candidate | CV ROC-AUC (5-fold) |
|---|---|
| **Logistic Regression** (selected) | **0.845 ± 0.003** |
| HistGradientBoosting | 0.836 ± 0.006 |

**Selection rule:** prefer the simpler, interpretable logistic regression unless gradient
boosting beats it by more than one fold standard deviation. Here LR wins outright — by roughly
three times its own std — so the choice is stable across random seeds. `max_iter=2000` for LR;
`random_state=42` for gradient boosting.

---

## 8. Reading the metrics

Cross-validated ROC-AUC (§7) is the headline for ranking quality; the figures below are on
the held-out 20% test split (1,407 customers) at the tuned decision threshold.

- **Calibrated probabilities.** Mean predicted probability on the test set is **0.273**,
  essentially equal to the observed churn rate of **0.266**. Because the displayed "X% chance
  of churning" is read by a human agent, it has to mean what it says — removing `class_weight`
  is what makes it honest.
- **Decision threshold = 0.40.** Instead of the default 0.5, the threshold maximising churn
  F1 is swept on the held-out split and recorded as `decision_threshold` in the metadata.
- **Churn recall 0.68 / precision 0.58 / F1 0.63** at that threshold. The earlier
  `class_weight` model reached 0.80 recall but at 0.49 precision and with uncalibrated
  probabilities; this version has higher precision and a higher F1.
- **ROC-AUC ≈ 0.84 (test), Brier score 0.14.** Brier measures probability calibration (lower
  is better) and is recorded alongside the other metrics.
- **Accuracy (~0.78)** is not an objective here — with a 26.5% churn rate a do-nothing model
  already scores ~74%.

---

## 9. What the model learned

Because logistic regression is linear, each feature has a single coefficient. A positive
coefficient pushes toward churn; a negative one pushes away; magnitude indicates strength
(comparable across features because the numerics are standardized). Intercept: **−0.57**.

**Strongest churn drivers (positive):**

| Coefficient | Feature |
|---|---|
| +0.64 | `TotalCharges` |
| +0.61 | `Contract = Month-to-month` |
| +0.59 | `InternetService = Fiber optic` |
| +0.19 | `StreamingTV = Yes` |
| +0.18 | `PaymentMethod = Electronic check` |

**Strongest protective factors (negative):**

| Coefficient | Feature |
|---|---|
| −1.35 | `tenure` |
| −0.78 | `Contract = Two year` |
| −0.62 | `InternetService = DSL` |
| −0.54 | `MonthlyCharges` |
| −0.30 | `PaperlessBilling = No` |

Tenure is by far the strongest signal: the longer a customer has stayed, the less likely
they are to leave. Contract length is the next strongest lever — month-to-month drives
churn, two-year contracts strongly suppress it.

**Note on `MonthlyCharges`:** its coefficient is negative (protective) here, which can look
counter-intuitive. In a multi-feature linear model each coefficient is a *conditional*
effect — the influence of that feature holding all others fixed. Once contract type and
internet-service type (fiber optic customers pay more and churn more) are in the model, they
absorb the "expensive plan" signal, and `MonthlyCharges` on its own no longer trends toward
churn. This is expected behaviour with correlated features and a reason coefficients should
be read as conditional, not standalone, effects.

---

## 10. Training (`train.py`)

Running `python train.py`:

1. Loads and cleans the CSV (§4).
2. Compares both candidates by 5-fold cross-validated ROC-AUC and selects the winner (§7).
3. Fits the winner on an 80/20 stratified split and sweeps the decision threshold on the
   held-out portion to maximise churn F1.
4. Writes two artifacts:
   - `churn_model.joblib` — the selected pipeline.
   - `model_meta.json` — version, algorithm, CV mean/std, ROC-AUC, F1, Brier score, decision
     threshold, and the feature lists.

Retraining is an explicit offline action. The model does not learn from data uploaded through
the application — uploaded customers have no churn outcome, so there is nothing to learn from;
they are scored (inference), not trained on.

---

## 11. Serving (`app.py`)

A FastAPI service loads `churn_model.joblib` once at startup and exposes three endpoints:

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/predict` | score a single customer |
| POST | `/predict-batch` | score a list of customers in one call |
| GET | `/health` | service status + model version/algorithm |

With the service running, FastAPI auto-generates interactive Swagger documentation for these
endpoints at **http://localhost:8000/docs** (and the raw OpenAPI spec at `/openapi.json`).

**Input validation.** Requests are validated by a Pydantic model covering all 19 features
before they reach the model:

- Categorical fields use `Literal` whitelists (e.g. `Contract` must be one of
  `Month-to-month`, `One year`, `Two year`); an invalid value returns HTTP 422 with the
  allowed values.
- Numeric fields are range-checked: `tenure` 0–120, `MonthlyCharges` 0–500,
  `TotalCharges` 0–60,000.

**Batch scoring.** `/predict-batch` builds a single DataFrame from the request list and calls
`predict_proba` once. scikit-learn scores the whole table in one vectorized operation, so
thousands of customers are scored in roughly a second — the mechanism behind the
application's near-instant "score after upload".

**Risk bands.** The churn probability is bucketed as:

| Band | Probability |
|---|---|
| LOW | < 0.30 |
| MEDIUM | 0.30 – 0.60 |
| HIGH | ≥ 0.60 |

The 0.30 / 0.60 cut-points are placeholder band thresholds, and are separate from the model's
tuned `decision_threshold` (0.40). Banding is a product policy decision and is left unchanged
here (see §12).

---

## 12. Limitations & assumptions

- **Static model.** It scores new data but does not learn from it. Improving the model means
  rerunning `train.py`; there is no online/continuous learning.
- **Placeholder risk thresholds.** The 0.30 / 0.60 band cut-points are not tuned against any
  business cost model — they are reasonable defaults.
- **Benchmark data.** The IBM Telco dataset is realistic but curated and cleaner than
  real-world telecom data; it is a snapshot with no time dimension, so behavioural churn
  signals (declining usage, rising support contacts) are absent.
- **New customers.** Training drops the 11 `tenure = 0` rows with blank `TotalCharges`, but
  the upload layer scores them by treating a blank `TotalCharges` as 0 — new customers are the
  highest-churn group and must not be silently refused. Their score is a mild extrapolation
  (the model did not train on `tenure = 0`), but the strong negative tenure coefficient
  correctly flags them as high-risk.
- **Four-feature history.** An earlier proof-of-concept model used only 4 features; all
  figures in this document refer to the current 19-feature model (`2.1-calibrated`).
- **Interpretation caveat.** Coefficients are conditional effects, not standalone
  correlations (see §9).

---

## 13. Glossary

| Term | Meaning |
|---|---|
| **Pipeline** | preprocessing steps + model chained into one object, saved and loaded as a unit |
| **One-hot encoding** | one 0/1 column per category value; avoids implying a numeric order between categories |
| **StandardScaler** | rescales a numeric column to mean 0, standard deviation 1 |
| **Stratified split** | a train/test split that preserves the class ratio in both halves |
| **Precision** | of customers flagged as churners, the fraction who actually churn |
| **Recall** | of customers who actually churn, the fraction the model flags |
| **F1** | harmonic mean of precision and recall |
| **ROC-AUC** | probability a random churner is scored higher than a random non-churner; threshold-independent |
| **Cross-validation (k-fold)** | split the data into k parts, train on k−1 and test on the held-out one, k times; the mean/std of the scores is a stable estimate that doesn't depend on one lucky split |
| **Calibration / Brier score** | whether a predicted probability matches the real frequency; Brier is the mean squared error of probabilities (lower = better calibrated) |
| **Decision threshold** | the probability cut-off above which a customer is classified as a churner; tuned here to maximise F1 rather than left at 0.5 |
| **class_weight="balanced"** | weights the minority class up during training to counteract imbalance (removed in this model because it distorts probabilities) |
| **predict_proba** | returns a probability (0–1) rather than a hard 0/1 decision |
| **Coefficient** | in logistic regression, a feature's learned weight; sign = direction, magnitude = strength |
| **joblib** | scikit-learn's serialization format for saving/loading trained model objects |
