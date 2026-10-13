#!/usr/bin/env python3
"""Read-only clinic integration diagnostics. Never print configuration or API bodies.

Run over SSH stdin; nothing is installed or saved on the server. --self-test runs
offline parser/redaction tests without opening files, databases or connections.
"""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import smtplib
import ssl
import subprocess
import sys
import time
import unittest
from unittest import mock
import urllib.error
import urllib.parse
import urllib.request
import zipfile

EXPECTED_TARGET = '43.166.154.98'
APP_ROOT = pathlib.Path('/www/wwwroot/yiyuanjava')
SERVICE = 'yiyuanjava'
CLINIC_HOSTS = {'otcm.app', 'www.otcm.app', 'yuanyuanyiyuan.oksja.cn', 'yuanyuanyiyuanht.oksja.cn'}
CONFIG_KEYS = {
    'spring.datasource.druid.master.url', 'spring.datasource.druid.master.username',
    'spring.datasource.druid.master.password', 'spring.datasource.url',
    'spring.datasource.username', 'spring.datasource.password',
    'spring.mail.host', 'spring.mail.port', 'spring.mail.username', 'spring.mail.password',
    'spring.mail.properties.mail.smtp.ssl.enable',
    'spring.mail.properties.mail.smtp.starttls.enable',
    'stripe.secret-key', 'stripe.webhook-secret', 'spring.profiles.active',
    'spring.config.location', 'spring.config.additional-location',
}
SECRET_PATTERN = re.compile(r'\b(?:sk|rk|pk)_(?:live|test)_[A-Za-z0-9]+|\bwhsec_[A-Za-z0-9]+|\bre_[A-Za-z0-9]{12,}')


def failure(error):
    result = {'ok': False, 'error_type': type(error).__name__}
    status = getattr(error, 'code', None)
    if isinstance(status, int):
        result['http_status'] = status
    return result


def redact(value, secrets=()):
    if isinstance(value, dict):
        return {key: redact(item, secrets) for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item, secrets) for item in value]
    if not isinstance(value, str):
        return value
    for secret in sorted((str(s) for s in secrets if s and len(str(s)) >= 6), key=len, reverse=True):
        value = value.replace(secret, '[redacted]')
    return SECRET_PATTERN.sub('[redacted]', value)


def safe_url(value, nginx=False):
    """Retain only known hosts and non-sensitive integration routes."""
    value = str(value or '').strip().strip('"\'')
    if nginx and re.fullmatch(r'https?://\$(?:host|server_name)/?\$(?:request_uri|uri)', value):
        return value
    suffix = ''
    if nginx:
        for variable in ('$request_uri', '$uri'):
            if value.endswith(variable):
                suffix = variable
                value = value[:-len(variable)]
    try:
        parts = urllib.parse.urlsplit(value)
        if parts.scheme not in ('https', 'http') or parts.hostname not in CLINIC_HOSTS:
            return '[unrelated-or-unrecognized-url]'
        port = parts.port
    except ValueError:
        return '[invalid-url]'
    path = parts.path or '/'
    if path not in ('/', '/api', '/api/', '/api/stripe/webhook', '/api/stripe/webhook/', '/booking', '/login'):
        path = '/[path-redacted]'
    result = parts.scheme + '://' + parts.hostname + ((':' + str(port)) if port else '') + path
    return result + suffix + ('?[query-redacted]' if parts.query else '')


def flatten(value, prefix='', target=None):
    target = {} if target is None else target
    if isinstance(value, dict):
        for key, item in value.items():
            flatten(item, prefix + ('.' if prefix else '') + str(key), target)
    elif prefix:
        target[prefix] = '' if value is None else str(value).lower() if isinstance(value, bool) else str(value)
    return target


def yaml_scalar(text):
    text = text.strip()
    if text.startswith("'") and text.endswith("'"):
        return text[1:-1].replace("''", "'")
    if text.startswith('"') and text.endswith('"'):
        return json.loads(text)
    return re.split(r'\s+#', text, maxsplit=1)[0].rstrip()


