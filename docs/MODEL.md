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

- **Type:** binary classification (logistic regression)
- **Library:** scikit-learn 1.8
- **Artifact:** `churn_model.joblib` (a complete preprocessing + model pipeline)
- **Version:** `2.0-full-features` (recorded in `model_meta.json`)

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

Two candidate classifiers were trained on the identical preprocessing pipeline and the
identical train/test split; the only difference between the two runs is the final estimator.

| Candidate | Churn precision | Churn recall | Churn F1 | ROC-AUC | Accuracy |
|---|---|---|---|---|---|
| **Logistic Regression** (selected) | 0.49 | 0.80 | 0.607 | **0.835** | 0.73 |
| HistGradientBoosting | 0.52 | 0.75 | 0.613 | 0.831 | 0.75 |

Both use `class_weight="balanced"` to counteract the class imbalance. Logistic regression
uses `max_iter=2000`; gradient boosting uses `random_state=42`.

**Selection rule:** the two models are effectively tied (churn-F1 differs by 0.006, and
logistic regression wins on ROC-AUC). The rule encoded in `train.py` requires gradient
boosting to beat logistic regression on churn-F1 by more than 0.01 to justify sacrificing
interpretability. It does not, so **logistic regression is selected** — the simpler model
whose every prediction can be explained via its coefficients.

---

## 8. Reading the metrics

The metrics are reported on the held-out 20% test set (1,407 customers).

- **Churn recall = 0.80** is the headline number. Of customers who actually churned, the
  model correctly flags 80%. For a retention use case this is the metric that matters: a
  missed churner (a lost customer) is far more costly than a false alarm (an unnecessary
  retention offer).
- **Churn precision = 0.49** means roughly half of the customers flagged as high-risk would
  not have churned. This is an accepted trade-off, tuned deliberately via
  `class_weight="balanced"`, which favours recall over precision.
- **Accuracy = 0.73** is intentionally not optimised. With a 26.5% churn rate, a model that
  never predicts churn scores ~74% accuracy while being useless. Accuracy is the wrong
  objective here; recall and ROC-AUC are the right ones.
- **ROC-AUC = 0.835** measures ranking quality independent of any threshold — the
  probability that a random churner is scored higher than a random non-churner.

---

## 9. What the model learned

Because logistic regression is linear, each feature has a single coefficient. A positive
coefficient pushes toward churn; a negative one pushes away; magnitude indicates strength
(comparable across features because the numerics are standardized). Intercept: **−0.30**.

**Strongest churn drivers (positive):**

| Coefficient | Feature |
|---|---|
| +0.70 | `Contract = Month-to-month` |
| +0.66 | `InternetService = Fiber optic` |
| +0.61 | `TotalCharges` |
| +0.25 | `StreamingTV = Yes` |
| +0.24 | `StreamingMovies = Yes` |
| +0.23 | `PaymentMethod = Electronic check` |

**Strongest protective factors (negative):**

| Coefficient | Feature |
|---|---|
| −1.25 | `tenure` |
| −0.78 | `Contract = Two year` |
| −0.62 | `MonthlyCharges` |
| −0.59 | `InternetService = DSL` |

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
2. Splits train/test (§2).
3. Trains both candidates on the shared pipeline and prints a full classification report plus
   ROC-AUC for each.
4. Applies the selection rule (§7) and prints the winner.
5. Writes two artifacts:
   - `churn_model.joblib` — the selected pipeline.
   - `model_meta.json` — version, algorithm, metrics, and the feature lists.

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

The 0.30 / 0.60 cut-points are placeholder thresholds (see §12).

---

## 12. Limitations & assumptions

- **Static model.** It scores new data but does not learn from it. Improving the model means
  rerunning `train.py`; there is no online/continuous learning.
- **Placeholder risk thresholds.** The 0.30 / 0.60 band cut-points are not tuned against any
  business cost model — they are reasonable defaults.
- **Benchmark data.** The IBM Telco dataset is realistic but curated and cleaner than
  real-world telecom data; it is a snapshot with no time dimension, so behavioural churn
  signals (declining usage, rising support contacts) are absent.
- **Four-feature history.** An earlier proof-of-concept model used only 4 features; all
  figures in this document refer to the current 19-feature model (`2.0-full-features`).
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
| **class_weight="balanced"** | weights the minority class up during training to counteract imbalance |
| **predict_proba** | returns a probability (0–1) rather than a hard 0/1 decision |
| **Coefficient** | in logistic regression, a feature's learned weight; sign = direction, magnitude = strength |
| **joblib** | scikit-learn's serialization format for saving/loading trained model objects |
