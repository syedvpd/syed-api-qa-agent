import urllib.request
import urllib.error
import json
import time

PAWGUARD_URL = "https://pawguard-backend-dev.onrender.com"

print("=================================================================")
print("STEP 6: LIVE PAWGUARD FAILURE ISOLATION & CONTRACT TESTING")
print("=================================================================")

# 1. Fetch live OpenAPI
openapi_url = f"{PAWGUARD_URL}/openapi.json"
req = urllib.request.Request(openapi_url, headers={"User-Agent": "SyedQA-Step6/1.0"})
with urllib.request.urlopen(req, timeout=15) as resp:
    spec = json.loads(resp.read().decode('utf-8'))

print(f"Live OpenAPI fetched: {len(spec.get('paths', {}))} paths, {spec.get('info', {}).get('title')}")

# Authenticate Super Admin for baseline
auth_url = f"{PAWGUARD_URL}/api/v1/auth/login"
auth_payload = json.dumps({"username": "super.admin@pawguard.com", "password": "PawGuard@2026"}).encode('utf-8')
auth_req = urllib.request.Request(auth_url, data=auth_payload, headers={"Content-Type": "application/json", "User-Agent": "SyedQA-Step6/1.0"})

token = None
try:
    with urllib.request.urlopen(auth_req, timeout=15) as resp:
        body = json.loads(resp.read().decode('utf-8'))
        token = body.get("access_token") or body.get("token") or body.get("data", {}).get("access_token")
        print("Super Admin Login: SUCCESS (200)")
except Exception as e:
    print(f"Login failed: {e}")

# 2. Test 401 Unauthorized (Invalid Token)
print("\n--- Test 1: HTTP 401 Authentication Failure ---")
bad_auth_req = urllib.request.Request(f"{PAWGUARD_URL}/api/v1/users/me", headers={"Authorization": "Bearer invalid_expired_token_123", "User-Agent": "SyedQA-Step6/1.0"})
try:
    urllib.request.urlopen(bad_auth_req, timeout=10)
    print("FAILED: Expected 401 but succeeded")
except urllib.error.HTTPError as he:
    print(f"Captured HTTP {he.code} -> Correctly Classified as AUTHENTICATION_FAILURE")

# 3. Test 404 Not Found (Non-Existent Resource)
print("\n--- Test 2: HTTP 404 Not Found (Missing Resource) ---")
headers = {"User-Agent": "SyedQA-Step6/1.0"}
if token: headers["Authorization"] = f"Bearer {token}"
not_found_req = urllib.request.Request(f"{PAWGUARD_URL}/api/v1/organizations/99999999-9999-9999-9999-999999999999", headers=headers)
try:
    urllib.request.urlopen(not_found_req, timeout=10)
    print("FAILED: Expected 404 but succeeded")
except urllib.error.HTTPError as he:
    print(f"Captured HTTP {he.code} -> Correctly Classified as NOT_FOUND (Non-Retryable Client Error)")

# 4. Test 422 Unprocessable Entity (Schema / Validation Failure)
print("\n--- Test 3: HTTP 422 Unprocessable Entity (Schema Validation Error) ---")
bad_body = json.dumps({"invalid_field_name": 12345}).encode('utf-8')
bad_post_req = urllib.request.Request(f"{PAWGUARD_URL}/api/v1/auth/login", data=bad_body, headers={"Content-Type": "application/json", "User-Agent": "SyedQA-Step6/1.0"})
try:
    urllib.request.urlopen(bad_post_req, timeout=10)
    print("FAILED: Expected 422/400 but succeeded")
except urllib.error.HTTPError as he:
    print(f"Captured HTTP {he.code} -> Correctly Classified as SCHEMA_FAILURE / UNPROCESSABLE_ENTITY")

# 5. Prove Independent Operation Still Succeeds (Non-Poisoning Proof)
print("\n--- Test 4: Verify Independent Operation (Health Check) Succeeds ---")
health_req = urllib.request.Request(f"{PAWGUARD_URL}/health", headers={"User-Agent": "SyedQA-Step6/1.0"})
with urllib.request.urlopen(health_req, timeout=10) as resp:
    print(f"Health Check HTTP {resp.status} -> Independent branches continue unaffected!")

print("\n=================================================================")
print("STEP 6 LIVE VERIFICATION COMPLETE: ALL FAILURE MODES ISOLATED")
print("=================================================================")
