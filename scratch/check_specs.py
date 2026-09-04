import urllib.request
import json
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

candidates = [
    ('USPTO (Official OAI)', 'https://raw.githubusercontent.com/OAI/OpenAPI-Specification/main/examples/v3.0/uspto.json'),
    ('Link Example (Official OAI)', 'https://raw.githubusercontent.com/OAI/OpenAPI-Specification/main/examples/v3.0/link-example.json'),
    ('API with Examples (Official OAI)', 'https://raw.githubusercontent.com/OAI/OpenAPI-Specification/main/examples/v3.0/api-with-examples.json'),
    ('Httpbin (APIs.guru master)', 'https://raw.githubusercontent.com/APIs-guru/openapi-directory/master/APIs/httpbin.org/0.9.2/openapi.json'),
    ('NASA APOD (APIs.guru master)', 'https://raw.githubusercontent.com/APIs-guru/openapi-directory/master/APIs/nasa.gov/1.0.0/openapi.json'),
    ('CoinGecko API (Official)', 'https://raw.githubusercontent.com/APIs-guru/openapi-directory/master/APIs/coingecko.com/1.0.0/openapi.json'),
    ('Chuck Norris Jokes API', 'https://raw.githubusercontent.com/APIs-guru/openapi-directory/master/APIs/chucknorris.io/0.0.1/openapi.json')
]

for name, url in candidates:
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=10, context=ctx) as resp:
            data = resp.read()
            doc = json.loads(data.decode('utf-8'))
            num_paths = len(doc.get('paths', {}))
            title = doc.get('info', {}).get('title', 'Untitled')
            print('[OK] ' + name + ' | Paths: ' + str(num_paths) + ' | Title: ' + title)
    except Exception as e:
        print('[FAIL] ' + name + ' | Error: ' + str(e))
