# ECRP — REST API

The Spring Boot application exposes a JSON REST API alongside its web UI. This is the
machine-facing interface (external systems, scripts); the Thymeleaf pages are the
human-facing equivalent.

When the app is running, interactive documentation is available via Swagger UI at
**http://localhost:8080/swagger-ui/index.html**, and the raw OpenAPI spec at
`/v3/api-docs`. This document is the static reference.

- **Base URL:** `http://localhost:8080/api`
- **Format:** JSON
- **Note:** scoring endpoints (`/upload`, `/scan`) require the ML service (FastAPI, :8000)
  to be running; read endpoints do not.

---

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/upload` | upload a customer CSV and score it |
| GET | `/api/customers` | list scored customers by risk band, paginated |
| GET | `/api/customers/{id}` | get one customer with prediction and flag reasons |
| POST | `/api/scan` | re-run predictions for all customers |
| POST | `/api/customers/{id}/unflag` | mark a customer as handled |
| DELETE | `/api/customers` | delete all customers and predictions |

---

### POST /api/upload

Uploads a customer CSV (multipart form field `file`), replaces the current dataset, and
scores every row. If the ML service is unreachable the data still loads and can be scored
later via `/api/scan`.

- **Request:** `multipart/form-data`, field `file` = a `.csv` file
- **Response `200`:** upload receipt

```json
{
  "rowsReceived": 40,
  "rowsLoaded": 38,
  "rowsSkipped": 2,
  "sampleErrors": [
    "Row 8 (id 9518-RWHZL): blank TotalCharges",
    "Row 17 (id 1494-EJZDW): blank TotalCharges"
  ]
}
```

- **`400`** if no file is provided.

---

### GET /api/customers

Returns the summary counts and one page of scored customers, highest churn risk first.

- **Query params:**
  - `band` — `HIGH` | `MEDIUM` | `LOW` | `ALL` (default `HIGH`)
  - `page` — zero-based page number (default `0`, 20 per page)
- **Response `200`:**

```json
{
  "totalCustomers": 7032,
  "highCount": 2394,
  "highPendingCount": 2394,
  "mediumCount": 1676,
  "lowCount": 2962,
  "scored": true,
  "band": "HIGH",
  "page": 0,
  "totalPages": 120,
  "rows": [
    {
      "customerId": 23335,
      "externalId": "9497-QCMMS",
      "contract": "Month-to-month",
      "tenure": 1,
      "monthlyCharges": 93.55,
      "churnPercentage": "93.8%",
      "riskBand": "HIGH",
      "actionStatus": "PENDING"
    }
  ]
}
```

(Medium/low pending counts are included as well; abbreviated here.)

---

### GET /api/customers/{id}

Returns one customer's full profile, prediction, and the reasons the model flagged them.

- **Path param:** `id` — the customer's internal id (from `customerId` in the list)
- **Response `200`:**

```json
{
  "customer": {
    "id": 23335,
    "externalId": "9497-QCMMS",
    "gender": "Male",
    "tenure": 1,
    "contract": "Month-to-month",
    "internetService": "Fiber optic",
    "monthlyCharges": 93.55,
    "totalCharges": 93.55
  },
  "prediction": {
    "churnProbability": 0.9384,
    "riskBand": "HIGH",
    "assessedAt": "2026-08-06T18:59:00",
    "actionStatus": "PENDING"
  },
  "churnPercentage": "93.8%",
  "reasons": [
    "Month-to-month contract - no commitment; the strongest churn driver in the model",
    "New customer (1 months) - churn risk is highest early in the relationship",
    "Fiber optic internet - historically the highest-churn service tier in this data"
  ]
}
```

(Customer object abbreviated; the full record has all 19 feature fields.)

- **`404`** if the customer does not exist or has not been scored.

---

### POST /api/scan

Re-runs churn predictions for all loaded customers.

- **Request:** no body
- **Response `200`:**

```json
{ "scored": 7032 }
```

- **`503`** if the ML service is unreachable.

---

### POST /api/customers/{id}/unflag

Marks a customer as handled (sets `actionStatus` to `UNFLAGGED`).

- **Path param:** `id`
- **Response `204`:** no content

---

### DELETE /api/customers

Deletes all customer data and predictions.

- **Request:** no body
- **Response `204`:** no content

---

## Status codes

| Code | Meaning |
|---|---|
| 200 | success, response body returned |
| 204 | success, no content |
| 400 | bad request (e.g. no file on upload) |
| 404 | customer not found or not yet scored |
| 503 | ML service unreachable during scoring |