def parse_config(text, suffix):
    if suffix == '.properties':
        result = {}
        for line in text.splitlines():
            if not line.strip() or line.lstrip().startswith(('#', '!')):
                continue
            match = re.match(r'\s*([^\s=:]+)\s*[=:]\s*(.*)$', line)
            if match:
                value = re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m.group(1), 16)), match[2])
                result[match[1]] = re.sub(r'\\([\\:= ])', r'\1', value)
        return result
    try:
        import yaml
    except ImportError:
        yaml = None
    if yaml is not None:
        documents = list(yaml.safe_load_all(text))
        if len(documents) != 1:
            raise ValueError('Profile documents require explicit resolution')
        return flatten(documents[0])
    # The packaged clinic YAML contains scalar mappings only. Reject structures
    # that cannot be resolved correctly rather than silently using a wrong key.
    stack, result = [], {}
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith('#'):
            continue
        if raw.strip() in ('---', '...') or raw.lstrip().startswith('- '):
            raise ValueError('Unsupported YAML structure')
        match = re.match(r'^( *)([A-Za-z0-9_.-]+):(?:\s+(.*)|\s*)$', raw)
        if not match:
            raise ValueError('Unsupported YAML line')
        depth = len(match[1])
        while stack and stack[-1][0] >= depth:
            stack.pop()
        path = '.'.join([item[1] for item in stack] + [match[2]])
        if match[3] is None or not match[3].strip():
            stack.append((depth, match[2]))
        else:
            result[path] = yaml_scalar(match[3])
    return result


def resolve(value, environment, properties):
    value = str(value or '')
    pattern = re.compile(r'\$\{([^{}:]+)(?::([^{}]*))?\}')
    for _ in range(12):
        replaced = pattern.sub(lambda m: str(environment.get(m[1], properties.get(m[1], m[2] or ''))), value)
        if replaced == value:
            break
        value = replaced
    if '${' in value:
        raise ValueError('Unresolved configuration')
    return value


def command(arguments, **kwargs):
    result = subprocess.run(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            universal_newlines=True, timeout=25, **kwargs)
    if result.returncode:
        raise RuntimeError('Read command failed')
    return result.stdout.strip()


def clinic_path(path):
    resolved = pathlib.Path(path).resolve()
    if resolved != APP_ROOT and APP_ROOT not in resolved.parents:
        raise ValueError('Path is outside the clinic service')
    return resolved


