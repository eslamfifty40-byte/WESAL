# وصال (WESAL) — تطبيق مراسلة فورية أصلي لنظام Android

المشروع المصدري الكامل لتطبيق **وصال (WESAL)** بباكج `com.wesal.messenger`، مبني بلغة **Kotlin** وواجهة **Jetpack Compose (Material 3)** مع دعم كامل للغة العربية **RTL**، والحد الأدنى للإصدار **Android 8.0 (API 26)**، ومهيأ للعمل على **خطة Firebase Spark المجانية بالكامل** دون الحاجة لخطة Blaze أو رسائل SMS مدفوعة.

> **ملاحظة شفافة مهمة:** تم إعداد وتحديث الشيفرة المصدرية الكاملة للمشروع وأيقونات الـ `mipmap` وقواعد Firebase هنا، ولم يتم تشغيل `Gradle Sync` أو بناء APK أو اختبار Firebase فعلياً داخل هذه البيئة لعدم توفر Android SDK / JDK / ADB. يجب فتح مجلد `WesalAndroid` داخل **Android Studio** وإتمام خطوات الربط والبناء والاختبار الموضحة أدناه قبل النشر.

---

## 1. هيكل المشروع المصدري (`WesalAndroid`)

```
WesalAndroid/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── firestore.rules
├── storage.rules
├── firebase/functions/          # Cloud Functions لإشعارات FCM الفورية (اختيارية)
└── app/
    ├── google-services.json     # مربوط بمشروع Firebase الحالي للتطبيق
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml  # android:label="وصال" + أيقونة Launcher الرسمية + <queries> للواتساب
        ├── res/
        │   ├── mipmap-*/        # ic_launcher, ic_launcher_round, ic_launcher_foreground
        │   ├── mipmap-anydpi-v26/ # Adaptive Launcher Icon
        │   ├── values/          # النصوص العربية، الألوان، الثيم بدون شاشة دعائية
        │   └── values-en/       # النصوص الإنجليزية (مع بقاء اسم الأيقونة «وصال»)
        └── java/com/wesal/messenger/
            ├── WesalApplication.kt
            ├── MainActivity.kt
            ├── core/            # Theme (أسود، زمردي، تركواز، أبيض)، DI، NetworkMonitor، WesalIdUtils، WesalShareUtils
            ├── security/        # E2EECryptoManager (ECDH P-256 + HKDF-SHA256 + AES-256-GCM)
            ├── data/            # Room Cache + DataStore + Firebase Repositories
            ├── domain/          # Models + Repository Interfaces
            ├── presentation/    # WesalNavHost + HomeScreen (5 تبويبات) + Components + AuthState Persistence
            ├── authentication/  # تسجيل Google + البريد الإلكتروني وكلمة المرور (Spark مجاني) + إعداد Wesal ID
            ├── profile/         # ProfileScreen + UserProfileDetailScreen (قص دائري بالكاميرا/المعرض uCrop + Wesal ID + تحقق التشفير)
            ├── chat/            # ChatsListScreen + ChatDetailScreen (رسائل نصية، ملفات، موجات صوتية، موقع، علامات قراءة/وصول فعلية)
            ├── contacts/        # PhoneContactsHelper (READ_CONTACTS + SHA-256) + البحث بـ Wesal ID + اكتشاف المستخدمين + دعوة واتساب
            ├── media/           # CameraX Capture + CameraHubScreen (نشر للحالة أو إرسال لمحادثة) + AvatarCropHelper
            ├── voice/           # VoiceRecorderManager (M4A/AAC) + VoicePlayerManager + Waveform
            ├── calls/           # WebRtcCallManager (ICE buffering + STUN/TURN) + CallActivity + IncomingCallActivity + CallHistory (زر مكالمة جديدة)
            ├── groups/          # GroupsScreen + GroupInfoScreen (مشرفون، أعضاء، فتح ملف أي عضو، مغادرة)
            ├── stories/         # StoriesScreen (كاميرا، قص CropActivity، حالة صوتية، نصية، معاينة + قائمة «من شاهد الحالة»)
            ├── notifications/   # WesalFirebaseMessagingService + deep link + إزالة Token عند تسجيل الخروج
            └── settings/        # SettingsScreen (تبديل وإدارة الحسابات، الخصوصية، المظهر، الجلسات، التواصل المباشر، الخروج الحقيقي)
```

