# پروژه جایگذاری و مهاجرت میکروسرویس‌ها در محیط صنعتی متحرک با iFogSim2 و PPO

این مخزن پیاده‌سازی پروژه آزمایشگاه اینترنت اشیاء (Industry 4.0) است: شبیه‌سازی سناریوی صنعتی متحرک در **iFogSim2 (جاوا)**، تصمیم‌گیری جایگذاری/مهاجرت با عامل‌های **ابتکاری، ژنتیک و یادگیری تقویتی عمیق (PPO)** در **پایتون**، و اتصال پایدار دو سمت از طریق **پل ارتباطی TCP/JSON (Bridge)**.

> این سند شرح مسئله، روش پیشنهادی، معماری، جزئیات Bridge، عامل‌ها، جریان آموزش/مقایسه و ساختار کد را پوشش می‌دهد.  
> **نتایج عددی آزمایش‌ها و تحلیل کمی نمودارها در این README درج نشده‌اند** (برای گزارش جداگانه نگه‌داری می‌شوند).

---

## فهرست مطالب

1. [شرح مسئله](#۱-شرح-مسئله)
2. [اهداف و خروجی‌های مورد انتظار پروژه](#۲-اهداف-و-خروجیهای-مورد-انتظار-پروژه)
3. [روش پیشنهادی و مفاهیم کاری](#۳-روش-پیشنهادی-و-مفاهیم-کاری)
4. [معماری کلی سامانه](#۴-معماری-کلی-سامانه)
5. [سناریوی شبیه‌سازی (جاوا)](#۵-سناریوی-شبیهسازی-جاوا)
6. [پل ارتباطی Java ↔ Python (Bridge)](#۶-پل-ارتباطی-java--python-bridge)
7. [عامل‌های هوشمند (پایتون)](#۷-عاملهای-هوشمند-پایتون)
8. [آموزش Shared PPO](#۸-آموزش-shared-ppo)
9. [چارچوب مقایسه عامل‌ها](#۹-چارچوب-مقایسه-عاملها)
10. [ساختار پوشه‌ها و اسکریپت‌ها](#۱۰-ساختار-پوشهها-و-اسکریپتها)
11. [نحوه اجرا](#۱۱-نحوه-اجرا)
12. [متریک‌های ارزیابی (بدون عدد)](#۱۲-متریکهای-ارزیابی-بدون-عدد)
13. [نکات پیاده‌سازی و محدودیت‌ها](#۱۳-نکات-پیادهسازی-و-محدودیتها)

---

## ۱. شرح مسئله

### ۱.۱ زمینه

در محیط‌های **Industry 4.0**، حسگرها و ربات‌های متحرک داده تولید می‌کنند و پردازش آن‌ها باید بین **لبه (Fog)** و **ابر (Cloud)** توزیع شود. برنامه‌ها به‌صورت **میکروسرویس** شکسته می‌شوند تا بتوان آن‌ها را روی گره‌های مختلف قرار داد و در صورت نیاز **مهاجرت (Migration)** داد.

چالش اصلی این است که:

- توپولوژی و بار شبکه **پویا** است (حرکت دستگاه‌ها، تغییر گیت‌وی والد).
- تسک‌ها دو دسته هستند: **دوره‌ای** و **بحرانی با مهلت زمانی (Deadline)**.
- تصمیم‌گیری باید هم‌زمان به **تأخیر انتهابه‌انتها**، **مصرف انرژی**، **محلیت (Locality)** و **قابلیت‌اجرا روی مهلت بحرانی** توجه کند.
- شبیه‌ساز iFogSim2 به زبان **جاوا** است، در حالی که مدل‌های DRL معمولاً در **پایتون** توسعه می‌یابند؛ بنابراین نیاز به **Bridge پایدار** است.

### ۱.۲ صورت مسئله پروژه (مطابق مشخصات)

طبق فایل مشخصات پروژه (`IoTLab-Project8.pdf`):

| بخش | شرح |
|-----|-----|
| عنوان | زمانبندی و جایگذاری میکروسرویس‌ها در محیط‌های صنعتی متحرک با استفاده از iFogSim2 |
| Fog | حدود ۵ تا ۱۵ گره مه (مثلاً Gateway صنعتی) + یک سرور ابری |
| میکروسرویس‌ها | ۱) پیش‌پردازش/فیلتر داده ۲) تحلیل هوشمند ۳) کنترل عملگر |
| IoT | حدود ۱۰۰ تا ۲۰۰ دستگاه (سنسور ثابت + ربات متحرک) |
| تحرک | Random Waypoint یا مسیر از پیش‌تعیین‌شده؛ تغییر پوشش بین گره‌های مه |
| تسک‌ها | دوره‌ای (پایش) و بحرانی (اخطار/توقف اضطراری با اولویت بالاتر) |

سه مکانیزم تصمیم‌گیری باید پیاده و مقایسه شوند:

1. **عامل ابتکاری (Heuristic)** به‌عنوان خط پایه  
2. **الگوریتم ژنتیک (Genetic Algorithm)** برای بهینه‌سازی چیدمان اولیه  
3. **یادگیری تقویتی عمیق (ترجیحاً PPO)** برای تصمیم برخط جایگذاری/مهاجرت و Offload

### ۱.۳ مسئله تصمیم‌گیری به زبان فنی

در هر لحظه شبیه‌سازی باید مشخص شود:

- هر میکروسرویس روی **کدام دستگاه** (کلاینت / Fog Gateway / Cloud) اجرا شود؛
- آیا نیاز به **مهاجرت** از دستگاه فعلی به دستگاه دیگر هست؛
- تسک‌های تولیدی به کجا **Offload** شوند (در عمل، اجرای تسک روی گره‌ای که میکروسرویس مربوطه روی آن قرار گرفته است).

تصمیم‌ها باید در حضور **حرکت**، **محدودیت ظرفیت (MIPS/RAM)**، و **مهلت تسک‌های بحرانی** گرفته شوند.

---

## ۲. اهداف و خروجی‌های مورد انتظار پروژه

1. مدل‌سازی سناریوی صنعتی متحرک در iFogSim2.  
2. طراحی Bridge جاوا↔پایتون برای تبادل State/Action به‌صورت برخط.  
3. پیاده‌سازی سه خانواده عامل: ابتکاری، ژنتیک، PPO.  
4. امکان آموزش PPO و مقایسه عادلانه روی seedهای یکسان.  
5. تولید نمودارهای مقایسه‌ای مورد نیاز گزارش (میانگین تأخیر، انرژی، تعداد مهاجرت سرویس، نرخ موفقیت تسک بحرانی، همگرایی پاداش).

این README روی **چگونگی کارکرد کد و مفاهیم** تمرکز دارد؛ نمودارها و اعداد در پوشه `agents/results/` تولید می‌شوند و باید در گزارش جداگانه تحلیل شوند.

---

## ۳. روش پیشنهادی و مفاهیم کاری

### ۳.۱ جدایی «جایگذاری اولیه» و «مهاجرت برخط»

در پیاده‌سازی فعلی مسیر اصلی (`shared policy`)، دو فاز متمایز وجود دارد:

| فاز | زمان | نقش |
|-----|------|-----|
| **جایگذاری اولیه (Initial Placement)** | گام صفر شبیه‌سازی | تعیین محل اولیه میکروسرویس‌های در انتظار |
| **مهاجرت برخط (Online Migration)** | گام‌های بعدی با فاصله `placement.interval` | جابه‌جایی سرویس‌های از قبل قرارگرفته بر اساس وضعیت فعلی |

این تفکیک امکان مقایسه ترکیبات مختلف را می‌دهد؛ مثلاً:

- ژنتیک فقط برای init + بدون مهاجرت  
- ژنتیک برای init + Heuristic برای مهاجرت  
- ژنتیک برای init + PPO برای مهاجرت  
- Heuristic-v2 برای init + PPO برای مهاجرت  

### ۳.۲ سیاست اشتراکی در سطح سرویس (Shared Service-Level Policy)

به‌جای یک بردار اکشن خیلی بزرگ برای همه ماژول‌ها، در گام‌های مهاجرت هر **بازیگر سرویس (Service Actor)** به‌صورت جداگانه تصمیم می‌گیرد. هر actor معمولاً به شکل `requestId:moduleName` شناخته می‌شود و لیست **کاندیدهای دستگاه** را با پرچم `feasible` می‌بیند.

مزایا:

- اندازه اکشن متغیر و قابل‌ماسک شدن است؛
- پاداش می‌تواند **به‌ازای همان درخواست/سرویس** شکل بگیرد؛
- می‌توان در هر گام فقط زیرمجموعه‌ای از actorها را (با round-robin) به پایتون فرستاد (`max.actors.per.step`).

### ۳.۳ Offload در این مدل

در این پروژه، Offload ابری به‌معنای «اجرای تسک بدون میکروسرویس» نیست. اگر پردازش باید در ابر انجام شود، میکروسرویس مربوطه باید آنجا **قرار داده یا مهاجرت داده** شده باشد. بنابراین تصمیم PPO/Heuristic در عمل همان **Placement/Migration میکروسرویس** است.

### ۳.۴ جریان مفهومی یک اپیزود آموزش/مقایسه

```text
[اسکریپت train/compare]
   ├─ سرور پایتون روی پورت TCP گوش می‌دهد
   └─ برای هر seed: جاوا IndustrialIoTSimulationTrain را اجرا می‌کند
         │
         ├─ t≈0 : shared_initial  → جایگذاری اولیه (GA / Heuristic / Heuristic-v2 / …)
         │
         ├─ هر PLACEMENT_INTERVAL :
         │     shared_step → لیست actors + پاداش گام قبل
         │                 ← actions (مهاجرت یا ماندن)
         │
         └─ پایان شبیه‌سازی: type=results → ذخیره JSON اپیزود (+ به‌روزرسانی PPO در حالت آموزش)
```

---

## ۴. معماری کلی سامانه

```text
┌──────────────────────────────────────────────┐
│                 Python (agents/)             │
│  servers.train / servers.compare / scenario  │
│  HeuristicAgent / GeneticAgent / SharedPPO   │
│  PPO update, checkpoint, plots               │
└──────────────────────▲───────────────────────┘
                       │ TCP، یک خط JSON در هر تبادل
                       │ (اتصال تازه برای هر پیام)
┌──────────────────────▼───────────────────────┐
│              Java (simulator/)               │
│  IndustrialIoTSimulationTrain                │
│  TrainingMobilityController                  │
│  SharedPolicyPPOBridgePlacementLogic         │
│  iFogSim2 / CloudSim هسته رویدادی            │
└──────────────────────────────────────────────┘
```

| لایه | مسیر اصلی | مسئولیت |
|------|-----------|---------|
| شبیه‌ساز | `simulator/src/...` | توپولوژی، تحرک، انرژی، حلقه‌های App، تسک بحرانی |
| Bridge | `.../placement/*Bridge*PlacementLogic.java` | ساخت State، اعمال Action، مهاجرت |
| عامل‌ها | `agents/agents/` | سیاست جایگذاری/مهاجرت |
| سرورها | `agents/servers/` | گوش‌دادن TCP و مسیریابی پیام‌ها |
| اجرا | `scripts/train.sh`, `scripts/compare.sh` | کامپایل، راه‌اندازی سرور، حلقه seed |

شناسه‌های منطق جایگذاری در `PlacementLogicFactory`:

| ثابت | مقدار | کلاس |
|------|-------|------|
| `PYTHON_BRIDGE_PLACEMENT` | 4 | `PythonBridgePlacementLogic` (یک‌باره، مسیر قدیمی) |
| `PPO_BRIDGE_PLACEMENT` | 5 | `PPOBridgePlacementLogic` (گام‌به‌گام قدیمی) |
| `SHARED_PPO_BRIDGE_PLACEMENT` | 6 | `SharedPolicyPPOBridgePlacementLogic` (**مسیر اصلی train/compare**) |

---

## ۵. سناریوی شبیه‌سازی (جاوا)

### ۵.۱ نقطه ورود

کلاس اصلی آموزش و مقایسه:

`simulator/src/org/fog/test/perfeval/IndustrialIoTSimulationTrain.java`

- آرگومان خط فرمان: **شماره seed اپیزود**  
- با `-Difogsim.shared.policy=true` حالت سیاست اشتراکی فعال می‌شود.

### ۵.۲ توپولوژی سلسله‌مراتبی

```text
سطح 0: cloud
سطح 1: FogGW-0 … FogGW-(N-1)     (FCN / gateway صنعتی)
سطح 2: MobileRobot-* / FixedSensor-*   (CLIENT / IoT)
```

ثابت‌های مهم تأخیر لینک (نمونه‌ای از کد):

- `IOT_TO_GW_LATENCY = 20` ms  
- `GW_TO_CLOUD_LATENCY = 100` ms  

در حالت shared-policy، اندازه توپولوژی با seed تصادفی‌سازی می‌شود (مثلاً ۱۵–۱۶ gateway و حدود ۱۰ کلاینت به‌ازای هر gateway). ظرفیت MIPS دروازه نیز متناسب با تعداد کلاینت تنظیم می‌شود تا میزبانی محلی از نظر ساختاری ممکن باشد.

### ۵.۳ میکروسرویس‌ها و یال‌های برنامه

برنامه با شناسه `industrial_iot` شامل:

| ماژول | نقش تقریبی |
|-------|------------|
| `data_preprocessor` | پیش‌پردازش روی کلاینت (در init معمولاً از قبل روی همان دستگاه کلاینت قرار می‌گیرد) |
| `smart_analyzer` | تحلیل هوشمند |
| `actuator_controller` | کنترل عملگر |

جریان داده (خلاصه): سنسور → preprocessor → analyzer → actuator_controller → عملگر.

دو **AppLoop** برای سنجش تأخیر انتهابه‌انتها تعریف می‌شود: حلقه عادی و حلقه بحرانی.

### ۵.۴ تحرک (Mobility)

کنترلر: `TrainingMobilityController`

- برای ربات‌های متحرک، ردپای حرکت seeded ساخته می‌شود (نمونه‌برداری دوره‌ای، محدوده سرعت، شبکه فاصله gateway).  
- تغییر والد (parent) کلاینت منجر به رویدادهای مدیریت تحرک و در صورت نیاز مهاجرت نسبی ماژول‌ها می‌شود.  
- این پویایی، نیاز به مهاجرت برخط را توجیه می‌کند.

### ۵.۵ تسک‌های دوره‌ای و بحرانی

هر کلاینت معمولاً:

- سنسور دوره‌ای با توزیع نمایی seeded؛  
- سنسور بحرانی با الگوی **incident** (پس‌زمینه نادر + بازه‌های هشدار متراکم‌تر)؛  
- مهلت بحرانی به‌صورت seeded در بازه مشخصی از میلی‌ثانیه نمونه‌برداری می‌شود.

در پایان اپیزود، شمارنده‌هایی مانند تعداد تسک بحرانی، on-time / missed / pending و نرخ موفقیت در JSON نتایج قرار می‌گیرد.

### ۵.۶ ویژگی‌های سیستمی مهم (`-D`)

| ویژگی | نقش |
|--------|-----|
| `ifogsim.shared.policy` | فعال‌سازی SharedPolicy bridge |
| `ifogsim.episode.seed` | seed اپیزود |
| `ifogsim.bridge.host` / `ifogsim.bridge.port` | آدرس سرور پایتون |
| `ifogsim.simulation.time` | طول شبیه‌سازی |
| `ifogsim.placement.interval` | فاصله تیک مهاجرت |
| `ifogsim.max.actors.per.step` | سقف actor در هر `shared_step` |
| `ifogsim.results.timeout.ms` | مهلت ارسال نتایج به پایتون |
| `ifogsim.log.summary` / `decisions` / `diagnostics` | سطح لاگ |

---

## ۶. پل ارتباطی Java ↔ Python (Bridge)

### ۶.۱ اصول مشترک

- پروتکل: **TCP**، پیام‌ها **یک خط JSON** (newline-terminated).  
- برای هر تبادل معمولاً یک **اتصال تازه** باز می‌شود تا چند نمونه FON بتوانند با یک سرور پایتون کار کنند.  
- جاوا State می‌سازد و Action را اعمال می‌کند؛ پایتون سیاست را محاسبه می‌کند.

سه لایه Bridge در پروژه وجود دارد؛ مسیر عملیاتی فعلی train/compare لایه سوم است.

---

### ۶.۲ `PythonBridgePlacementLogic` (جایگذاری یک‌باره)

**مسیر فایل:** `simulator/src/org/fog/placement/PythonBridgePlacementLogic.java`

کاربرد تاریخی/سناریوهای قدیمی: یک دور جایگذاری در زمان شروع.

**State (جاوا → پایتون):**

```json
{
  "step": 0,
  "devices": [
    {"id": 1, "name": "...", "level": 1, "parentId": 0,
     "availableMips": ..., "availableRam": ..., "currentLoad": ...}
  ],
  "requests": [
    {
      "requestId": 101,
      "appId": "industrial_iot",
      "gatewayDeviceId": ...,
      "alreadyPlaced": {"data_preprocessor": 12},
      "pendingModules": [
        {"name": "smart_analyzer", "requiredMips": ..., "requiredRam": ...}
      ]
    }
  ],
  "allModules": ["data_preprocessor", "smart_analyzer", "actuator_controller"]
}
```

**Action (پایتون → جاوا):**

```json
{
  "placement": {
    "101": {"smart_analyzer": 5, "actuator_controller": 5}
  }
}
```

---

### ۶.۳ `PPOBridgePlacementLogic` (حلقه گام‌به‌گام قدیمی)

**مسیر فایل:** `simulator/src/org/fog/placement/PPOBridgePlacementLogic.java`

در حالت PERIODIC، هر `PLACEMENT_INTERVAL` یک پیام `"type":"step"` با فیلدهای `step`, `simTime`, `done`, `reward`, `devices`, `modules` ارسال می‌شود و پاسخ شامل آرایه‌های `placements` و `migrations` است.

پاداش میراثی تقریباً به‌صورت منفی ترکیب انرژی و تأخیر تعریف شده است. این مسیر بیشتر برای سازگاری با سناریوهای قدیمی است؛ مسیر اصلی پروژه shared policy است.

---

### ۶.۴ `SharedPolicyPPOBridgePlacementLogic` (مسیر اصلی)

**مسیر فایل:** `simulator/src/org/fog/placement/SharedPolicyPPOBridgePlacementLogic.java`  
**ارث‌بری از:** `PPOBridgePlacementLogic` (برای ابزار مهاجرت/لاگ و سوکت)

#### گام صفر — `shared_initial`

هدف: استفاده از همان اسکیمای جایگذاری که Genetic/Heuristic می‌فهمند.

جاوا می‌فرستد:

- `type`: `"shared_initial"`
- `episodeSeed`, `step: 0`
- `devices` (فرمت legacy با `availableMips` / `availableRam` / `currentLoad`)
- `requests` و `allModules` مشابه Bridge یک‌باره

پایتون برمی‌گرداند:

```json
{
  "placements": [
    {"requestId": 101, "module": "smart_analyzer", "deviceId": 5}
  ],
  "migrations": []
}
```

#### گام‌های بعدی — `shared_step`

جاوا برای زیرمجموعه‌ای از سرویس‌ها actor می‌سازد. نمونه فیلدهای هر actor:

| فیلد | مفهوم |
|------|--------|
| `actorId` | رشته `"requestId:module"` |
| `currentDeviceId` / `currentLevel` | محل فعلی |
| `currentLatency` | برآورد تأخیر فعلی درخواست |
| `homeGatewayId` | گیت‌وی مه خانه (والد gateway کلاینت) |
| `mobileClient` | آیا کلاینت متحرک است |
| `reward` | شیء پاداش گام قبل برای همین actor |
| `candidates[]` | گزینه‌های دستگاه با ویژگی‌ها و `feasible` |

نمونه پاسخ پایتون:

```json
{
  "actions": [
    {"actorId": "101:smart_analyzer", "toDeviceId": 7}
  ]
}
```

اگر `toDeviceId` برابر دستگاه فعلی باشد، عملاً **ماندن** است؛ در غیر این صورت مهاجرت (در صورت ظرفیت کافی) اعمال می‌شود. نتیجه (`migrated` / `rejected` / `no_op`) در گام بعد روی پاداش اثر می‌گذارد.

در پایان اپیزود، اگر شبیه‌سازی تمام نشده باشد، همان PlacementRequestها دوباره با تأخیر `PLACEMENT_INTERVAL` به صف ارسال می‌شوند تا تیک بعدی رخ دهد.

#### پایان اپیزود — `results`

`IndustrialIoTSimulationTrain.sendResultsToPython` یک JSON با `"type":"results"` می‌فرستد شامل انرژی، هزینه ابر، تأخیرها، آمار تسک بحرانی، `meanLocalReward`، لاگ placements/migrations و غیره. سرور پایتون آن را ذخیره می‌کند و در حالت آموزش PPO را به‌روز می‌کند، سپس یک خط تأیید برمی‌گرداند.

---

### ۶.۵ تابع پاداش مثبت در Shared Policy

متد `buildPositiveReward` در `SharedPolicyPPOBridgePlacementLogic` پاداش را **مثبت و شکل‌دهی‌شده** می‌سازد (نه فقط منفی خام). اجزا:

| مؤلفه | ایده |
|--------|------|
| `latencyScore` | هرچه تأخیر کمتر، امتیاز بالاتر: \(1/(1 + L/500)\) |
| `energyScore` | حساس به دلتای انرژی نسبی ماژول روی میزبان |
| `deadlineScore` | نزدیکی به مهلت بحرانی میانگین |
| `localityScore` | ابر = ۰؛ home fog/کلاینت = ۱؛ سایر fog ≈ ۰٫۶۵ |
| `actionPenalty` | جریمه رد مهاجرت یا خود مهاجرت |

ترکیب وزن‌دار تقریبی:

\[
R = 0.30\,L + 0.20\,E + 0.30\,D + 0.20\,Loc - Penalty
\]

این \(R\) داخل فیلد `reward.total` به پایتون می‌رسد و Shared PPO برای هدف TD از آن استفاده می‌کند.

---

## ۷. عامل‌های هوشمند (پایتون)

پکیج: `agents/agents/`

### ۷.۱ پایه مشترک — `base_agent.py`

کلاس انتزاعی `BasePlacementAgent`:

- `candidate_devices(state)`: دستگاه‌های واجد شرایط با `level < 2` (معمولاً cloud + fog؛ نه کلاینت به‌عنوان میزبان عادی پردازش سنگین، مگر منطق جدا).  
- `available_mips(device)`  
- `build_action(placement_map)` → `{"placement": {...}}`

---

### ۷.۲ عامل ابتکاری — `heuristic.py`

#### حالت خوب (`bad_placement=False`)

برای هر ماژول در انتظار:

1. شناسایی **home fog** از روی `gatewayDeviceId` درخواست و `parentId` آن؛  
2. مرتب‌سازی کاندیدها با اولویت: home → غیرابر → سطح بالاتر → بیشترین MIPS آزاد؛  
3. انتخاب اولین دستگاه با ظرفیت کافی MIPS/RAM.

این سیاست خط پایه قوی برای محلیت و تأخیر است.

#### حالت Heuristic-v2 (`bad_placement=True`)

جایگذاری اولیه عمداً ضعیف است تا سیاست مهاجرت چیزی برای «تعمیر» داشته باشد:

- ترجیح **ابر**؛  
- اجتناب از home fog؛  
- بسته‌بندی روی دستگاه‌های شلوغ‌تر.

**مهاجرت برخط** (`decide_step`) همیشه با منطق «خوب» انجام می‌شود (ترجیح home gateway، سپس غیرابر/لبه، سپس MIPS آزاد) و با سقف `max_migrations` در هر گام محدود می‌شود. خروجی:

```json
{"actions": [{"actorId": "...", "toDeviceId": 123}]}
```

---

### ۷.۳ الگوریتم ژنتیک — `genetic.py`

هدف: بهینه‌سازی **سراسری چیدمان اولیه** با کمینه‌کردن هزینه ترکیبی تأخیر و انرژی.

#### کدگذاری

- یک **کروموزوم**: لیست اندیس دستگاه در میان کاندیدها، به‌ازای هر جفت `(request, pendingModule)`.

#### برازندگی (Fitness)

تقریباً:

\[
fitness = w_L \cdot latency\_cost + w_E \cdot energy\_cost + infeasibility
\]

با وزن‌های نمونه `W_LATENCY=0.6`, `W_ENERGY=0.4`.  
تأخیر با سطح سلسله‌مراتب و حساسیت ماژول (`LATENCY_WEIGHTS`) تقریب زده می‌شود؛ انرژی با تمرکز بار نسبی MIPS مرتبط است.

#### پارامترهای نمونه GA

| پارامتر | مقدار نمونه در کد |
|---------|-------------------|
| `POP_SIZE` | 50 |
| `MAX_GENERATIONS` | 100 |
| `MUTATION_RATE` | 0.1 |
| `ELITE_FRAC` | 0.1 |
| `TOURNAMENT_K` | 5 |

خروجی همان قالب `placement` است که Bridge یک‌باره می‌فهمد. در مسیر shared، سرور compare/train آن را به آرایه `placements` تخت تبدیل می‌کند.

GA در این پروژه عمدتاً برای **init** استفاده می‌شود؛ مهاجرت بعدی یا خالی است، یا Heuristic، یا PPO.

---

### ۷.۴ Shared PPO — `shared_ppo.py`

این عامل اصلی DRL مسیر train/compare است.

#### اجزا

- شبکه Actor-Critic روی بردار ویژگی هر کاندید (`FEATURE_DIM=20`)  
- توزیع Categorical با ماسک `feasible`  
- بهینه‌ساز Adam، به‌روزرسانی PPO با clipping  
- ذخیره `model.pth` و تاریخچه `convergence.json`

#### `placement_init`

| مقدار | جایگذاری اولیه |
|--------|----------------|
| `genetic` | پیش‌فرض آموزش GA+PPO |
| `heuristic` | Heuristic خوب |
| `bad_heuristic` | Heuristic-v2 (ضعیف) |

#### `decide_initial`

seed کردن RNG با `episodeSeed`، اجرای GA/Heuristic، برگرداندن `placements`.

#### `decide_step`

برای هر actor:

1. ساخت تنسور ویژگی‌ها برای همه کاندیدها؛  
2. ماسک غیرفعال برای گزینه‌های غیرممکن؛  
3. اگر سقف مهاجرت گام پر شده باشد، فقط ماندن روی دستگاه فعلی مجاز است؛  
4. در آموزش: نمونه‌گیری از سیاست؛ در استنتاج: `argmax`؛  
5. رزرو موقت CPU داخل همان batch برای جلوگیری از oversubscribe.

نمونه ابعاد ویژگی (خلاصه مفهومی): نوع ماژول، نیاز MIPS/RAM نرمال‌شده، متحرک بودن کلاینت، سطح فعلی/کاندید، پرچم home/current/peer، utilization، تأخیر تخمینی و دلتای آن، انرژی، فاصله تا کلاینت، feasibility.

#### یادگیری

پاداش از جاوا (`reward.total`) خوانده می‌شود؛ انتقال‌ها با \(\gamma\) و value شبکه هدف TD می‌سازند؛ سپس چند اپوک PPO روی minibatchها اجرا می‌شود.

هایپرپارامترهای نمونه: `GAMMA=0.98`, `CLIP_EPS=0.2`, `ENTROPY_COEF=0.01`, `LEARNING_RATE=3e-4`, `PPO_EPOCHS=6`.

---

### ۷.۵ PPO قدیمی سناریو — `ppo.py`

برای `servers/scenario.py` و پروتکل `"type":"step"` قدیمی. با Shared PPO یکی نیست و مسیر اصلی آموزش فعلی نیست، ولی برای سازگاری سناریوهای قبلی نگه داشته شده است.

---

## ۸. آموزش Shared PPO

### ۸.۱ سرور — `agents/servers/train.py`

- پیام‌های `shared_initial` / `shared_step` / `results` را به `SharedPPOAgent` می‌سپارد.  
- فلگ `--inference` آموزش را خاموش می‌کند.  
- `--placement-init` نوع init را تعیین می‌کند.  
- `--max-migrations` سقف مهاجرت در هر گام را به عامل می‌دهد.

### ۸.۲ لانچر — `scripts/train.sh` (و `./train.sh` در ریشه)

جریان:

1. ساخت پوشه نتایج روزانه: `agents/results/<تاریخ>/single/<run-name>/`  
2. مدیریت `model.pth` (ادامه از همان run، یا seed از آخرین مدل، یا `--reset-training`)  
3. کامپایل اختیاری جاوا  
4. اجرای سرور پایتون در پس‌زمینه  
5. حلقه اپیزود: `IndustrialIoTSimulationTrain <seed>` با `-Difogsim.shared.policy=true`

فلگ‌های مهم:

| فلگ | مفهوم |
|-----|--------|
| `--episodes` / `--start-seed` | تعداد و شروع seed |
| `--run-name` | نام پوشه run |
| `--placement-init` | `genetic` / `heuristic` / `bad_heuristic` |
| `--max-migrations` | سقف مهاجرت PPO در هر گام |
| `--placement-interval` | فاصله تیک جاوا |
| `--simulation-time` | طول شبیه‌سازی |
| `--reset-training` | پاک کردن مدل و convergence |
| `--skip-compile` | رد شدن از javac |

مثال مفهومی آموزش GA + PPO از صفر:

```bash
bash train.sh --episodes 100 --start-seed 1 --run-name shared_ppo_scratch \
  --reset-training --placement-init genetic \
  --max-migrations 4 --placement-interval 5 --skip-compile
```

مثال آموزش با init از نوع Heuristic-v2:

```bash
bash train.sh --episodes 100 --start-seed 1 --run-name shared_ppo_heur_v2 \
  --reset-training --placement-init bad_heuristic \
  --max-migrations 4 --placement-interval 5 --skip-compile
```

---

## ۹. چارچوب مقایسه عامل‌ها

### ۹.۱ سرور — `agents/servers/compare.py`

یک عامل در هر بار اجرا (`--agent`) انتخاب می‌شود تا مقایسه روی seedهای یکسان **عادلانه** باشد (هر عامل جداگانه همان اپیزودهای جاوا را می‌بیند).

#### جدول ترکیب Placement + Migration

| شناسه عامل | جایگذاری اولیه | مهاجرت برخط |
|------------|----------------|-------------|
| `heuristic` | Heuristic خوب | ندارد (`actions=[]`) |
| `genetic` | Genetic | ندارد |
| `genetic_heuristic` | Genetic | Heuristic خوب |
| `ppo` | Genetic (از طریق SharedPPO) | SharedPPO |
| `heuristic_heuristic` | Heuristic خوب | Heuristic خوب |
| `heuristic_ppo` | Heuristic خوب | SharedPPO |
| `heur_v2_heuristic` | Heuristic-v2 | Heuristic خوب |
| `heur_v2_ppo` | Heuristic-v2 | SharedPPO |

این جدول همان «روش‌های دیگر مقایسه» است که در گزارش می‌توان توضیح داد: تفاوت فقط در سیاست است، نه در seed یا طول شبیه‌سازی (در صورت ثابت بودن فلگ‌های لانچر).

### ۹.۲ لانچر — `scripts/compare.sh`

- برای هر عامل در `--agents` سرور compare را بالا می‌آورد؛  
- همان بازه seed را اجرا می‌کند؛  
- در پایان `python -m plots.comparison` نمودار مقایسه‌ای می‌سازد.

پیش‌فرضها با train کمی فرق دارند (مثلاً پورت `5556`، `placement-interval` پیش‌فرض ۵، `max-migrations` پیش‌فرض ۴) تا استنتاج مهاجرت فعال‌تر و مستقل از سرور آموزش باشد.

`--model` مسیر `model.pth` را برای عامل‌های دارای PPO مشخص می‌کند؛ در غیر این صورت آخرین مدل مناسب از نتایج جستجو می‌شود.

### ۹.۳ رسم نمودار — `agents/plots/`

| ماژول | کاربرد |
|--------|--------|
| `plots.comparison` | مقایسه چند عامل روی seedها |
| `plots.training` | منحنی همگرایی از `convergence.json` |
| `plots.episodes` | متریک‌های اپیزودی یک run تکی |

برچسب‌های نمایشی نمونه: `GA + PPO`, `GA + Heuristic`, `Heuristic-v2 + PPO`, …

### ۹.۴ مفهوم مقایسه عادلانه

برای مقایسه معتبر باید ثابت بمانند:

- بازه seed (`start-seed` و تعداد اپیزود)؛  
- `simulation.time` و `placement.interval`؛  
- توپولوژی/تصادفی‌سازی ناشی از همان seed در جاوا؛  
- مدل PPO (اگر طرف مقایسه مهاجرت PPO است) بدون retraining وسط مقایسه.

تفاوت مجاز و هدفمند: فقط منطق Placement/Migration عامل.

---

## ۱۰. ساختار پوشه‌ها و اسکریپت‌ها

```text
iFogSim_PPO/
├── IoTLab-Project8.pdf          # مشخصات پروژه
├── train.sh / compare.sh / run.sh
├── scripts/
│   ├── train.sh
│   ├── compare.sh
│   ├── run.sh
│   └── train_windows.ps1
├── simulator/                   # iFogSim2 + سناریو و Bridge
│   ├── src/org/fog/...
│   └── jars/
└── agents/
    ├── agents/                  # heuristic, genetic, shared_ppo, ppo, base
    ├── servers/                 # train, compare, scenario
    ├── plots/
    ├── utils/                   # results_paths, plotting
    └── results/
        └── <YYYY-MM-DD>/
            ├── single/<run_name>/
            │     model.pth, convergence.json, episode_*.json, …
            └── compare/<run_name>/
                  ├── plots/agent_comparison.png
                  └── <agent_id>/episode_*.json
```

راهنمای مسیر نتایج در `agents/utils/results_paths.py` است (`make_run_dir`, `latest_model_path`, …).

---

## ۱۱. نحوه اجرا

### پیش‌نیاز

- JDK + `javac`/`java`  
- پایتون ۳ با وابستگی‌های `agents/` (ترجیحاً venv داخل `agents/venv`)  
- PyTorch برای Shared PPO  

### آموزش

از ریشه مخزن:

```bash
bash train.sh --help   # در صورت پشتیبانی؛ یا مطالعه فلگ‌ها در scripts/train.sh
bash train.sh --episodes 100 --start-seed 1 --run-name my_run --skip-compile
```

### مقایسه

```bash
bash compare.sh --episodes 100 --start-seed 1 \
  --agents genetic_heuristic,ppo \
  --model agents/results/<date>/single/<run>/model.pth \
  --run-name my_compare --skip-compile
```

یا برای Heuristic-v2:

```bash
bash compare.sh --episodes 100 --start-seed 1 \
  --agents heur_v2_heuristic,heur_v2_ppo \
  --model agents/results/<date>/single/shared_ppo_heur_v2/model.pth \
  --run-name heur_v2_vs_heur_ppo_seeds1-100 --skip-compile
```

### سناریوهای قدیمی

`scripts/run.sh` → `servers.scenario` برای مسیرهای غیر shared (در صورت نیاز به سازگاری با مثال‌های قبلی).

---

## ۱۲. متریک‌های ارزیابی (بدون عدد)

گزارش نهایی باید این نمودارها را برای عامل‌ها تولید و تحلیل کند (اعداد را از JSON/`plots` بردارید، نه از این README):

| متریک | مفهوم |
|--------|--------|
| Average Latency | میانگین تأخیر انتهابه‌انتها؛ حفظ رفتار نزدیک به بلادرنگ |
| Energy | مجموع انرژی سخت‌افزارهای شبیه‌سازی‌شده |
| Service Migration Count | تعداد مهاجرت‌های پذیرفته‌شده میکروسرویس |
| Critical Task Success Rate | درصد تسک‌های بحرانی تکمیل‌شده قبل از Deadline |
| Convergence Curve | روند پاداش/متریک‌های آموزش PPO |

تحلیل پیشنهادی در گزارش (کیفی):

- آیا مهاجرت بیشتر همیشه بهتر است یا thrashing ایجاد می‌کند؟  
- آیا init قوی (Heuristic خوب) کار مهاجرت PPO را کم‌اثر یا مخرب می‌کند؟  
- آیا Heuristic-v2 + PPO نسبت به Heuristic-v2 + Heuristic در تعمیر چیدمان ضعیف بهتر عمل می‌کند؟  
- تفاوت GA بدون مهاجرت با GA+Heuristic/PPO چیست؟

---

## ۱۳. نکات پیاده‌سازی و محدودیت‌ها

1. **چندزبانه بودن:** هر باگ در JSON schema یا timeout سوکت کل اپیزود را خراب می‌کند؛ timeout نتایج در حالت shared معمولاً بیشتر از حالت قدیمی است.  
2. **preprocessor از پیش‌قرارگرفته:** روی کلاینت شروع می‌شود ولی می‌تواند در actor set بعدی مهاجرت کند.  
3. **ماسک feasibility:** PPO نباید دستگاه غیرممکن را انتخاب کند؛ ماندن روی فعلی همیشه مجاز نگه داشته می‌شود.  
4. **تفاوت train و compare:** پیش‌فرض فاصله تیک و سقف مهاجرت ممکن است فرق کند؛ برای مقایسه علمی آن‌ها را یکسان کنید.  
5. **نام Heuristic-v2:** در کد داخلی هنوز `bad_placement` / `bad_heuristic` برای init ضعیف استفاده می‌شود؛ در نمودارها و پوشه‌های نتیجه با نام Heuristic-v2 نمایش داده می‌شود.  
6. **README بالادستی iFogSim2:** این سند مخصوص پروژه درسی است؛ هسته شبیه‌ساز همچنان مبتنی بر iFogSim2/CloudSim است.

---

## مراجع پیاده‌سازی کلیدی (برای مطالعه کد)

| موضوع | مسیر |
|--------|------|
| مشخصات پروژه | `IoTLab-Project8.pdf` |
| سناریوی آموزش | `simulator/src/org/fog/test/perfeval/IndustrialIoTSimulationTrain.java` |
| تحرک | `simulator/src/org/fog/placement/TrainingMobilityController.java` |
| Shared Bridge | `simulator/src/org/fog/placement/SharedPolicyPPOBridgePlacementLogic.java` |
| Bridgeهای قدیمی‌تر | `PythonBridgePlacementLogic.java`, `PPOBridgePlacementLogic.java` |
| Heuristic | `agents/agents/heuristic.py` |
| Genetic | `agents/agents/genetic.py` |
| Shared PPO | `agents/agents/shared_ppo.py` |
| سرور آموزش | `agents/servers/train.py` |
| سرور مقایسه | `agents/servers/compare.py` |
| لانچرها | `scripts/train.sh`, `scripts/compare.sh` |

---

**نویسنده پیاده‌سازی پروژه:** مطابق هدرهای کد (`M-H-Boroumandnia` و همکاران پروژه درسی).  
برای بخش «نتایج و تحلیل نتایج»، خروجی‌های موجود در `agents/results/<تاریخ>/...` و نمودارهای `plots/` را در گزارش جداگانه مستند کنید.
