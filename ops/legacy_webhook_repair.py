#!/usr/bin/env python3
"""Narrow, reviewable repairs for the two diagnosed webhook configuration gaps.

Default mode is read-only inspection. No payment creation, webhook resend, DNS,
database mutation, application deployment or SMTP command exists in this file.
"""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import sys
import tempfile
import time
import unittest
import urllib.parse
import urllib.request
from unittest import mock

import legacy_integration_diagnostics as diagnostics

EVENT = 'checkout.session.async_payment_succeeded'
ENDPOINT_URLS = ('https://www.otcm.app/api/stripe/webhook',
                 'https://yuanyuanyiyuan.oksja.cn/api/stripe/webhook')
NGINX_CONFIG = pathlib.Path('/www/server/panel/vhost/nginx/otcm.app.conf')
NGINX_BINARY = '/www/server/nginx/sbin/nginx'
NGINX_BACKUP_DIR = pathlib.Path('/www/backup/otcm-webhook-routing')
MARKER = '# otcm-webhook-redirect-exemption-v1'
REPLACEMENT = '''# otcm-webhook-redirect-exemption-v1
    set $otcm_webhook_www_redirect 0;
    if ($host = otcm.app) { set $otcm_webhook_www_redirect 1; }
    if ($uri = /api/stripe/webhook) { set $otcm_webhook_www_redirect 0; }
    if ($uri = /api/stripe/webhook/) { set $otcm_webhook_www_redirect 0; }
    if ($otcm_webhook_www_redirect = 1) {
        return 301 https://www.otcm.app$request_uri;
    }'''


def digest(value):
    return hashlib.sha256(value).hexdigest()


def safe_events(events):
    if not isinstance(events, list) or not events or not all(isinstance(event, str) and re.fullmatch(r'\*|[a-z][a-z0-9_.]{0,100}', event) for event in events):
        raise ValueError('Invalid existing event subscriptions')
    return list(dict.fromkeys(events))


def stripe_request(key, endpoint_id, events=None):
    if not re.fullmatch(r'we_[A-Za-z0-9]+', endpoint_id):
        raise ValueError('Invalid webhook endpoint ID')
    headers = {'Authorization': 'Bearer ' + key}
    body = None
    if events is not None:
        events = safe_events(events)
        if EVENT not in events:
            raise ValueError('The repair can only add the diagnosed event')
        body = urllib.parse.urlencode([('enabled_events[]', event) for event in events]).encode()
        headers['Content-Type'] = 'application/x-www-form-urlencoded'
        headers['Idempotency-Key'] = 'otcm-webhook-subscriptions-' + digest((endpoint_id + '\n' + json.dumps(events)).encode())
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), diagnostics.NoRedirect())
    request = urllib.request.Request('https://api.stripe.com/v1/webhook_endpoints/' + endpoint_id,
                                     headers=headers, data=body, method='POST' if body is not None else 'GET')
    with opener.open(request, timeout=25) as response:
        return json.loads(response.read(1024 * 1024))


def clinic_endpoints(key):
    endpoints = {}
    cursor = ''
    for _ in range(5):
        parameters = {'limit': 100}
        if cursor:
            parameters['starting_after'] = cursor
        page = diagnostics.stripe_get('/v1/webhook_endpoints?' + urllib.parse.urlencode(parameters), key)
        for endpoint in page.get('data', []):
            url = endpoint.get('url')
            if url not in ENDPOINT_URLS:
                continue
            if url in endpoints:
                raise ValueError('Duplicate clinic endpoint URL')
            validate_endpoint(endpoint, url)
            endpoints[url] = endpoint
        if not page.get('has_more'):
            break
        if not page.get('data'):
            raise ValueError('Incomplete Stripe endpoint list')
        cursor = page['data'][-1]['id']
    else:
        raise ValueError('Stripe endpoint list exceeds diagnostic bound')
    if set(endpoints) != set(ENDPOINT_URLS):
        raise ValueError('Expected two confirmed clinic endpoints')
    return endpoints


def validate_endpoint(endpoint, expected_url):
    if endpoint.get('url') != expected_url or endpoint.get('status') != 'enabled' or endpoint.get('livemode') is not True:
        raise ValueError('Endpoint no longer matches confirmed live clinic configuration')
    if not re.fullmatch(r'we_[A-Za-z0-9]+', str(endpoint.get('id', ''))):
        raise ValueError('Invalid endpoint identity')
    safe_events(endpoint.get('enabled_events'))


