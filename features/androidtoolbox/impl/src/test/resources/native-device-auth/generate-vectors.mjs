// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
// Uses OpenClaw's real TypeScript payload owner and Node's independent Ed25519 implementation.
// The seed is the PUBLIC RFC 8032 section 7.1 test vector, never an operational identity.
import { createHash, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const [ownerFile, outputFile, mode] = process.argv.slice(2);
if (!ownerFile || !outputFile) throw new Error('Usage: node generate-vectors.mjs native-device-auth.ts output.json');
const { buildDeviceAuthPayloadV3 } = await import(pathToFileURL(ownerFile).href);
const seedHex = '9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60';
const privateKey = createPrivateKey({ key: Buffer.from('302e020100300506032b657004220420' + seedHex, 'hex'), format: 'der', type: 'pkcs8' });
const publicKey = createPublicKey(privateKey).export({ format: 'der', type: 'spki' }).subarray(-32);
const deviceId = createHash('sha256').update(publicKey).digest('hex');
const base = { clientId: 'openclaw-android', clientMode: 'node', role: 'node', scopes: [], signedAtMs: 1700000000000, token: null, nonce: 'nonce-abc', platform: ' Android ', deviceFamily: ' Phone ' };
const cases = [
  { name: 'node-without-token', claim: base },
  { name: 'canonical-operator', claim: { ...base, clientId: 'openclaw-macos', clientMode: 'ui', role: 'operator', scopes: ['operator.admin', 'operator.read'], token: 'tok-123', platform: '  IOS  ', deviceFamily: '  iPhone  ' } },
  { name: 'preserve-order-token-and-unicode', claim: { ...base, scopes: ['operator.write', 'operator.read'], token: ' token with spaces ', nonce: 'nonce-café', platform: '  İOS  ', deviceFamily: '  ÄPHONE  ' } },
  { name: 'empty-metadata-slots', claim: { ...base, signedAtMs: 1700000000001, platform: null, deviceFamily: null } },
  { name: 'native-unicode-whitespace', claim: { ...base, platform: '\u00a0 Android \u00a0', deviceFamily: '\t PHONE\n' } },
];
const vectors = cases.map(({ name, claim }) => {
  const payload = buildDeviceAuthPayloadV3({ deviceId, ...claim });
  const bytes = Buffer.from(payload, 'utf8');
  const signature = sign(null, bytes, privateKey);
  if (!verify(null, bytes, createPublicKey(privateKey), signature)) throw new Error('Node verification failed');
  return { name, seedHex, claim, expected: { deviceId, publicKey: publicKey.toString('base64url'), payload, payloadUtf8Hex: bytes.toString('hex'), signature: signature.toString('base64url') } };
});
const ownerText = readFileSync(ownerFile, 'utf8').replace(/\r\n/g, '\n');
const output = JSON.stringify({ schemaVersion: 1, owner: 'packages/gateway-client/src/device-auth.ts', ownerSHA256LF: createHash('sha256').update(ownerText).digest('hex'), vectors }, null, 2) + '\n';
if (mode === '--check') {
  if (readFileSync(outputFile, 'utf8') !== output) throw new Error('Native V3 vectors differ; do not rewrite the checked source automatically.');
} else {
  writeFileSync(outputFile, output, 'utf8');
}
process.stdout.write(`Verified ${vectors.length} synthetic native V3 vectors.\n`);
