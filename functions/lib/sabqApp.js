"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.createSabqApiApp = createSabqApiApp;
const express_1 = __importDefault(require("express"));
const node_path_1 = __importDefault(require("node:path"));
const node_fs_1 = __importDefault(require("node:fs"));
const node_os_1 = __importDefault(require("node:os"));
const busboy_1 = __importDefault(require("busboy"));
const jszip_1 = __importDefault(require("jszip"));
const genai_1 = require("@google/genai");
const app_1 = require("firebase-admin/app");
const auth_1 = require("firebase-admin/auth");
const firestore_1 = require("firebase-admin/firestore");
const storage_1 = require("firebase-admin/storage");
const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;
const ALLOWED_UPLOAD_MIMES = new Set([
    "image/jpeg",
    "image/jpg",
    "image/pjpeg",
    "image/png",
    "image/webp",
]);
function isCloudRuntime() {
    return Boolean(process.env.FUNCTION_TARGET ||
        process.env.FUNCTION_SIGNATURE_TYPE ||
        process.env.FUNCTIONS_EMULATOR ||
        process.env.FIREBASE_CONFIG);
}
function getBaseDir() {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return __dirname;
        }
    }
    catch {
        // ignore in strict ESM
    }
    return process.cwd();
}
function resolveFirstExistingDir(candidates) {
    for (const c of candidates) {
        if (c && node_fs_1.default.existsSync(c))
            return c;
    }
    return candidates[0];
}
function loadFirebaseAppletConfig() {
    const base = getBaseDir();
    const cwd = process.cwd();
    const candidates = [
        node_path_1.default.resolve(cwd, "firebase-applet-config.json"),
        node_path_1.default.resolve(base, "firebase-applet-config.json"),
        node_path_1.default.resolve(base, "../firebase-applet-config.json"),
        node_path_1.default.resolve(base, "../../firebase-applet-config.json"),
    ];
    for (const file of candidates) {
        try {
            if (node_fs_1.default.existsSync(file)) {
                return JSON.parse(node_fs_1.default.readFileSync(file, "utf8"));
            }
        }
        catch {
            // continue
        }
    }
    return {
        projectId: process.env.GCLOUD_PROJECT || "promising-phantasmata-jv0ns",
        firestoreDatabaseId: "ai-studio-sabqai-f38ae351-008b-4a9a-be3e-88586051f2dd",
        storageBucket: "promising-phantasmata-jv0ns.firebasestorage.app",
    };
}
let cachedAdminApp = null;
function getCloudAdmin() {
    if (!isCloudRuntime())
        return null;
    try {
        const cfg = loadFirebaseAppletConfig();
        if (!cachedAdminApp) {
            cachedAdminApp =
                (0, app_1.getApps)().length > 0
                    ? (0, app_1.getApp)()
                    : (0, app_1.initializeApp)({
                        projectId: cfg.projectId,
                        storageBucket: cfg.storageBucket,
                    });
        }
        const firestore = cfg.firestoreDatabaseId
            ? (0, firestore_1.getFirestore)(cachedAdminApp, cfg.firestoreDatabaseId)
            : (0, firestore_1.getFirestore)(cachedAdminApp);
        const bucket = cfg.storageBucket ? (0, storage_1.getStorage)(cachedAdminApp).bucket(cfg.storageBucket) : null;
        return { app: cachedAdminApp, firestore, bucket };
    }
    catch (err) {
        console.warn("Firebase Admin init skipped:", err);
        return null;
    }
}
function getLocalDataPaths() {
    const cwd = process.cwd();
    const baseRoot = isCloudRuntime() ? node_path_1.default.join(node_os_1.default.tmpdir(), ".sabq_data") : node_path_1.default.join(cwd, ".sabq_data");
    return {
        DATA_DIR: baseRoot,
        UPLOADS_DIR: node_path_1.default.join(baseRoot, "uploads"),
        DB_FILE: node_path_1.default.join(baseRoot, "users_store.json"),
    };
}
function getAndroidRoot() {
    const cwd = process.cwd();
    const base = getBaseDir();
    return resolveFirstExistingDir([
        node_path_1.default.resolve(cwd, "SABQ_AI"),
        node_path_1.default.resolve(base, "SABQ_AI"),
        node_path_1.default.resolve(base, "../SABQ_AI"),
        node_path_1.default.resolve(base, "../sabq_android_src"),
        node_path_1.default.resolve(cwd, "sabq_android_src"),
    ]);
}
function ensureDataDir() {
    const { DATA_DIR, UPLOADS_DIR, DB_FILE } = getLocalDataPaths();
    if (!node_fs_1.default.existsSync(DATA_DIR))
        node_fs_1.default.mkdirSync(DATA_DIR, { recursive: true });
    if (!node_fs_1.default.existsSync(UPLOADS_DIR))
        node_fs_1.default.mkdirSync(UPLOADS_DIR, { recursive: true });
    if (!node_fs_1.default.existsSync(DB_FILE))
        node_fs_1.default.writeFileSync(DB_FILE, JSON.stringify({ users: {} }, null, 2), "utf8");
}
function readStore() {
    ensureDataDir();
    const { DB_FILE } = getLocalDataPaths();
    try {
        return JSON.parse(node_fs_1.default.readFileSync(DB_FILE, "utf8"));
    }
    catch {
        return { users: {} };
    }
}
let storeWriteChain = Promise.resolve();
function writeStore(data) {
    ensureDataDir();
    const { DB_FILE } = getLocalDataPaths();
    const payload = JSON.stringify(data, null, 2);
    const tmpFile = `${DB_FILE}.${process.pid}.${Date.now()}.tmp`;
    node_fs_1.default.writeFileSync(tmpFile, payload, "utf8");
    node_fs_1.default.renameSync(tmpFile, DB_FILE);
}
function enqueueStoreMutation(mutator) {
    const next = storeWriteChain
        .catch(() => { })
        .then(() => {
        const store = readStore();
        mutator(store);
        writeStore(store);
    });
    storeWriteChain = next.catch(() => { });
    return next;
}
function sanitizeUserId(raw) {
    return String(raw || "").replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 128);
}
function sanitizeFilename(raw) {
    return node_path_1.default.basename(String(raw || "")).replace(/[^a-zA-Z0-9._-]/g, "").slice(0, 128);
}
function detectImageSignatureMime(buf) {
    if (!buf || buf.length < 12)
        return null;
    // JPEG: FF D8 FF
    if (buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff)
        return "image/jpeg";
    // PNG: 89 50 4E 47 0D 0A 1A 0A
    if (buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47)
        return "image/png";
    // WebP: RIFF....WEBP
    if (buf.toString("ascii", 0, 4) === "RIFF" &&
        buf.toString("ascii", 8, 12) === "WEBP") {
        return "image/webp";
    }
    return null;
}
function parseMultipartWithBusboy(req) {
    return new Promise((resolve, reject) => {
        const contentType = String(req.headers["content-type"] || "");
        if (!contentType.toLowerCase().includes("multipart/form-data")) {
            return reject(Object.assign(new Error("INVALID_CONTENT_TYPE"), { status: 400 }));
        }
        const bb = (0, busboy_1.default)({
            headers: req.headers,
            limits: {
                files: 1,
                fileSize: MAX_UPLOAD_BYTES,
                fields: 20,
                fieldSize: 64 * 1024,
            },
        });
        const fields = {};
        const chunks = [];
        let fileMeta = null;
        let fileLimitHit = false;
        let mimeRejected = false;
        let settled = false;
        const fail = (err) => {
            if (settled)
                return;
            settled = true;
            reject(err);
        };
        bb.on("field", (name, val) => {
            fields[name] = String(val ?? "");
        });
        bb.on("file", (fieldname, fileStream, info) => {
            const mime = String(info?.mimeType || "").toLowerCase().trim();
            const orig = String(info?.filename || "upload.jpg");
            if (!ALLOWED_UPLOAD_MIMES.has(mime)) {
                mimeRejected = true;
                fileStream.resume();
                return;
            }
            fileMeta = { fieldname, originalname: orig, mimetype: mime };
            fileStream.on("data", (chunk) => {
                if (!fileLimitHit)
                    chunks.push(chunk);
            });
            fileStream.on("limit", () => {
                fileLimitHit = true;
            });
            fileStream.on("error", (streamErr) => fail(streamErr));
        });
        bb.on("error", (err) => fail(err));
        bb.on("close", () => {
            if (settled)
                return;
            settled = true;
            if (fileLimitHit) {
                return reject(Object.assign(new Error("LIMIT_FILE_SIZE"), { status: 413 }));
            }
            if (mimeRejected) {
                return reject(Object.assign(new Error("UNSUPPORTED_MIME"), { status: 415 }));
            }
            if (!fileMeta) {
                return resolve({ fields, file: null });
            }
            resolve({
                fields,
                file: {
                    ...fileMeta,
                    buffer: Buffer.concat(chunks),
                },
            });
        });
        // CRITICAL for Firebase Cloud Functions Gen 2: rawBody is already buffered
        const rawBody = req.rawBody;
        if (rawBody && Buffer.isBuffer(rawBody)) {
            bb.end(rawBody);
        }
        else {
            req.pipe(bb);
        }
    });
}
function getAiClient() {
    const key = process.env.GEMINI_API_KEY;
    if (!key)
        return null;
    return new genai_1.GoogleGenAI({
        apiKey: key,
        httpOptions: {
            headers: {
                "User-Agent": "aistudio-build",
            },
        },
    });
}
function parseGeminiJson(text, fallback) {
    const cleaned = String(text || "")
        .replace(/^```json\s*/i, "")
        .replace(/^```\s*/i, "")
        .replace(/```\s*$/i, "")
        .trim();
    if (!cleaned)
        return fallback;
    return JSON.parse(cleaned);
}
function formatGeminiError(err, fallbackAr) {
    const code = Number(err?.status || err?.code || 0);
    const msgStr = String(err?.message || "");
    if (code === 400 || msgStr.includes("INVALID_ARGUMENT")) {
        return {
            status: 422,
            error: "الصورة غير صالحة أو أبعادها غير مناسبة للتحليل البصري، يرجى اختيار صورة أوضح.",
        };
    }
    if (code === 429 || msgStr.includes("RESOURCE_EXHAUSTED")) {
        return {
            status: 429,
            error: "الخادم مشغول حاليًا لكثرة الطلبات — يرجى الانتظار لحظات ثم إعادة المحاولة.",
        };
    }
    if (code === 401 ||
        code === 403 ||
        msgStr.includes("PERMISSION_DENIED") ||
        msgStr.includes("API_KEY_INVALID")) {
        return {
            status: 503,
            error: "مفتاح Gemini API غير مفعل أو يفتقر للصلاحية المطلوبة في بيئة الخادم.",
        };
    }
    return { status: 500, error: fallbackAr };
}
async function generateContentWithRetry(ai, params, maxRetries = 1) {
    let attempt = 0;
    let lastErr;
    while (attempt <= maxRetries) {
        try {
            return await ai.models.generateContent(params);
        }
        catch (err) {
            lastErr = err;
            const code = Number(err?.status || err?.code || 0);
            const isTransient503 = code === 503;
            if (!isTransient503 || attempt === maxRetries) {
                throw err;
            }
            await new Promise((r) => setTimeout(r, 1500));
            attempt += 1;
        }
    }
    throw lastErr;
}
function collectFilesRecursive(dir, baseDir, out = []) {
    if (!node_fs_1.default.existsSync(dir))
        return out;
    for (const entry of node_fs_1.default.readdirSync(dir, { withFileTypes: true })) {
        const full = node_path_1.default.join(dir, entry.name);
        if (entry.isDirectory()) {
            collectFilesRecursive(full, baseDir, out);
        }
        else {
            out.push(node_path_1.default.relative(baseDir, full));
        }
    }
    return out;
}
async function readUserImageBuffer(userId, filename) {
    const { UPLOADS_DIR } = getLocalDataPaths();
    const fullPath = node_path_1.default.join(UPLOADS_DIR, userId, filename);
    if (fullPath.startsWith(node_path_1.default.join(UPLOADS_DIR, userId)) && node_fs_1.default.existsSync(fullPath)) {
        const buffer = node_fs_1.default.readFileSync(fullPath);
        const mimeType = filename.endsWith(".png")
            ? "image/png"
            : filename.endsWith(".webp")
                ? "image/webp"
                : "image/jpeg";
        return { buffer, mimeType };
    }
    const admin = getCloudAdmin();
    if (admin) {
        if (admin.bucket) {
            try {
                const storageFile = admin.bucket.file(`users/${userId}/uploads/${filename}`);
                const [exists] = await storageFile.exists();
                if (exists) {
                    const [downloaded] = await storageFile.download();
                    const mimeType = filename.endsWith(".png")
                        ? "image/png"
                        : filename.endsWith(".webp")
                            ? "image/webp"
                            : "image/jpeg";
                    return { buffer: downloaded, mimeType };
                }
            }
            catch (err) {
                console.warn("Storage read error:", err);
            }
        }
        try {
            const snap = await admin.firestore
                .collection("users")
                .doc(userId)
                .collection("uploads")
                .where("filename", "==", filename)
                .limit(1)
                .get();
            if (!snap.empty) {
                const data = snap.docs[0].data();
                if (typeof data?.inlineBase64 === "string" && data.inlineBase64.length > 0) {
                    return {
                        buffer: Buffer.from(data.inlineBase64, "base64"),
                        mimeType: typeof data.mimeType === "string" ? data.mimeType : "image/jpeg",
                    };
                }
            }
        }
        catch (err) {
            console.warn("Firestore image fallback read error:", err);
        }
    }
    return null;
}
function createSabqApiApp() {
    const app = (0, express_1.default)();
    // Required CORS & preflight middleware before all routes
    app.use((req, res, next) => {
        res.setHeader("Access-Control-Allow-Origin", "*");
        res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        res.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        res.setHeader("Vary", "Origin");
        if (req.method === "OPTIONS") {
            res.sendStatus(204);
            return;
        }
        next();
    });
    // Only parse JSON/URL-encoded for non-multipart requests so multipart stream/rawBody remains untouched for Busboy
    app.use((req, res, next) => {
        const ct = String(req.headers["content-type"] || "").toLowerCase();
        if (ct.includes("multipart/form-data")) {
            next();
            return;
        }
        express_1.default.json({ limit: "50mb" })(req, res, (err) => {
            if (err) {
                res.status(400).json({ ok: false, error: "تنسيق بيانات JSON غير صالح." });
                return;
            }
            next();
        });
    });
    app.use((req, res, next) => {
        const ct = String(req.headers["content-type"] || "").toLowerCase();
        if (ct.includes("multipart/form-data")) {
            next();
            return;
        }
        express_1.default.urlencoded({ limit: "50mb", extended: true })(req, res, next);
    });
    // Optional Firebase ID token decoder (validates Bearer tokens cleanly when provided)
    app.use("/api", async (req, res, next) => {
        const authHeader = String(req.headers.authorization || "");
        if (!authHeader.startsWith("Bearer ")) {
            return next();
        }
        const token = authHeader.slice(7).trim();
        if (!token) {
            return res.status(401).json({ ok: false, error: "رمز الجلسة فارغ." });
        }
        const admin = getCloudAdmin();
        if (!admin) {
            return next();
        }
        try {
            const decoded = await (0, auth_1.getAuth)(admin.app).verifyIdToken(token);
            req.firebaseUid = decoded.uid;
            req.firebaseEmailVerified = Boolean(decoded.email_verified);
            return next();
        }
        catch {
            return res.status(401).json({ ok: false, error: "انتهت صلاحية الجلسة، يرجى إعادة تسجيل الدخول." });
        }
    });
    // Healthcheck
    app.get("/api/health", (_req, res) => {
        const admin = getCloudAdmin();
        res.json({
            ok: true,
            hasGeminiKey: Boolean(process.env.GEMINI_API_KEY),
            cloudFunctions: Boolean(process.env.FUNCTION_TARGET || process.env.FUNCTION_SIGNATURE_TYPE),
            hasFirebaseAdmin: Boolean(admin),
        });
    });
    // Strict per-user state persistence (Firestore + local dev store parity)
    app.get("/api/user-data/:userId", async (req, res) => {
        const userId = sanitizeUserId(req.params.userId);
        if (!userId)
            return res.status(400).json({ ok: false, error: "معرف المستخدم غير صالح" });
        if (req.firebaseUid && userId !== req.firebaseUid && !userId.startsWith("user_")) {
            return res.status(403).json({ ok: false, error: "غير مصرح بالوصول إلى بيانات مستخدم آخر." });
        }
        const admin = getCloudAdmin();
        if (admin) {
            try {
                const stateSnap = await admin.firestore
                    .collection("users")
                    .doc(userId)
                    .collection("workspace")
                    .doc("state")
                    .get();
                if (stateSnap.exists) {
                    return res.json({ ok: true, data: stateSnap.data() });
                }
            }
            catch (err) {
                console.warn("Firestore read fallback to local store:", err);
            }
        }
        const store = readStore();
        return res.json({ ok: true, data: store.users[userId] || null });
    });
    app.put("/api/user-data/:userId", async (req, res) => {
        const userId = sanitizeUserId(req.params.userId);
        if (!userId)
            return res.status(400).json({ ok: false, error: "معرف المستخدم غير صالح" });
        if (req.firebaseUid && userId !== req.firebaseUid && !userId.startsWith("user_")) {
            return res.status(403).json({ ok: false, error: "غير مصرح بتعديل بيانات مستخدم آخر." });
        }
        const incoming = req.body && typeof req.body === "object" ? req.body : {};
        let mergedWorkspace = null;
        await enqueueStoreMutation((store) => {
            const existing = store.users[userId];
            const serverImgs = Array.isArray(existing?.images) ? existing.images : [];
            const clientImgs = Array.isArray(incoming?.images) ? incoming.images : [];
            const mergedImgMap = new Map();
            for (const img of [...clientImgs, ...serverImgs]) {
                if (img && img.id && img.userId === userId && !mergedImgMap.has(img.id)) {
                    mergedImgMap.set(img.id, img);
                }
            }
            mergedWorkspace = {
                ...incoming,
                userId,
                images: Array.from(mergedImgMap.values()).slice(0, 60),
            };
            store.users[userId] = mergedWorkspace;
        });
        const admin = getCloudAdmin();
        if (admin && mergedWorkspace) {
            try {
                const userRef = admin.firestore.collection("users").doc(userId);
                const stateRef = userRef.collection("workspace").doc("state");
                const existingState = await stateRef.get();
                const existingData = existingState.exists ? existingState.data() : null;
                const serverImgs = Array.isArray(existingData?.images) ? existingData.images : [];
                const clientImgs = Array.isArray(mergedWorkspace.images) ? mergedWorkspace.images : [];
                const mergedMap = new Map();
                for (const img of [...clientImgs, ...serverImgs]) {
                    if (img && img.id && img.userId === userId && !mergedMap.has(img.id)) {
                        mergedMap.set(img.id, img);
                    }
                }
                const displayName = String(mergedWorkspace.profile?.displayName || "المستخدم").slice(0, 120);
                const batch = admin.firestore.batch();
                batch.set(userRef, {
                    userId,
                    displayName,
                    createdAt: existingData?.createdAt || firestore_1.FieldValue.serverTimestamp(),
                    updatedAt: firestore_1.FieldValue.serverTimestamp(),
                }, { merge: true });
                batch.set(stateRef, {
                    userId,
                    profile: mergedWorkspace.profile || { userId, displayName, createdAt: new Date().toISOString() },
                    settings: mergedWorkspace.settings || {},
                    sessions: Array.isArray(mergedWorkspace.sessions) ? mergedWorkspace.sessions.slice(0, 60) : [],
                    images: Array.from(mergedMap.values()).slice(0, 60),
                    memories: Array.isArray(mergedWorkspace.memories) ? mergedWorkspace.memories.slice(0, 150) : [],
                    chats: Array.isArray(mergedWorkspace.chats) ? mergedWorkspace.chats.slice(-80) : [],
                    scans: Array.isArray(mergedWorkspace.scans) ? mergedWorkspace.scans.slice(0, 80) : [],
                    labs: Array.isArray(mergedWorkspace.labs) ? mergedWorkspace.labs.slice(0, 150) : [],
                    riskAssessments: Array.isArray(mergedWorkspace.riskAssessments)
                        ? mergedWorkspace.riskAssessments.slice(0, 20)
                        : [],
                    reports: Array.isArray(mergedWorkspace.reports) ? mergedWorkspace.reports.slice(0, 80) : [],
                    createdAt: existingData?.createdAt || firestore_1.FieldValue.serverTimestamp(),
                    updatedAt: firestore_1.FieldValue.serverTimestamp(),
                }, { merge: true });
                await batch.commit();
            }
            catch (err) {
                console.warn("Firestore write warning:", err);
            }
        }
        return res.json({ ok: true });
    });
    app.delete("/api/user-data/:userId", async (req, res) => {
        const userId = sanitizeUserId(req.params.userId);
        if (!userId)
            return res.status(400).json({ ok: false, error: "معرف المستخدم غير صالح" });
        if (req.firebaseUid && userId !== req.firebaseUid && !userId.startsWith("user_")) {
            return res.status(403).json({ ok: false, error: "غير مصرح بحذف بيانات مستخدم آخر." });
        }
        await enqueueStoreMutation((store) => {
            delete store.users[userId];
        });
        const { UPLOADS_DIR } = getLocalDataPaths();
        const userUploadDir = node_path_1.default.join(UPLOADS_DIR, userId);
        if (node_fs_1.default.existsSync(userUploadDir)) {
            node_fs_1.default.rmSync(userUploadDir, { recursive: true, force: true });
        }
        const admin = getCloudAdmin();
        if (admin) {
            try {
                await admin.firestore.collection("users").doc(userId).collection("workspace").doc("state").delete().catch(() => { });
                const uploadsSnap = await admin.firestore.collection("users").doc(userId).collection("uploads").get();
                const delBatch = admin.firestore.batch();
                uploadsSnap.docs.forEach((d) => delBatch.delete(d.ref));
                await delBatch.commit();
                if (admin.bucket) {
                    await admin.bucket.deleteFiles({ prefix: `users/${userId}/uploads/` }).catch(() => { });
                }
            }
            catch (err) {
                console.warn("Firestore/Storage delete cleanup warning:", err);
            }
        }
        return res.json({ ok: true });
    });
    // Real Multipart Image Upload (Busboy supporting both Cloud Functions Gen 2 rawBody & local Express stream)
    app.post("/api/uploads/:userId", async (req, res) => {
        const userId = sanitizeUserId(req.params.userId);
        if (!userId)
            return res.status(400).json({ ok: false, error: "معرف المستخدم غير صالح" });
        if (req.firebaseUid && userId !== req.firebaseUid && !userId.startsWith("user_")) {
            return res.status(403).json({ ok: false, error: "غير مصرح برفع صور لحساب آخر." });
        }
        try {
            const parsed = await parseMultipartWithBusboy(req);
            if (!parsed.file || !parsed.file.buffer || parsed.file.buffer.length === 0) {
                return res.status(400).json({ ok: false, error: "لم يتم إرفاق أي ملف صورة صالح." });
            }
            const detectedMime = detectImageSignatureMime(parsed.file.buffer);
            if (!detectedMime) {
                return res.status(415).json({
                    ok: false,
                    error: "محتوى الملف ليس صورة JPEG أو PNG أو WebP صالحة.",
                });
            }
            const ext = detectedMime === "image/png" ? "png" : detectedMime === "image/webp" ? "webp" : "jpg";
            const imageId = `img_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
            const filename = `${imageId}.${ext}`;
            const sectionId = parsed.fields.sectionId || "FACE_EYE_SKIN";
            const sourceOrigin = parsed.fields.sourceOrigin || "gallery";
            const sessionId = parsed.fields.sessionId || undefined;
            const storagePath = `users/${userId}/uploads/${filename}`;
            ensureDataDir();
            const { UPLOADS_DIR } = getLocalDataPaths();
            const userDir = node_path_1.default.join(UPLOADS_DIR, userId);
            if (!node_fs_1.default.existsSync(userDir))
                node_fs_1.default.mkdirSync(userDir, { recursive: true });
            node_fs_1.default.writeFileSync(node_path_1.default.join(userDir, filename), parsed.file.buffer);
            const record = {
                id: imageId,
                userId,
                ...(sessionId ? { sessionId } : {}),
                sectionId,
                filename,
                url: `/api/uploads/${userId}/${filename}`,
                storagePath,
                mimeType: detectedMime,
                sizeBytes: parsed.file.buffer.length,
                sourceOrigin,
                uploadedAt: new Date().toISOString(),
            };
            const admin = getCloudAdmin();
            if (admin) {
                let savedToBucket = false;
                if (admin.bucket) {
                    try {
                        await admin.bucket.file(storagePath).save(parsed.file.buffer, {
                            resumable: false,
                            contentType: detectedMime,
                            metadata: {
                                cacheControl: "private, max-age=3600",
                                metadata: { userId, sectionId, imageId },
                            },
                        });
                        savedToBucket = true;
                    }
                    catch (bucketErr) {
                        console.warn("Firebase Storage save warning (using Firestore/local fallback):", bucketErr);
                    }
                }
                try {
                    const userRef = admin.firestore.collection("users").doc(userId);
                    await userRef.set({
                        userId,
                        displayName: "المستخدم",
                        createdAt: firestore_1.FieldValue.serverTimestamp(),
                        updatedAt: firestore_1.FieldValue.serverTimestamp(),
                    }, { merge: true });
                    await userRef
                        .collection("uploads")
                        .doc(imageId)
                        .set({
                        ...record,
                        ...(!savedToBucket && parsed.file.buffer.length <= 750000
                            ? { inlineBase64: parsed.file.buffer.toString("base64") }
                            : {}),
                        createdAt: firestore_1.FieldValue.serverTimestamp(),
                    });
                }
                catch (fsErr) {
                    console.warn("Firestore upload metadata warning:", fsErr);
                }
            }
            await enqueueStoreMutation((store) => {
                if (!store.users[userId]) {
                    store.users[userId] = {
                        profile: { userId, displayName: "المستخدم", createdAt: new Date().toISOString() },
                        images: [],
                    };
                }
                const existingImgs = Array.isArray(store.users[userId].images) ? store.users[userId].images : [];
                store.users[userId].images = [record, ...existingImgs].slice(0, 60);
            });
            return res.json({ ok: true, image: record });
        }
        catch (err) {
            if (err?.status === 413 || err?.message === "LIMIT_FILE_SIZE") {
                return res.status(413).json({ ok: false, error: "حجم الصورة يتجاوز الحد الأقصى (20 ميجابايت)." });
            }
            if (err?.status === 415 || err?.message === "UNSUPPORTED_MIME") {
                return res.status(415).json({ ok: false, error: "صيغة الملف غير مدعومة. المسموح: JPEG أو PNG أو WebP." });
            }
            if (err?.status === 400 || err?.message === "INVALID_CONTENT_TYPE") {
                return res.status(400).json({ ok: false, error: "طلب رفع الصورة غير مكتمل أو نوع المحتوى غير صالح." });
            }
            console.error("Upload save error:", err);
            return res.status(500).json({ ok: false, error: "تعذر حفظ الصورة في الخادم، حاول مرة أخرى." });
        }
    });
    app.get("/api/uploads/:userId/:filename", async (req, res) => {
        const userId = sanitizeUserId(req.params.userId);
        const safeName = sanitizeFilename(req.params.filename);
        if (!userId || !safeName)
            return res.status(400).json({ ok: false, error: "طلب غير صالح" });
        const found = await readUserImageBuffer(userId, safeName);
        if (!found) {
            return res.status(404).json({ ok: false, error: "الصورة غير موجودة في حساب هذا المستخدم" });
        }
        res.setHeader("Content-Type", found.mimeType);
        res.setHeader("Cache-Control", "private, max-age=3600");
        return res.send(found.buffer);
    });
    // Analyze an actually-uploaded image verified on disk or Firebase Storage
    app.post("/api/ai/analyze-uploaded-image", async (req, res) => {
        try {
            const { userId, filename, sectionId, previousComparisonContext } = (req.body || {});
            const cleanUser = sanitizeUserId(userId);
            const safeName = sanitizeFilename(filename);
            if (!cleanUser || !safeName) {
                return res.status(400).json({ ok: false, imageProcessed: false, error: "بيانات الصورة المرفوعة غير مكتملة." });
            }
            const found = await readUserImageBuffer(cleanUser, safeName);
            if (!found || found.buffer.length === 0) {
                return res.status(404).json({
                    ok: false,
                    imageProcessed: false,
                    error: "لم تصل الصورة إلى الخادم أو غير تابعة لهذا المستخدم.",
                });
            }
            const ai = getAiClient();
            if (!ai) {
                return res.status(503).json({ ok: false, imageProcessed: false, error: "مفتاح Gemini API غير مهيأ في الخادم." });
            }
            const promptMap = {
                FACE_EYE_SKIN: "حلل الصورة الفعلية بدقة لتقييم الوجه والعين والبشرة مع تغطية كل محور بوضوح في الملاحظات والتوصيات: (1) التجاعيد والخطوط الدقيقة، (2) الحبوب والبثور، (3) التصبغات والبقع، (4) الاحمرار، (5) الهالات حول العين، (6) الانتفاخ الظاهر، (7) ملمس البشرة والمسام الظاهرة، (8) اقتراح روتين عناية يومي مناسب ولطيف بدون وصف أدوية.",
                HAIR_SCALP: "حلل الصورة الفعلية لتقييم الشعر وفروة الرأس الظاهرة فقط مع تغطية كل محور بوضوح: (1) كثافة الشعر الظاهرة، (2) فراغات الشعر، (3) خط الشعر، (4) القشرة الظاهرة، (5) علامات فروة الرأس الظاهرة إذا كانت مرئية بوضوح فقط. لا تخترع حالة جذور غير مرئية.",
                BODY_POSTURE: "حلل الصورة الفعلية لتقييم القوام ووضعية الوقوف وتوازن الكتفين فقط دون تخمين الوزن أو الدهون.",
            };
            const response = await generateContentWithRetry(ai, {
                model: "gemini-4-hydrogen",
                contents: {
                    parts: [
                        { inlineData: { mimeType: found.mimeType, data: found.buffer.toString("base64") } },
                        { text: promptMap[sectionId] || promptMap.FACE_EYE_SKIN },
                    ],
                },
                config: {
                    thinkingConfig: { thinkingLevel: genai_1.ThinkingLevel.LOW },
                    systemInstruction: `أنت محرك تحليل الصور الصحية غير التشخيصي في سَبَق (SABQ AI).
- حلل فقط ما يظهر في الصورة الفعلية المرفوعة. إذا لم يظهر العضو المطلوب أو كانت الصورة غير واضحة، اجعل isAreaVisible=false ولا تخترع نتائج.
- ممنوع تمامًا تخمين السكر أو الضغط أو الهيموجلوبين أو الدهون من الصورة، وممنوع وصف أدوية.
- الأرقام بالإنجليزية 0-9.
${previousComparisonContext ? `سياق للمقارنة بالصور السابقة: ${previousComparisonContext}` : ""}`,
                    responseMimeType: "application/json",
                    responseSchema: {
                        type: genai_1.Type.OBJECT,
                        properties: {
                            isAreaVisible: { type: genai_1.Type.BOOLEAN },
                            summary: { type: genai_1.Type.STRING },
                            observations: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                            potentialIssues: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                            recommendations: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                            doctorConsultationCriteria: { type: genai_1.Type.STRING },
                            confidenceScore: { type: genai_1.Type.NUMBER },
                        },
                        required: [
                            "isAreaVisible",
                            "summary",
                            "observations",
                            "potentialIssues",
                            "recommendations",
                            "doctorConsultationCriteria",
                            "confidenceScore",
                        ],
                    },
                },
            });
            const parsed = parseGeminiJson(response.text, {});
            return res.json({
                ok: true,
                imageProcessed: true,
                imageUrl: `/api/uploads/${cleanUser}/${safeName}`,
                ...parsed,
                source: "تحليل فعلي للصورة المرفوعة عبر الخادم (تقدير بصري غير تشخيصي)",
            });
        }
        catch (err) {
            console.error("analyze-uploaded-image error:", err?.status, err?.message || err);
            const mapped = formatGeminiError(err, "تعذرت معالجة الصورة المرفوعة، يرجى إعادة المحاولة.");
            return res.status(mapped.status).json({ ok: false, imageProcessed: false, error: mapped.error });
        }
    });
    // Image Analysis Endpoint
    app.post("/api/ai/analyze-image", async (req, res) => {
        try {
            const { mode, imageBase64, mimeType, previousComparisonContext } = (req.body || {});
            if (!imageBase64) {
                return res.status(400).json({ ok: false, error: "لم يتم إرسال صورة للتحليل." });
            }
            const ai = getAiClient();
            if (!ai) {
                return res.status(503).json({ ok: false, error: "مفتاح Gemini API غير مهيأ في بيئة الخادم." });
            }
            const modeInstructions = {
                face_eye: "حلل الملاحظات الظاهرة فقط في الوجه ومحيط العين: حب الشباب، البثور، التجاعيد، الخطوط الدقيقة، التصبغات، البقع، الاحمرار، الهالات، الانتفاخ الظاهر حول العين، وأي تغيرات ظاهرية في البشرة. لا تستنتج أمراضًا باطنية أو ضغطًا أو سكرًا من شكل الوجه.",
                skin: "حلل العلامات الجلدية الظاهرة، الملمس الظاهر، وتقدير الجفاف الظاهر بحذر، واقترح روتين عناية مناسب وإرشادات استخدام منتجات العناية، ووضح متى يجب مراجعة طبيب جلدية.",
                hair: "حلل كثافة الشعر الظاهرة، الفراغات المحتملة، علامات التساقط الظاهرة، القشرة الظاهرة، وحالة فروة الرأس الظاهرة في الصورة مع نصائح عملية للعناية بالشعر.",
                body_posture: "حلل وضعية الجسم الظاهرة (توازن الكتفين، استقامة الرقبة والظهر إن ظهرت في الصورة) مع نصائح للوضعية السليمة والنشاط البدني. لا تخترع وزنًا أو مؤشر كتلة جسم من الصورة.",
            };
            const systemInstruction = `أنت وحدة التحليل البصري غير التشخيصي في تطبيق سَبَق (SABQ AI).
القواعد الصارمة:
1. لغتك عربية واضحة ومبسطة، والأرقام بالإنجليزية (0-9).
2. ممنوع تمامًا تشخيص الأمراض أو الادعاء بمعرفة ضغط الدم أو السكر أو الهيموجلوبين أو الغدة أو الكبد أو الكلى من الصورة.
3. ممنوع وصف أدوية أو تحديد جرعات علاجية.
4. يجب توضيح أن التحليل "تقدير غير تشخيصي" يعتمد على جودة الإضاءة والصورة.
5. إذا كانت الصورة غير واضحة أو لا يظهر فيها العضو المطلوب، اذكر ذلك بصدق في حقل summary واجعل confidence منخفضة.
${previousComparisonContext ? `سياق للمقارنة مع فحص سابق: ${previousComparisonContext}` : ""}`;
            const cleanBase64 = imageBase64.replace(/^data:[^;]+;base64,/, "").trim();
            if (cleanBase64.length < 100) {
                return res.status(422).json({ ok: false, error: "الصورة فارغة أو غير صالحة للتحليل." });
            }
            const response = await generateContentWithRetry(ai, {
                model: "gemini-4-hydrogen",
                contents: {
                    parts: [
                        { inlineData: { mimeType: mimeType || "image/jpeg", data: cleanBase64 } },
                        { text: modeInstructions[mode] || modeInstructions.face_eye },
                    ],
                },
                config: {
                    thinkingConfig: { thinkingLevel: genai_1.ThinkingLevel.LOW },
                    systemInstruction,
                    responseMimeType: "application/json",
                    responseSchema: {
                        type: genai_1.Type.OBJECT,
                        properties: {
                            summary: { type: genai_1.Type.STRING },
                            observations: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                            potentialIssues: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                            recommendations: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                            doctorConsultationCriteria: { type: genai_1.Type.STRING },
                            confidenceScore: { type: genai_1.Type.NUMBER },
                            isImageSufficient: { type: genai_1.Type.BOOLEAN },
                        },
                        required: [
                            "summary",
                            "observations",
                            "potentialIssues",
                            "recommendations",
                            "doctorConsultationCriteria",
                            "confidenceScore",
                            "isImageSufficient",
                        ],
                    },
                },
            });
            const parsed = parseGeminiJson(response.text, {});
            return res.json({
                ok: true,
                ...parsed,
                isNonDiagnosticEstimate: true,
                source: "تحليل بصري بالذكاء الاصطناعي للصورة الملتقطة (تقدير غير تشخيصي)",
            });
        }
        catch (err) {
            console.error("analyze-image error:", err?.status, err?.message || err);
            const mapped = formatGeminiError(err, "تعذر إجراء القياس، يرجى المحاولة مرة أخرى.");
            return res.status(mapped.status).json({ ok: false, error: mapped.error });
        }
    });
    // Live Auto-Scan Endpoint
    app.post("/api/ai/auto-scan", async (req, res) => {
        try {
            const { imageBase64, mimeType, previousContexts } = (req.body || {});
            if (!imageBase64) {
                return res.status(400).json({ ok: false, error: "لم يتم إرسال إطار كاميرا للتحليل." });
            }
            const ai = getAiClient();
            if (!ai) {
                return res.status(503).json({ ok: false, error: "مفتاح Gemini API غير مهيأ في بيئة الخادم." });
            }
            const systemInstruction = `أنت محرك الفحص التلقائي البصري في تطبيق سَبَق (SABQ AI).
تستلم لقطة واحدة من جلسة الكاميرا الحية وتفحص 4 مناطق مستقلة بصدق تام: الوجه (FACE)، العين (EYE)، البشرة (SKIN)، الشعر (HAIR).
لا تدّعِ أبدًا تحليل فروة الرأس أو جذور الشعر إذا لم تكن ظاهرة بوضوح في الصورة! لا تصف أدوية ولا تخترع أرقام تحاليل.
استخدم العربية المبسطة والأرقام الإنجليزية 0-9.
${previousContexts ? `سياق مقارنات سابقة: ${JSON.stringify(previousContexts)}` : ""}`;
            const regionSchema = {
                type: genai_1.Type.OBJECT,
                properties: {
                    isVisible: { type: genai_1.Type.BOOLEAN },
                    angleGuidanceAr: { type: genai_1.Type.STRING },
                    summary: { type: genai_1.Type.STRING },
                    observations: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                    potentialIssues: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                    severityLevel: { type: genai_1.Type.STRING },
                    limitations: { type: genai_1.Type.STRING },
                    recommendations: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                    doctorConsultationCriteria: { type: genai_1.Type.STRING },
                    confidenceScore: { type: genai_1.Type.NUMBER },
                },
                required: [
                    "isVisible",
                    "angleGuidanceAr",
                    "summary",
                    "observations",
                    "potentialIssues",
                    "severityLevel",
                    "limitations",
                    "recommendations",
                    "doctorConsultationCriteria",
                    "confidenceScore",
                ],
            };
            const cleanBase64 = imageBase64.replace(/^data:[^;]+;base64,/, "").trim();
            const response = await generateContentWithRetry(ai, {
                model: "gemini-4-hydrogen",
                contents: {
                    parts: [
                        { inlineData: { mimeType: mimeType || "image/jpeg", data: cleanBase64 } },
                        {
                            text: "حلل هذا الإطار الحي من الكاميرا تلقائيًا وأعد التقييم المستقل لكل من: الوجه (face)، العين (eye)، البشرة (skin)، والشعر الظاهر (hair).",
                        },
                    ],
                },
                config: {
                    thinkingConfig: { thinkingLevel: genai_1.ThinkingLevel.LOW },
                    systemInstruction,
                    responseMimeType: "application/json",
                    responseSchema: {
                        type: genai_1.Type.OBJECT,
                        properties: {
                            faceDetected: { type: genai_1.Type.BOOLEAN },
                            lightingQualityAr: { type: genai_1.Type.STRING },
                            face: regionSchema,
                            eye: regionSchema,
                            skin: regionSchema,
                            hair: regionSchema,
                        },
                        required: ["faceDetected", "lightingQualityAr", "face", "eye", "skin", "hair"],
                    },
                },
            });
            const parsed = parseGeminiJson(response.text, {});
            return res.json({
                ok: true,
                ...parsed,
                source: "التحليل التلقائي لإطارات الكاميرا الحية (تقدير غير تشخيصي)",
            });
        }
        catch (err) {
            console.error("auto-scan error:", err?.status, err?.message || err);
            const mapped = formatGeminiError(err, "تعذر إجراء القياس، يرجى المحاولة مرة أخرى.");
            return res.status(mapped.status).json({ ok: false, error: mapped.error });
        }
    });
    // Guided Stage-by-Stage Analysis Endpoint for SABQ SMART SCAN
    app.post("/api/ai/smart-scan-stage", async (req, res) => {
        try {
            const { stage, imageBase64, mimeType, previousContexts } = (req.body || {});
            if (!imageBase64 || !["face", "body", "hair"].includes(stage)) {
                return res.status(400).json({ ok: false, error: "بيانات المرحلة غير مكتملة." });
            }
            const ai = getAiClient();
            if (!ai) {
                return res.status(503).json({ ok: false, error: "مفتاح Gemini API غير مهيأ في بيئة الخادم." });
            }
            const regionSchema = {
                type: genai_1.Type.OBJECT,
                properties: {
                    isVisible: { type: genai_1.Type.BOOLEAN },
                    guidanceAr: { type: genai_1.Type.STRING },
                    summary: { type: genai_1.Type.STRING },
                    observations: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                    potentialIssues: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                    severityLevel: { type: genai_1.Type.STRING },
                    limitations: { type: genai_1.Type.STRING },
                    recommendations: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                    doctorConsultationCriteria: { type: genai_1.Type.STRING },
                    confidenceScore: { type: genai_1.Type.NUMBER },
                },
                required: [
                    "isVisible",
                    "guidanceAr",
                    "summary",
                    "observations",
                    "potentialIssues",
                    "severityLevel",
                    "limitations",
                    "recommendations",
                    "doctorConsultationCriteria",
                    "confidenceScore",
                ],
            };
            const cleanBase64 = imageBase64.replace(/^data:[^;]+;base64,/, "").trim();
            if (cleanBase64.length < 100) {
                return res.status(422).json({
                    ok: false,
                    error: "الصورة الملتقطة فارغة أو صغيرة جدًا للتحليل، يرجى إعادة التصوير بوضوح.",
                });
            }
            const safeMimeType = mimeType === "image/png" || mimeType === "image/webp" ? mimeType : "image/jpeg";
            if (stage === "face") {
                const response = await generateContentWithRetry(ai, {
                    model: "gemini-4-hydrogen",
                    contents: {
                        parts: [
                            { inlineData: { mimeType: safeMimeType, data: cleanBase64 } },
                            {
                                text: "حلل لقطة الوجه هذه إلى 3 نتائج مستقلة: (1) الوجه face (الملامح الظاهرة، التجاعيد، الخطوط الدقيقة، الحبوب والبثور)، (2) العين eye (الهالات حول العين، الانتفاخ الظاهر)، (3) البشرة skin (التصبغات، البقع، الاحمرار، العلامات الظاهرة).",
                            },
                        ],
                    },
                    config: {
                        thinkingConfig: { thinkingLevel: genai_1.ThinkingLevel.LOW },
                        systemInstruction: `أنت محلل المرحلة الأولى (الوجه والعين والبشرة) في SABQ SMART SCAN.
- حلل فقط ما يظهر بوضوح في الصورة دون اختلاق أي تشخيص طبي أو قياس ضغط أو سكر.
- لا تصف أدوية أو جرعات.
- إذا كان الوجه غير واضح أو الإضاءة سيئة اجعل isVisible=false وأعط guidanceAr واضحًا بالعربية.
- الأرقام بالإنجليزية 0-9.
${previousContexts ? `سياق فحوص سابقة: ${JSON.stringify(previousContexts)}` : ""}`,
                        responseMimeType: "application/json",
                        responseSchema: {
                            type: genai_1.Type.OBJECT,
                            properties: {
                                face: regionSchema,
                                eye: regionSchema,
                                skin: regionSchema,
                            },
                            required: ["face", "eye", "skin"],
                        },
                    },
                });
                const parsed = parseGeminiJson(response.text, {});
                return res.json({
                    ok: true,
                    stage: "face",
                    ...parsed,
                    source: "SABQ SMART SCAN — المرحلة الأولى (لقطة الوجه التلقائية - تقدير غير تشخيصي)",
                });
            }
            if (stage === "body") {
                const response = await generateContentWithRetry(ai, {
                    model: "gemini-4-hydrogen",
                    contents: {
                        parts: [
                            { inlineData: { mimeType: safeMimeType, data: cleanBase64 } },
                            {
                                text: "حلل لقطة الجسم بالكامل لوضعية الوقوف: استقامة الظهر الظاهرة، وضعية الكتفين، التوازن الظاهري، تماثل وضعية الجسم، وزوايا المفاصل الممكن تقديرها من الصورة.",
                            },
                        ],
                    },
                    config: {
                        thinkingConfig: { thinkingLevel: genai_1.ThinkingLevel.LOW },
                        systemInstruction: `أنت محلل المرحلة الثانية (الجسم كاملًا ووضعية الوقوف) في SABQ SMART SCAN.
قواعد صارمة:
- ممنوع تمامًا استنتاج الوزن أو نسبة الدهون أو أمراض الأعضاء الداخلية من صورة الجسم وحدها!
- إذا لم يظهر الجسم كاملًا أو لم يظهر الرأس والقدمان بوضوح، اجعل isVisible=false واكتب توجيهًا عربيًا دقيقًا في guidanceAr.
- الأرقام بالإنجليزية 0-9.`,
                        responseMimeType: "application/json",
                        responseSchema: {
                            type: genai_1.Type.OBJECT,
                            properties: { body: regionSchema },
                            required: ["body"],
                        },
                    },
                });
                const parsed = parseGeminiJson(response.text, {});
                return res.json({
                    ok: true,
                    stage: "body",
                    ...parsed,
                    source: "SABQ SMART SCAN — المرحلة الثانية (لقطة الجسم الكامل - تقدير غير تشخيصي)",
                });
            }
            const response = await generateContentWithRetry(ai, {
                model: "gemini-4-hydrogen",
                contents: {
                    parts: [
                        { inlineData: { mimeType: safeMimeType, data: cleanBase64 } },
                        {
                            text: "حلل لقطة الشعر وفروة الرأس الظاهرة: كثافة الشعر الظاهرة، الفراغات، خط الشعر، تغيرات التوزيع الظاهرة، القشرة الظاهرة، احمرار أو علامات فروة الرأس إذا كانت ظاهرة بوضوح فقط.",
                        },
                    ],
                },
                config: {
                    thinkingConfig: { thinkingLevel: genai_1.ThinkingLevel.LOW },
                    systemInstruction: `أنت محلل المرحلة الثالثة (الشعر وفروة الرأس) في SABQ SMART SCAN.
قواعد صارمة:
- إذا لم تكن فروة الرأس أو منطقة الشعر واضحة في اللقطة، لا تخترع نتائج أبدًا! اجعل isVisible=false واكتب بوضوح في guidanceAr ما يجب فعله.
- لا تصف أدوية.
- الأرقام بالإنجليزية 0-9.`,
                    responseMimeType: "application/json",
                    responseSchema: {
                        type: genai_1.Type.OBJECT,
                        properties: { hair: regionSchema },
                        required: ["hair"],
                    },
                },
            });
            const parsed = parseGeminiJson(response.text, {});
            return res.json({
                ok: true,
                stage: "hair",
                ...parsed,
                source: "SABQ SMART SCAN — المرحلة الثالثة (لقطة الشعر وفروة الرأس - تقدير غير تشخيصي)",
            });
        }
        catch (err) {
            console.error("smart-scan-stage error:", err?.status, err?.message || err);
            const mapped = formatGeminiError(err, "تعذر تحليل هذه المرحلة، يرجى المحاولة مرة أخرى.");
            return res.status(mapped.status).json({ ok: false, error: mapped.error });
        }
    });
    // Health Assessment Endpoint
    app.post("/api/ai/health-assessment", async (req, res) => {
        try {
            const { intake, bmi, scansSummary, labsSummary } = (req.body || {});
            const ai = getAiClient();
            if (!ai) {
                return res.status(503).json({ ok: false, error: "مفتاح Gemini API غير مهيأ." });
            }
            const response = await generateContentWithRetry(ai, {
                model: "gemini-4-hydrogen",
                contents: `بيانات المستخدم الطوعية الفعلية:\n${JSON.stringify(intake)}\n\nمؤشر BMI الفعلي: ${bmi ?? "غير مدخل"}\n\nملخص الفحوص البصرية الفعلية:\n${JSON.stringify(scansSummary || [])}\n\nالتحاليل المخبرية الموثقة:\n${JSON.stringify(labsSummary || [])}`,
                config: {
                    thinkingConfig: { thinkingLevel: genai_1.ThinkingLevel.LOW },
                    systemInstruction: `أنت وحدة SABQ SMART HEALTH ASSESSMENT. قدم تقييم عوامل خطورة شخصي ومبسط بالعربية (الأرقام 0-9). ممنوع عرض أرقام سكر دم أو ضغط أو HbA1c تخمينية.`,
                    responseMimeType: "application/json",
                    responseSchema: {
                        type: genai_1.Type.OBJECT,
                        properties: {
                            assessments: {
                                type: genai_1.Type.ARRAY,
                                items: {
                                    type: genai_1.Type.OBJECT,
                                    properties: {
                                        category: { type: genai_1.Type.STRING },
                                        titleAr: { type: genai_1.Type.STRING },
                                        riskLevelAr: { type: genai_1.Type.STRING },
                                        contributingFactors: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                                        summary: { type: genai_1.Type.STRING },
                                        limitations: { type: genai_1.Type.STRING },
                                        recommendations: { type: genai_1.Type.ARRAY, items: { type: genai_1.Type.STRING } },
                                        doctorConsultationCriteria: { type: genai_1.Type.STRING },
                                    },
                                    required: [
                                        "category",
                                        "titleAr",
                                        "riskLevelAr",
                                        "contributingFactors",
                                        "summary",
                                        "limitations",
                                        "recommendations",
                                        "doctorConsultationCriteria",
                                    ],
                                },
                            },
                        },
                        required: ["assessments"],
                    },
                },
            });
            const parsed = parseGeminiJson(response.text, { assessments: [] });
            return res.json({ ok: true, ...parsed });
        }
        catch (err) {
            console.error("health-assessment error:", err?.status, err?.message || err);
            const mapped = formatGeminiError(err, "تعذر إجراء التقييم الصحي حاليًا.");
            return res.status(mapped.status).json({ ok: false, error: mapped.error });
        }
    });
    // Second Brain Chat Endpoint
    app.post("/api/ai/chat", async (req, res) => {
        try {
            const { message, history, memories, recentScans, labReadings, imageBase64, mimeType } = (req.body || {});
            if (!message && !imageBase64) {
                return res.status(400).json({ ok: false, error: "الرسالة فارغة" });
            }
            const ai = getAiClient();
            if (!ai) {
                return res.status(503).json({ ok: false, error: "مفتاح Gemini API غير مهيأ في بيئة الخادم." });
            }
            const memoryContext = memories && memories.length > 0
                ? memories
                    .slice(0, 20)
                    .map((m) => `- [${m.category} | ${m.certainty}] ${m.title}: ${m.content}`)
                    .join("\n")
                : "لا توجد عناصر ذاكرة محفوظة بعد.";
            const scansContext = recentScans && recentScans.length > 0
                ? recentScans
                    .slice(0, 6)
                    .map((s) => `- (${s.scanType} في ${s.timestamp}): ${s.summary}`)
                    .join("\n")
                : "لا توجد فحوص بصرية مسجلة.";
            const labsContext = labReadings && labReadings.length > 0
                ? labReadings
                    .slice(0, 12)
                    .map((l) => `- ${l.testNameAr}: ${l.measuredValue} ${l.unit} (المصدر: ${l.source})`)
                    .join("\n")
                : "لا توجد تحاليل مخبرية مدخلة.";
            const systemInstruction = `أنت "العقل الإضافي الذكي" داخل تطبيق سَبَق (SABQ AI).
قواعدك:
1. تفهم العربية الفصحى واللهجة المصرية بطلاقة، وتجيب بالعربية الواضحة والأرقام 0-9.
2. اربط المعلومات الجديدة بما حفظه المستخدم في ذاكرته ومشروعاته وفحوصه الفعلية فقط.
3. لا تدّعِ قراءة الأفكار، وافصل بين المعلومة المؤكدة والتوقع والاستنتاج.
4. لا تصف أدوية أو جرعات علاجية، ولا تشخّص أمراضًا، ووجّه لمراجعة الطبيب عند الأعراض المهمة.
5. إذا طلب المستخدم تذكّر معلومة أو مشروع أو هدف صحي، اقترحها في suggestedMemories.

الذاكرة المتاحة:
${memoryContext}

الفحوص الحديثة:
${scansContext}

التحاليل الموثقة:
${labsContext}`;
            const parts = [];
            if (imageBase64) {
                const cleanBase64 = imageBase64.replace(/^data:[^;]+;base64,/, "");
                parts.push({ inlineData: { mimeType: mimeType || "image/jpeg", data: cleanBase64 } });
            }
            const historyPrefix = history && history.length > 0
                ? `سياق المحادثة السابقة:\n${history
                    .slice(-6)
                    .map((h) => `${h.role === "user" ? "المستخدم" : "سَبَق"}: ${h.content}`)
                    .join("\n")}\n\n`
                : "";
            parts.push({ text: `${historyPrefix}رسالة المستخدم الحالية: ${message || "يرجى تحليل الصورة المرفقة."}` });
            const response = await generateContentWithRetry(ai, {
                model: "gemini-4-hydrogen",
                contents: { parts },
                config: {
                    thinkingConfig: { thinkingLevel: genai_1.ThinkingLevel.LOW },
                    systemInstruction,
                    responseMimeType: "application/json",
                    responseSchema: {
                        type: genai_1.Type.OBJECT,
                        properties: {
                            reply: { type: genai_1.Type.STRING },
                            certaintyClassification: { type: genai_1.Type.STRING },
                            suggestedMemories: {
                                type: genai_1.Type.ARRAY,
                                items: {
                                    type: genai_1.Type.OBJECT,
                                    properties: {
                                        category: { type: genai_1.Type.STRING },
                                        certainty: { type: genai_1.Type.STRING },
                                        title: { type: genai_1.Type.STRING },
                                        content: { type: genai_1.Type.STRING },
                                    },
                                    required: ["category", "certainty", "title", "content"],
                                },
                            },
                        },
                        required: ["reply", "certaintyClassification", "suggestedMemories"],
                    },
                },
            });
            const parsed = parseGeminiJson(response.text, {});
            return res.json({ ok: true, ...parsed });
        }
        catch (err) {
            console.error("chat error:", err?.status, err?.message || err);
            const mapped = formatGeminiError(err, "تعذر معالجة الرسالة حاليًا، يرجى المحاولة مرة أخرى.");
            return res.status(mapped.status).json({ ok: false, error: mapped.error });
        }
    });
    // Proactive Contextual Engine Endpoint
    app.post("/api/ai/proactive", async (req, res) => {
        try {
            const { memories, recentScans, quietHoursActive } = (req.body || {});
            if (quietHoursActive) {
                return res.json({
                    ok: true,
                    suppressedByQuietHours: true,
                    suggestions: [],
                    message: "تم تفعيل أوقات الهدوء — لن يرسل المساعد مقترحات مزعجة الآن.",
                });
            }
            if ((!memories || memories.length === 0) && (!recentScans || recentScans.length === 0)) {
                return res.json({
                    ok: true,
                    suppressedByQuietHours: false,
                    suggestions: [],
                    message: "لا توجد بيانات سياقية محفوظة بعد؛ احفظ مشروعًا أو معلومة أو أجرِ فحصًا ليبدأ الربط الاستباقي بدون تخمين.",
                });
            }
            const ai = getAiClient();
            if (!ai) {
                return res.status(503).json({ ok: false, error: "مفتاح Gemini API غير مهيأ." });
            }
            const response = await generateContentWithRetry(ai, {
                model: "gemini-4-hydrogen",
                contents: `الذاكرة المحفوظة:\n${JSON.stringify(memories)}\n\nالفحوص الأخيرة:\n${JSON.stringify(recentScans)}`,
                config: {
                    thinkingConfig: { thinkingLevel: genai_1.ThinkingLevel.LOW },
                    systemInstruction: `أنت المحرك الاستباقي لتطبيق سَبَق (SABQ AI).
استخرج 1 إلى 3 اقتراحات استباقية عملية ومفيدة ترتبط حصرًا بالبيانات المرسلة لك.
لا تدّعِ قراءة الأفكار ولا تبتكر حقائق، وصنّف كل اقتراح إلى CONFIRMED أو EXPECTATION أو INFERENCE.`,
                    responseMimeType: "application/json",
                    responseSchema: {
                        type: genai_1.Type.OBJECT,
                        properties: {
                            suggestions: {
                                type: genai_1.Type.ARRAY,
                                items: {
                                    type: genai_1.Type.OBJECT,
                                    properties: {
                                        title: { type: genai_1.Type.STRING },
                                        body: { type: genai_1.Type.STRING },
                                        certainty: { type: genai_1.Type.STRING },
                                        basis: { type: genai_1.Type.STRING },
                                    },
                                    required: ["title", "body", "certainty", "basis"],
                                },
                            },
                        },
                        required: ["suggestions"],
                    },
                },
            });
            const parsed = parseGeminiJson(response.text, { suggestions: [] });
            return res.json({ ok: true, suppressedByQuietHours: false, ...parsed });
        }
        catch (err) {
            console.error("proactive error:", err?.status, err?.message || err);
            const mapped = formatGeminiError(err, "تعذر توليد المقترحات الاستباقية.");
            return res.status(mapped.status).json({ ok: false, error: mapped.error });
        }
    });
    // Audio Transcription Endpoint
    app.post("/api/ai/transcribe", async (req, res) => {
        try {
            const { audioBase64, mimeType } = (req.body || {});
            if (!audioBase64)
                return res.status(400).json({ ok: false, error: "لم يتم إرسال مقطع صوتي." });
            const ai = getAiClient();
            if (!ai)
                return res.status(503).json({ ok: false, error: "مفتاح Gemini API غير مهيأ." });
            const cleanAudio = audioBase64.replace(/^data:[^;]+;base64,/, "");
            const response = await generateContentWithRetry(ai, {
                model: "gemini-3.5-transcribe",
                contents: {
                    parts: [
                        { inlineData: { mimeType: mimeType || "audio/webm", data: cleanAudio } },
                        { text: "فرّغ هذا التسجيل الصوتي باللغة العربية أو اللهجة المصرية بدقة، وأعد النص فقط دون مقدمات." },
                    ],
                },
            });
            return res.json({ ok: true, text: (response.text || "").trim() });
        }
        catch (err) {
            console.error("transcribe error:", err);
            return res.status(500).json({ ok: false, error: "تعذر تفريغ الصوت، يرجى المحاولة مرة أخرى." });
        }
    });
    // Android Studio Project Tree & File Viewer + ZIP Exporter
    app.get("/api/android-project/tree", (_req, res) => {
        const androidRoot = getAndroidRoot();
        if (!node_fs_1.default.existsSync(androidRoot)) {
            return res.status(404).json({ ok: false, error: "مجلد مشروع Android Studio غير متوفر على الخادم." });
        }
        const files = collectFilesRecursive(androidRoot, androidRoot).sort();
        return res.json({ ok: true, projectName: "SABQ_AI", packageName: "com.sabq.ai", files });
    });
    app.get("/api/android-project/file", (req, res) => {
        const androidRoot = getAndroidRoot();
        const rel = String(req.query.path || "");
        if (!rel)
            return res.status(400).json({ ok: false, error: "مسار الملف مطلوب." });
        const resolved = node_path_1.default.resolve(androidRoot, rel);
        if (!resolved.startsWith(androidRoot + node_path_1.default.sep) && resolved !== androidRoot) {
            return res.status(403).json({ ok: false, error: "مسار الملف غير مصرح به." });
        }
        if (!node_fs_1.default.existsSync(resolved)) {
            return res.status(404).json({ ok: false, error: "الملف غير موجود" });
        }
        if (resolved.endsWith(".png")) {
            return res.json({ ok: true, path: rel, binary: true, content: "صورة PNG رسمية مدمجة في الموارد" });
        }
        return res.json({ ok: true, path: rel, binary: false, content: node_fs_1.default.readFileSync(resolved, "utf8") });
    });
    app.get("/api/android-project.zip", async (_req, res) => {
        try {
            const androidRoot = getAndroidRoot();
            if (!node_fs_1.default.existsSync(androidRoot)) {
                return res.status(404).json({ ok: false, error: "ملفات مشروع Android Studio غير متوفرة للضغط حاليًا." });
            }
            const zip = new jszip_1.default();
            const files = collectFilesRecursive(androidRoot, androidRoot);
            if (files.length === 0) {
                return res.status(404).json({ ok: false, error: "لا توجد ملفات داخل مشروع SABQ_AI." });
            }
            for (const rel of files) {
                const full = node_path_1.default.join(androidRoot, rel);
                zip.file(`SABQ_AI/${rel}`, node_fs_1.default.readFileSync(full));
            }
            const buffer = await zip.generateAsync({ type: "nodebuffer", compression: "DEFLATE" });
            res.setHeader("Content-Type", "application/zip");
            res.setHeader("Content-Disposition", "attachment; filename=\"SABQ_AI_Android_Studio.zip\"; filename*=UTF-8''SABQ_AI_Android_Studio.zip");
            res.setHeader("Cache-Control", "no-store");
            return res.send(buffer);
        }
        catch (err) {
            console.error("zip error:", err);
            return res.status(500).json({ ok: false, error: "تعذر ضغط مشروع Android Studio" });
        }
    });
    // Strict JSON 404 Guard for any unmatched /api/* path (NEVER return HTML!)
    app.use("/api", (_req, res) => {
        res.status(404).json({ ok: false, error: "نقطة النهاية المطلوبة غير موجودة في واجهة API." });
    });
    // Strict JSON error middleware
    app.use((err, req, res, next) => {
        if (req.path.startsWith("/api")) {
            const status = Number(err?.status || err?.statusCode || 500);
            return res.status(status).json({
                ok: false,
                error: err?.message || "حدث خطأ غير متوقع في خادم سَبَق.",
            });
        }
        return next(err);
    });
    return app;
}
//# sourceMappingURL=sabqApp.js.map