def stripe_event_plan(endpoint):
    before = safe_events(endpoint.get('enabled_events'))
    after = before if EVENT in before or '*' in before else before + [EVENT]
    return {'url': endpoint['url'], 'status': endpoint['status'], 'before': before, 'after': after,
            'change_required': after != before}


def repair_stripe(key, apply=False):
    endpoints = clinic_endpoints(key)
    report = {'ok': True, 'apply': apply, 'endpoints': [], 'subscription_field_only': True}
    for url in ENDPOINT_URLS:
        listed = endpoints[url]
        try:
            # Re-read immediately before replacing the event array so every
            # currently subscribed event is retained, including later additions.
            current = stripe_request(key, listed['id'])
            validate_endpoint(current, url)
            plan = stripe_event_plan(current)
            plan['updated'] = False
            report['endpoints'].append(plan)
            if apply and plan['change_required']:
                try:
                    stripe_request(key, current['id'], plan['after'])
                except Exception as error:
                    # A timeout may follow a successful POST. Read the current
                    # endpoint once; never blindly resend or claim success.
                    plan['post_error'] = diagnostics.failure(error)
                verified = stripe_request(key, current['id'])
                validate_endpoint(verified, url)
                actual = safe_events(verified['enabled_events'])
                if EVENT not in actual or not set(plan['before']).issubset(actual):
                    raise ValueError('Subscription update did not preserve the existing event set')
                plan['after'] = actual
                plan['updated'] = True
                plan['verified'] = True
        except Exception as error:
            report['ok'] = False
            report.setdefault('errors', []).append({'url': url, **diagnostics.failure(error)})
    return report


def nginx_redirect_match(text):
    # Only the already diagnosed, standalone host condition and its single
    # literal return are eligible. Unrelated regex/module syntax is untouched.
    declarations = re.findall(r'(?m)^[ \t]*server_name\s+([^;]+);', text)
    if not declarations:
        # Some test fixtures put the first directive on the server opening line.
        declarations = re.findall(r'\bserver_name\s+([^;]+);', text)
    if len(declarations) != 1 or set(declarations[0].split()) != {'otcm.app', 'www.otcm.app'}:
        raise ValueError('Unexpected clinic virtual-host names')
    pattern = r'''\b(if\s*\(\s*\$host\s*=\s*(?:"otcm\.app"|'otcm\.app'|otcm\.app)\s*\)\s*\{\s*return\s+301\s+https://www\.otcm\.app/?\$request_uri\s*;\s*\})'''
    matches = [match for match in re.finditer(pattern, text)
               if not text[text.rfind('\n', 0, match.start()) + 1:match.start()].lstrip().startswith('#')]
    if len(matches) != 1:
        raise ValueError('Expected exactly one confirmed root-to-www redirect')
    return matches[0]


def replacement_for(text):
    if MARKER in text:
        replacements = [REPLACEMENT, REPLACEMENT.replace('https://www.otcm.app$request_uri', 'https://www.otcm.app/$request_uri')]
        existing = [candidate for candidate in replacements if candidate in text]
        if len(existing) != 1:
            raise ValueError('Existing repair marker has unexpected contents')
        return existing[0]
    block = nginx_redirect_match(text).group(1)
    target = re.search(r'return\s+301\s+(https://www\.otcm\.app/?\$request_uri)\s*;', block).group(1)
    return REPLACEMENT.replace('https://www.otcm.app$request_uri', target)


def nginx_patch(text):
    if MARKER in text:
        replacement_for(text)
        return text, False
    if '$otcm_webhook_www_redirect' in text:
        raise ValueError('Repair variable already exists')
    selected = nginx_redirect_match(text)
    patched = text[:selected.start(1)] + replacement_for(text) + text[selected.end(1):]
    return patched, True


def confirmed_api_proxy(proxies):
    return any(proxy.get('target') == 'http://127.0.0.1:8006'
               and len(proxy.get('locations', [])) == 1
               and proxy['locations'][0].get('path') == '/api/'
               and proxy['locations'][0].get('modifier') in ('', '^~')
               and not proxy.get('conditions') for proxy in proxies)