def runtime_configuration():
    pid = int(command(['systemctl', 'show', SERVICE, '--property=MainPID', '--value']))
    if pid <= 0:
        raise RuntimeError('Clinic service is not running')
    proc = pathlib.Path('/proc') / str(pid)
    environment = dict(item.split('=', 1) for item in (proc / 'environ').read_bytes().decode().split('\0') if '=' in item)
    arguments = (proc / 'cmdline').read_bytes().decode().split('\0')
    cwd = clinic_path((proc / 'cwd').resolve())
    jar = pathlib.Path(arguments[arguments.index('-jar') + 1])
    jar = clinic_path(jar if jar.is_absolute() else cwd / jar)
    cli = dict(item[2:].split('=', 1) for item in arguments if item.startswith('--') and '=' in item)
    java = dict(item[2:].split('=', 1) for item in arguments if item.startswith('-D') and '=' in item)
    properties, config_count = {}, 0
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        for stem in ('application',):
            for ext in ('.yml', '.yaml', '.properties'):
                name = 'BOOT-INF/classes/' + stem + ext
                if name in names:
                    properties.update(parse_config(archive.read(name).decode('utf-8-sig'), ext))
                    config_count += 1
        profile = cli.get('spring.profiles.active', java.get('spring.profiles.active', environment.get('SPRING_PROFILES_ACTIVE', properties.get('spring.profiles.active', 'druid'))))
        profile = resolve(profile, environment, properties)
        profiles = profile.split(',')
        if not all(re.fullmatch(r'[A-Za-z0-9_-]+', item) for item in profiles):
            raise ValueError('Invalid profile')
        for selected in profiles:
            for ext in ('.yml', '.yaml', '.properties'):
                name = 'BOOT-INF/classes/application-' + selected + ext
                if name in names:
                    properties.update(parse_config(archive.read(name).decode('utf-8-sig'), ext))
                    config_count += 1
        version = {}
        for name in ('BOOT-INF/classes/git.properties', 'BOOT-INF/classes/META-INF/build-info.properties'):
            if name in names:
                metadata = parse_config(archive.read(name).decode(), '.properties')
                for key in ('git.commit.id', 'git.commit.id.abbrev', 'build.version', 'build.time'):
                    value = metadata.get(key, '')
                    if value and re.fullmatch(r'[A-Za-z0-9_.:+-]{1,80}', value):
                        version[key] = value
    external = []
    for directory in (cwd, cwd / 'config'):
        for stem in ['application'] + ['application-' + item for item in profiles]:
            for ext in ('.yml', '.yaml', '.properties'):
                path = directory / (stem + ext)
                if path.is_file():
                    external.append(clinic_path(path))
    for property_name, env_name in (('spring.config.location', 'SPRING_CONFIG_LOCATION'), ('spring.config.additional-location', 'SPRING_CONFIG_ADDITIONAL_LOCATION')):
        locations = cli.get(property_name, java.get(property_name, environment.get(env_name, '')))
        for item in locations.split(','):
            item = item.removeprefix('optional:') if hasattr(item, 'removeprefix') else re.sub(r'^optional:', '', item)
            item = re.sub(r'^file:', '', item)
            if not item:
                continue
            path = pathlib.Path(item)
            path = clinic_path(path if path.is_absolute() else cwd / path)
            if path.is_dir():
                for stem in ['application'] + ['application-' + selected for selected in profiles]:
                    external.extend(path / (stem + ext) for ext in ('.yml', '.yaml', '.properties') if (path / (stem + ext)).is_file())
            else:
                external.append(path)
    for path in dict.fromkeys(external):
        properties.update(parse_config(path.read_text(encoding='utf-8-sig'), path.suffix))
        config_count += 1
    if environment.get('SPRING_APPLICATION_JSON'):
        properties.update(flatten(json.loads(environment['SPRING_APPLICATION_JSON'])))
    for key in CONFIG_KEYS:
        conventional = key.replace('.', '_').replace('-', '_').upper()
        relaxed = key.replace('.', '_').replace('-', '').upper()
        if relaxed in environment:
            properties[key] = environment[relaxed]
        if conventional in environment:
            properties[key] = environment[conventional]
    properties.update(java)
    properties.update(cli)
    selected = {key: resolve(properties.get(key, ''), environment, properties) for key in CONFIG_KEYS}
    digest = hashlib.sha256()
    with jar.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    summary = {'ok': True, 'service': SERVICE, 'active': command(['systemctl', 'is-active', SERVICE]) == 'active',
               'jar_sha256': digest.hexdigest(), 'version': version, 'config_files_loaded': config_count}
    return selected, summary


def stripe_settings(properties):
    jdbc = properties.get('spring.datasource.druid.master.url') or properties.get('spring.datasource.url', '')
    if not jdbc.startswith('jdbc:mysql://'):
        raise ValueError('Unsupported clinic database')
    parsed = urllib.parse.urlsplit(jdbc[5:])
    database = parsed.path.lstrip('/')
    if parsed.hostname not in ('127.0.0.1', 'localhost', '::1') or not re.fullmatch(r'[A-Za-z0-9_]+', database):
        raise ValueError('Database is not local')
    username = properties.get('spring.datasource.druid.master.username') or properties.get('spring.datasource.username')
    password = properties.get('spring.datasource.druid.master.password') or properties.get('spring.datasource.password')
    environment = os.environ.copy()
    environment['MYSQL_PWD'] = password or ''
    query = "START TRANSACTION READ ONLY; SELECT setting_key,HEX(setting_value) FROM tcm_clinic_setting WHERE setting_key IN ('stripeSecretKey','stripeWebhookSecret'); ROLLBACK;"
    output = command(['mysql', '--protocol=TCP', '--host=' + parsed.hostname, '--port=' + str(parsed.port or 3306),
                      '--user=' + str(username or ''), '--batch', '--skip-column-names', '--default-character-set=utf8mb4', database],
                     input=query, env=environment)
    settings = {}
    for line in output.splitlines():
        key, encoded = line.split('\t', 1)
        if key not in ('stripeSecretKey', 'stripeWebhookSecret') or encoded == 'NULL':
            continue
        value = bytes.fromhex(encoded).decode()
        try:
            value = json.loads(value)
        except (TypeError, ValueError):
            pass
        if isinstance(value, str):
            settings[key] = value.strip()
    return settings


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('API redirects are not allowed')


