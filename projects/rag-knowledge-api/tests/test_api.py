import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.knowledge import KnowledgeBase
from app.llm import AnthropicProvider
from app.main import create_app

KEY = {"X-API-Key": "test-key"}


def client_with(kb: KnowledgeBase | None = None) -> TestClient:
    # a custom knowledge base runs in isolation; the default one loads the sample corpus
    return TestClient(create_app(kb=kb, api_keys={"test-key"}, corpus_dir="" if kb else None))


@pytest.fixture()
def client():
    with client_with() as c:
        yield c


def test_requires_api_key(client):
    assert client.post("/v1/ask", json={"question": "anything"}).status_code == 401
    assert client.post("/v1/ask", json={"question": "anything"}, headers={"X-API-Key": "nope"}).status_code == 401
    assert client.get("/healthz").json()["documents"] == 5


def test_ask_returns_cited_answer(client):
    r = client.post("/v1/ask", headers=KEY, json={"question": "Kafka consumer lag is growing, how do I mitigate?"})
    body = r.json()
    assert r.status_code == 200
    assert body["grounded"] and body["provider"] == "extractive"
    assert body["citations"][0]["doc_id"] == "kafka-consumer-lag-md"


def test_upload_then_ask(client):
    r = client.post("/v1/documents", headers=KEY, json={
        "title": "Payments gateway 503s",
        "text": "# Payments gateway 503s\n## Mitigation\nFail over the payments gateway to the secondary region "
                "with `make failover REGION=us-east1` and page the payments on-call.",
        "tags": ["payments"]})
    assert r.status_code == 201 and r.json()["chunks"] == 1
    body = client.post("/v1/ask", headers=KEY, json={"question": "payments gateway returning 503", "tags": ["payments"]}).json()
    assert "failover" in body["answer"]
    assert body["citations"][0]["title"] == "Payments gateway 503s"


def test_search_endpoint_reports_both_rankers(client):
    hits = client.get("/v1/search", headers=KEY, params={"q": "openssl verify root.pem", "top_k": 3}).json()
    assert hits[0]["doc_id"] == "tls-certificate-expiry-md"
    assert hits[0]["lexical_rank"] == 0 and hits[0]["dense_rank"] is not None


def test_validation(client):
    assert client.post("/v1/ask", headers=KEY, json={"question": "hi", "top_k": 50}).status_code == 422


def test_anthropic_provider_gets_redacted_context_and_answer_is_parsed():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["headers"] = request.headers
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"content": [{"type": "text", "text": "Raise the memory limit [1]."}]})

    provider = AnthropicProvider("sk-test", model="test-model",
                                 client=httpx.Client(base_url="https://api.anthropic.com",
                                                     transport=httpx.MockTransport(handler),
                                                     headers={"x-api-key": "sk-test"}))
    kb = KnowledgeBase(provider=provider)
    kb.add_document("Leaky runbook", "# Leaky runbook\nPod OOMKilled? Ask jane@corp.com, SSN 123-45-6789 on file. "
                    "Raise the memory limit.", source="leaky.md")
    with client_with(kb) as c:
        body = c.post("/v1/ask", headers=KEY, json={"question": "pod OOMKilled what now?"}).json()

    prompt = seen["body"]["messages"][0]["content"]
    assert seen["body"]["model"] == "test-model"
    assert "jane@corp.com" not in prompt and "123-45-6789" not in prompt
    assert "[REDACTED_EMAIL]" in prompt and "<passage n=\"1\"" in prompt
    assert "untrusted" in seen["body"]["system"]
    assert body["answer"] == "Raise the memory limit [1]."
    assert body["provider"] == "anthropic" and body["redactions"] == {"SSN": 1, "EMAIL": 1}
    assert body["citations"][0]["doc_id"] == "leaky-md"


def test_provider_outage_returns_502():
    provider = AnthropicProvider("sk-test", client=httpx.Client(
        base_url="https://api.anthropic.com", transport=httpx.MockTransport(lambda r: httpx.Response(529))))
    kb = KnowledgeBase(provider=provider)
    kb.add_document("Doc", "# Doc\nRestart the consumer deployment when lag grows.", source="doc.md")
    with client_with(kb) as c:
        r = c.post("/v1/ask", headers=KEY, json={"question": "lag grows what do I restart?"})
    assert r.status_code == 502
