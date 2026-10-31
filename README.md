# HKG Gen5W USB Wi-Fi Hotspot (AP) & Client (STA) Enabler

현대·기아 표준형 5세대 와이드 내비게이션(**Standard-class Gen5W**, Telechips `tcc803x` / `tcc897x`, Linux `3.18.24-tcc`, Android 4.4.2) 헤드유닛에 **USB 무선랜카드(RTL8812BU / RTL8822BU / RTL8831BU / RTL8851BU)** 를 연결하여 **차량 LTE 핫스팟 공유(AP 모드)** 및 **외부 핫스팟 인터넷 수신(STA 클라이언트 모드)** 을 사용할 수 있게 해주는 NEMESIS 패키지입니다.

---

## 주요 기능

- **듀얼 동작 모드 (AP 핫스팟 / STA 클라이언트)**
  - **AP 모드 (핫스팟)**: 차량 내장 LTE 모뎀(`rmnet0`/`rmnet1`)의 데이터 연결을 USB 무선랜카드를 통해 Wi-Fi(2.4GHz / 5GHz)로 공유합니다.
  - **STA 모드 (클라이언트)**: 스마트폰 핫스팟이나 외부 공유기 Wi-Fi를 수신하여 헤드유닛에 인터넷을 공급합니다. 주변 Wi-Fi 검색, 신호 감도(RSSI) 표시, 다중 AP 비밀번호 자동 저장 및 우선순위 재연결을 지원합니다.
- **순정 시스템 무간섭 격리 설계**
  - 인터페이스 이름을 `rtwlan0`으로 분리하고, 독립 실행형 `hostapd_usb` 바이너리 패치 및 커널 드라이버 내장 DHCP 포트 변환(`UDP 67 <-> 1067`)을 적용하여 순정 내장 Wi-Fi(`wlan0`), 폰 프로젝션(무선 카플레이/안드로이드 오토), 텔레매틱스(블루링크/기아 커넥트)와 충돌하지 않습니다.
- **차종별 서브넷 자동 감지 (`AUTO`)**
  - 순정 내장 Wi-Fi(`wlan0` / `bcmdhd.ko`)가 있는 차량(EV6 등): `192.168.43.1/24` 대역 자동 사용
  - 순정 내장 Wi-Fi가 없는 차량(아이오닉 5, K8 HEV, 니로 EV 등): `192.168.0.1/24` 대역 자동 사용 (미사용 내부 `eth0` `192.168.0.123` 경로 충돌 자동 해제)
- **물리 `[MAP]` 버튼 3초 롱프레스 대시보드 팝업**
  - 내비게이션 전면의 물리 **`[MAP]` 버튼을 3초 이상 길게 누르면** 언제든 화면 위에 USB Wi-Fi 상태·설정 대시보드가 호출됩니다. (짧게 누를 때는 순정 지도 화면 이동이 그대로 동작합니다.)
- **GitHub 기반 자동 업데이트 (OTA)**
  - 인터넷 연결 시 본 저장소의 [`ota/version.json`](ota/version.json)을 확인하고, 새 버전이 있으면 SHA-256 무결성 검증 후 대시보드 UI(`usbwifistatus.jar`)와 백그라운드 데몬을 자동 또는 수동으로 즉시 업데이트합니다.
- **Android Auto(AOA) 리셋 차단 및 Hot-Unplug 보호**
  - 부팅 즉시 커널 모듈이 USB 동글을 선점(`dev.devt |= 0x00080000U`)하여 Android Auto `usbfs`의 AOA 프로빙으로 인한 동글 끊김 현상을 방지하며, 통전 중 갑작스러운 USB 탈거 시에도 커널 패닉 없이 안전하게 종료됩니다.

---

## 지원 차종 및 커널 호환성

헤드유닛의 SoC 플랫폼(`ro.board.platform`)을 자동으로 판별하여 해당 차종 커널 ABI에 맞는 커널 모듈을 로드합니다.

