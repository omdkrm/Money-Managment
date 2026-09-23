# راهنمای جامع خط لوله CI/CD در GitHub Actions (مدیریت سرمایه)

این پروژه مجهز به گردش‌کارهای خودکار و امن در **GitHub Actions** جهت تست مداوم (CI) و تولید ایمن نسخه‌های انتشار (Release) است.

---

## ۱. ساختار گردش‌کارها (Workflows)

| نام فایل | هدف | نحوه فعال‌سازی | خروجی‌ها و گزارش‌ها |
|---|---|---|---|
| **`.github/workflows/android-ci.yml`** | ارزیابی مداوم کد، اعتبارسنجی پکیج، تست‌های واحد و ساخت فایل تست | خودکار روی تمام `push`ها و `pull_request`ها | • گزارش تست‌های واحد<br>• گزارش Android Lint<br>• نسخه تست CI (`app-debug.apk`) |
| **`.github/workflows/android-release.yml`** | ساخت نسخه‌های رسمی امضاشده (Signed APK / AAB) | دستی از طریق **Run workflow** (`workflow_dispatch`) | • فایل رسمی `ir.modiriatsarmaye.app-release.apk`<br>• فایل رسمی `ir.modiriatsarmaye.app-release.aab` |

---

## ۲. گردش‌کار اعتبارسنجی مداوم (`android-ci.yml`)

### مراحل اصلی اجرا:
1. **بررسی سورس‌کد (Checkout)**
2. **اعتبارسنجی قطعی پکیج:**
   - بررسی `namespace = "ir.modiriatsarmaye.app"`
   - بررسی `applicationId = "ir.modiriatsarmaye.app"`
   - رد هرگونه پکیج یا شناسه ساختگی (`com.example`, `com.sample`, `com.yourapp`, `ir.sample`)
3. **اعتبارسنجی هش و یکپارچگی Gradle Wrapper**
4. **تنظیم جاوا ۲۱ (Temurin LTS)**
5. **راه‌اندازی هوشمند کش Gradle**
6. **اجرای تست‌های رگرسیون و محاسباتی:**
   - `GoldPriceSyncTest` (تست‌های همگام‌سازی و تحلیل قیمت طلا و سکه)
   - `StockPriceSyncTest` (تست‌های همگام‌سازی نمادها و قیمت‌های بورس TSETMC)
   - `FundPriceSyncTest` (تست‌های صندوق‌های سرمایه‌گذاری ETF، طلا و سهامی)
   - `SafeBackupRestoreTest` (تست‌های پیشرفته اعتبارسنجی و بازیابی فایل پشتیبان)
   - `WealthCalculationTest` (تست‌های موتور محاسبه سود/زیان، ارزش روز و رشد دارایی‌ها)
   - `JalaliDateAndCloudBackupTest` (تست‌های تقویم جلالی، سال‌های کبیسه و Google Drive)
   - `WealthAppRobolectricTest` (تست‌های محیطی رابط کاربری و پایگاه داده Room)
7. **اجرای آنالیز ایستای Android Lint**
8. **تولید فایل تست `app-debug.apk` با برچسب صریح:**
   > `CI TEST BUILD — NOT FOR STORE RELEASE`

---

## ۳. گردش‌کار ساخت نسخه نهایی انتشار (`android-release.yml`)

### الزامات و امنیت امضای رسمی:
این گردش‌کار به صورت **دستی** فعال می‌شود و به هیچ عنوان به صورت خودکار فایل‌ها را به Google Play، کافه‌بازار یا مایکت ارسال نمی‌کند.

برای ساخت موفق نسخه انتشار، ۴ مقدار محرمانه در بخش **GitHub Secrets** مخزن الزامی است:

| نام Secret در گیت‌هاب | توضیحات |
|---|---|
| `KEYSTORE_BASE64` | محتوای رمزگذاری‌شده Base64 فایل Keystore رسمی |
| `KEYSTORE_PASSWORD` | رمز عبور Keystore |
| `KEY_ALIAS` | نام مستعار کلید (Key Alias) |
| `KEY_PASSWORD` | رمز عبور اختصاصی کلید |

> ⚠️ **هشدار امنیتی اکید:** اگر مقادیر فوق تنظیم نشده باشند، فرآیند ساخت به صورت صریح و با خطای واضح متوقف می‌گردد. سیستم به هیچ وجه به `debug.keystore`، `androiddebugkey` یا امضای آزمایشی برای نسخه Release برنمی‌گردد (No Debug Fallback).

---

## ۴. راهنمای تولید و ثبت Keystore انتشار در GitHub Secrets

### الف) ساخت Keystore با دستور keytool در ترمینال سیستم خود:
```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias modiriatsarmaye_key \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

### ب) تبدیل فایل Keystore به فرمت Base64:
```bash
# در لینوکس / مک:
base64 -w 0 release.keystore > keystore_base64.txt

# در مک‌اواس (بدون -w):
base64 -i release.keystore > keystore_base64.txt
```

### ج) ثبت در مخزن گیت‌هاب:
1. در صفحه مخزن خود به مسیر زیر بروید:
   **Settings > Secrets and variables > Actions**
2. روی دکمه **New repository secret** کلیک کنید و مقادیر زیر را اضافه نمایید:
   - `KEYSTORE_BASE64`: محتوای متنی داخل فایل `keystore_base64.txt`
   - `KEYSTORE_PASSWORD`: رمز عبور انتخابی Keystore
   - `KEY_ALIAS`: عنوان انتخابی (مانند `modiriatsarmaye_key`)
   - `KEY_PASSWORD`: رمز عبور انتخابی کلید

### د) اجرای دستی ساخت Release:
1. به تب **Actions** در گیت‌هاب بروید.
2. گردش‌کار **Android Release Build** را انتخاب کنید.
3. روی **Run workflow** کلیک کرده و فرمت خروجی مورد نظر (`both` یا `apk` یا `aab`) را برگزینید.
4. پس از اتمام ساخت، خروجی‌های امضاشده به عنوان Artifact امن در صفحه اجرا قابل دانلود خواهند بود.