def stripe_get(path, key):
    # Only code-defined Stripe read endpoints; authorization never follows redirects.
    if not (path == '/v1/account' or path.startswith('/v1/webhook_endpoints?') or path.startswith('/v1/events?')):
        raise ValueError('Unsupported read endpoint')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    request = urllib.request.Request('https://api.stripe.com' + path, headers={'Authorization': 'Bearer ' + key}, method='GET')
    with opener.open(request, timeout=25) as response:
        body = response.read(8 * 1024 * 1024 + 1)
        if len(body) > 8 * 1024 * 1024:
            raise ValueError('API response exceeds diagnostic limit')
        return json.loads(body)


def check_stripe(properties, settings):
    key = settings.get('stripeSecretKey') or properties.get('stripe.secret-key') or ''
    if not key:
        return {'ok': False, 'error_type': 'MissingConfiguration'}
    account = stripe_get('/v1/account', key)
    result = {'ok': True, 'key_mode': 'live' if key.startswith(('sk_live_', 'rk_live_')) else 'test' if key.startswith(('sk_test_', 'rk_test_')) else 'unknown',
              'charges_enabled': account.get('charges_enabled') is True,
              'webhook_signing_secret_configured': bool(settings.get('stripeWebhookSecret') or properties.get('stripe.webhook-secret'))}
    endpoints, other_count, cursor, truncated = [], 0, '', False
    for _ in range(5):
        query = {'limit': 100}
        if cursor:
            query['starting_after'] = cursor
        payload = stripe_get('/v1/webhook_endpoints?' + urllib.parse.urlencode(query), key)
        for item in payload.get('data', []):
            try:
                belongs = urllib.parse.urlsplit(str(item.get('url', ''))).hostname in CLINIC_HOSTS
            except ValueError:
                belongs = False
            if not belongs:
                other_count += 1
                continue
            endpoints.append({'url': safe_url(item.get('url')), 'status': item.get('status') if item.get('status') in ('enabled', 'disabled') else 'unknown',
                              'livemode': item.get('livemode') is True,
                              'enabled_events': [event for event in item.get('enabled_events', []) if isinstance(event, str) and re.fullmatch(r'\*|[a-z][a-z0-9_.]{0,100}', event)]})
        truncated = payload.get('has_more') is True
        if not truncated or not payload.get('data'):
            break
        cursor = str(payload['data'][-1]['id'])
    result.update({'clinic_webhooks': endpoints, 'unrelated_endpoint_count': other_count, 'endpoint_list_truncated': truncated})
    try:
        count, pending, cursor, truncated = 0, 0, '', False
        for _ in range(5):
            query = {'limit': 100, 'delivery_success': 'false', 'created[gte]': int(time.time()) - 7 * 86400}
            if cursor:
                query['starting_after'] = cursor
            payload = stripe_get('/v1/events?' + urllib.parse.urlencode(query), key)
            # Do not inspect, persist or return event.data, customers, amounts or IDs.
            count += len(payload.get('data', []))
            pending += sum(1 for item in payload.get('data', []) if int(item.get('pending_webhooks') or 0) > 0)
            truncated = payload.get('has_more') is True
            if not truncated or not payload.get('data'):
                break
            cursor = str(payload['data'][-1]['id'])
        result['recent_delivery_aggregate'] = {'ok': True, 'window_days': 7, 'account_scope': True,
                                                'pending_or_failed_event_count': count, 'still_pending_event_count': pending,
                                                'truncated': truncated, 'endpoint_specific_failure_confirmation': False}
    except Exception as error:
        result['recent_delivery_aggregate'] = failure(error)
    return result


