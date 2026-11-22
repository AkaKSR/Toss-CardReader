# 토스프론트 카드 리더 앱 (`com.toss.cardapp`)

토스프론트(PLF02WH, LineageOS 13)에 들어 있는 IC·마그네틱 리더로 카드를 읽는 결제 외 용도 앱입니다. 회원·직원 카드 확인, 특정 카드에 반응하는 규칙, 단순 출입 기록에 씁니다. 결제, PIN, 암호문(cryptogram), 키 관련 API는 쓰지 않습니다.

## 무엇을 읽나

| 카드 | 읽는 값 |
|---|---|
| IC | ATR, EMV 공개 레코드 전체(카드번호 5A, 유효기간 5F24, 소유자 5F20/9F0B, AID, 앱 이름 50/9F12, 발급 국가, 서비스 코드 등). 화면 아래에 태그 목록이 모두 나옵니다 |
| 마그네틱 | 트랙 1·2·3 원문 전체, 그리고 트랙에서 뽑은 카드번호, 유효기간, 소유자(트랙 1), 서비스 코드 |

IC 읽기 순서: `SELECT 1PAY.SYS.DDF01`(PSE) → `2PAY.SYS.DDF01`(PPSE) → 알려진 RID 부분 선택(Visa, Mastercard, AMEX, JCB, UnionPay, Discover, 국내 D410000001) → `GET PROCESSING OPTIONS`(금액 0) → AFL에 적힌 `READ RECORD`. VERIFY(PIN), GENERATE AC, INTERNAL AUTHENTICATE는 보내지 않습니다. GPO를 보내면 카드 안의 거래 카운터(ATC)가 1 늘어날 수 있습니다. 결제는 일어나지 않습니다.

리더 접근은 순정 `SunmiPayHardwareService`(Magisk 모듈 `sunmi_payhw`, v3.3.357)의 `ReadCardOptV2`를 raw binder로 호출합니다. 쓰는 기능은 `checkCard`, `cancelCheckCard`, `transmitApdu`, `cardOff`, `getCardExistStatus`뿐입니다. 서비스 APK와 lib, Magisk 모듈은 건드리지 않습니다.

## 기능

- **실시간 표시**: 카드를 꽂거나 긁으면 전체 정보가 바로 화면에 뜹니다.
- **기록**: 읽은 시각, 종류, 전체 데이터, 판정, 적용된 규칙을 기기 안에 암호화해서 저장합니다(최대 10,000건). 기록 화면에서 목록과 상세를 보고, **파일로 내보내기**(직접 고른 위치에 CSV)와 **전체 삭제**를 할 수 있습니다.
- **회원·직원 확인**: 최근에 읽은 카드나 직접 입력한 값을 이름과 함께 등록합니다. 비교 기준은 카드번호(기본), 소유자 이름, 트랙 1/2/3, ATR, AID 중에서 고릅니다. 카드를 읽으면 `허용` / `차단` / `미등록`을 보여 줍니다.
- **규칙**: "언제(모든 카드, 허용된 회원, 차단된 회원, 미등록 카드, 특정 회원, IC, 마그네틱) → 무엇을(환영 화면, 기록 화면, 회원 화면, 다른 앱 열기)". 위에서부터 처음 맞는 규칙 하나만 실행합니다.
- **단순 출입 기록**: 회원과 규칙이 없으면 꽂기·긁기 기록기로 동작합니다.
- **카드 넣으면 자동 실행**(1.1.0): 앱이 꺼져 있을 때 IC 카드를 꽂거나 마그네틱 카드를 긁으면 카드 리더 화면이 열리고 바로 읽습니다. 아래 "자동 실행" 절을 보세요.

## 자동 실행 (1.1.0)

