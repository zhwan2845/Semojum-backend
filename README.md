# 세모점 — Backend

> **AI 점자 번역 에디터**의 서비스 서버입니다.
>
> 이 저장소는 실제 서비스 중인 비공개 저장소의 **공개 버전**입니다. 서버 주소 · 계정 정보 · 도메인처럼 보안상 민감한 값만 제거하거나 예시 값으로 바꿨고, 비즈니스 로직과 구현은 원본과 같습니다.

<br>

## 1. 서비스 소개

시각장애인용 교재 · 문서는 점역사가 묵자(일반 글자) 원문을 점자로 옮겨 만듭니다. 전 과정이 수작업으로 진행되어 한 권에 수 주에서 수개월이 걸립니다.

**세모점**은 이 과정을 AI로 줄입니다.

1. 점역사가 PDF · HWP · HWPX · TXT · 이미지를 올리면
2. 서버가 쪽 단위로 나눠 GPU 추론서버에 보내 **텍스트를 추출 · 교열**하고
3. 쪽이 끝날 때마다 **실시간(SSE)** 으로 데스크톱 앱에 보내면
4. 점역사는 **원본 | 텍스트 | 점자 3패널 에디터**에서 결과를 검수 · 수정하고
5. 앱이 점자 규정에 맞춰 점역 · 조판해 `.brf` 점자 파일로 출력합니다.

| 사용자 | 역할 |
|---|---|
| 점역사 (`ROLE_USER`) | 문서 업로드 · 변환 · 편집 · 저장, 점자 규정 검색, QnA |
| 기관 관리자 (`ROLE_ORG_ADMIN`) | 소속 계정 · 크레딧 사용 현황 관리 (파일 내용 열람 불가) |
| 운영자 (`ROLE_ADMIN`) | 기관 · 계정 발급, 단가표 · 크레딧 · 쿠폰, 통계 · 수익성, 공지 · 문의 |

B2B 서비스라 자체 가입이 없고, 운영자가 기관별로 계정을 발급합니다. 변환한 쪽만큼 **기관 크레딧이 차감**됩니다.

### 블록 단위 편집

AI 결과는 쪽 전체가 한 덩어리 글로 오지 않고 **제목 · 본문 · 표 · 수식 · 그림 같은 블록** 단위로 옵니다. 점역사는 블록 하나하나를 고치고, 블록을 추가 · 삭제하거나 순서를 바꿀 수 있습니다.

- **텍스트 블록과 점자 블록이 짝을 이룹니다.** 두 패널이 같은 블록 id를 써서 어느 글이 어느 점자가 됐는지 블록 단위로 맞춰 볼 수 있습니다.
- **원본과 대조하며 검수합니다.** 블록마다 원본 이미지 속 위치(bounding box)가 저장되어 있어, 원본에서 그 부분을 찾아 확인할 수 있습니다.
- **AI 결과가 지워지지 않습니다.** AI가 처음 준 결과와 사용자가 고친 결과를 따로 보관합니다. AI가 후보를 여러 개 주면 블록마다 골라 쓸 수 있습니다.
- **어디를 먼저 볼지 알려 줍니다.** 블록마다 적용된 점자 규정 근거와 추출 신뢰도가 붙어 있습니다.
- **검수 의견을 블록에 남깁니다.** 줄 메모 · 답글 · 해결 표시를 블록에 달고, `.smj` 파일로 다른 점역사와 주고받습니다.

<br>

## 2. 기술 스택

| 구분 | 기술 |
|---|---|
| Language · Framework | Java 21, Spring Boot 3.5, Spring Security, Spring Data JPA |
| Database | PostgreSQL 18 (AWS RDS, private subnet), Flyway |
| Cache · Queue | Redis (작업 큐 · 스케줄러 링 · 진행 상태 · 로그인 잠금) |
| Storage | AWS S3 (EC2 IAM Role 키리스 인증, presigned URL) |
| 통신 | gRPC + TLS (GPU 추론서버), SSE (실시간 진행 상황) |
| 인증 | JWT (jjwt) Access/Refresh, BCrypt, 세션 테이블 |
| 문서 처리 | OpenPDF, PDFBox, poppler(`pdftoppm`), hwplib, pyhwp + LibreOffice |
| Infra · CI/CD | AWS EC2 · RDS · S3 · ECR, Docker Compose, Envoy, GitHub Actions (OIDC) |
| Test | JUnit 5, Testcontainers (PostgreSQL · Redis), 테스트 클래스 80개 |

<br>

## 3. 아키텍처