def nginx_plan():
    if NGINX_CONFIG.is_symlink() or NGINX_CONFIG.resolve() != NGINX_CONFIG:
        raise ValueError('Unexpected clinic config path')
    original = NGINX_CONFIG.read_bytes()
    text = original.decode('utf-8')
    patched, changed = nginx_patch(text)
    replacement = replacement_for(text)
    block = nginx_redirect_match(text).group(1) if changed else replacement
    structure = diagnostics.check_nginx()
    proxies = [proxy for site in structure.get('sites', []) for proxy in site.get('structure', {}).get('clinic_loopback_proxies', [])]
    if not confirmed_api_proxy(proxies):
        raise ValueError('Existing API route is not the confirmed clinic loopback backend')
    report = {'ok': True, 'config_sha256': digest(original), 'proposed_sha256': digest(patched.encode()),
              'redirect_block_sha256': digest(block.encode()),
              'change_required': changed, 'webhook_paths_exempted': ['/api/stripe/webhook', '/api/stripe/webhook/'],
              'backend_preserved': 'http://127.0.0.1:8006',
              'other_root_requests_redirect_to': re.search(r'return 301 (\S+);', replacement).group(1),
              'replacement_directives': replacement.splitlines()}
    return original, patched.encode(), report


def local_http_status(host, path, method):
    args = ['curl', '--silent', '--show-error', '--max-time', '20', '--resolve', host + ':443:127.0.0.1',
            '--output', '/dev/null', '--write-out', '%{http_code}', '--request', method]
    if method == 'POST':
        args += ['--header', 'Content-Type: application/json', '--data-binary', '{}']
    args += ['https://' + host + path]
    return int(diagnostics.command(args))


def route_probe():
    result = {}
    for host in ('otcm.app', 'www.otcm.app'):
        for path in ('/api/stripe/webhook', '/api/stripe/webhook/'):
            result['POST ' + host + path] = local_http_status(host, path, 'POST')
    result['GET otcm.app/booking'] = local_http_status('otcm.app', '/booking', 'GET')
    result['GET www.otcm.app/booking'] = local_http_status('www.otcm.app', '/booking', 'GET')
    return result


def routes_healthy(probes):
    webhook_keys = ['POST ' + host + path for host in ('otcm.app', 'www.otcm.app')
                    for path in ('/api/stripe/webhook', '/api/stripe/webhook/')]
    return (all(probes.get(key) == 400 for key in webhook_keys)
            and probes.get('GET otcm.app/booking') == 301
            and probes.get('GET www.otcm.app/booking') == 200)


def atomic_config_write(content, metadata):
    descriptor, path = tempfile.mkstemp(prefix='.otcm-webhook-', dir=str(NGINX_CONFIG.parent))
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(path, metadata.st_mode & 0o7777)
        os.chown(path, metadata.st_uid, metadata.st_gid)
        os.replace(path, NGINX_CONFIG)
    finally:
        if os.path.exists(path):
            os.unlink(path)


