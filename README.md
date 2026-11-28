# PopSpot 🎪

> 팝업스토어 예약 플랫폼 — 기다림은 줄이고, 방문 경험은 더 확실하게

현장 선착순으로 운영되던 팝업스토어를 **사전 예약 + 실시간 정원 관리** 방식으로 전환한 예약 플랫폼입니다. 예약이 순간적으로 몰려도 정원을 초과하지 않고, 대기열로 공정한 입장 순서를 보장합니다.

- 프론트엔드 배포: https://popspot-gilt.vercel.app
- GitHub: prgrms-be-devcourse/NBE9-11-final-Team07

---

## 기술 스택

### Frontend
![Next.js](https://img.shields.io/badge/Next.js_16-000000?style=for-the-badge&logo=nextdotjs&logoColor=white)
![React](https://img.shields.io/badge/React_19-61DAFB?style=for-the-badge&logo=react&logoColor=black)
![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white)
![Tailwind CSS](https://img.shields.io/badge/Tailwind_CSS-06B6D4?style=for-the-badge&logo=tailwindcss&logoColor=white)

### Backend
![Java](https://img.shields.io/badge/Java_25-007396?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_4.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-6DB33F?style=for-the-badge&logo=spring&logoColor=white)
![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?style=for-the-badge&logo=springsecurity&logoColor=white)
![JWT](https://img.shields.io/badge/JWT-000000?style=for-the-badge&logo=jsonwebtokens&logoColor=white)

### Database / Cache
![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=for-the-badge&logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white)

### Infra / DevOps
![AWS](https://img.shields.io/badge/AWS-232F3E?style=for-the-badge&logo=amazonwebservices&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![Terraform](https://img.shields.io/badge/Terraform-7B42BC?style=for-the-badge&logo=terraform&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=prometheus&logoColor=white)
![Grafana](https://img.shields.io/badge/Grafana-F46800?style=for-the-badge&logo=grafana&logoColor=white)

### Test / External
![JUnit5](https://img.shields.io/badge/JUnit5-25A162?style=for-the-badge&logo=junit5&logoColor=white)
![k6](https://img.shields.io/badge/k6-7D64FF?style=for-the-badge&logo=k6&logoColor=white)
![Toss Payments](https://img.shields.io/badge/Toss_Payments-0064FF?style=for-the-badge&logo=tosser&logoColor=white)

---

## 시스템 아키텍처

<img width="955" height="646" alt="PopSpot 시스템 아키텍처" src="https://github.com/user-attachments/assets/72642fa3-c7ab-4fa2-9760-dcc5c29a560b" />

User → Frontend(Next.js) → Backend(Spring Boot) 구조이며, 데이터 계층은 MySQL·Redis·S3, 외부 연동은 Google OAuth·Toss Payments를 사용합니다. 인프라는 Terraform으로 AWS(EC2/RDS/S3)를 프로비저닝하고, GitHub Actions → GHCR → EC2 Blue-Green으로 배포합니다.

---

## 팀 구성

| 이름 | 담당 |
|------|------|
| 김지오 | 대기열 진입·복구·정리 스케줄러, 대기열 CircuitBreaker 복구, 주최자 굿즈 API |
| 강경서 | OAuth 인증, 팝업 주최자 API, Redis 단일 카운터·CircuitBreaker |
| 정종욱 | 굿즈 주문·환불 API, 재고 동시성, EC2 Blue/Green·GitHub Actions 배포 |
| 김동호 | 예약 API·동시성, 취소표 재오픈 정책, Redis 정원 차감, 대기열 설계·최적화 |
| 정준용 | 결제 API 연동·승인/취소, 결제-주문 정합성, 쿠폰 발급·검증 |

---

## 담당: 결제 (정준용)

토스페이먼츠 승인·취소를 연동하고, 동시 요청·PG 응답 지연·스케줄러 다중 실행 속에서도 결제 상태와 예약·굿즈 주문이 어긋나지 않게 했습니다.

```text
승인   READY -> CONFIRMING -> PAID
       CONFIRMING -> READY                  (토스가 명확히 거절, 4xx)
보상   CONFIRMING -> COMPENSATING -> CANCELED  (승인됐지만 예약·주문을 확정할 수 없음)
       COMPENSATING -> COMPENSATION_FAILED -> COMPENSATING  (60초마다 재시도, 생성 후 24시간까지)
취소   PAID -> CANCELING -> CANCELED
       CANCELING -> CANCEL_FAILED -> CANCELING  (같은 멱등키로 재시도, 생성 후 24시간까지)
```

### 정합성 설계

| 위험 | 대응 |
|------|------|
| 같은 주문의 승인 요청이 동시에 들어와 토스 승인을 중복 호출 | `READY → CONFIRMING`을 조건부 UPDATE(`beginConfirmationIfReady`)로 선점. 진 요청은 `FOR SHARE`로 최신 상태를 다시 읽어 409(처리 중)로 응답 |
| 서로 다른 멱등키의 동시 취소로 데드락·PG 중복 취소 | `PAID`/`CANCEL_FAILED → CANCELING`을 조건부 UPDATE(`beginCancelIfCancelable`)로 선점. 진 요청은 `PAYMENT_CANCEL_ALREADY_REQUESTED`로 거부 |
| 한 예약·굿즈 주문에 활성 결제가 여러 건 생성 | `uk_payment_active_reservation`, `uk_payment_active_goods_order` 유니크 제약. 취소 완료 시 활성 키를 비워 재결제 허용 |
| 네트워크 재시도로 같은 요청이 반복 | 결제 생성·승인·취소·보상 취소에 멱등성 키를 쓰고, 토스 취소 재시도도 같은 키로 호출 |
| 승인 결과가 불확실한 채로 `CONFIRMING`에 고정 | 90초가 지난 결제를 스케줄러가 토스 결제 조회로 확인해 `PAID`로 확정하거나 `READY`로 되돌림 |
| 승인 완료 처리 중 오래된 스냅샷으로 상태를 덮어씀(lost update) | `complete()`에서 결제를 `FOR UPDATE`로 조회해 같은 결제의 완료 처리를 직렬화 |
| 토스가 응답하지 않아 호출 스레드(스케줄러 포함)가 무기한 대기 | 연결 5초, 응답 대기 60초 타임아웃. 60초는 토스 권장값이며 복구 기준 90초보다 짧음 |
| 토스가 계속 거절하는 환불을 끝없이 재시도 | 실패 환불은 생성 후 24시간까지만 재시도. 같은 멱등키로 재시도하므로 토스 멱등키 유효기간(15일)보다 짧게 잡음. 기간이 지난 건은 `COMPENSATION_FAILED`/`CANCEL_FAILED`로 남아 수동 처리 |
| 취소 완료 후 늦게 도착한 다른 취소 요청의 PG 실패가 결제를 `CANCEL_FAILED`로 되돌림 | 취소 실패 처리는 결제가 `CANCELING`일 때만 조건부 UPDATE(`failCancelIfCanceling`)로 반영. 이미 `CANCELED`면 해당 환불 기록만 실패로 남김 |
| 토스 승인 중에 예약 점유(5분)가 만료돼 결제 후 환불됨 | 점유 마감은 승인 시작 시점에 판정. `READY → CONFIRMING` 선점과 같은 트랜잭션에서 점유를 최소 90초 뒤까지 연장(`extendHeldUntil`). 90초는 응답 대기 60초보다 길고 복구 기준과 같음 |

### 스케줄러 다중 인스턴스 안전성

`PaymentCompensationScheduler`는 분산 락 없이 60초마다 세 작업을 실행합니다. 작업마다 행 단위로 직렬화되는지 통합 테스트로 확인했고, 그래서 ShedLock을 걸지 않았습니다.

| 작업 | 직렬화 방식 |
|------|------|
| 보상 취소 재시도 | 실패한 환불을 `FOR UPDATE`로 조회하고 `FAILED → REQUESTED`로 바꿔, 다른 인스턴스는 같은 건을 집지 못함 |
| 일반 취소 재시도 | 위와 같음 |
| 승인 복구 | 되돌림은 조건부 UPDATE로 한 번만 적용. 확정은 `complete()`의 `FOR UPDATE`로 직렬화 |

### 코드 위치

| 파일 | 역할 |
|------|------|
| `PaymentService` | 토스 호출과 트랜잭션 경계 조율, 승인 복구·보상 취소 흐름 |
| `PaymentTransactionService` | 상태 선점·검증·완료 처리 (DB 트랜잭션 단위) |
| `PaymentRepository` | 조건부 UPDATE, 잠금 읽기 쿼리 |
| `ReservationRepository.extendHeldUntil` | 승인 시작 시 예약 점유 연장 (조건부 UPDATE) |
| `PaymentRepository.failCancelIfCanceling` | 취소 실패를 `CANCELING`인 결제에만 반영 (조건부 UPDATE) |
| `PaymentCompensationScheduler` | 보상 취소·일반 취소 재시도, 승인 복구 |
| `TossPaymentsClient`, `TossPaymentsConfig` | 토스 API 호출, 타임아웃 설정 |

### 테스트

- 단위 테스트: `PaymentServiceTest` 18개, `PaymentTransactionServiceTest` 28개, `PaymentControllerTest` 4개, `TossPaymentsConfigTest` 3개
- 통합 테스트(Testcontainers MySQL·Redis): `PaymentConfirmConcurrencyTest` 1개, `PaymentCancelConcurrencyTest` 2개, `PaymentSchedulerConcurrencyTest` 4개, `PaymentRetryWindowTest` 2개, `PaymentConfirmHoldExpirationTest` 3개, `PaymentCancelFailureRaceTest` 1개
  - 실제 동시 요청으로 데드락·PG 중복 호출·lost update가 없는지 확인합니다. 통합 테스트로 찾은 결함 2건(동시 승인 에러 코드, 승인 복구 lost update)은 수정 전 코드에서 실패하는 것을 확인한 뒤 고쳤습니다.
  - 재시도 기간 상한과 승인 중 점유 만료도 실제 DB에서 확인합니다. 승인 중 점유 만료와 늦은 취소 실패 테스트는 수정 전 코드에서 실패하는 것을 확인했습니다.

```bash
cd backend
./gradlew test --tests "com.back.popspot.domain.payment.*"
```

---

## 주요 기능

- **회차별 사전 예약** — 방문 가능한 시간과 잔여 정원을 확인하고 원하는 회차를 예약
- **대기열·정원 관리** — 예약이 집중되면 대기 순번 부여, 잔여 정원을 원자적으로 차감
- **예약·결제 통합** — 예약·결제·취소·팝업/굿즈/쿠폰 관리를 하나의 서비스에서 제공
- **개최자 기능** — 팝업 등록/수정/삭제(유료·무료), 예약 슬롯·굿즈·쿠폰 관리

---

## 핵심 기술 포인트

### Redis 단일 카운터로 원자적 정원 차감
잔여 정원을 `DECR` 한 번으로 원자적 차감해, 동시 예약에도 정원을 초과하지 않습니다. Lua Script 대비 임계 구역이 짧아 고부하에서 응답 시간이 유리합니다. (1,000 req/s p95: 단일 카운터 36ms vs Lua 187ms)

### Redis + DB 정합성 전략
Redis는 실시간 게이트, DB는 최종 원장으로 역할을 나눕니다.

- **예약** — Redis로 잔여 정원을 원자적 차감(실시간 판단), DB에 예약 상태(HELD/CONFIRMED)를 기록. **실패 시 오버셀 대신 과소판매 방향으로 깨지도록** 설계해, 장애가 나도 정원을 초과 판매하지 않고 Redis 복구로 자리를 다시 엽니다.
- **대기열** — Redis로 진입 순번(seq/score)을 빠르게 관리, DB에 대기 상태(WAITING/ADMITTED)를 기록. 진입 시 WAITING 저장 → Polling으로 순번 조회 → 스케줄러가 선착순 입장 처리(ADMITTED).

### 대기열 기반 입장 제어
Redis Sorted Set으로 진입 순서(score)를 관리하고, 허용된 사용자만 예약 API로 전달해 DB 커넥션 풀을 보호합니다. 대기열 적용 시 예약 API p95가 크게 개선됩니다. (Baseline 2.63s → Gated 250ms)

### 장애 대응
CircuitBreaker로 Redis 장애 확산 방지. 대기열은 서버 재기동 시 자동 rebuild(ShedLock 단일 인스턴스 + 완료 전 enqueue 503 차단), 정원은 DB 확정 예약 기준 수동 복구.

### 결제-예약 정합성
`READY → CONFIRMING → PAID` 상태 전이와 보상 취소, 멱등성 키 검증으로 중복 승인 및 결제-예약 불일치를 방지합니다. 자세한 설계는 [담당: 결제](#담당-결제-정준용)에 정리했습니다.

---

## 테스트 & 배포

- **테스트** — JUnit 5 + Mockito 단위 테스트(결제·쿠폰 등 도메인 서비스), Testcontainers 통합 테스트(대기열 복구, 결제 동시 승인·취소·스케줄러 다중 실행), k6 부하 테스트(대기열·예약, `k6/`)
- **CI/CD** — GitHub Actions: 자동 테스트 → Docker 빌드 → GHCR → EC2 Blue-Green 배포
- **모니터링** — Spring Actuator → Prometheus → Grafana

---

## 프로젝트 종료 후 개인 보강

원본 팀 저장소([prgrms-be-devcourse/NBE9-11-final-Team07](https://github.com/prgrms-be-devcourse/NBE9-11-final-Team07))에서 프로젝트를 마친 뒤, 이 저장소에서 결제 도메인 정합성을 추가로 보강했습니다. 아래 내용은 원본 팀 저장소에는 없습니다.

| 시기 | 문제 | 해결 |
|------|------|------|
| 2026.10 | 같은 결제에 서로 다른 멱등키로 취소 요청이 동시에 들어오면 환불 INSERT와 결제 UPDATE가 경합해 데드락(500)이 나고, PG 취소가 중복 호출될 수 있음 | `PAID`/`CANCEL_FAILED → CANCELING`을 조건부 UPDATE로 선점하고, 선점에 실패한 요청은 `PAYMENT_CANCEL_ALREADY_REQUESTED`로 거부 |
| 2026.10 | `READY → CONFIRMING` 전환 후 토스 승인이 실패하면 결제가 `CONFIRMING`에 고정돼 재시도가 모두 409로 막힘 | 명확한 거절(4xx)은 `READY`로 되돌리고, 타임아웃처럼 결과가 불확실한 결제는 스케줄러가 토스 결제 조회로 실제 상태를 확인해 복구 |
| 2026.10 | 이미 만료돼 재고가 반환된 굿즈 주문도 결제 승인되어 `PAID`로 확정될 수 있음 | 승인 준비·완료 시점에 굿즈 주문 상태와 만료 시각을 검증하고, 승인 후 확정할 수 없으면 보상 취소 |
| 2026.11 | 위 동시성 수정이 Mockito 단위 테스트로만 검증됨. 실제 MySQL에서 돌려 보니, 동시 승인에서 진 요청이 REPEATABLE READ 스냅샷의 `READY`를 읽어 409 대신 400을 응답 | Testcontainers 동시 승인·취소 통합 테스트 추가. 선점에 진 요청은 `FOR SHARE`로 최신 상태를 다시 읽도록 수정 |
| 2026.11 | 승인 복구 스케줄러가 두 인스턴스에서 같은 결제를 DONE으로 복구하는 사이 사용자 취소가 커밋되면, 늦은 쪽이 스냅샷의 `CONFIRMING`을 보고 `CANCELING`을 `PAID`로 덮어씀(lost update) | `complete()`에서 결제를 `FOR UPDATE`로 조회해 직렬화. 수정 전 코드에서 실패하고 수정 후 통과하는 회귀 테스트 추가 |
| 2026.11 | 토스 API 호출에 타임아웃이 없어 응답이 없으면 스케줄러 스레드(기본 1개)가 멈추고, 같은 스레드를 쓰는 대기열 스케줄러도 함께 멈춤 | 토스 전용 클라이언트에 연결 5초·응답 대기 60초 타임아웃 지정. 타임아웃 시 기존 복구 경로(승인 복구, 취소 재시도)로 처리 |
| 2026.11 | 실패한 환불을 60초마다 상한 없이 재시도해, 계속 거절되는 건이 PG를 끝없이 호출하고 오래된 실패 건이 재시도 대상 20건을 차지할 수 있음 | 생성 후 24시간 안의 실패 환불만 재시도. 스키마 변경 없이 기존 `created_at`(첫 요청 시각)으로 판단 |
| 2026.11 | 점유 마감 직전에 승인을 시작하면 토스 승인 도중 예약 만료 스케줄러가 예약을 만료시켜, 결제 후 보상 취소(환불)로 이어짐 | 승인 시작 시 점유를 최소 90초 뒤까지 연장. 연장 전에 이미 만료됐으면 토스를 호출하지 않고 거부. 수정 전 코드에서 실패하는 통합 테스트 추가 |
| 2026.11 | 실패한 취소를 같은 멱등키로 재시도하는 사이 새 멱등키 취소가 먼저 환불을 끝내면, 늦게 도착한 재시도의 PG 실패가 `CANCELED` 결제를 `CANCEL_FAILED`로 되돌림. 환불은 됐는데 취소 실패로 보이고 스케줄러가 24시간 동안 재시도 | 취소 실패 처리는 결제가 `CANCELING`일 때만 조건부 UPDATE로 반영. 수정 전 코드에서 실패하는 두 스레드 통합 테스트 추가 |

관련 변경은 아래 PR에 있습니다.

- [PR #1](https://github.com/sat7312/popspot/pull/1) — 동시 취소 데드락, `CONFIRMING` 고착, 만료 주문 승인 (단위 테스트)
- [PR #2](https://github.com/sat7312/popspot/pull/2) — 동시 승인·취소 통합 테스트, 동시 승인 에러 코드 수정
- [PR #3](https://github.com/sat7312/popspot/pull/3), [PR #4](https://github.com/sat7312/popspot/pull/4) — 스케줄러 다중 인스턴스 통합 테스트, 승인 복구 lost update 수정과 회귀 테스트
- [PR #5](https://github.com/sat7312/popspot/pull/5) — 토스 API 타임아웃
- [PR #7](https://github.com/sat7312/popspot/pull/7) — 실패 환불 재시도 기간 상한(24시간)
- [PR #8](https://github.com/sat7312/popspot/pull/8) — 결제 승인 중 예약 점유 만료 방지
- [PR #10](https://github.com/sat7312/popspot/pull/10) — 늦게 도착한 취소 실패가 취소 완료 결제를 되돌리는 문제 수정

---

## 협업 방식

- 작업 관리: Notion·WBS / 코드 통합: PR + 리뷰 후 `develop` 병합
- 브랜치: `feat/`·`fix/`·`refactor/` 접두어 / 컨벤션: 네이버 Java 코딩 컨벤션