```
 Electron 데스크톱 앱 (FE)                       운영자 콘솔 (Web)
            │  HTTPS (REST · SSE)                        │
            └───────────────────┬────────────────────────┘
                                ▼
                     Envoy (TLS 종료 · 헬스체크 라우팅)
                         │                  │
                  backend-blue        backend-green      ← 블루그린 무중단 배포
                         └────────┬─────────┘
                                  │
        ┌───────────────┬─────────┼──────────────┬──────────────────────┐
        ▼               ▼         ▼              ▼                      ▼
   PostgreSQL         Redis       S3       GPU 추론서버            IMAP 메일함
  (작업 · 결과 ·   (공정 큐 ·  (원본 ·    gRPC/TLS, 사설 IP       (문의 폴링,
   크레딧 · 통계)   진행 상태)  미리보기)  슬롯 수 = 워커 수          읽기 전용)
```

**변환 파이프라인**

```
업로드 ──▶ 파일 종류 판별(내용물 시그니처) ──▶ HWP/HWPX → PDF 변환 ──▶ 쪽 분리 · 썸네일
   │
   └─▶ (트랜잭션 커밋 후) Redis 작업별 큐에 쪽 태스크 적재
                │
     JobDispatcher: 유저 링 → 작업 링 → 쪽 1장 (2계층 라운드로빈, FG:BG = 4:1)
                │
     CreditGate: 기관 잔여 크레딧 확인 (부족하면 한 쪽씩 / 소진되면 차단)
                │
     PageWorker × N (AI 슬롯 수만큼) ──gRPC──▶ GPU 추론서버
                │
     ResultService: 결과 저장 · 원가 계산 · 크레딧 차감 ──▶ SSE page_done (쪽 순서대로)
```

<br>

## 4. 주요 구현 내용

### 4-1. 공정한 작업 스케줄링 — 한 사람의 대형 작업이 전부를 막지 않게

> [`JobDispatcher`](src/main/java/com/semojum/backend/domain/job/scheduler/JobDispatcher.java) · [`PageWorker`](src/main/java/com/semojum/backend/domain/job/worker/PageWorker.java)

- 300쪽짜리 문서 하나가 AI 슬롯을 독점하면 뒤에 온 사람은 몇십 분을 기다려야 합니다. 그래서 **작업마다 큐(`queue:job:{id}`)를 따로 두고, 유저 링 → 작업 링 → 쪽 1장 순서의 2계층 라운드로빈**으로 꺼냅니다. 사용자 간, 그리고 한 사용자의 여러 작업 간에 차례가 돌아갑니다.
- 지금 화면을 보고 있는 작업(Foreground)을 먼저 처리합니다. SSE 연결 · 상태 폴링이 TTL 30초짜리 리스 키를 갱신하고, FG:BG를 4:1로 섞어 백그라운드 작업도 굶지 않게 했습니다.
- 링과 큐를 모두 Redis에 두어 **서버를 재시작해도 대기 중인 쪽이 이어서 처리**됩니다.
- **워커 수 = GPU 추론서버 총 슬롯 수**입니다. 워커 하나가 슬롯 하나를 잡고 블로킹 gRPC를 호출하므로 GPU 추론서버에 과부하를 주지 않습니다. GPU 추론서버 증설은 `host:port:슬롯수` 환경변수만 바꾸면 됩니다([`AiServerPool`](src/main/java/com/semojum/backend/global/grpc/AiServerPool.java)).
- 오류가 나면 큐 **머리**에 다시 넣어(순서 유지) 최대 3회 재시도하고, 그래도 실패하면 그 쪽만 BLOCKED 처리합니다. 한 쪽이 실패해도 작업 전체는 **부분 성공 = 완료**로 끝납니다.

### 4-2. 실시간 진행 상황(SSE) — 병렬 처리지만 쪽 순서는 보장

> [`SseService`](src/main/java/com/semojum/backend/domain/job/service/SseService.java)

- 쪽은 병렬로 처리되어 3쪽이 1쪽보다 먼저 끝날 수 있습니다. 에디터는 앞쪽부터 채워야 하므로 **커서 방식으로 `page_done`을 1, 2, 3… 순서대로만 내보내고**, 재연결 시 완료분을 순서대로 다시 보냅니다.
- 마지막 쪽들이 모두 GPU 추론서버에 들어가 있으면 최대 수백 초 동안 보낼 것이 없어 끊긴 연결을 알 수 없었습니다. **30초 하트비트(`: ping`)** 로 죽은 연결을 드러냅니다.
- 진행 상태 Hash는 종료 1시간 뒤 사라집니다. 그 뒤에 붙은 연결이 "아직 시작 전"과 구분되지 않아 몇 시간씩 매달리던 문제가 있어, **Hash가 비면 DB로 상태를 확인해 즉시 종료**하게 고쳤습니다.
- `spring.jpa.open-in-view=false`로 두어 긴 SSE 연결이 DB 커넥션을 붙잡지 않게 했습니다.

