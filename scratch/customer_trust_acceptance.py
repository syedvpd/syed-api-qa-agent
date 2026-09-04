import urllib.request
import json
import time
import hashlib
import os

BASE_URL = 'https://syed-api-testing-agent.onrender.com'
OPENAPI_URL = 'https://petstore.swagger.io/v2/swagger.json'
TARGET_BASE = 'https://petstore.swagger.io/v2'

print('=== 1. FETCHING PETSTORE OPENAPI CONTRACT & COMPUTING IMMUTABLE HASH ===')
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

raw_spec = None
for attempt in range(5):
    try:
        req_spec = urllib.request.Request(OPENAPI_URL, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req_spec, timeout=30, context=ctx) as resp:
            raw_spec = resp.read()
            break
    except Exception as e:
        print(f'Attempt {attempt+1} fetching spec failed: {e}. Retrying...')
        time.sleep(3)

if not raw_spec:
    raise RuntimeError('Failed to fetch OpenAPI spec after 5 attempts')

contract_hash = hashlib.sha256(raw_spec).hexdigest()
spec_json = json.loads(raw_spec.decode('utf-8'))
paths_count = len(spec_json.get('paths', {}))
definitions_count = len(spec_json.get('definitions', {}))
print(f'OpenAPI URL: {OPENAPI_URL}')
print(f'SHA-256 Contract Hash: {contract_hash}')
print(f'Raw Paths Count: {paths_count}')
print(f'Definitions/Schemas Count: {definitions_count}')

print('\n=== 2. OBTAINING AUTH TOKEN & TRIGGERING NEW LIVE TEST RUN ===')
auth_req = urllib.request.Request(
    f'{BASE_URL}/api/auth/token',
    data=json.dumps({}).encode('utf-8'),
    headers={'Content-Type': 'application/json', 'User-Agent': 'Mozilla/5.0'}
)
with urllib.request.urlopen(auth_req, timeout=15) as auth_resp:
    auth_data = json.loads(auth_resp.read().decode('utf-8'))
    auth_token = auth_data['token']
    user_id = auth_data.get('userId', 'anonymous')
    print(f'Authenticated as {user_id} | Bearer Token acquired.')

payload = {
    'openapiUrl': OPENAPI_URL,
    'environmentType': 'STAGING',
    'authType': 'NONE',
    'timeoutSeconds': 600
}

req_run = urllib.request.Request(
    f'{BASE_URL}/api/runs',
    data=json.dumps(payload).encode('utf-8'),
    headers={
        'Content-Type': 'application/json',
        'Authorization': f'Bearer {auth_token}',
        'User-Agent': 'Mozilla/5.0'
    }
)

with urllib.request.urlopen(req_run, timeout=30) as resp:
    run_data = json.loads(resp.read().decode('utf-8'))
    run_id = run_data.get('runId') or run_data.get('id')
    print(f'NEW Test Run Created: ID = {run_id}')

auth_headers = {
    'Authorization': f'Bearer {auth_token}',
    'User-Agent': 'Mozilla/5.0'
}

print('\n=== 3. POLLING TEST RUN EXECUTION PROGRESS ===')
status = 'RUNNING'
for i in range(40):
    time.sleep(3)
    req_status = urllib.request.Request(f'{BASE_URL}/api/runs/{run_id}', headers=auth_headers)
    with urllib.request.urlopen(req_status, timeout=30) as resp:
        curr = json.loads(resp.read().decode('utf-8'))
        status = curr.get('status')
        completed = curr.get('completedSteps', 0)
        total = curr.get('totalSteps', 0)
        print(f'Poll {i+1}: Status = {status} | Progress = {completed}/{total}')
        if status in ['COMPLETED', 'FAILED', 'CANCELLED']:
            break

print(f'\nFinal Run Status: {status}')

print('\n=== 4. FETCHING CANONICAL ROOT CAUSE SUMMARY & RECONCILIATION LEDGER ===')
summary_req = urllib.request.Request(f'{BASE_URL}/api/runs/{run_id}/evidence/summary', headers=auth_headers)
with urllib.request.urlopen(summary_req, timeout=30) as resp:
    summary = json.loads(resp.read().decode('utf-8'))
    print(json.dumps(summary, indent=2))

print('\n=== 5. VERIFYING 4 CANONICAL PILLARS & ACCOUNTING INVARIANTS ===')
discovered = summary.get('discoveredOperations', 0)
planned = summary.get('totalPlannedTests', 0)
sent = summary.get('httpSentCount', 0)
not_sent = summary.get('httpNotSentCount', 0)
passed = summary.get('passedCount', 0)
failed = summary.get('failedCount', 0)
other_term = summary.get('otherTerminalCount', 0)
blocked = summary.get('blockedCount', 0)
unsupported = summary.get('unsupportedCount', 0)
unique_dispatched = summary.get('uniqueEndpointsDispatched', 0)
accounting_status = summary.get('accountingStatus')
reconciled = summary.get('reconciled')
equation = summary.get('reconciliationEquation')