def check_smtp(properties):
    host = properties.get('spring.mail.host', '')
    username, password = properties.get('spring.mail.username', ''), properties.get('spring.mail.password', '')
    if not re.fullmatch(r'[A-Za-z0-9.-]+', host) or not username or not password:
        return {'ok': False, 'error_type': 'MissingConfiguration', 'sent_messages': 0}
    port = int(properties.get('spring.mail.port') or 25)
    context = ssl.create_default_context()
    implicit_tls = port == 465 or properties.get('spring.mail.properties.mail.smtp.ssl.enable', '').lower() == 'true'
    if implicit_tls:
        connection = smtplib.SMTP_SSL(host, port, timeout=20, context=context)
    else:
        connection = smtplib.SMTP(host, port, timeout=20)
    with connection as smtp:
        smtp.ehlo()
        if not implicit_tls:
            smtp.starttls(context=context)
            smtp.ehlo()
        smtp.login(username, password)
    return {'ok': True, 'server': host, 'port': port, 'tls': True, 'authenticated': True, 'sent_messages': 0}


def nginx_nodes(text):
    tokens = [(match.group(), match.start(), match.end()) for match in re.finditer(
        r'''\#[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{};]|[^\s{};#]+''', text)
        if not match.group().startswith('#')]
    position = 0

    def parse(in_block=False):
        nonlocal position
        nodes, header = [], []
        while position < len(tokens):
            token = tokens[position]
            position += 1
            if token[0] == '}':
                if header or not in_block:
                    raise ValueError('Unbalanced nginx configuration')
                return nodes, token[2]
            if token[0] in ('{', ';'):
                if not header:
                    raise ValueError('Empty nginx directive')
                children, end = parse(True) if token[0] == '{' else (None, token[2])
                nodes.append({'header': [item[0] for item in header], 'start': header[0][1], 'end': end, 'children': children})
                header = []
            else:
                header.append(token)
        if header or in_block:
            raise ValueError('Unterminated nginx configuration')
        return nodes, len(text)

    return parse()[0]


def safe_condition(header):
    value = ' '.join(header)
    match = re.fullmatch(r'''if\s*\(\s*(\$(?:host|http_host|server_name|scheme|https|server_port|uri|request_uri))\s*(=|!=|~\*?|!~\*?)\s*["']?([^"']+?)["']?\s*\)''', value)
    allowed = {'otcm.app', 'www.otcm.app', 'http', 'https', 'on', 'off', '80', '443',
               '/api/stripe/webhook', '/api/stripe/webhook/', '^otcm\\.app$', '^otcm.app$', '^www\\.otcm\\.app$'}
    if match and match[3].strip() in allowed:
        return {'variable': match[1], 'operator': match[2], 'value': match[3].strip()}
    return {'condition': '[unrecognized-condition]',
            'known_variables': sorted(set(re.findall(r'\$(?:host|http_host|server_name|scheme|https|server_port|uri|request_uri)\b', value)))}