### 4-3. 크레딧 · 원가 계산 — 숫자가 틀리지 않게

> [`CreditGate`](src/main/java/com/semojum/backend/domain/job/scheduler/CreditGate.java) · [`CreditBalanceService`](src/main/java/com/semojum/backend/domain/billing/service/CreditBalanceService.java) · [`CreditDeductionService`](src/main/java/com/semojum/backend/domain/billing/service/CreditDeductionService.java) · [`UsageCostService`](src/main/java/com/semojum/backend/domain/billing/service/UsageCostService.java)

- GPU 추론서버는 **측정값(토큰 수 · GPU 시간 · 쪽 레이아웃 종류)만** 보내고, 금액과 크레딧은 서비스 서버가 계산합니다.
- 단가 · 환율 · 배율은 코드가 아니라 `pricing_configs` 테이블에 두고, **수정은 새 행 추가**로만 합니다. 각 쪽 결과가 계산에 쓴 단가표를 가리키고 원자료도 함께 저장해, 단가가 바뀌어도 과거 기록이 변하지 않고 재검산할 수 있습니다.
- 단가표에 없는 모델은 0원으로 처리하지 않고 `cost_uncertain=true`로 표시합니다.
- 크레딧은 **성공한 쪽만** 레이아웃별 배율(텍스트 1 · 수식 2 · 표 3 · 그림 5)로 차감합니다. `(job_id, page_no)` 유니크 제약으로 **워커가 재시도해도 이중 차감이 불가능**합니다. 0 차감도 고객 검산용으로 기록합니다.
- **잔여 크레딧 관문**: 크레딧은 결과를 받은 뒤 빠지므로, 동시에 보낸 쪽들은 모두 같은 잔액을 보고 통과합니다. 잔여가 10 미만이면 **기관 전체에서 한 번에 한 쪽만** 보내 초과 사용을 줄이고, 0 이하면 AI에 보내지 않고 `CREDIT_EXHAUSTED`로 막습니다. 충전 후 [`JobResumeService`](src/main/java/com/semojum/backend/domain/job/service/JobResumeService.java)로 막힌 쪽만 이어서 처리합니다.

### 4-4. 문서 처리 — 메모리 768MB 안에서 100MB 파일 여러 개

> [`JobService`](src/main/java/com/semojum/backend/domain/job/service/JobService.java) · [`HwpToPdfConverter`](src/main/java/com/semojum/backend/global/hwp/HwpToPdfConverter.java) · [`HwpxToHtml`](src/main/java/com/semojum/backend/global/hwp/HwpxToHtml.java) · [`PdfPageRenderer`](src/main/java/com/semojum/backend/global/pdf/PdfPageRenderer.java)

- 업로드 원본을 `byte[]`로 힙에 올리면 **100MB 파일 4건 동시 업로드에서 OOM**이 났습니다(실측). 원본을 임시 파일로 옮겨 **경로 기반으로 쪽을 나누고 썸네일을 만들도록** 바꿨고, 임시 파일은 `finally`에서 정리합니다.
- **HWP**: pyhwp → ODT → LibreOffice로 PDF를 만듭니다. 변환기가 머리말 · 꼬리말을 잃어버려서 hwplib로 직접 읽어 다시 넣습니다.
- **HWPX**는 LibreOffice가 열지 못해 본문 XML을 HTML로 풀어 PDF로 만듭니다. 확장자가 아닌 **파일 내용(ZIP 시그니처)** 으로 형식을 구분합니다.
- **스캔 PDF 미리보기**: PDFBox는 JPEG 2000 스캔본을 백지로 그리고, 순수 Java 디코더는 OOM이 났습니다. poppler `pdftoppm`을 **별도 프로세스**로 실행해 JVM과 격리하고, 실패하면 PDFBox로 폴백합니다.
- 서버가 미리 렌더한 JPEG를 내려주어 앱의 원본 패널 표시 시간이 **1.8~2.9초(pdf.js) → 6~9ms(`<img>`)** 로 줄었습니다.

### 4-5. 에디터 저장 — 쪽 단위 부분 성공

