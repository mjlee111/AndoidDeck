"""Self-signed TLS identity. Phones pin the certificate's SHA-256 fingerprint when pairing."""

from __future__ import annotations

import hashlib
import os
import ssl
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path


def ensure_certificate(directory: Path, common_name: str) -> tuple[Path, Path, str]:
    """Create (once) and load the server key pair. Returns (key, cert, sha256 hex of cert DER)."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    directory.mkdir(parents=True, exist_ok=True)
    key_path = directory / "tls.key"
    crt_path = directory / "tls.crt"
    if not (key_path.is_file() and crt_path.is_file()):
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([
            x509.NameAttribute(NameOID.COMMON_NAME, common_name[:64] or "AndroidDeck"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "AndroidDeck"),
        ])
        now = datetime.now(timezone.utc)
        cert = (
            x509.CertificateBuilder()
            .subject_name(name)
            .issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(days=1))
            .not_valid_after(now + timedelta(days=365 * 30))
            .sign(key, hashes.SHA256())
        )
        key_bytes = key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        tmp = key_path.with_suffix(".tmp")
        tmp.write_bytes(key_bytes)
        if sys.platform != "win32":
            os.chmod(tmp, 0o600)
        os.replace(tmp, key_path)
        crt_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))

    cert = x509.load_pem_x509_certificate(crt_path.read_bytes())
    der = cert.public_bytes(serialization.Encoding.DER)
    return key_path, crt_path, hashlib.sha256(der).hexdigest()


def server_context(key_path: Path, crt_path: Path) -> ssl.SSLContext:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(str(crt_path), str(key_path))
    return ctx


def short_fingerprint(fp: str) -> str:
    """First 64 bits, grouped for reading aloud: 'AB12 CD34 EF56 7890'. Shown on PC and phone."""
    head = fp[:16].upper()
    return " ".join(head[i:i + 4] for i in range(0, 16, 4))
