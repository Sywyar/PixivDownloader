"""独立消费者只核对严格 JSON 编码；Schema 仍由发行工具唯一实现负责。"""
import json
import sys
from decimal import Decimal
from pathlib import Path


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('duplicate key')
        value[key] = item
    return value


def inspect(value, depth=0):
    if isinstance(value, str):
        if len(value.encode('utf-16-le')) // 2 > 16384:
            raise ValueError('string budget')
    elif isinstance(value, (int, Decimal)) and not isinstance(value, bool):
        if not Decimal(value).is_finite() or value != int(value) or abs(value) > 9007199254740991:
            raise ValueError('safe integer')
    elif isinstance(value, (dict, list)):
        if depth >= 16:
            raise ValueError('depth budget')
        if isinstance(value, dict):
            for key in value:
                inspect(key, depth + 1)
            value = value.values()
        for item in value:
            inspect(item, depth + 1)


vectors = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
results = []
for case in vectors['strictJson']:
    try:
        raw = bytes.fromhex(case['utf8Hex']).decode('utf-8')
        value = json.loads(raw, object_pairs_hook=unique_object, parse_float=Decimal, parse_constant=Decimal)
        inspect(value)
        accepted = True
    except (ValueError, UnicodeError, OverflowError):
        accepted = False
    results.append({'id': case['id'], 'accepted': accepted})
sys.stdout.write(json.dumps(results))