> [`JobSaveService`](src/main/java/com/semojum/backend/domain/job/service/JobSaveService.java) · [`PageContentSaver`](src/main/java/com/semojum/backend/domain/result/service/PageContentSaver.java) · [`JobCopyService`](src/main/java/com/semojum/backend/domain/job/service/JobCopyService.java)

- 앱이 「저장」을 누르면 작업 전체의 최종 상태(설정 · 텍스트/점자 패널 · 줄 메모 · 답글)가 한 번에 옵니다. 블록 추가 · 삭제 · 순서 변경은 서버가 판정합니다.
- **쪽마다 `REQUIRES_NEW` 트랜잭션**을 걸어, 한 쪽이 실패해도 나머지는 저장하고 실패한 쪽 번호와 쪽별 에러 코드를 돌려줍니다. 같은 클래스 안에서 호출하면 프록시를 거치지 않아 트랜잭션이 걸리지 않으므로 클래스를 둘로 나눴습니다.
- 블록 id는 앱이 만들어 텍스트 · 점자 패널이 같은 id로 짝을 이룹니다. 같은 요청을 다시 보내도 중복이 생기지 않습니다(멱등).
- 요청 형식 오류는 **DB에 쓰기 전에 400**으로 막고, 올바른 요청에서 난 쪽별 실패만 부분 성공으로 응답합니다.
- 협업은 `.smj` 파일을 주고받는 방식입니다. 다른 사람의 파일을 저장하면 **내 계정에 사본을 만들어 저장**하고, 사본은 통계 · 원가 집계에서 빠지도록 표시합니다.

### 4-6. 인증 · 보안

> [`LoginAttemptService`](src/main/java/com/semojum/backend/domain/auth/service/LoginAttemptService.java) · [`global/jwt`](src/main/java/com/semojum/backend/global/jwt) · [`global/config`](src/main/java/com/semojum/backend/global/config)

- JWT Access(1시간) / Refresh(12시간)와 세션 테이블을 함께 씁니다. 로그인하면 기존 세션을 모두 끊어 **중복 로그인을 막습니다**.
- **로그인 5회 실패 시 10분 잠금**(Redis TTL).
- 운영자 계정을 웹 콘솔용과 앱용으로 나누고, 로그인 요청의 Origin으로 콘솔 로그인인지 판별합니다.
- 기관 계정을 잠그면 즉시 세션을 끊고 **진행 중인 변환도 취소**합니다. 쪽 단위로 차감하므로 완료된 쪽까지만 차감됩니다.
- 열람 범위를 역할별로 제한합니다. 기관 관리자는 파일 내용과 접속 정보를, 점역사는 다른 계정의 개별 사용량을 볼 수 없습니다.

### 4-7. 점자 규정 검색 — 인메모리 검색 + 무중단 갱신

> [`BrailleRuleRegistry`](src/main/java/com/semojum/backend/domain/rule/service/BrailleRuleRegistry.java)

- 점자 규정 340건을 메모리에 올려 검색합니다. 여러 단어는 AND로 묶고(OR이면 "한글 점자 약자"가 239건 중 208건이 걸렸습니다), 점수는 결과를 거르지 않고 순서만 정합니다.
- 소문자로 합친 문자열로 먼저 거른 뒤 걸린 조항만 칸별로 점수를 매겨 **검색 1회 1.5ms → 0.2~0.6ms**로 줄였습니다.
- 조항 번호는 문자열 정렬이면 `1.4.10`이 `1.4.8` 앞에 오므로 **숫자로 비교해 정렬**합니다.
- 규정 데이터는 서버 폴더를 컨테이너에 연결하고 1분마다 변경 여부를 확인해 **재시작 없이 반영**합니다. 파일이 깨졌으면 기존 데이터를 유지하고, 없어도 서버는 뜹니다(규정 검색 때문에 변환이 멈추지 않게).

### 4-8. 인프라 · 배포 — 블루그린 무중단 배포

> [`scripts/deploy.sh`](scripts/deploy.sh) · [`docker-compose.yml`](docker-compose.yml) · [`envoy.yaml`](envoy.yaml) · [`.github/workflows`](.github/workflows)