- 상단 알림 "카드 자동 실행 켜짐"이 떠 있는 포그라운드 서비스(`CardWatchService`, START_STICKY)가 대기합니다. 기기를 켜거나(BOOT_COMPLETED) 앱을 업데이트하면(MY_PACKAGE_REPLACED) 자동으로 시작합니다. 앱을 한 번 열어도 시작합니다.
- 감지 방식: SP는 이벤트를 먼저 보내지 않으므로 서비스가 `checkCard(IC | 마그네틱, 60초)` **하나만** 걸어 두고, 60초 타임아웃마다 다시 겁니다. `getCardExistStatus` 폴링은 IC만 알 수 있어서 마그네틱 때문에 이 방식을 골랐습니다. 슬롯에 카드가 남아 있으면 빠질 때까지 걸지 않습니다(같은 카드로 화면이 다시 열리는 것 방지).
- 리더 넘겨주기: 앱 화면(리더, 기록, 설정 등) 하나라도 앞에 있으면 서비스는 즉시 `cancelCheckCard`로 리더를 놓고, 마지막 화면이 내려가고 1초 뒤에 다시 잡습니다. 그래서 서비스와 화면이 동시에 checkCard를 걸지 않습니다(INVOKING_REPEAT 없음). 앱 안에서는 1.0.0과 똑같이 동작하고, 키오스크는 늘 앞에 있으니 서비스는 쉬고 있습니다.
- 한 번만 읽기: 마그네틱은 다시 긁을 수 없으니 서비스가 받은 트랙을 앱 프로세스 안에서 화면에 그대로 넘깁니다(Intent에 싣지 않음). IC는 서비스가 감지만 하고 화면을 열면, 화면의 checkCard가 꽂혀 있는 카드를 바로 다시 감지해서 EMV 읽기는 화면이 한 번만 합니다.
- 설정 → "카드 넣으면 자동 실행"(기본 켬). 끄면 서비스와 알림이 사라집니다.
- 필요한 권한: **다른 앱 위에 표시**(Android 10 이상은 이 권한이 있어야 백그라운드 서비스가 화면을 열 수 있음). 설정 화면에 상태와 허용 버튼이 있습니다. 허용이 안 돼 있으면 화면은 안 열리고, 긁은 카드는 암호화 기록에만 남습니다. adb로 주려면 아래 "설치와 실행"을 보세요.
- 배터리: 이 기기는 AC 전원에 화면 항상 켜짐(`stay_on_while_plugged_in=7`)이라 도즈가 동작하지 않습니다. 전원이 빠졌을 때를 대비해 배터리 최적화 예외(`deviceidle whitelist`)에도 넣었습니다.
- logcat에는 태그 `CardAutoLaunch`로 카드 종류와 시간만 남깁니다(`detected IC`, `shown MSR +350ms`). 카드 데이터는 남기지 않습니다.
- 자동 실행의 "앱이 꺼져 있음"은 홈으로 나가거나 화면을 닫은 상태입니다. Android 설정의 **강제 종료**는 서비스와 부팅 수신까지 막으므로 앱을 직접 한 번 열어야 다시 대기합니다.
- 기기 검증: 업데이트 후 시작, 재부팅 1회 후 시작, 설정 토글, 화면과 서비스 인계 5회(INVOKING_REPEAT 0회)를 확인했습니다. 합성 감지부터 화면의 첫 그리기 직전까지는 MSR 70~96 ms, IC 69~81 ms였습니다(`evidence/autolaunch_latency.txt`, `evidence/boot_start.txt`).
- 실물 시험: 홈 화면에서 IC 삽입과 마그네틱 긁기를 시험했고 바로 화면이 열리는 것을 확인했습니다. 기기 로그와 기록 목록을 대조해 IC 2회·마그네틱 2회 각각 자동 실행과 단일 저장을 확인했습니다. 감지 후 첫 그리기 직전까지 IC 363/67 ms, 마그네틱 56/85 ms였습니다. 물리적인 삽입·긁기 시작부터 감지까지는 계측하지 않았습니다(`evidence/physical_autolaunch.txt`, `evidence/physical_record_metadata.txt`).
- CPU 측정(대기/해제 각각 180초): 대기 시 결제 서비스의 CPU 사용은 코어 1개 기준 약 0.47%p 증가했고 앱 자체는 0.01초를 썼습니다(`evidence/cpu.txt`). 배터리 방전량을 측정한 값은 아닙니다.

