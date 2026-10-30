/* 与 ValueEncoding 对应的无损标量；生成客户端内嵌同一资源，页面端口直接导入。 */
const int64Minimum = -9223372036854775808n, int64Maximum = 9223372036854775807n;
function encodingInvalid() { throw new Error('SDK_VALUE_ENCODING_INVALID'); }
function decimal(value, minimum = int64Minimum, maximum = int64Maximum) {
    if (typeof value !== 'string' || !/^(?:0|-[1-9][0-9]*|[1-9][0-9]*)$/.test(value) || value.length > 20) encodingInvalid();
    const number = BigInt(value);
    if (number < minimum || number > maximum) encodingInvalid();
    return number;
}
function floatNumber(value, bits) {
    if (typeof value !== 'string' || !(bits === 32 ? /^[0-9a-f]{8}$/ : /^[0-9a-f]{16}$/).test(value)) encodingInvalid();
    const view = new DataView(new ArrayBuffer(bits / 8));
    if (bits === 32) view.setUint32(0, Number.parseInt(value, 16));
    else view.setBigUint64(0, BigInt('0x' + value));
    const number = bits === 32 ? view.getFloat32(0) : view.getFloat64(0);
    if (!Number.isFinite(number)) encodingInvalid();
    return number;
}
export function validateWireValue(schema, value) {
    if (typeof value !== 'string' || value.length > 40) encodingInvalid();
    switch (schema['x-encoding']) {
        case 'DECIMAL_STRING': decimal(value, decimal(schema['x-minimum']), decimal(schema['x-maximum'])); break;
        case 'UUID_STRING': if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)) encodingInvalid(); break;
        case 'INSTANT_SECONDS_NANOS':
        case 'DURATION_SECONDS_NANOS': {
            const parts = value.split(':');
            if (parts.length !== 2 || !/^[0-9]{9}$/.test(parts[1])) encodingInvalid();
            decimal(parts[0], schema['x-encoding'] === 'INSTANT_SECONDS_NANOS' ? -31557014167219200n : int64Minimum,
                schema['x-encoding'] === 'INSTANT_SECONDS_NANOS' ? 31556889864403199n : int64Maximum);
            break;
        }
        case 'FLOAT32_HEX': floatNumber(value, 32); break;
        case 'FLOAT64_HEX': floatNumber(value, 64); break;
        default: encodingInvalid();
    }
    return value;
}
function timeValue(encoding, seconds, nanos = 0) {
    if (typeof seconds !== 'bigint' || !Number.isInteger(nanos) || nanos < 0 || nanos > 999999999) encodingInvalid();
    return validateWireValue({'x-encoding': encoding}, seconds + ':' + String(nanos).padStart(9, '0'));
}
function floatValue(value, bits) {
    if (typeof value !== 'number' || !Number.isFinite(value) || bits === 32 && !Number.isFinite(Math.fround(value))) encodingInvalid();
    const view = new DataView(new ArrayBuffer(bits / 8));
    if (bits === 32) view.setFloat32(0, value); else view.setFloat64(0, value);
    return (bits === 32 ? view.getUint32(0).toString(16) : view.getBigUint64(0).toString(16)).padStart(bits / 4, '0');
}
export const wireValues = Object.freeze({
    int64(value) { if (typeof value !== 'bigint') encodingInvalid(); return validateWireValue({
        'x-encoding': 'DECIMAL_STRING', 'x-minimum': String(int64Minimum), 'x-maximum': String(int64Maximum)}, String(value)); },
    uuid(value) { return validateWireValue({'x-encoding': 'UUID_STRING'}, value); },
    instant: (seconds, nanos = 0) => timeValue('INSTANT_SECONDS_NANOS', seconds, nanos),
    duration: (seconds, nanos = 0) => timeValue('DURATION_SECONDS_NANOS', seconds, nanos),
    float32: value => floatValue(value, 32),
    float64: value => floatValue(value, 64),
    number32: value => floatNumber(value, 32),
    number64: value => floatNumber(value, 64)
});
