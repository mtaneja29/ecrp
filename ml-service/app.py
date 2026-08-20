import json
from typing import List, Literal

import joblib
import pandas as pd
from fastapi import FastAPI
from pydantic import BaseModel, Field

app = FastAPI(title="ECRP ML Service")

# loaded once at startup, not per request
model = joblib.load("churn_model.joblib")
with open("model_meta.json") as f:
    META = json.load(f)

YES_NO = Literal["Yes", "No"]
INTERNET_ADDON = Literal["Yes", "No", "No internet service"]


class CustomerFeatures(BaseModel):
    tenure: int = Field(ge=0, le=120)
    MonthlyCharges: float = Field(ge=0, le=500)
    TotalCharges: float = Field(ge=0, le=60000)
    SeniorCitizen: Literal[0, 1]
    gender: Literal["Male", "Female"]
    Partner: YES_NO
    Dependents: YES_NO
    PhoneService: YES_NO
    MultipleLines: Literal["Yes", "No", "No phone service"]
    InternetService: Literal["DSL", "Fiber optic", "No"]
    OnlineSecurity: INTERNET_ADDON
    OnlineBackup: INTERNET_ADDON
    DeviceProtection: INTERNET_ADDON
    TechSupport: INTERNET_ADDON
    StreamingTV: INTERNET_ADDON
    StreamingMovies: INTERNET_ADDON
    Contract: Literal["Month-to-month", "One year", "Two year"]
    PaperlessBilling: YES_NO
    PaymentMethod: Literal["Electronic check", "Mailed check",
                           "Bank transfer (automatic)", "Credit card (automatic)"]


def band(prob: float) -> str:
    return "LOW" if prob < 0.3 else "MEDIUM" if prob < 0.6 else "HIGH"


def score(rows: List[CustomerFeatures]) -> list[dict]:
    frame = pd.DataFrame([r.model_dump() for r in rows])
    probs = model.predict_proba(frame)[:, 1]
    return [{"churn_probability": round(float(p), 4), "risk_band": band(p)} for p in probs]


@app.get("/health")
def health():
    return {"status": "ok",
            "model_version": META["model_version"],
            "algorithm": META["algorithm"]}


@app.post("/predict")
def predict(customer: CustomerFeatures):
    return score([customer])[0]


@app.post("/predict-batch")
def predict_batch(customers: List[CustomerFeatures]):
    return score(customers)
