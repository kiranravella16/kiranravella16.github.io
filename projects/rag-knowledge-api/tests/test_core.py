import re
from pathlib import Path

import pytest

from app.chunking import chunk_document, split_sections, window
from app.knowledge import KnowledgeBase
from app.redaction import redact

CORPUS = Path(__file__).parent.parent / "corpus"


@pytest.fixture(scope="module")
def kb() -> KnowledgeBase:
    kb = KnowledgeBase()
    assert kb.load_directory(CORPUS) == 5
    return kb


# --- chunking -------------------------------------------------------------------------------------------

def test_sections_carry_heading_path():
    md = "# Runbook\nintro\n## Diagnosis\nstep one\n### Deep\ndeeper\n## Mitigation\nfix it"
    assert split_sections(md) == [
        ("Runbook", "intro"),
        ("Runbook > Diagnosis", "step one"),
        ("Runbook > Diagnosis > Deep", "deeper"),
        ("Runbook > Mitigation", "fix it"),
    ]


def test_long_sections_are_windowed_with_overlap():
    words = [f"w{i}" for i in range(25)]
    parts = window(words, size=10, overlap=3)
    assert parts[0][-3:] == parts[1][:3]
    assert parts[-1][-1] == "w24"
    chunks = chunk_document("d", "T", "# T\n" + " ".join(words), max_words=10, overlap=3)
    assert chunks[1].section == "T (cont. 1)"
    assert len({c.chunk_id for c in chunks}) == len(chunks)


# --- retrieval ------------------------------------------------------------------------------------------

@pytest.mark.parametrize("question, expected_doc", [
    ("pod exit code 137 OOMKilled restarting", "gke-crashloopbackoff-md"),
    ("consumer group lag growing on a few partitions", "kafka-consumer-lag-md"),
    ("PKIX path validation failed certificate expired", "tls-certificate-expiry-md"),
    ("HikariPool connection is not available request timed out", "db-connection-pool-md"),
    ("when should the release captain roll back a deploy", "release-rollback-md"),
    ("rebalance storm max.poll.interval.ms", "kafka-consumer-lag-md"),
])
def test_hybrid_search_finds_the_right_runbook(kb, question, expected_doc):
    assert kb.search(question, top_k=1)[0].chunk.doc_id == expected_doc


def test_tag_filter_restricts_results(kb):
    hits = kb.search("restart the deployment", top_k=10, tags={"pki"})
    assert hits and {h.chunk.doc_id for h in hits} == {"tls-certificate-expiry-md"}


def test_reupload_is_idempotent_and_replacement_reindexes():
    kb = KnowledgeBase()
    d1 = kb.add_document("Note", "# Note\nalpha bravo charlie", source="note.md")
    assert kb.add_document("Note", "# Note\nalpha bravo charlie", source="note.md") is d1
    kb.add_document("Note", "# Note\ndelta echo foxtrot", source="note.md")
    assert len(kb.index.chunks) == 1
    assert kb.search("delta foxtrot")[0].chunk.text == "delta echo foxtrot"
    assert all(h.lexical_rank is None for h in kb.search("alpha bravo"))


# --- answering ------------------------------------------------------------------------------------------

def test_extractive_answer_is_grounded_and_cited(kb):
    a = kb.ask("How do I fix an OOMKilled pod?")
    assert a.grounded
    assert a.citations and a.citations[0]["doc_id"] == "gke-crashloopbackoff-md"
    assert "memory limit" in a.answer
    assert re.search(r"\[\d+\]", a.answer)
    assert a.redactions == {}  # runbook prose like "Missing secret: recreate it" is not a credential


def test_unanswerable_question_is_not_grounded(kb):
    a = kb.ask("zebra quantum lasagna")
    assert not a.grounded and a.citations == []


# --- redaction ------------------------------------------------------------------------------------------

def test_redacts_pii_and_secrets():
    text = ("Customer 123-45-6789 jane@corp.com called from (408) 555-0199 about card 4111 1111 1111 1111. "
            "Key AKIAIOSFODNN7EXAMPLE, header Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456, "
            "password=hunter2 client_secret: s3cr3tv4lue\n-----BEGIN PRIVATE KEY-----\nMIIEv...\n-----END PRIVATE KEY-----")
    out, counts = redact(text)
    for leaked in ["123-45-6789", "jane@corp.com", "555-0199", "4111 1111", "AKIAIOSFODNN7EXAMPLE",
                   "abcdefghijklmnop", "hunter2", "s3cr3tv4lue", "MIIEv"]:
        assert leaked not in out
    assert counts == {"SSN": 1, "EMAIL": 1, "PHONE": 1, "CARD_NUMBER": 1, "AWS_ACCESS_KEY": 1,
                      "BEARER_TOKEN": 1, "PASSWORD": 2, "PRIVATE_KEY": 1}


def test_does_not_redact_non_luhn_numbers_or_metrics():
    text = "lag above 50000 messages, order 1234567890123 failed, max.poll.records=500"
    assert redact(text) == (text, {})


def test_document_grouping_and_mitigation_expansion(kb):
    # one GKE chunk literally says "Bad release: roll back", but the release runbook has more supporting chunks
    a = kb.ask("How do I roll back a bad release?")
    assert {c["doc_id"] for c in a.citations} == {"release-rollback-md"}
    assert "kubectl rollout undo" in a.answer

    # the question only matches the symptoms wording, but the answer must include the fix
    a = kb.ask("Certificate expired and clients get PKIX errors. What do I do?")
    assert any(c["section"].endswith("Mitigation") for c in a.citations)
    assert "/renew" in a.answer
