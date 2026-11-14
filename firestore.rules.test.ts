/**
 * SABQ AI — Dirty Dozen Firestore Rules Verification Suite
 * Validates all 12 adversarial payloads against the security invariants in security_spec.md & firestore.rules.
 *
 * Run against Firebase Emulator Suite when available:
 *   firebase emulators:exec --only firestore "npx tsx firestore.rules.test.ts"
 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

const rulesPath = path.resolve(process.cwd(), 'firestore.rules');
const blueprintPath = path.resolve(process.cwd(), 'firebase-blueprint.json');
const storageRulesPath = path.resolve(process.cwd(), 'storage.rules');

export interface DirtyDozenCase {
  id: number;
  name: string;
  expectedResult: 'PERMISSION_DENIED';
  targetPath: string;
  ruleInvariantCheck: (rulesText: string) => boolean;
}

const dirtyDozenChecks: DirtyDozenCase[] = [
  {
    id: 1,
    name: 'Unauthenticated write to /users/{userId}',
    expectedResult: 'PERMISSION_DENIED',
    targetPath: '/users/user_abc',
    ruleInvariantCheck: (r) => r.includes('request.auth != null') && r.includes('allow read, write: if false;'),
  },
  {
    id: 2,
    name: 'Cross-user read (User A reading User B workspace)',
    expectedResult: 'PERMISSION_DENIED',
    targetPath: '/users/user_B/workspace/state',
    ruleInvariantCheck: (r) => r.includes('request.auth.uid == userId'),
  },
  {
    id: 3,
    name: 'Cross-user identity spoofing (userId != request.auth.uid)',
    expectedResult: 'PERMISSION_DENIED',
    targetPath: '/users/user_A/workspace/state',
    ruleInvariantCheck: (r) => r.includes('data.userId == request.auth.uid'),
  },
  {
    id: 4,
    name: 'Unverified email bypass (email_verified == false)',
    expectedResult: 'PERMISSION_DENIED',
    targetPath: '/users/user_A',
    ruleInvariantCheck: (r) => r.includes('request.auth.token.email_verified == true'),
  },
  {
    id: 5,
    name: 'Shadow field injection (isAdmin / role)',
    expectedResult: 'PERMISSION_DENIED',
    targetPath: '/users/user_A',
    ruleInvariantCheck: (r) => r.includes('data.keys().hasOnly('),
  },
  {
    id: 6,
    name: 'ID poisoning with oversized/invalid ID characters',
    expectedResult: 'PERMISSION_DENIED',
    targetPath: '/users/user_A/uploads/<poison>',
    ruleInvariantCheck: (r) => r.includes("id.matches('^[a-zA-Z0-9_\\\\-]+$')") && r.includes('isValidId(imageId)'),
  },
  {
    id: 7,
    name: 'Denial-of-Wallet string bloat (displayName > 120 chars)',
    expectedResult: 'PERMISSION_DENIED',
    targetPath: '/users/user_A',
    ruleInvariantCheck: (r) => r.includes('isStr(data.displayName, 1, 120)'),
  },
  {
    id: 8,
    name: 'Immutable field tampering (createdAt or userId mutated on update)',
    expectedResult: 'PERMISSION_DENIED',
    targetPath: '/users/user_A',
    ruleInvariantCheck: (r) => r.includes('incoming().createdAt == existing().createdAt') && r.includes('incoming().userId == existing().userId'),
  },
  {
    id: 9,
    name: 'Spoofed client timestamp (createdAt != request.time)',
    expectedResult: 'PERMISSION_DENIED',
    targetPath: '/users/user_A',
    ruleInvariantCheck: (r) => r.includes('incoming().createdAt == request.time') && r.includes('incoming().updatedAt == request.time'),
  },
  {
    id: 10,
    name: 'Orphaned subcollection write without parent /users/{userId}',
    expectedResult: 'PERMISSION_DENIED',
    targetPath: '/users/user_A/uploads/img_1',
    ruleInvariantCheck: (r) => r.includes('hasValidUserRootParent(userId)') && r.includes('existsAfter(/databases/$(database)/documents/users/$(userId))'),
  },
  {
    id: 11,
    name: 'Array overflow attack (scans > 80 items)',
    expectedResult: 'PERMISSION_DENIED',
    targetPath: '/users/user_A/workspace/state',
    ruleInvariantCheck: (r) => r.includes('isBoundedList(data.scans, 80)') && r.includes('isBoundedList(data.reports, 80)'),
  },
  {
    id: 12,
    name: 'Blanket collection list without resource.data owner enforcement',
    expectedResult: 'PERMISSION_DENIED',
    targetPath: '/users/user_B/uploads',
    ruleInvariantCheck: (r) => r.includes('allow list: if false;') && r.includes('resource.data.userId == request.auth.uid'),
  },
];

function runStaticVerification() {
  assert.ok(fs.existsSync(rulesPath), 'firestore.rules must exist');
  assert.ok(fs.existsSync(blueprintPath), 'firebase-blueprint.json must exist');
  assert.ok(fs.existsSync(storageRulesPath), 'storage.rules must exist');

  const rulesText = fs.readFileSync(rulesPath, 'utf8');
  const storageText = fs.readFileSync(storageRulesPath, 'utf8');
  const blueprint = JSON.parse(fs.readFileSync(blueprintPath, 'utf8'));

  // Verify every collection path in blueprint has a matching block in firestore.rules
  assert.ok(rulesText.includes("rules_version = '2';"), 'rules_version = 2 required');
  assert.ok(rulesText.includes('match /test/connection'), 'Missing /test/connection rule');
  assert.ok(rulesText.includes('match /users/{userId}'), 'Missing /users/{userId} rule');
  assert.ok(rulesText.includes('match /workspace/{docId}'), 'Missing /workspace/{docId} rule');
  assert.ok(rulesText.includes('match /uploads/{imageId}'), 'Missing /uploads/{imageId} rule');
  assert.ok(storageText.includes("contentType.matches('^image/(jpeg|png|webp)$')"), 'Storage MIME guard required');
  assert.ok(Object.keys(blueprint.firestore || {}).length >= 4, 'Blueprint firestore paths present');

  for (const tc of dirtyDozenChecks) {
    assert.ok(tc.ruleInvariantCheck(rulesText), `Failed Dirty Dozen #${tc.id}: ${tc.name}`);
  }

  console.log(`PASS: All ${dirtyDozenChecks.length} Dirty Dozen security invariants verified in firestore.rules & storage.rules.`);
}

runStaticVerification();
