# وصال (WESAL) — تطبيق مراسلة فورية أصلي لنظام Android

المشروع المصدري الكامل لتطبيق **وصال (WESAL)** بباكج `com.wesal.messenger`، مبني بلغة **Kotlin** وواجهة **Jetpack Compose (Material 3)** مع دعم كامل للغة العربية **RTL**، والحد الأدنى للإصدار **Android 8.0 (API 26)**.

> **ملاحظة شفافة مهمة:** تم إنشاء الشيفرة المصدرية الكاملة للمشروع وأيقونات الـ `mipmap` وقواعد Firebase هنا، ولم يتم تشغيل `Gradle Sync` أو بناء APK أو اختبار Firebase فعلياً لعدم توفر Android SDK / JDK / ADB داخل بيئة التوليد. يجب فتح مجلد `WesalAndroid` داخل **Android Studio** وإتمام خطوات الربط والبناء والاختبار الموضحة أدناه قبل النشر.

---

## 1. هيكل المشروع المصدري (`WesalAndroid`)

```
WesalAndroid/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── firestore.rules
├── storage.rules
├── firebase/functions/          # Cloud Functions لإشعارات FCM الفورية
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml  # android:label="وصال" + أيقونة Launcher الرسمية
        ├── res/
        │   ├── mipmap-*/        # ic_launcher, ic_launcher_round, ic_launcher_foreground
        │   ├── mipmap-anydpi-v26/ # Adaptive Launcher Icon
        │   ├── values/          # النصوص العربية، الألوان، الثيم بدون شاشة دعائية
        │   └── values-en/       # النصوص الإنجليزية (مع بقاء اسم الأيقونة «وصال»)
        └── java/com/wesal/messenger/
            ├── WesalApplication.kt
            ├── MainActivity.kt
            ├── core/            # Theme (أسود، زمردي، تركواز، أبيض)، DI، NetworkMonitor، Utils
            ├── security/        # E2EECryptoManager (ECDH P-256 + HKDF-SHA256 + AES-256-GCM)
            ├── data/            # Room Cache + DataStore + Firebase Repositories
            ├── domain/          # Models + Repository Interfaces
            ├── presentation/    # WesalNavHost + HomeScreen (5 تبويبات) + Components
            ├── authentication/  # PhoneLoginScreen (+20 افتراضياً) + OTP + ProfileSetup
            ├── chat/            # ChatsListScreen + ChatDetailScreen
            ├── contacts/        # NewChatContactsScreen (بحث بالاسم/المعرف/الهاتف)
            ├── media/           # CameraX Capture + Photo Picker + Image/ExoPlayer Viewers
            ├── voice/           # VoiceRecorderManager (M4A/AAC) + VoicePlayerManager
            ├── calls/           # WebRtcCallManager + CallActivity + IncomingCallActivity + CallHistory
            ├── groups/          # GroupsScreen + GroupInfoScreen (مشرفون، أعضاء، مغادرة)
            ├── stories/         # StoriesScreen (نصوص/صور/فيديو، 24 ساعة، مشاهدات، خصوصية)
            ├── notifications/   # WesalFirebaseMessagingService
            └── settings/        # SettingsScreen (الخصوصية، المظهر، حجم الخط، الجلسات، الحظر، الخروج)
```

---

## 2. خطوات ربط Firebase الحقيقي (إلزامية قبل التشغيل)

1. أنشئ مشروعاً في [Firebase Console](https://console.firebase.google.com/).
2. أضف تطبيق Android باسم الحزمة: `com.wesal.messenger`.
3. استخرج بصمات **SHA-1** و**SHA-256** لمفتاح التوقيع:
   ```bash
   ./gradlew signingReport
   ```
   ثم أضف البصمتين في إعدادات المشروع في Firebase Console (ضروري لعمل Phone Auth SMS وPlay Integrity).
4. نزّل ملف **`google-services.json`** وضعه مباشرة داخل المسار:
   ```
   WesalAndroid/app/google-services.json
   ```
5. من لوحة **Authentication > Sign-in method** في Firebase Console:
   - فعّل **Phone** (المصادقة برقم الهاتف).
6. أنشئ قاعدة **Cloud Firestore** وفعّل **Firebase Storage** و**Firebase Cloud Messaging**.
7. ارفع قواعد الأمان من جذر `WesalAndroid`:
   ```bash
   firebase deploy --only firestore:rules,storage
   ```
8. *(اختياري لدفع إشعارات الخلفية عند إغلاق التطبيق)* انشر الدوال السحابية:
   ```bash
   cd firebase/functions && npm install && npm run build && npm run deploy
   ```
9. فعّل **App Check** باستخدام **Play Integrity** في Firebase Console (وفي وضع Debug أضف Debug Token الظاهر في Logcat).

---

## 3. إعداد خادم TURN للمكالمات المرئية والصوتية (WebRTC)

التطبيق مهيأ بـ STUN servers افتراضياً وبنظام Signaling حقيقي عبر Firestore. للمكالمات عبر شبكات الهاتف أو NAT الصارمة، أضف بيانات خادم TURN (مثل Coturn أو Metered TURN) في `gradle.properties` أو `~/.gradle/gradle.properties`:
```properties
WESAL_TURN_URL=turn:your-turn-server.com:3478
WESAL_TURN_USERNAME=wesal_user
WESAL_TURN_CREDENTIAL=strong_password
```

---

## 4. البناء، التوقيع، واستخراج APK و AAB

1. افتح مجلد `WesalAndroid` في **Android Studio** (إصدار Ladybug / Meerkat أو أحدث).
2. نفّذ **Sync Project with Gradle Files** وانتظر تحميل الاعتماديات.
3. شغّل الاختبارات:
   ```bash
   ./gradlew testDebugUnitTest
   ```
4. بناء **Debug APK** للاختبار على هاتف فعلي:
   ```bash
   ./gradlew assembleDebug
   ```
5. إنشاء مفتاح توقيع Release آمن:
   ```bash
   keytool -genkey -v -keystore wesal-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias wesal
   ```
   ثم أضف خصائص التوقيع في `~/.gradle/gradle.properties`:
   ```properties
   WESAL_KEYSTORE_PATH=/path/to/wesal-release.jks
   WESAL_KEYSTORE_PASSWORD=your_store_password
   WESAL_KEY_ALIAS=wesal
   WESAL_KEY_PASSWORD=your_key_password
   ```
6. بناء **Release APK** و**AAB** للنشر على Google Play:
   ```bash
   ./gradlew assembleRelease bundleRelease
   ```
   وتأكد بعد تثبيت الـ APK على هاتف Android فعلي من ظهور اسم **وصال** والأيقونة الرسمية ونجاح إرسال رمز SMS الحقيقي والمحادثات والمكالمات.
