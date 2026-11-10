# AndroidDeck

안드로이드 폰을 같은 네트워크의 PC(Windows / macOS / Linux)용 스트림덱으로 쓰는 앱입니다.
한 앱에서 여러 PC를 동시에 연결해 두고 상단에서 바로 전환할 수 있습니다.

```
android/   안드로이드 앱 (Kotlin · Jetpack Compose · Material 3 · Glance 위젯)
server/    PC 서버 (Python 3.10+ · aiohttp · psutil · pynput · pystray)
```

## 기능

| 영역 | 내용 |
|---|---|
| 덱 | 페이지별 버튼 그리드, 길게 눌러 드래그로 순서 변경, 색상/심볼/앱 아이콘, **폴더 버튼**(2×2 미리보기) |
| 프로그램 실행 | PC에 설치된 앱 목록을 **실제 아이콘과 함께** 폰에서 검색·선택 |
| 단축키 | 조합키 편집기 + OS별 프리셋 |
| 멀티 액션 | 여러 동작을 단계별 지연(0~5초)과 함께 순서대로 실행 (예: 앱 실행 → 단축키) |
| 오디오 | 마스터 볼륨, **마이크 음소거**(버튼에 상태 점 표시), **앱별 볼륨 믹서**, **지금 재생 중**(제목·아티스트·앨범 아트) |
| 트랙패드 | 폰을 마우스로: 이동, 탭=클릭, 두 손가락 스크롤/탭=우클릭, 길게 눌러 드래그, 실시간 키보드 입력(한글 포함) |
| 모니터링 | CPU(코어별·온도·클럭), RAM, GPU, 디스크, 네트워크, 상위 프로세스, 60초 그래프 |
| 멀티 PC | 모든 PC에 동시 연결, 상단 칩으로 전환, **다른 PC를 대상으로 하는 버튼**, 페이지를 다른 PC로 복사 |
| Wake-on-LAN | 꺼진/절전 PC를 폰에서 깨우기 (오프라인 배너·기기 목록·덱 버튼) |
| 자동 검색·연결 | UDP 브로드캐스트로 PC 자동 검색, IP가 바뀌어도 자동 재연결 |
| 위젯·빠른 설정 | 홈 화면 위젯(최대 8개 버튼), 빠른 설정 타일 3개 — 앱을 열지 않고 실행 |
| 백업 | 덱 내보내기/가져오기(JSON 파일, 추가 또는 교체) |
| 보안 | TLS 암호화 + 인증서 지문 고정, PIN 페어링 |
| PC 서버 | 트레이 아이콘, 로그인 시 자동 시작, Python 없이 실행되는 단일 실행 파일 |

덱 레이아웃은 **각 PC에 저장**됩니다(PC마다 설치된 앱이 다르기 때문). 같은 PC에 여러 폰을 붙이면 레이아웃이 실시간 동기화됩니다.

## 빠른 시작

### 1. PC에서 서버 실행

**실행 파일(권장)** — GitHub 릴리스에서 OS에 맞는 파일을 받아 실행하면 트레이(메뉴 막대)에 아이콘이 생깁니다. 트레이 메뉴에서 PIN 확인, 새 PIN, 페어링 해제, **로그인 시 자동 시작**을 켤 수 있습니다.

| OS | 파일 | 처음 실행할 때 |
|---|---|---|
| Windows | `AndroidDeckServer-x.y.z-windows.exe` | SmartScreen 경고 → *추가 정보 → 실행*, 방화벽 창에서 *개인 네트워크* 허용 |
| macOS | `AndroidDeckServer-x.y.z-macos.zip` | 압축 해제 → 앱을 오른쪽 클릭 → *열기* (서명되지 않은 앱) |
| Linux | `AndroidDeckServer-x.y.z-linux-x64.tar.gz` | `tar xzf …` 후 `./AndroidDeckServer` |

**소스에서 실행** — Python 3.10 이상이 필요합니다. 첫 실행 때 `server/.venv`에 의존성을 자동 설치합니다.

| OS | 실행 |
|---|---|
| Windows | `server\start-windows.bat` |
| macOS | `server/start-macos.command` |
| Linux | `./server/start-linux.sh` (트레이로 실행하려면 `--tray`) |

서버 창(또는 트레이 메뉴)에 이름, 주소, **6자리 PIN**, **지문(Fingerprint)** 이 표시됩니다.

