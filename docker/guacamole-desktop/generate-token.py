#!/usr/bin/env python3
# Generates a guacamole-auth-json token for auto-login to VNC desktop.
#
# Token format: standard-base64( HMAC-SHA256(key, IV+encrypted) || IV || AES-128-CBC(JSON) )
#
# IMPORTANT: Guacamole uses DatatypeConverter.parseBase64Binary() which expects
# STANDARD base64 (+, /, = padding), NOT base64url (-, _, no padding).
#
# The key is the raw bytes decoded from the hex string in guacamole.properties
# (json-secret-key).  It is used directly for both HMAC and AES — no MD5 hashing.
import json, os, hmac, hashlib, base64, urllib.parse
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.padding import PKCS7
from cryptography.hazmat.backends import default_backend

# Must match json-secret-key in guacamole.properties (hex-encoded 128-bit key)
SECRET_HEX = "4b38735f707570735f677561636b6579"
key = bytes.fromhex(SECRET_HEX)

payload = {
    "username": "user",
    "expires": 4102444800000,
    "connections": {
        "desktop": {
            "protocol": "vnc",
            "parameters": {
                "hostname": "localhost",
                "port": "5901",
                "color-depth": "24"
            }
        }
    }
}

json_bytes = json.dumps(payload, separators=(",", ":")).encode("utf-8")

iv = os.urandom(16)

padder = PKCS7(128).padder()
padded = padder.update(json_bytes) + padder.finalize()

cipher = Cipher(algorithms.AES(key), modes.CBC(iv), backend=default_backend())
enc = cipher.encryptor()
encrypted = enc.update(padded) + enc.finalize()

data = iv + encrypted
sig = hmac.new(key, data, hashlib.sha256).digest()

# Standard base64 (not urlsafe) with padding — Guacamole requires this format.
token = base64.b64encode(sig + data).decode("utf-8")
# URL-encode for safe embedding in ?data= query parameter
# (+, /, = must be percent-encoded)
print(urllib.parse.quote(token, safe=''), end="")