위 `evidence/` 경로는 로컬 검증 기록을 가리킵니다. 카드 데이터와 장치 정보를 보호하기 위해 검증 파일, 화면 캡처, 서명 키, 로컬 설정, 배포 APK는 공개 소스 트리에 포함하지 않습니다. 배포 APK는 GitHub Releases에서 별도로 제공합니다.

## 두 가지 모드 (같은 코어)

- **일반 앱**: 런처의 "카드 리더". 위쪽에 기록, 회원, 규칙, 설정 버튼이 있습니다.
- **키오스크**: 일반 앱의 "키오스크" 버튼이나 설정 → "키오스크 시작"으로 들어갑니다. 먼저 설정에서 **관리자 PIN**(4~8자리)을 정해야 합니다.
  - 전체 화면(immersive)으로 바뀌고, 뒤로 가기가 막히며, 결과는 15초 뒤 자동으로 지워집니다. 관리 화면을 열거나 다른 앱을 여는 규칙은 키오스크에서 실행하지 않습니다.
  - 잠금 방식: 기본은 Android **앱 고정(screen pinning)**입니다. 처음 들어갈 때 "앱 고정됨 → 확인"을 한 번 눌러야 합니다(`mLockTaskModeState=PINNED`).
  - 나가기: 오른쪽 아래 "관리자" → PIN 입력 → 고정이 풀리고 일반 화면으로 돌아갑니다.
  - 확인 창 없는 완전 잠금(LOCKED)이 필요하면 앱을 기기 소유자(device owner)로 지정합니다. 이 경우 앱이 스스로 lock task 허용 목록에 들어갑니다. 기기 전체 정책이 바뀌는 작업이라 기본 설치에서는 하지 않았습니다. 기기에 계정이 없어야 하고, 아래 명령으로 지정·해제합니다.
    ```
    adb shell dpm set-device-owner com.toss.cardapp/.ui.AdminReceiver
    adb shell dpm remove-active-admin com.toss.cardapp/.ui.AdminReceiver   # 해제 (testOnly가 아니면 앱 제거로 해제)
    ```

## 데이터 처리

- 기록, 회원, 규칙은 앱 전용 저장소(`/data/data/com.toss.cardapp/files/*.enc`)에 **Android Keystore AES-256-GCM** 키로 암호화해서 저장합니다. 키는 기기 밖으로 꺼낼 수 없습니다. 백업과 기기 이전에서도 제외됩니다(`allowBackup=false`, data extraction rules).
- **인터넷 권한이 없습니다.** 데이터가 기기 밖으로 나가는 경로는 사용자가 직접 고른 위치로 내보내는 CSV 하나뿐이고, 그 파일은 평문입니다.
- 앱은 카드 데이터를 logcat에 쓰지 않습니다. 서비스 쪽 로그도 이 기기 설정(`p1_security_trans_mode` 없음 → INFO 레벨)에서는 명령 APDU 머리와 길이만 남기고 응답(카드 데이터)은 남기지 않습니다.
- 화면 카드번호 표시: 설정 → "카드번호 가리기". 기본은 **전체 표시**입니다(사장님 요청). 가려도 저장되는 기록에는 전체 값이 암호화돼 들어갑니다.
- 관리자 PIN은 PBKDF2(솔트 포함) 해시로만 저장합니다.

## 빌드

필요한 것: JDK 21(`/opt/homebrew/opt/openjdk@21`), Android SDK(`platforms;android-34`, `build-tools;34.0.0`, `~/Library/Android/sdk`).

```
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew testDebugUnitTest assembleRelease
```

- 릴리스 APK: `app/build/outputs/apk/release/app-release.apk`
- 릴리스 서명: `keystore.properties`와 `keystore/release.jks`(git 제외)를 씁니다. 파일이 없으면 디버그 키로 서명합니다. 새로 만들려면:
  ```
  keytool -genkeypair -keystore keystore/release.jks -alias cardapp -keyalg RSA -keysize 3072 -validity 10000
  printf 'storeFile=keystore/release.jks\nstorePassword=...\nkeyAlias=cardapp\nkeyPassword=...\n' > keystore.properties
  ```
  같은 키로 서명해야 `adb install -r` 업데이트가 됩니다. 키를 잃으면 앱을 지우고(기록도 사라짐) 다시 설치해야 합니다.