### 2. 폰에 앱 설치

```bash
cd android
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/app-release.apk
adb install app/build/outputs/apk/release/app-release.apk
```

### 3. 페어링

같은 Wi-Fi에서 앱을 열면 PC가 자동으로 나타납니다 → **Pair** → PIN 입력.
PIN 창에 표시되는 **지문이 PC 화면의 지문과 같은지** 확인하세요. 같으면 중간에서 가로채는 기기가 없다는 뜻입니다.
한 번 페어링하면 이후에는 자동으로 연결됩니다. 다른 PC는 상단 **+** 에서 추가합니다(새 PC가 보이면 `+ 1 new` 배지).

## 사용법

- **편집 모드**: 상단 연필 → 버튼을 탭해 편집, `+` 로 추가, 길게 눌러 끌면 순서 변경. 일반 모드에서 버튼을 길게 눌러도 편집됩니다.
- **폴더**: 액션 종류에서 *Folder* 를 고르고 저장 → 편집 모드에서 폴더를 탭하면 안으로 들어가 버튼을 추가합니다. 폴더 이름·색은 폴더 안 상단 연필로 바꿉니다.
- **멀티 액션**: *Multi* → 단계 추가, 각 단계의 대기 시간과 동작을 지정합니다. 중간 단계가 실패하면 "step N: …" 오류를 보여줍니다.
- **다른 PC 대상**: PC가 2대 이상이면 편집기에 *Run on* 이 생깁니다. 다른 PC를 대상으로 하는 버튼은 오른쪽 위에 작은 PC 아이콘이 붙습니다.
- **Wake-on-LAN**: 한 번 연결된 PC는 MAC 주소를 기억합니다. 오프라인일 때 배너·기기 목록의 *Wake*, 또는 *Wake PC* 버튼으로 깨웁니다. PC의 BIOS/네트워크 어댑터에서 WoL이 켜져 있어야 합니다.
- **위젯·타일**: 버튼 편집기에서 *Show in widget & quick tiles* 를 켜고, 홈 화면에 *Deck buttons* 위젯을 추가하거나 빠른 설정에 *Deck 1~3* 타일을 추가합니다(앞의 3개가 타일).
- **Touch 탭**: 화면을 트랙패드로 사용합니다. 키보드 버튼을 누르면 폰 키보드 입력이 PC로 실시간 전송됩니다. 속도·스크롤 방향은 설정에서 바꿉니다.
- **백업**: 설정 → *Deck backup* 에서 내보내기/가져오기. 페이지 하나만 다른 PC로 보내려면 편집 모드에서 페이지 탭을 다시 누르고 *Copy this page to*.

## OS별 참고

| | Windows | macOS | Linux |
|---|---|---|---|
| 앱 목록·아이콘 | 시작 메뉴 "모든 앱"(스토어 앱 포함, PowerShell) | `/Applications` 등 `.app` (`sips`) | `.desktop` + 아이콘 테마 |
| 볼륨·마이크 | Core Audio (pycaw) | AppleScript (마이크 음소거 = 입력 볼륨 0) | wpctl → pactl → amixer |
| 앱별 믹서 | ✅ | ❌ (공개 API 없음) | ✅ PipeWire / PulseAudio |
| 지금 재생 중 | ✅ 시스템 미디어 세션 (Python 3.11+) | Spotify·Music만 (처음에 자동화 권한 요청) | ✅ MPRIS 지원 플레이어 |
| 키보드·마우스 | ✅ | *손쉬운 사용* 권한 필요 | X11 ✅ / **Wayland는 `ydotool` + `ydotoold` 필요** |
| 트레이 아이콘 | ✅ | 메뉴 막대 | AppIndicator 지원 데스크톱 (GNOME은 확장 필요) |

Linux에서 `python3 -m venv` 가 실패하면 `sudo apt install python3-venv`, `evdev` 빌드가 실패하면 `sudo apt install python3-dev build-essential`.

## 서버 옵션

```
AndroidDeckServer [--tray | --console] [--port 47777] [--name 이름] [--new-pin] [--revoke-all]
                  [--autostart on|off|status] [--no-discovery] [--host 0.0.0.0] [--paths] [-v]
```

실행 파일은 기본이 트레이 모드, 소스 실행은 기본이 콘솔 모드입니다. 설정·인증서·덱·로그(`config.json`, `tls.key/crt`, `profile.json`, `server.log`) 위치:

