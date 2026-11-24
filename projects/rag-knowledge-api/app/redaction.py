"""Scrubs secrets and PII from text before it leaves the service (to an LLM provider, logs, etc.)."""
from __future__ import annotations

import re
from collections import Counter

_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("PRIVATE_KEY", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", re.S)),
    ("AWS_ACCESS_KEY", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("BEARER_TOKEN", re.compile(r"(?i)\bbearer\s+[a-z0-9._~+/=-]{20,}")),
    ("JWT", re.compile(r"\beyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\b")),
    ("PASSWORD", re.compile(r"(?i)\b(password|passwd|pwd|api[_-]?key|secret[_-]?key|client[_-]?secret)\s*[:=]\s*\S+")),
    ("SSN", re.compile(r"\b(?!000|666|9\d\d)\d{3}-(?!00)\d{2}-(?!0000)\d{4}\b")),
    ("EMAIL", re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")),
    ("PHONE", re.compile(r"(?<!\d)(?:\+?1[ .-]?)?\(?\d{3}\)?[ .-]\d{3}[ .-]\d{4}(?!\d)")),
]
_CARD = re.compile(r"\b(?:\d[ -]?){13,19}\b")


def _luhn_ok(number: str) -> bool:
    digits = [int(d) for d in number if d.isdigit()]
    checksum = 0
    for i, d in enumerate(reversed(digits)):
        if i % 2 == 1:
            d *= 2
            if d > 9:
                d -= 9
        checksum += d
    return len(digits) >= 13 and checksum % 10 == 0


def redact(text: str) -> tuple[str, dict[str, int]]:
    """Returns the redacted text and a count per redaction type."""
    counts: Counter[str] = Counter()

    def sub(label: str):
        def _replace(m: re.Match[str]) -> str:
            counts[label] += 1
            return f"[REDACTED_{label}]"
        return _replace

    for label, pattern in _PATTERNS:
        text = pattern.sub(sub(label), text)

    def card(m: re.Match[str]) -> str:
        if _luhn_ok(m.group(0)):
            counts["CARD_NUMBER"] += 1
            return "[REDACTED_CARD_NUMBER]"
        return m.group(0)

    text = _CARD.sub(card, text)
    return text, dict(counts)