- 디버그 빌드(`com.toss.cardapp.debug`)에는 QA용 가짜 카드 주입 리시버가 있습니다(릴리스에는 없음):
  `adb shell am broadcast -n com.toss.cardapp.debug/com.toss.cardapp.debug.InjectReceiver --es kind msr --es track2 '<SYNTHETIC_TRACK2>'`
  `<SYNTHETIC_TRACK2>`는 실제 카드와 무관한 테스트 트랙으로 바꾸세요. 공개 단위 테스트의 값은 합성 fixture이며 실제 카드에서 수집한 데이터가 아닙니다.
  `--es target service`를 붙이면 앱을 닫은 상태에서 자동 실행 서비스가 감지한 것처럼 화면을 엽니다(지연 측정용). 디버그와 릴리스를 같이 켜 두면 두 서비스가 리더를 다투니, 측정할 때는 릴리스를 `am force-stop` 하세요.

## 설치와 실행

```
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/release/app-release.apk
adb -s YOUR_DEVICE_SERIAL shell appops set com.toss.cardapp SYSTEM_ALERT_WINDOW allow          # 자동 실행이 화면을 열 수 있게
adb -s YOUR_DEVICE_SERIAL shell pm grant com.toss.cardapp android.permission.POST_NOTIFICATIONS # 대기 알림 표시
adb -s YOUR_DEVICE_SERIAL shell dumpsys deviceidle whitelist +com.toss.cardapp                  # 배터리 최적화 예외
adb -s YOUR_DEVICE_SERIAL shell am start -n com.toss.cardapp/.ui.ReaderActivity   # 일반
adb -s YOUR_DEVICE_SERIAL shell am start -n com.toss.cardapp/.ui.KioskActivity    # 키오스크 (PIN을 먼저 정한 뒤)
```

전제: Magisk 모듈 `sunmi_payhw`(순정 SunmiPayHardwareService)가 설치돼 있어야 합니다. 없으면 화면에 "결제 하드웨어 서비스를 찾을 수 없어요"가 나옵니다. 서비스 APK나 lib을 다른 버전으로 바꾸지 마세요. SP 펌웨어가 자동 업데이트될 위험이 있습니다(`toss-front-card-readers/REPORT.md` 3단계 5절).

## 제거

```
adb -s YOUR_DEVICE_SERIAL uninstall com.toss.cardapp          # 앱 + 암호화 기록 + Keystore 키 삭제
adb -s YOUR_DEVICE_SERIAL uninstall com.toss.cardapp.debug    # QA용 디버그 빌드를 설치했다면
```

키오스크에서 고정된 상태라면 먼저 관리자 PIN으로 나간 뒤 제거하세요. device owner로 지정했다면 먼저 해제해야 제거할 수 있습니다.

## 소스 구성

- `hw/PayHardware.kt`: 서비스 바인딩과 raw binder 호출(트랜잭션 번호는 서비스 v3.3.357의 ReadCardOptV2 스텁에서 확인)
- `emv/`: BER-TLV 파서, EMV 리더, 태그 이름
- `card/`: 카드 모델, 트랙 파서, 카드번호 가리기
- `core/`: 회원, 규칙, 판정(`Access`), 읽기 루프(`ReaderController`), 앱 화면 표시 여부(`AppForeground`)
- `service/`: 자동 실행 대기 서비스(`CardWatchService`)와 부팅·업데이트 리시버
- `store/`: Keystore 암호화 파일, 기록·회원·규칙 저장소, CSV 내보내기, 설정
- `ui/`: 일반, 키오스크, 기록, 회원, 규칙, 설정, 환영 화면
- 테스트: `app/src/test`(TLV, EMV 흐름, 트랙 파서, 판정)