def nginx_known_structure(text):
    """Diagnose known routing directives even when unrelated nginx modules use
    syntax outside the small AST parser. This never authorizes a config edit.
    """
    conditions = []
    for match in re.finditer(r'\bif\s*\(([^{}\n]{1,300})\)\s*\{([^{}]{0,4000})\}', text):
        condition = safe_condition(['if', '(' + match[1] + ')'])
        conditions.append((match.start(), match.end(), condition, hashlib.sha256(match.group().encode('utf-8')).hexdigest()))
    locations = []
    for match in re.finditer(r'\blocation\s+(?:(=|\^~)\s+)?(/api/|/api/stripe/webhook/?)\s*\{([^{}]{0,8000})\}', text):
        locations.append((match.start(), match.end(), {'path': match[2], 'modifier': match[1] or ''}))
    returns, proxies = [], []
    for match in re.finditer(r'\breturn\s+(301|302|307|308)\s+([^;]+);', text):
        scoped_conditions = [item[2] for item in conditions if item[0] <= match.start() < item[1]]
        scoped_locations = [item[2] for item in locations if item[0] <= match.start() < item[1]]
        blocks = [item for item in conditions if item[0] <= match.start() < item[1]]
        returns.append({'status': int(match[1]), 'target': safe_url(match[2], nginx=True),
                        'conditions': scoped_conditions, 'locations': scoped_locations,
                        'parent_block_scope': 'matched-condition' if scoped_conditions else 'unresolved',
                        'line': text.count('\n', 0, match.start()) + 1,
                        'condition_block_sha256': blocks[0][3] if len(blocks) == 1 else None})
    for start, end, location in locations:
        for match in re.finditer(r'\bproxy_pass\s+http://(?:127\.0\.0\.1|localhost):8006\s*;', text[start:end]):
            proxies.append({'target': 'http://127.0.0.1:8006', 'locations': [location], 'conditions': []})
    return {'return_rules': returns, 'clinic_loopback_proxies': proxies, 'parser_mode': 'known-directives-only',
            'unrelated_syntax_not_parsed': True}


def nginx_structure(text):
    returns, proxies = [], []

    def walk(nodes, conditions=None, locations=None):
        conditions, locations = conditions or [], locations or []
        for node in nodes:
            header = node['header']
            if header[0] == 'return' and len(header) == 3 and header[1] in ('301', '302', '307', '308'):
                returns.append({'status': int(header[1]), 'target': safe_url(header[2], nginx=True),
                                'conditions': conditions, 'locations': locations,
                                'parent_block_scope': 'conditional' if conditions else 'location' if locations else 'server'})
            if header[0] == 'proxy_pass' and len(header) == 2:
                try:
                    target = urllib.parse.urlsplit(header[1])
                    valid = target.scheme == 'http' and target.hostname in ('127.0.0.1', 'localhost', '::1') and target.port == 8006 and target.path in ('', '/', '/api/', '/api/stripe/webhook', '/api/stripe/webhook/')
                except ValueError:
                    valid = False
                if valid:
                    proxies.append({'target': 'http://127.0.0.1:8006' + target.path, 'locations': locations, 'conditions': conditions})
            if node['children'] is not None:
                next_conditions = conditions + [safe_condition(header)] if header[0] == 'if' else conditions
                next_locations = locations
                if header[0] == 'location':
                    location = header[-1]
                    if location not in ('/', '/api', '/api/', '/api/stripe/webhook', '/api/stripe/webhook/'):
                        location = '[location-redacted]'
                    next_locations = locations + [{'path': location, 'modifier': header[1] if len(header) == 3 and header[1] in ('=', '^~', '~', '~*') else ''}]
                walk(node['children'], next_conditions, next_locations)

    try:
        walk(nginx_nodes(text))
        return {'return_rules': returns, 'clinic_loopback_proxies': proxies, 'parser_mode': 'structured'}
    except ValueError:
        # Do not emit any source or failing token: nginx configs can hold keys.
        return nginx_known_structure(text)