print(f'PILLAR 1 - API Surface (Discovered Operations): {discovered}')
print(f'PILLAR 2 - Test Plan (Total Planned Tests): {planned}')
print(f'PILLAR 3 - Wire Execution: Dispatched={sent}, Withheld={not_sent}')
print(f'PILLAR 4 - Unique Dispatched Endpoints: {unique_dispatched}')
print(f'Results Breakdown: Passed={passed}, Failed={failed}, Blocked={blocked}, Unsupported={unsupported}')
print(f'Accounting Status: {accounting_status}')
print(f'Reconciled: {reconciled}')
print(f'Equation: {equation}')

assert planned == sent + not_sent, f'Planned mismatch: {planned} != {sent} + {not_sent}'
assert sent == passed + failed + other_term, f'Dispatched mismatch: {sent} != {passed} + {failed} + {other_term}'
assert not_sent == blocked + unsupported, f'Withheld mismatch: {not_sent} != {blocked} + {unsupported}'
assert accounting_status == 'VALID', f'Accounting status is not VALID: {accounting_status}'
assert reconciled == True, f'Run is not marked reconciled: {reconciled}'
print('✓ ALL 4 PILLARS & INVARIANT CHECKS PASSED!')

print('\n=== 6. INSPECTING TEST-STEP LEVEL EVIDENCE LEDGER ===')
evidence_req = urllib.request.Request(f'{BASE_URL}/api/runs/{run_id}/evidence', headers=auth_headers)
with urllib.request.urlopen(evidence_req, timeout=30) as resp:
    evidence_list = json.loads(resp.read().decode('utf-8'))
    print(f'Total Step-Level Evidence Records: {len(evidence_list)}')

    pass_sample = next((e for e in evidence_list if e.get('status') == 'PASSED'), None)
    fail_sample = next((e for e in evidence_list if e.get('status') == 'FAILED'), None)
    blocked_sample = next((e for e in evidence_list if e.get('status') == 'BLOCKED'), None)

    if pass_sample:
        print('\n--- CANONICAL PASS EVIDENCE SAMPLE ---')
        print(f'Step ID: {pass_sample.get("stepId")}')
        print(f'Step Name: {pass_sample.get("stepName")} | {pass_sample.get("method")} {pass_sample.get("pathTemplate")}')
        print(f'HTTP_SENT: {pass_sample.get("httpSent")} | Status Code: {pass_sample.get("responseStatus")}')
        print(f'Resolved URL: {pass_sample.get("requestUrl")}')
        print(f'Latency: {pass_sample.get("latencyMs")}ms')
        print(f'Customer Explanation: {pass_sample.get("customerExplanation")}')
        assert pass_sample.get('httpSent') == True, 'PASS must have httpSent=True'

    if fail_sample:
        print('\n--- CANONICAL FAIL EVIDENCE SAMPLE ---')
        print(f'Step ID: {fail_sample.get("stepId")}')
        print(f'Step Name: {fail_sample.get("stepName")} | {fail_sample.get("method")} {fail_sample.get("pathTemplate")}')
        print(f'HTTP_SENT: {fail_sample.get("httpSent")} | Status Code: {fail_sample.get("responseStatus")}')
        print(f'Classification: {fail_sample.get("classification")}')
        print(f'Root Cause: {fail_sample.get("rootCause")}')
        print(f'Customer Explanation: {fail_sample.get("customerExplanation")}')
        assert fail_sample.get('httpSent') == True, 'FAIL must have httpSent=True'

    if blocked_sample:
        print('\n--- CANONICAL BLOCKED EVIDENCE SAMPLE ---')
        print(f'Step ID: {blocked_sample.get("stepId")}')
        print(f'Step Name: {blocked_sample.get("stepName")} | {blocked_sample.get("method")} {blocked_sample.get("pathTemplate")}')
        print(f'HTTP_SENT: {blocked_sample.get("httpSent")}')
        print(f'Classification: {blocked_sample.get("classification")}')
        print(f'Root Cause: {blocked_sample.get("rootCause")}')
        print(f'Customer Explanation: {blocked_sample.get("customerExplanation")}')
        assert blocked_sample.get('httpSent') == False, 'BLOCKED must have httpSent=False'

print('\n=== 7. VERIFYING HTML & PDF AUDIT REPORTS ===')
html_req = urllib.request.Request(f'{BASE_URL}/api/runs/{run_id}/report/html', headers=auth_headers)
with urllib.request.urlopen(html_req, timeout=30) as resp:
    html_content = resp.read().decode('utf-8')
    print(f'HTML Report fetched: {len(html_content)} bytes (Status {resp.status})')

pdf_req = urllib.request.Request(f'{BASE_URL}/api/runs/{run_id}/report/pdf', headers=auth_headers)
with urllib.request.urlopen(pdf_req, timeout=30) as resp:
    pdf_bytes = resp.read()
    print(f'PDF Report fetched: {len(pdf_bytes)} bytes (Status {resp.status})')
    assert pdf_bytes.startswith(b'%PDF'), 'Response must be valid PDF stream'

os.makedirs('scratch', exist_ok=True)
with open('scratch/latest_customer_run.json', 'w') as f:
    json.dump({
        'runId': run_id,
        'contractHash': contract_hash,
        'summary': summary,
        'passSample': pass_sample,
        'failSample': fail_sample,
        'blockedSample': blocked_sample
    }, f, indent=2)
print('\nSaved canonical run proof to scratch/latest_customer_run.json')