---

## 2. خطوات ربط وتفعيل Firebase Spark المجاني

1. المشروع مرتبط حالياً بملف `WesalAndroid/app/google-services.json` (لا تقم بإنشاء مشروع بديل إذا كنت تستخدم نفس مشروع Firebase).
2. استخرج بصمات **SHA-1** و**SHA-256** لمفتاح التوقيع من Android Studio أو الطرفية:
   ```bash
   ./gradlew signingReport
   ```
   ثم أضف البصمتين في **Project Settings > Your Apps (Android: `com.wesal.messenger`)** في Firebase Console — هذا ضروري لعمل **Google Sign-In (Credential Manager)** وميزة اختيار/تبديل الحسابات.
3. من لوحة **Authentication > Sign-in method** في Firebase Console (على خطة **Spark المجانية** بدون Blaze):
   - فعّل **Google** (مع التأكد من مطابقة Web Client ID في `google-services.json`).
   - فعّل **Email/Password** (لتسجيل الدخول وإنشاء الحساب واستعادة كلمة المرور).
4. تأكد من تفعيل **Cloud Firestore** و**Firebase Cloud Messaging**. *(يُنصح أيضاً بتفعيل **Firebase Storage** مجاناً لرفع الفيديو والملفات الكبيرة، بينما تدعم الصور والرسائل الصوتية القصيرة والحالات الصوتية وضع الترميز الآمن التلقائي في Firestore عند تعذر Storage على Spark)*.
5. ارفع قواعد الأمان من جذر `WesalAndroid` (تشمل قواعد `/stories/{storyId}/viewers/{viewerId}`):
   ```bash
   firebase deploy --only firestore:rules,storage
   ```
6. *(ملاحظة حول Phone Auth / الحسابات السابقة)*: التطبيق يحتفظ بدعم قراءة حالة التوثيق السابقة للحسابات الموثقة برقم هاتف، دون إلزام المستخدمين الجدد بتوثيق SMS أو الترقية إلى خطة Blaze.

---

## 3. ما يعمل على خطة Spark المجانية وما يتطلب خدمات إضافية

- **يعمل مجاناً 100% على خطة Firebase Spark**:
  - تسجيل الدخول والخروج الحقيقي + **التبديل بين حسابات متعددة** على نفس الجهاز مع عزل مفاتيح التشفير E2EE وقاعدة Room لكل UID.
  - البحث الفوري بالمعرف `Wesal ID` + اكتشاف مستخدمي وصال المسجلين + مطابقة جهات الاتصال بـ `SHA-256`.
  - الدردشات الفورية المشفرة (نص، صور، فيديو، صوت، ملفات، موقع، إعادة توجيه) + علامات الوصول والقراءة الفعلية.
  - الحالات (Stories) لمدة 24 ساعة بأنواعها الأربع: **صور (مع القص)، فيديو، صوتية، نصية** مع قائمة المشاهدين الفعلية (`stories/{storyId}/viewers`).
  - زر الكاميرا السريع في الشريط العلوي (`CameraHubScreen`) لالتقاط صورة أو فيديو وإرساله مباشرة إلى محادثة أو نشره كحالة.
  - المكالمات الصوتية والمرئية المباشرة عبر WebRTC مع زر **«مكالمة جديدة»** لاختيار أي مستخدم مباشرة من تبويب المكالمات.
