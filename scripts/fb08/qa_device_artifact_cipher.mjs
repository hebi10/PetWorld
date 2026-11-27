import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { lstatSync, readFileSync, writeFileSync } from 'node:fs';

const kinds = new Set(['firebase-config', 'unsigned-apk', 'signed-apk']);
const nonceLength = 12;
const tagLength = 16;
const cipherOptions = { authTagLength: tagLength };

function encrypt(input, key, header) {
  const nonce = randomBytes(nonceLength);
  const cipher = createCipheriv('aes-256-gcm', key, nonce, cipherOptions);
  cipher.setAAD(header);
  return Buffer.concat([header, nonce, cipher.update(input), cipher.final(), cipher.getAuthTag()]);
}

function decrypt(input, key, header) {
  const ciphertextStart = header.length + nonceLength;
  const tagStart = input.length - tagLength;
  if (tagStart < ciphertextStart || !input.subarray(0, header.length).equals(header)) {
    throw new Error('Invalid artifact format');
  }
  const nonce = input.subarray(header.length, ciphertextStart);
  const decipher = createDecipheriv('aes-256-gcm', key, nonce, cipherOptions);
  decipher.setAAD(header);
  decipher.setAuthTag(input.subarray(tagStart));
  // Do not write plaintext until final() authenticates the whole artifact.
  return Buffer.concat([decipher.update(input.subarray(ciphertextStart, tagStart)), decipher.final()]);
}

const [mode, kind, inputPath, outputPath, ...extra] = process.argv.slice(2);
const hexKey = process.env.QA_ARTIFACT_KEY ?? '';
delete process.env.QA_ARTIFACT_KEY;

if (!['encrypt', 'decrypt'].includes(mode) || !kinds.has(kind) || !inputPath || !outputPath || extra.length) {
  console.error('Usage: qa_device_artifact_cipher.mjs encrypt|decrypt ARTIFACT_KIND INPUT OUTPUT');
  process.exit(2);
}
if (!/^[0-9a-f]{64}$/.test(hexKey)) {
  console.error('A disposable 256-bit QA artifact key is required.');
  process.exit(2);
}

try {
  if (!lstatSync(inputPath).isFile()) {
    throw new Error('Regular input file required');
  }
  const header = Buffer.from(`PETWORLD FB08 QA v1\0${kind}\0`);
  const input = readFileSync(inputPath);
  const key = Buffer.from(hexKey, 'hex');
  const output = mode === 'encrypt' ? encrypt(input, key, header) : decrypt(input, key, header);
  writeFileSync(outputPath, output, { flag: 'wx', mode: 0o600 });
} catch {
  console.error('QA artifact protection failed. Check the artifact kind, key, input and unused output path.');
  process.exitCode = 1;
}