| OS | 경로 |
|---|---|
| Windows | `%APPDATA%\AndroidDeck\` |
| macOS | `~/Library/Application Support/AndroidDeck/` |
| Linux | `~/.config/androiddeck/` |

`ANDROIDDECK_HOME` 환경 변수로 위치를 바꿀 수 있습니다(포터블 설치·테스트용).

## 보안

- 모든 통신은 **TLS(HTTPS/WSS)** 입니다. 서버는 처음 실행할 때 자체 서명 인증서를 만들고, 폰은 페어링할 때 그 인증서의 SHA-256 지문을 **고정(pin)** 합니다. 이후에는 지문이 다른 인증서로는 연결하지 않습니다.
- 처음 페어링하는 순간이 유일한 약점(TOFU)입니다. PIN 창의 지문과 PC 화면의 지문을 비교하면 이 위험도 없앨 수 있습니다.
- PIN 5회 오답 시 해당 IP는 60초 차단, PC에는 기기 토큰의 해시만 저장합니다. `--new-pin`, `--revoke-all`, 트레이 메뉴로 관리합니다.
- 페어링된 폰은 앱 실행·키 입력·마우스·셸 명령을 할 수 있습니다. 셸 명령이 필요 없다면 `config.json` 에서 `"allow_commands": false`.
- PC 인증서(`tls.key`)를 지우면 지문이 바뀌므로 모든 폰에서 다시 페어링해야 합니다.

## 프로토콜 요약 (v2)

| 경로 | 설명 |
|---|---|
| UDP 47778 | `{"t":"androiddeck.discover"}` → `{"t":"androiddeck.here", id, name, os, port, tls, fp, …}` |
| `GET /api/info` | 서버 정보 (인증 없음) |
| `POST /api/pair` | `{pin, device}` → `{token, server, wol}` |
| `GET /api/apps[?refresh=1]`, `GET /api/icon/{id}` | 앱 목록, 아이콘 |
| `GET /api/media/art` | 지금 재생 중 앨범 아트 (로컬 파일·시스템 썸네일) |
| `GET/PUT /api/profile` | 덱 레이아웃 |
| `POST /api/action` | 단발성 액션 (위젯·타일용) |
| `GET /ws` | 실시간: `action`·`sub`·`mouse`·`scroll`·`click`·`button`·`type`·`key` 전송 / `welcome`·`ack`·`stats`·`volume`·`mic`·`mixer`·`media`·`profile` 수신 |

`/api/info`, `/api/pair` 를 제외한 모든 요청에는 `Authorization: Bearer <token>` 이 필요합니다. 모든 포트는 TCP 47777(HTTPS/WSS)과 UDP 47778(검색)입니다.

## CI / 릴리스 (GitHub Actions)

| 워크플로 | 트리거 | 하는 일 |
|---|---|---|
| [`ci.yml`](.github/workflows/ci.yml) | `main` 푸시, PR, 수동 | 안드로이드 빌드 + lint, 서버 ruff + [스모크 테스트](server/tests/smoke_test.py)를 Ubuntu·Windows·macOS에서 실행하고, **실행 파일을 빌드해 그 실행 파일로 다시 스모크 테스트** |
| [`release.yml`](.github/workflows/release.yml) | `v*` 태그 푸시, 수동 | 서명된 APK + OS별 서버 실행 파일 + 소스 zip + `SHA256SUMS.txt` 를 **초안(draft) 릴리스**에 첨부. 공개는 사람이 직접 |

스모크 테스트는 격리된 폴더에서 서버를 띄워 TLS 지문 고정, 검색, 페어링, 앱 목록·아이콘, 프로필, 멀티 액션, 믹서, WebSocket을 확인합니다. PC에 영향을 주는 동작은 보내지 않습니다.

```bash
cd server && python tests/smoke_test.py --min-apps 1 --min-icons 1
python packaging/build.py && python tests/smoke_test.py --exe dist/AndroidDeckServer   # 실행 파일 테스트
```

릴리스마다 같은 키로 서명해야 폰에서 앱을 덮어써 업데이트할 수 있습니다. 저장소 Secrets에 `ANDROIDDECK_KEYSTORE_BASE64`, `ANDROIDDECK_KEYSTORE_PASSWORD`, `ANDROIDDECK_KEY_ALIAS`, `ANDROIDDECK_KEY_PASSWORD` 를 등록하세요(없으면 일회용 debug 키로 서명하고 경고).

```bash
keytool -genkeypair -keystore release.jks -alias androiddeck -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # → ANDROIDDECK_KEYSTORE_BASE64
```

키 파일과 비밀번호는 저장소 밖에 백업해 두세요. 잃어버리면 기존 설치본을 업데이트할 수 없습니다.

## 검증 현황

이 저장소에서 실제로 확인한 것 (Linux, Ubuntu 24.04 GNOME Wayland / PipeWire):

- 서버 스모크 테스트 25항목 통과(새 venv, 소스 실행과 PyInstaller 실행 파일 모두): TLS·잘못된 지문 거부, 검색, 페어링, 앱 67개·아이콘 66개, 프로필 검증, 멀티 액션과 실패 단계 보고, 믹서, 통계
- 앱별 믹서: 실제 재생 스트림으로 목록·볼륨(0.42 왕복)·음소거 확인
- 지금 재생 중: 가짜 MPRIS 플레이어로 제목(따옴표·한글)·아티스트·로컬 앨범 아트·재생/일시정지 명령 확인
- Wake-on-LAN 정보(MAC·브로드캐스트) 추출, 트레이 모드 기동, 로그인 자동 시작 등록/해제
- 안드로이드: 실제 서버 2대에 붙인 화면 렌더링(Robolectric)으로 지문 표시 페어링, 잘못된 지문 거부, 두 PC 전환, 다른 PC 대상 버튼 실행·확인 응답, 멀티 액션, 폴더, 오디오(지금 재생·마이크·믹서), Touch, 시스템, 설정 확인. lint 0건

아직 확인하지 않은 것: **Windows·macOS에서의 실행**(PowerShell 아이콘 추출, pycaw 믹서·마이크, 시스템 미디어 세션, sips, PyInstaller 빌드) — CI의 Windows/macOS 작업이 처음 실행하는 곳입니다. **실제 폰 설치**(위젯·타일·트랙패드 감도는 실기기에서만 확인 가능), 실제 앱 실행·키 입력·마우스 이동(테스트 중 PC를 조작하지 않도록 일부러 보내지 않음), 실제 Wake-on-LAN 기상.

### 검증 체크리스트 (사용자 확인용)

| # | 절차 | 통과 기준 |
|---|---|---|
| 1 | 서버 실행 후 앱 실행 | 2~3초 안에 PC 표시, PIN 창의 지문 = PC 화면 지문 |
| 2 | 기본 덱의 Vol ± / Mute / Mic | PC 볼륨 변화, Mic 버튼 점이 빨강(음소거)↔초록 |
| 3 | Audio 탭 | 재생 중인 곡과 앨범 아트 표시, 앱 목록 슬라이더로 그 앱 볼륨만 변경 |
| 4 | App 버튼 추가 → 탭 | 실제 아이콘, PC에서 앱 실행 |
| 5 | Folder · Multi 버튼 | 폴더가 열리고, 멀티 액션 단계가 지연을 두고 순서대로 실행 |
| 6 | Touch 탭 | 포인터 이동·클릭·두 손가락 스크롤·우클릭, 키보드로 한글 입력 |
| 7 | 두 번째 PC 페어링 | 상단 칩 전환, *Run on* 으로 다른 PC 버튼 실행, 페이지 복사 |
| 8 | PC 절전 → *Wake* | PC가 깨어나고 자동 재연결 (BIOS WoL 설정 필요) |
| 9 | 위젯·타일 | 앱을 닫은 상태에서 위젯/타일로 실행 |
| 10 | 트레이 *Start at login* 켜고 재부팅 | 로그인 후 서버 자동 실행, 폰 자동 연결 |
| 11 | 덱 내보내기 → 가져오기(추가) | 페이지가 복제되어 추가됨 |

문제가 생기면 서버를 `-v` 로 실행하거나 `server.log` 를 확인하세요.

## 다음 단계로 고려할 것

- OBS 연동 (obs-websocket)
- UI 한국어 번역
- 버튼별 앱 볼륨 액션 (예: 디스코드만 음소거 버튼)
- 실행 파일 코드 서명 (Windows SmartScreen / macOS Gatekeeper 경고 제거)