def check_nginx():
    directory = pathlib.Path('/www/server/panel/vhost/nginx')
    sites = []
    for name in ('otcm.app.conf', 'www.otcm.app.conf'):
        path = directory / name
        if not path.is_file():
            continue
        text = path.read_text(errors='replace')
        included_count, unexpanded = 0, 0
        # Expand only snippets explicitly belonging to these clinic domains;
        # shared server/php/WAF configuration and other virtual hosts stay unread.
        for declaration in re.findall(r'\binclude\s+([^;]+);', text):
            include = pathlib.Path(declaration.strip().strip('"\''))
            permitted = any(domain in include.parts or include.name == domain + '.conf'
                            for domain in ('otcm.app', 'www.otcm.app'))
            if not include.is_absolute() or not permitted or include.parent.parts[:5] != ('/', 'www', 'server', 'panel', 'vhost'):
                unexpanded += 1
                continue
            matches = sorted(include.parent.glob(include.name))[:20]
            for candidate in matches:
                resolved = candidate.resolve()
                if pathlib.Path('/www/server/panel/vhost') not in resolved.parents or not candidate.is_file():
                    continue
                if not any(domain in resolved.parts or resolved.name == domain + '.conf'
                           for domain in ('otcm.app', 'www.otcm.app')):
                    continue
                text += '\n' + candidate.read_text(errors='replace')
                included_count += 1
        text = '\n'.join(line for line in text.splitlines() if not line.lstrip().startswith('#'))
        domains = sorted({domain for declaration in re.findall(r'\bserver_name\s+([^;]+);', text) for domain in declaration.split() if domain in CLINIC_HOSTS})
        if not domains:
            continue
        redirects = [{'status': int(code), 'target': safe_url(target, nginx=True)}
                     for code, target in re.findall(r'\breturn\s+(301|302|307|308)\s+([^;]+);', text)]
        rewrites = []
        for source, target, flag in re.findall(r'\brewrite\s+(\S+)\s+(\S+)\s+(permanent|redirect|last|break)\s*;', text):
            rewrites.append({'source': source if source in ('^', '^/(.*)$', '^(.*)$', '^/api/stripe/webhook/?$') else '[pattern-redacted]',
                             'target': safe_url(target, nginx=True), 'flag': flag})
        locations = sorted({location for location in re.findall(r'\blocation\s+(?:=\s+|\^~\s+)?([^\s{]+)\s*\{', text)
                            if location in ('/', '/api', '/api/', '/api/stripe/webhook', '/api/stripe/webhook/')})
        sites.append({'domains': domains, 'redirects': redirects, 'rewrites': rewrites, 'api_locations': locations,
                      'clinic_snippets_loaded': included_count, 'other_includes_not_expanded': unexpanded,
                      'structure': nginx_structure(text)})
    return {'ok': bool(sites), 'sites': sites, 'scope': 'two clinic site files and their explicitly named clinic snippets only'}