| 플랫폼 (`ro.board.platform`) | 커널 버전 (`vermagic`) | 검증 완료 차종 | 로드 모듈 (`AC` / `AX`) |
| :---: | :---: | :--- | :---: |
| **`tcc803x`** | `3.18.24-tcc SMP preempt mod_unload ARMv7 p2v8` | **EV6, 아이오닉 5, K8 HEV** 등 표준형 Gen5W 전반 | `88x2bu.ko`<br>`8851bu.ko` |
| **`tcc897x`** | `3.18.24-tcc SMP preempt mod_unload ARMv7 p2v8` | **니로 EV (DE EV)** 등 `tcc897x` 기반 Gen5W 전반 | `88x2bu_tcc897x.ko`<br>`8851bu_tcc897x.ko` |

---

## 지원 USB 무선랜카드 목록

별도 설정 없이 USB 포트에 꽂으면 자동으로 인식되어 동작합니다.

### 1. Wi-Fi 5 (802.11ac, AC1200 / AC1300급) — `88x2bu` 계열
- **지원 칩셋**: **Realtek RTL8812BU / RTL8822BU**
- **커널 드라이버**: `88x2bu.ko` (`tcc803x`), `88x2bu_tcc897x.ko` (`tcc897x`)

| 제조사 / 브랜드 | 지원 USB ID (`VID:PID`) | 대표 시판 모델 예시 |
| :--- | :---: | :--- |
| **Realtek 기본 (레퍼런스/OEM)** | `0bda:b812`, `0bda:b82c`, `0bda:b81a`,<br>`0bda:b820`, `0bda:b82b` | ipTIME A3000UA / A3000MINI / A3000U,<br>일반 RTL8812BU / RTL8822BU 기반 듀얼밴드 USB 동글 다수 |
| **TP-Link (티피링크)** | `2357:012d`, `2357:0138`, `2357:0115` | Archer T3U, Archer T3U Plus, Archer T3U Nano, Archer T4U (v3) |
| **ASUS (에이수스)** | `0b05:184c`, `0b05:1841`, `0b05:19aa` | USB-AC53 Nano, USB-AC55 B1, USB-AC58 |
| **Edimax / NEXT** | `7392:b822`, `7392:c822` | EW-7822ULC, EW-7822UTC |
| **D-Link (디링크)** | `2001:331c`, `2001:331e` | DWA-182 (Rev D), DWA-181 |
| **Netgear (넷기어)** | `0846:9055` | A6150 (AC1200 Mini) |
| **Linksys (링크시스)** | `13b1:0043` | WUSB6400M |
| **Elecom (엘레컴)** | `056e:4011` | WDC-867DU3S |

### 2. Wi-Fi 6 (802.11ax, AX900급) — `8851bu` 계열
- **지원 칩셋**: **Realtek RTL8831BU / RTL8851BU** (RTL8831AU/BU 표기 AX900 동글 포함)
- **커널 드라이버**: `8851bu.ko` (`tcc803x`), `8851bu_tcc897x.ko` (`tcc897x`)

| 제조사 / 브랜드 | 지원 USB ID (`VID:PID`) | 대표 시판 모델 예시 |
| :--- | :---: | :--- |
| **Realtek 기본 (레퍼런스/OEM)** | `0bda:b831`, `0bda:b851`, `0bda:b832` | RTL8831BU / RTL8851BU 기반 AX900 초소형 USB 동글,<br>ipTIME AX900U 계열 및 국내외 AX900 OEM 무선랜카드 |
| **TP-Link (티피링크)** | `3625:010b`, `2357:0142` | Archer TX10UB Nano, Archer TX900U Nano (AX900) |
| **Mercusys (머큐시스)** | `2c4e:0128` | MA30N (AX900 Nano) |
| **D-Link (디링크)** | `2001:332a` | DWA-X1850 / AX900 계열 |
| **Edimax (에디맥스)** | `7392:e611` | EW-7811UAX (AX900) |
| **Elecom (엘레컴)** | `056e:4027`, `056e:4028` | WDC-X0901SU2 계열 |
| **Buffalo (버팔로)** | `0411:03ed` | WI-U2-600AX2 계열 |
| **가상 CD-ROM 내장형 동글** | `0bda:1a2b` *(자동 전환)* | 윈도우 드라이버 설치용 가상 CD-ROM이 내장된 RTL8831BU/8851BU 동글<br>*(연결 시 커널/데몬이 자동으로 SCSI Eject/Switch 명령을 전송하여 Wi-Fi 모드로 즉시 전환)* |