def repair_nginx(expected_hash, expected_redirect_hash):
    original, patched, report = nginx_plan()
    if not re.fullmatch(r'[0-9a-f]{64}', expected_hash or '') or report['config_sha256'] != expected_hash:
        raise ValueError('Clinic config differs from the inspected SHA256')
    if not re.fullmatch(r'[0-9a-f]{64}', expected_redirect_hash or '') or report['redirect_block_sha256'] != expected_redirect_hash:
        raise ValueError('Redirect block differs from the inspected SHA256')
    diagnostics.command([NGINX_BINARY, '-t'])
    report['before_http'] = route_probe()
    report['probe_type'] = 'unsigned requests must be rejected with HTTP 400; no valid events are generated'
    if not report['change_required']:
        report.update({'updated': False, 'verified': routes_healthy(report['before_http'])})
        report['ok'] = report['verified']
        return report
    # A previous readonly inspection must match just before the atomic write.
    if NGINX_CONFIG.read_bytes() != original:
        raise ValueError('Clinic config changed during inspection')
    metadata = NGINX_CONFIG.stat()
    backup_dir = NGINX_BACKUP_DIR
    backup_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    backup = backup_dir / ('otcm.app.' + stamp + '.conf')
    with os.fdopen(os.open(str(backup), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as output:
        output.write(original)
    os.chmod(backup, 0o600)
    report['backup'] = str(backup)
    report['updated'] = False
    try:
        atomic_config_write(patched, metadata)
        diagnostics.command([NGINX_BINARY, '-t'])
        diagnostics.command([NGINX_BINARY, '-s', 'reload'])
        for attempt in range(3):
            report['after_http'] = route_probe()
            if routes_healthy(report['after_http']):
                break
            if attempt < 2:
                time.sleep(0.4)
        if not routes_healthy(report['after_http']):
            raise ValueError('Post-reload routing checks failed')
        report.update({'updated': True, 'verified': True, 'rolled_back': False})
    except Exception as error:
        report.update(diagnostics.failure(error))
        try:
            atomic_config_write(original, metadata)
            diagnostics.command([NGINX_BINARY, '-t'])
            diagnostics.command([NGINX_BINARY, '-s', 'reload'])
            report['rolled_back'] = True
            report['rollback_verified_sha256'] = digest(NGINX_CONFIG.read_bytes()) == digest(original)
        except Exception as rollback_error:
            report['rolled_back'] = False
            report['rollback_error'] = diagnostics.failure(rollback_error)
    return report


class RepairTests(unittest.TestCase):
    def test_additive_event_plan(self):
        endpoint = {'url': ENDPOINT_URLS[0], 'status': 'enabled', 'enabled_events': ['checkout.session.completed', 'charge.refunded']}
        plan = stripe_event_plan(endpoint)
        self.assertEqual(plan['after'], endpoint['enabled_events'] + [EVENT])
        self.assertEqual(endpoint['enabled_events'], ['checkout.session.completed', 'charge.refunded'])

    def test_idempotent_event_plan(self):
        for events in (['*'], ['checkout.session.completed', EVENT]):
            plan = stripe_event_plan({'url': ENDPOINT_URLS[0], 'status': 'enabled', 'enabled_events': events})
            self.assertFalse(plan['change_required'])

    def test_nginx_narrow_and_idempotent(self):
        source = 'server { server_name otcm.app www.otcm.app;\n if ($host = otcm.app) { return 301 https://www.otcm.app$request_uri; }\n location /api/ { proxy_pass http://127.0.0.1:8006; }\n}'
        patched, changed = nginx_patch(source)
        self.assertTrue(changed)
        self.assertIn('location /api/ { proxy_pass http://127.0.0.1:8006; }', patched)
        self.assertIn('if ($uri = /api/stripe/webhook/)', patched)
        self.assertEqual(nginx_patch(patched), (patched, False))

    def test_nginx_refuses_unknown_or_multiple_redirects(self):
        for source in ('server { server_name another.example; return 301 https://www.otcm.app$request_uri; }',
                       'server { server_name otcm.app www.otcm.app; if ($host = otcm.app) { set $x 1; return 301 https://www.otcm.app$request_uri; } }'):
            with self.assertRaises(ValueError):
                nginx_patch(source)

    def test_nginx_keeps_unrelated_regex_unchanged(self):
        source = 'server { server_name otcm.app www.otcm.app;\nif ($host = "otcm.app") { return 301 https://www.otcm.app$request_uri; }\nlocation ~ token-[a-z]{4,16} { return 404; }\n}'
        patched, changed = nginx_patch(source)
        self.assertTrue(changed)
        self.assertIn('location ~ token-[a-z]{4,16} { return 404; }', patched)

    def test_nginx_keeps_exact_literal_redirect_target(self):
        for target in ('https://www.otcm.app$request_uri', 'https://www.otcm.app/$request_uri'):
            source = 'server { server_name otcm.app www.otcm.app; if ($host = otcm.app) { return 301 ' + target + '; } }'
            patched, changed = nginx_patch(source)
            self.assertTrue(changed)
            self.assertIn('return 301 ' + target + ';', patched)
            self.assertEqual(nginx_patch(patched), (patched, False))

    def test_confirmed_legacy_api_prefix_modifier(self):
        for modifier in ('', '^~'):
            self.assertTrue(confirmed_api_proxy([{'target': 'http://127.0.0.1:8006', 'locations': [{'path': '/api/', 'modifier': modifier}], 'conditions': []}]))
        self.assertFalse(confirmed_api_proxy([{'target': 'http://127.0.0.1:8006', 'locations': [{'path': '/', 'modifier': '^~'}], 'conditions': []}]))

    def test_route_probe_requires_rejected_unsigned_posts_and_page_redirect(self):
        probes = {'POST ' + host + path: 400 for host in ('otcm.app', 'www.otcm.app') for path in ('/api/stripe/webhook', '/api/stripe/webhook/')}
        probes.update({'GET otcm.app/booking': 301, 'GET www.otcm.app/booking': 200})
        self.assertTrue(routes_healthy(probes))
        self.assertFalse(routes_healthy(dict(probes, **{'POST otcm.app/api/stripe/webhook': 301})))

    def test_stripe_repair_preserves_fresh_subscriptions_and_is_scoped(self):
        endpoints = {url: {'id': 'we_test' + str(i), 'url': url, 'status': 'enabled', 'livemode': True,
                          'enabled_events': ['checkout.session.completed']} for i, url in enumerate(ENDPOINT_URLS)}
        state = {item['id']: dict(item, enabled_events=item['enabled_events'] + ['refund.created']) for item in endpoints.values()}
        writes = []
        def request(key, endpoint_id, events=None):
            if events is not None:
                writes.append((endpoint_id, list(events)))
                state[endpoint_id]['enabled_events'] = events
            return dict(state[endpoint_id])
        with mock.patch(__name__ + '.clinic_endpoints', return_value=endpoints), mock.patch(__name__ + '.stripe_request', side_effect=request):
            report = repair_stripe('unused-test-key', apply=True)
        self.assertTrue(report['ok'])
        self.assertEqual(len(writes), 2)
        self.assertTrue(all(events == ['checkout.session.completed', 'refund.created', EVENT] for _, events in writes))

    def test_nginx_post_reload_failure_restores_exact_config(self):
        with tempfile.TemporaryDirectory() as directory:
            config = pathlib.Path(directory) / 'otcm.app.conf'
            backup = pathlib.Path(directory) / 'backups'
            original, patched, block_hash = b'original fixture config', b'changed fixture config', 'b' * 64
            config.write_bytes(original)
            plan = {'ok': True, 'change_required': True, 'config_sha256': digest(original), 'redirect_block_sha256': block_hash}
            with mock.patch.multiple(__name__, NGINX_CONFIG=config, NGINX_BACKUP_DIR=backup), \
                 mock.patch(__name__ + '.nginx_plan', return_value=(original, patched, plan)), \
                 mock.patch(__name__ + '.route_probe', return_value={'POST root': 301}), \
                 mock.patch(__name__ + '.atomic_config_write', side_effect=lambda content, metadata: config.write_bytes(content)), \
                 mock.patch.object(time, 'sleep'), \
                 mock.patch.object(diagnostics, 'command', return_value='') as command:
                report = repair_nginx(digest(original), block_hash)
            self.assertFalse(report['ok'])
            self.assertTrue(report['rolled_back'])
            self.assertTrue(report['rollback_verified_sha256'])
            self.assertEqual(config.read_bytes(), original)
            self.assertEqual(next(backup.iterdir()).read_bytes(), original)
            self.assertEqual(command.call_args_list[-1].args[0], [NGINX_BINARY, '-s', 'reload'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--target', choices=[diagnostics.EXPECTED_TARGET])
    parser.add_argument('--mode', choices=['inspect', 'repair-stripe-events', 'repair-webhook-routing'], default='inspect')
    parser.add_argument('--expected-nginx-sha256', default='')
    parser.add_argument('--expected-redirect-sha256', default='')
    parser.add_argument('--self-test', action='store_true')
    options = parser.parse_args()
    if options.self_test:
        result = unittest.TextTestRunner().run(unittest.defaultTestLoader.loadTestsFromTestCase(RepairTests))
        return 0 if result.wasSuccessful() else 1
    if options.target != diagnostics.EXPECTED_TARGET:
        parser.error('The fixed legacy target is required')
    report = {'target': options.target, 'mode': options.mode, 'read_only': options.mode == 'inspect',
              'checked_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'payments_created': 0, 'events_resent': 0, 'sent_messages': 0, 'database_mutations': 0,
              'host_key_verification': 'first-use ssh-keyscan on the fixed legacy IP; not independently pinned'}
    secrets = []
    try:
        properties, service = diagnostics.runtime_configuration()
        report['service'] = service
        secrets.extend(value for key, value in properties.items() if 'password' in key or 'secret' in key)
        if options.mode in ('inspect', 'repair-stripe-events'):
            settings = diagnostics.stripe_settings(properties)
            secrets.extend(settings.values())
            key = settings.get('stripeSecretKey') or properties.get('stripe.secret-key')
            if not key or not key.startswith(('sk_live_', 'rk_live_')):
                raise ValueError('Expected the confirmed live Stripe key')
            report['stripe'] = repair_stripe(key, apply=options.mode == 'repair-stripe-events')
        if options.mode == 'inspect':
            try:
                _, _, report['nginx'] = nginx_plan()
            except Exception as error:
                report['nginx'] = diagnostics.failure(error)
        elif options.mode == 'repair-webhook-routing':
            report['nginx'] = repair_nginx(options.expected_nginx_sha256, options.expected_redirect_sha256)
        report['ok'] = all(report.get(key, {}).get('ok') for key in ('service', 'stripe', 'nginx') if key in report)
    except Exception as error:
        report.update(diagnostics.failure(error))
    print(json.dumps(diagnostics.redact(report, secrets), ensure_ascii=False, indent=2))
    return 0 if report.get('ok') else 2


if __name__ == '__main__':
    sys.exit(main())
