# SABQ AI — Firestore Security & Integrity Specification (Phase 0 TDD)

## 1. Data Invariants
1. **Strict Ownership**: Every document under `/users/{userId}` and its subcollections (`/workspace/{docId}`, `/uploads/{imageId}`) MUST belong exclusively to `request.auth.uid == userId` with a verified email (`request.auth.token.email_verified == true`).
2. **Parent Existence Gate (Master Gate)**: No `/users/{userId}/workspace/{docId}` or `/users/{userId}/uploads/{imageId}` document can be created by a client unless `/users/{userId}` exists (`exists(/databases/$(database)/documents/users/$(userId))`) or is created atomically in the same batch (`existsAfter(...)`).
3. **Immutable Identity & Creation Timestamp**: `userId` and `createdAt` can never change after creation. `createdAt == request.time` on create; `updatedAt == request.time` on update.
4. **No Ghost / Shadow Fields**: Every `create` uses `keys().hasAll(...) && keys().hasOnly(...)`, and every `update` uses `affectedKeys().hasOnly(...)`.
5. **Bounded Types & Sizes**: Every string field enforces `.size() <= max`, every ID enforces `isValidId()` (`^[a-zA-Z0-9_\-]+$`, `<= 128` chars), and every array enforces bounded `.size() <= MAX`.
6. **No Client-Delegated Queries**: `allow list` on any subcollection explicitly verifies `resource.data.userId == request.auth.uid` and never calls `get()` or `exists()`.
7. **Storage Isolation**: Firebase Storage objects under `/users/{userId}/uploads/{fileName}` can only be read or written by `request.auth.uid == userId`, must be `image/jpeg`, `image/png`, or `image/webp`, and `<= 20MB`.

## 2. The "Dirty Dozen" Adversarial Payloads (Must Return PERMISSION_DENIED)
1. **Unauthenticated Write**: Creating `/users/user_abc` with `request.auth == null`.
2. **Cross-User Read (PII Leak)**: Signed-in User A (`uid=A`) reading `/users/B` or `/users/B/workspace/state`.
3. **Cross-User Write (Identity Spoofing)**: Signed-in User A writing `/users/A/workspace/state` with `userId: "B"`.
4. **Unverified Email Bypass**: Signed-in user with `email_verified: false` creating `/users/uid`.
5. **Shadow Field Injection (Ghost Field)**: Creating `/users/uid` with extra `isAdmin: true` or `role: "admin"`.
6. **ID Poisoning Attack**: Creating `/users/uid/uploads/<1.5KB junk string>` or `invalid/slash$id`.
7. **Denial-of-Wallet String Bloat**: Updating `displayName` on `/users/uid` with a 50,000-character string (`> 120` limit).
8. **Immutable Field Tampering**: Updating `/users/uid` to alter `createdAt` or `userId`.
9. **Spoofed Client Timestamp**: Creating `/users/uid` with `createdAt: "2020-01-01T00:00:00Z"` instead of `request.time`.
10. **Orphaned Subcollection Write**: Creating `/users/uid/uploads/img_1` when `/users/uid` does not exist.
11. **Array Overflow Attack**: Writing `/users/uid/workspace/state` with `scans` array containing 500 items (`> 80` limit).
12. **Blanket Collection Listing**: Executing `list` query on `/users/B/uploads` without matching `resource.data.userId == request.auth.uid`.
