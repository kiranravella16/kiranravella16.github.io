# BNPL Risk Engine

A **Buy Now, Pay Later credit decisioning service** in Java 21 / Spring Boot 3. It underwrites Pay-in-4 and monthly installment applications. For thin-file applicants on long-term financing, it runs an **alternate-bureau "second look"**. Every decline or counter offer comes with a compliant **adverse action notice**.

It's modeled on real BNPL risk platform patterns: a primary bureau pull, a rules-driven fallback to alternate data bureaus, risk-tier pricing, an explainable rule trace, idempotent decisions, a simulate mode for replaying true-case scenarios, and decision events on Kafka.

## Decision flow

```
POST /api/v1/decisions
        │
        ▼
 Primary bureau pull (Experian / FICO 8)
        │
        ▼
 RuleBook ─ state · fraud alert · delinquency · credit history · min score · inquiries · DTI
        │
        ├── all pass ───────────────────────────────► price by tier ─► APPROVED / COUNTER_OFFER
        │
        ├── hard stop (fraud, delinquency, state) ──► DECLINED + adverse action notice
        │
        └── thin file + PAY_MONTHLY + no hard stop
                 │
                 ▼  SECOND LOOK (config-ordered, stops at first hit)
            Equifax Insight Score ─► DataX ─► TransUnion alt
                 │
                 ├── hit ─► re-run rules on alternate floor ─► APPROVED / COUNTER_OFFER / DECLINED
                 └── no hit anywhere ─► DECLINED (insufficient credit history)
```

## Highlights

- **Policy as config.** Score floors, DTI cap, tiers and APRs, unsupported states, and the second-look bureau order, vendor IDs and packages all live in `application.yml` (`RiskPolicy`). Changing policy doesn't touch code.
- **Explainability.** Every rule writes a `RuleTrace` entry (pass/fail, reason code, detail), and the full trace comes back with the decision.
- **Adverse action.** Up to 4 principal reasons, ordered by severity, plus the bureau's name, address and phone, the score used, and the FCRA disclosure text.
- **Simulate mode.** `X-Simulate: true` replays recorded bureau responses from `src/main/resources/simulations/*.json`, so you can test true-case scenarios end to end without live pulls. `GET /api/v1/simulations` lists them.
- **Idempotent.** A retried `applicationId` returns the original decision and never triggers a second hard pull.
- **Events.** Decisions are published to `risk.decisions.v1`, keyed by applicationId (`acks=all`, idempotent producer), with the rule trace stripped from the event.
- **Metrics.** `risk_decisions_total{outcome,second_look,product}` and `risk_decision_latency` on `/actuator/prometheus`.

## Run it

```bash
mvn spring-boot:run                 # standalone, events logged
docker compose up --build           # with Kafka (KAFKA_ENABLED=true)
```

## Try it

```bash
# Thin file at Experian → approved via Equifax Insight Score (simulate mode)
curl -s -X POST localhost:8081/api/v1/decisions -H 'Content-Type: application/json' -H 'X-Simulate: true' -d '{
  "applicationId":"app-1001","customerId":"sim-thin-approve","product":"PAY_MONTHLY",
  "amount":1800,"termMonths":12,"monthlyIncome":4800,"state":"CA"}' | jq '{outcome,tier,apr,bureauUsed,secondLookApplied}'

# Delinquent → declined with adverse action notice
curl -s -X POST localhost:8081/api/v1/decisions -H 'Content-Type: application/json' -H 'X-Simulate: true' -d '{
  "applicationId":"app-1002","customerId":"sim-delinquent","product":"PAY_MONTHLY",
  "amount":3000,"termMonths":12,"monthlyIncome":4800,"state":"CA"}' | jq .adverseAction

# Live mode uses the deterministic mock bureau: try customerIds "thin-123", "fraud-9", "cust-42"
```

Simulation scenarios:

| customerId | Scenario |
|---|---|
| `sim-prime` | Thick file, 781 → Tier A |
| `sim-thin-approve` | Experian no-hit → Equifax Insight Score 712 → Tier B |
| `sim-thin-datax` | Experian and Equifax no-hit → DataX 668 → Tier C |
| `sim-thin-decline` | Thin everywhere → declined |
| `sim-delinquent` | Hard stop: no second look, even though Equifax would have scored |

## Tests

```bash
mvn test
```

`DecisionEngineTest` covers each branch of the decision flow with stub bureaus: prime approval, a second-look hit, fall-through order, product eligibility, hard stops, severity-ordered notices, counter offers, and state rules. `DecisionApiTest` covers simulate mode, idempotency, and validation through the HTTP layer.

## Layout

```
src/main/java/com/kiran/risk
├── bureau/     BureauGateway, deterministic mock, simulate-mode replayer
├── config/     RiskPolicy (typed, externalized underwriting policy)
├── decision/   DecisionEngine (pure logic), DecisionService (idempotency, metrics, events)
├── events/     Kafka / logging publishers
├── model/      records, reason codes
├── rules/      RuleBook
└── web/        REST API
```

> The bureau data and notice wording are illustrative. The bureau integrations are mocks, not real vendor APIs.