---

## 설치 및 사용 방법

### 1. USB 최초 설치
1. 빌드된 `HKG_5W_USBWifi_Hotspot.zip` 압축을 FAT32 포맷된 USB 메모리 루트에 해제합니다.
   - USB 루트에 `NEMESIS.data`와 `packages/usbwifi.hotspot.zip`이 위치해야 합니다.
2. 차량 USB 포트에 메모리를 꽂으면 NEMESIS가 패키지를 자동 설치하고 백그라운드 데몬을 시작합니다.
3. 설치 완료 후 USB 무선랜카드를 연결하면 자동으로 드라이버가 로드되고 Wi-Fi 인터페이스가 활성화됩니다.

### 2. 대시보드 화면 열기
- **방법 1 (물리 버튼)**: 내비게이션의 **`[MAP]` 버튼을 3초 이상 길게 누르면** 현재 화면 위에 대시보드가 표시됩니다.
- **방법 2 (NEMESIS 런처)**: NEMESIS 앱 목록에서 **USB WiFi Hotspot Enabler**를 실행합니다.

### 3. 대시보드 주요 조작
- **핫스팟(AP) ⇄ 클라이언트(STA) 모드 전환**: 상단 모드 전환 버튼을 눌러 즉시 전환할 수 있습니다.
- **AP 설정 변경 (SSID / 비밀번호 / 2.4GHz·5GHz 밴드)**: `[AP 설정 변경]` 버튼 클릭 후 내장 키보드로 수정·저장합니다.
- **STA 외부 Wi-Fi 연결**: STA 모드에서 `[주변 Wi-Fi 검색]` 또는 검색된 네트워크 항목을 터치하여 비밀번호를 입력하면 연결 및 자동 저장됩니다.
- **OTA 업데이트**: `[업데이트 확인]` 버튼을 누르거나 `[자동 업데이트: 켜짐]` 상태에서 최신 버전을 GitHub로부터 받아 재부팅 없이 즉시 반영합니다.

---

## 저장소 구조 및 빌드 방법

```text
├── package_usbwifi_hotspot/
│   ├── build_package.py               # DEX 035 UI JAR 컴파일, 자체 테스트, NEMESIS 패키지 및 OTA 파일 생성
│   ├── src_java/com/hkg/usbwifi/
│   │   └── UsbWifiStatusMain.java     # 대시보드 오버레이 UI, 키 이벤트(MAP 3초), 호스트 패치 및 OTA 클라이언트
│   └── kernel_build/
│       ├── setup_build_env.sh         # Linux 3.18.24-tcc + GCC 4.7 크로스 컴파일 환경 구성 스크립트
│       ├── build_both_ko.sh           # 88x2bu / 8851bu (tcc803x & tcc897x) 4종 커널 모듈 패치 및 빌드
│       ├── config_tcc803x.txt         # tcc803x (EV6, 아이오닉 5, K8 HEV) 커널 설정
│       └── config_tcc897x.txt         # tcc897x (니로 EV) 커널 설정
├── dump_kernel_usbwifi/
│   └── build_dump_usb.py              # 타 차량 이식성 검증용 커널/환경 덤프 수집 USB 생성 스크립트
└── ota/
    ├── version.json                   # OTA 버전 및 SHA-256 해시 매니페스트
    ├── usbwifi-hotspot-daemon.sh      # 백그라운드 서비스 데몬 스크립트
    ├── launch.sh                      # 수동 실행 런처 스크립트
    └── usbwifistatus.b64              # DEX 035 JAR Base64 인코딩 페이로드
```

### 패키지 빌드 명령
```bash
# 1. (최초 1회 또는 드라이버 수정 시) WSL 환경에서 커널 모듈 4종 빌드
wsl sh -c "sh package_usbwifi_hotspot/kernel_build/setup_build_env.sh && sh package_usbwifi_hotspot/kernel_build/build_both_ko.sh"

# 2. Java UI 컴파일, 자체 유닛 테스트, 커널 심볼 검증 및 NEMESIS ZIP + OTA 생성
python package_usbwifi_hotspot/build_package.py
```