class OfflineTests(unittest.TestCase):
    def test_redaction(self):
        value = {'nested': ['sk_live_abc123', 'whsec_abcdefgh', 'prefix-smtp-password-suffix'], 'count': 3}
        encoded = json.dumps(redact(value, ['smtp-password']))
        self.assertNotIn('abc123', encoded)
        self.assertNotIn('abcdefgh', encoded)
        self.assertNotIn('smtp-password', encoded)
        self.assertIn('3', encoded)

    def test_url(self):
        self.assertEqual(safe_url('https://user:password@otcm.app/api/stripe/webhook?secret=123'), 'https://otcm.app/api/stripe/webhook?[query-redacted]')
        self.assertEqual(safe_url('https://otcm.app/manage/private-token'), 'https://otcm.app/[path-redacted]')
        self.assertEqual(safe_url('https://other.example/private'), '[unrelated-or-unrecognized-url]')
        self.assertEqual(safe_url('https://www.otcm.app$request_uri', nginx=True), 'https://www.otcm.app/$request_uri')
        self.assertEqual(safe_url('https://$host$request_uri', nginx=True), 'https://$host$request_uri')

    def test_yaml_and_environment(self):
        parsed = parse_config("spring:\n  mail:\n    password: ${MAIL_SECRET:abc:def}\n    host: 'smtp.example.com'\n", '.yml')
        self.assertEqual(parsed['spring.mail.host'], 'smtp.example.com')
        self.assertEqual(resolve(parsed['spring.mail.password'], {'MAIL_SECRET': 'actual-secret'}, parsed), 'actual-secret')
        self.assertEqual(resolve(parsed['spring.mail.password'], {}, parsed), 'abc:def')

    def test_properties(self):
        parsed = parse_config('secret=a\\=b\\\\c\nlabel=\\u4e2d\n', '.properties')
        self.assertEqual(parsed, {'secret': 'a=b\\c', 'label': '中'})

    def test_yaml_without_optional_dependency(self):
        with mock.patch.dict(sys.modules, {'yaml': None}):
            parsed = parse_config('spring:\n  mail:\n    host: smtp.example.com # comment\n    password: "a#b:c"\n', '.yml')
        self.assertEqual(parsed, {'spring.mail.host': 'smtp.example.com', 'spring.mail.password': 'a#b:c'})

    def test_failure_has_no_message(self):
        self.assertEqual(failure(ValueError('sk_live_secret')), {'ok': False, 'error_type': 'ValueError'})

    def test_nginx_condition_and_proxy(self):
        text = '''server { server_name otcm.app www.otcm.app;
        if ($host = otcm.app) { return 301 https://www.otcm.app$request_uri; }
        location /api/ { proxy_pass http://127.0.0.1:8006; }
        location /private { proxy_pass https://unrelated.example/private-key; }
        }'''
        structure = nginx_structure(text)
        self.assertEqual(structure['return_rules'][0]['conditions'], [{'variable': '$host', 'operator': '=', 'value': 'otcm.app'}])
        self.assertEqual(structure['clinic_loopback_proxies'], [{'target': 'http://127.0.0.1:8006', 'locations': [{'path': '/api/', 'modifier': ''}], 'conditions': []}])
        self.assertNotIn('private-key', json.dumps(structure))

    def test_nginx_known_directives_ignore_unrelated_regex_braces(self):
        text = '''server { server_name otcm.app www.otcm.app;
        if ($host = "otcm.app") { return 301 https://www.otcm.app$request_uri; }
        location /api/ { proxy_pass http://127.0.0.1:8006; }
        location ~ token-[a-z]{4,16} { return 404; }
        }'''
        structure = nginx_known_structure(text)
        self.assertEqual(structure['return_rules'][0]['conditions'], [{'variable': '$host', 'operator': '=', 'value': 'otcm.app'}])
        self.assertEqual(len(structure['clinic_loopback_proxies']), 1)
        self.assertNotIn('token-', json.dumps(structure))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--target', choices=[EXPECTED_TARGET])
    parser.add_argument('--self-test', action='store_true')
    options = parser.parse_args()
    if options.self_test:
        result = unittest.TextTestRunner().run(unittest.defaultTestLoader.loadTestsFromTestCase(OfflineTests))
        return 0 if result.wasSuccessful() else 1
    if options.target != EXPECTED_TARGET:
        parser.error('The fixed legacy target is required')
    report = {'target': EXPECTED_TARGET, 'checked_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'read_only': True, 'sent_messages': 0, 'payments_created': 0,
              'host_key_verification': 'first-use ssh-keyscan on the fixed legacy IP; not an independently pinned fingerprint'}
    secrets, properties, settings = [], {}, {}
    try:
        properties, report['service'] = runtime_configuration()
        secrets.extend(value for key, value in properties.items() if 'password' in key or 'secret' in key)
    except Exception as error:
        report['service'] = failure(error)
    if report['service'].get('ok'):
        try:
            settings = stripe_settings(properties)
            secrets.extend(settings.values())
            report['database_settings'] = {'ok': True, 'stripe_secret_override_present': bool(settings.get('stripeSecretKey')),
                                           'webhook_secret_override_present': bool(settings.get('stripeWebhookSecret'))}
        except Exception as error:
            report['database_settings'] = failure(error)
        # Database overrides take precedence. Do not authenticate an embedded key
        # and report it as the active integration if override lookup failed.
        if report['database_settings'].get('ok'):
            try:
                report['stripe'] = check_stripe(properties, settings)
            except Exception as error:
                report['stripe'] = failure(error)
        else:
            report['stripe'] = {'ok': False, 'error_type': 'DatabaseSettingsUnavailable'}
        try:
            report['smtp'] = check_smtp(properties)
        except Exception as error:
            report['smtp'] = dict(failure(error), sent_messages=0)
    try:
        report['nginx'] = check_nginx()
    except Exception as error:
        report['nginx'] = failure(error)
    print(json.dumps(redact(report, secrets), ensure_ascii=False, indent=2))
    return 0 if all(report.get(key, {}).get('ok') for key in ('service', 'database_settings', 'stripe', 'smtp', 'nginx')) else 2


if __name__ == '__main__':
    sys.exit(main())