- `dev` 브랜치 push → GitHub Actions 테스트 게이트 → 비공개 ECR(커밋 해시 태그, 덮어쓰기 금지) → EC2 배포.
- **블루그린**: 쉬고 있는 색으로 새 버전을 띄우고 `/api/health`가 통과해야만(120초) Envoy가 넘겨받습니다. 실패하면 구버전이 계속 서비스합니다. 서버 파일 잠금(`flock`)으로 수동 배포와 CI 배포가 겹치지 않게 했습니다.
- **키 없는 인증**: CI는 GitHub OIDC로 dev 브랜치에서만 배포 역할을 맡고, EC2는 IAM Role로 S3 · ECR에 접근합니다. 저장된 AWS 키가 없습니다.
- SSH 22번은 상시 개방하지 않고 배포 동안만 러너 IP를 열었다가 `if: always()`로 회수합니다. RDS는 private subnet에 두어 VPC 안에서만 닿고, Redis는 서버 밖으로 포트를 열지 않습니다. EC2 메타데이터는 IMDSv2만 허용합니다.
- Envoy는 연결 실패에만 재시도해 **POST 중복 처리를 막고**, SSE 라우트는 타임아웃을 끕니다. 배포로 컨테이너가 바뀌면 로그도 사라지므로 배포 직전에 로그를 보관합니다.

### 4-9. 운영 관측

- 로그에 추적 키를 넣었습니다. 요청은 `req-xxxxxxxx|loginId`, 워커는 `jobId|p{쪽}`이라 **`grep <jobId>` 한 번으로 업로드부터 완료까지 묶어 볼 수 있습니다**.
- 4xx에는 스택을 찍지 않아 `grep -E "WARN|ERROR"` 결과가 곧 장애 목록이 되게 했습니다.
- 쪽 조회에서 요소마다 반복 조회(N+1)하던 것을 쪽 단위 배치 조회로 바꿔 **95개 요소 쪽 응답 266ms(콜드 1.4s)** 문제를 없앴습니다.

<br>

## 5. 패키지 구조

```
com.semojum.backend
├── domain
│   ├── auth      로그인 · 로그아웃 · 토큰 갱신, 로그인 잠금, 세션
│   ├── admin     운영자 API (기관 · 계정 · 단가 · 통계 · 모니터링 · 공지 · 문의)
│   ├── org       기관 관리자 API (소속 계정 · 크레딧)
│   ├── user      계정 기본 설정 · 점역 양식 · 쪽 조회 · 사용량
│   ├── job       작업 생성 · 설정 · 상태 · 저장 · 취소 · 이어서 처리 · SSE
│   │   ├── scheduler   JobDispatcher(공정 큐) · CreditGate · StaleJobScheduler
│   │   └── worker      PageWorker
│   ├── result    AI 결과 저장 · 쪽 내용 저장 · 직렬화
│   ├── billing   단가표 · 원가 계산 · 크레딧 차감 · 쿠폰
│   ├── board     QnA 게시판 (비밀글 · 첨부 · 답변 알림)
│   ├── support   공지 · 문의(IMAP 폴링) · 주문
│   ├── app       앱 버전 공지
│   └── rule      점자 규정 검색 (인메모리)
├── global        config · jwt · exception · grpc · s3 · hwp · pdf · thumbnail · logging · health
└── grpc          proto 생성 클래스
```

<br>

## 6. 로컬 실행

```bash
# Redis
docker run -d -p 6379:6379 redis:8.8

# 필수 환경변수
export DB_HOST=localhost DB_PASSWORD=... JWT_SECRET=... GRPC_CERT_PATH=file:/path/to/server.crt

./gradlew bootRun
```

```bash
./gradlew test   # Testcontainers가 PostgreSQL · Redis를 띄우므로 Docker가 필요합니다
```

- 프로파일을 지정하지 않으면 `local`로 뜹니다. 비밀값은 어떤 설정 파일에도 두지 않고 환경변수로만 받습니다.
- GPU 추론서버와 점자 규정 데이터(비공개)는 이 레포에 포함되지 않습니다. 없어도 서버는 기동되며, 변환 · 규정 검색만 동작하지 않습니다.

<br>

## 7. 공개 버전에서 제거 · 대체한 항목

| 항목 | 처리 |
|---|---|
| 내부 운영 문서(인프라 구성 · 계정 · 주소가 담긴 개발 가이드) | 삭제 |
| 운영 DB · S3에 직접 붙는 스모크 테스트 스크립트 | 삭제 |
| AWS 계정 ID · IAM 역할 ARN | GitHub Secrets 참조로 대체 |
| 서비스 서버 · GPU 추론서버 · 콘솔 IP, 서비스 도메인, S3 버킷 이름 | 예시 값(`example.com`, 문서용 IP 대역)으로 대체 |
| gRPC 테스트용 인증서 · 키 | 공개 버전용으로 새로 만든 자체 서명 인증서로 교체 |
| 커밋 이력 | 포함하지 않음 (원본 저장소의 이력에는 과거 설정이 남아 있어 단일 커밋으로 올림) |