- **ملاحظات اختيارية متقدمة**:
  - مجلد `firebase/functions/` (إشعارات FCM في الخلفية عند إغلاق التطبيق تماماً) يتطلب خطة **Firebase Blaze** لنشره عبر Firebase CLI؛ إذا بقيت على Spark فستعمل جميع الميزات داخل التطبيق والمزامنة الفورية بشكل طبيعي دون نشر Functions.
  - المكالمات عبر الشبكات المقيدة جداً (Carrier-grade NAT / 4G الصارم) قد تحتاج إضافة خادم **TURN** بجانب خوادم STUN المجانية المضمنة في `WebRtcCallManager.kt`.

---

## 4. نظام Wesal ID، جهات الاتصال، والدعوات

- **معرف وصال الفريد (`publicId` / Wesal ID)**: يحصل كل مستخدم تلقائياً على معرف وصال فريد عند الدخول (أو يختاره أثناء الإعداد/الملف الشخصي)، ويمكن لأي صديق البحث عنه وبدء محادثة أو مكالمة بدون رقم هاتف.
- **رقم الهاتف الاختياري**: يمكن إضافته أو إزالته من **الملف الشخصي** مع عرض واضح لحالة التحقق (`رقم موثق ✓` أو `غير موثق (اختياري)`).
- **مطابقة جهات الاتصال الآمنة (SHA-256)**: بعد موافقة المستخدم على إذن `READ_CONTACTS`، تُحوَّل أرقام الهاتف محلياً إلى بصمة SHA-256 دون رفع دفتر الهاتف بالكامل، ولا تظهر علامة **«يستخدم وصال ✓»** إلا للحسابات ذات الأرقام الموثقة فعلياً (`isPhoneVerified == true`).
- **دعوات WhatsApp**: يمكن مشاركة رابط الدعوة ومعرف وصال مباشرة عبر WhatsApp أو أي تطبيق آخر من شاشة جهات الاتصال أو الملف الشخصي.

---

## 5. البناء، التوقيع، وقائمة الاختبار على جهازين حقيقيين

1. افتح مجلد `WesalAndroid` في **Android Studio** (إصدار Ladybug / Meerkat أو أحدث).
2. نفّذ **Sync Project with Gradle Files** وانتظر تحميل الاعتماديات (`androidx.credentials`, `googleid`, Firebase BOM, Jetpack Compose, WebRTC, CameraX, uCrop).
3. شغّل اختبارات الوحدة:
   ```bash
   ./gradlew testDebugUnitTest
   ```
4. بناء **Debug APK** للاختبار على هاتف فعلي:
   ```bash
   ./gradlew assembleDebug
   ```
5. إنشاء مفتاح توقيع Release آمن وبناء النسخة النهائية:
   ```bash
   keytool -genkey -v -keystore wesal-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias wesal
   ./gradlew assembleRelease bundleRelease
   ```
6. **قائمة التحقق بعد التثبيت على جهازين حقيقيين**:
   - تسجيل الدخول بـ Google أو Email على الجهازين، وتجربة **تبديل الحساب وإضافة حساب جديد** من الإعدادات دون فقدان الحسابات المحفوظة أو تعارض مفاتيح التشفير.
   - فتح قائمة الأصدقاء والتحقق من ظهور الحسابات المسجلة، والضغط على أيقونة الدردشة أو الاتصال أو الفيديو.
   - إرسال رسائل وصور ومقاطع صوتية وموقع وإعادة توجيهها، والتأكد من علامات `✓` ثم `✓✓` ثم `✓✓ زرقاء`، وحتى بعد حذف المحادثة من طرف واحد وإرسال رسالة جديدة.
   - التقاط صورة من أيقونة الكاميرا العلوية وإرسالها لمحادثة أو نشرها كحالة، بالإضافة إلى نشر حالة صوتية وصورة مقصوصة ومشاهدتها من الجهاز الثاني ثم فحص **«من شاهد الحالة»**.
   - بدء مكالمة صوتية ومكالمة فيديو من تبويب المكالمات عبر زر **«مكالمة جديدة»** والتحقق من رنين وإشعار الشاشة المغلقة.
   - تغيير صورة الملف الشخصي من الكاميرا ومن المعرض مع القص الدائري، وفتح رابط التواصل المباشر مع المطور في الإعدادات.
