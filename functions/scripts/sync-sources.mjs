import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const functionsRoot = path.resolve(__dirname, '..');
const repoRoot = path.resolve(functionsRoot, '..');

function copyRecursive(src, dest) {
  if (!fs.existsSync(src)) return;
  const stat = fs.statSync(src);
  if (stat.isDirectory()) {
    fs.mkdirSync(dest, { recursive: true });
    for (const entry of fs.readdirSync(src)) {
      copyRecursive(path.join(src, entry), path.join(dest, entry));
    }
  } else {
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.copyFileSync(src, dest);
  }
}

const backendSrc = path.join(repoRoot, 'backend', 'sabqApp.ts');
const syncedAppDest = path.join(functionsRoot, 'src', 'sabqApp.ts');
if (fs.existsSync(backendSrc)) {
  fs.copyFileSync(backendSrc, syncedAppDest);
}

const firebaseConfigSrc = path.join(repoRoot, 'firebase-applet-config.json');
const firebaseConfigDest = path.join(functionsRoot, 'firebase-applet-config.json');
if (fs.existsSync(firebaseConfigSrc)) {
  fs.copyFileSync(firebaseConfigSrc, firebaseConfigDest);
}

const androidSrc = path.join(repoRoot, 'SABQ_AI');
const androidDest = path.join(functionsRoot, 'sabq_android_src');
if (fs.existsSync(androidSrc)) {
  fs.rmSync(androidDest, { recursive: true, force: true });
  copyRecursive(androidSrc, androidDest);
}

console.log('Synced backend/sabqApp.ts, firebase-applet-config.json, and SABQ_AI into functions/.');
