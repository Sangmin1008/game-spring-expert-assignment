# Webcraft — 멀티플레이 샌드박스 게임 서버

Spring Boot + WebSocket 기반의 실시간 멀티플레이 게임 서버입니다.
여러 플레이어가 같은 월드에 접속해 이동하고, 채팅하고, 서로의 접속 상태를 확인할 수 있습니다.

브라우저에서 `http://localhost:8080` 으로 접속해 플레이할 수 있습니다.

---

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 언어 / 런타임 | Java 21 |
| 프레임워크 | Spring Boot 4.1.0 (WebMVC, WebSocket, Data JPA, Validation) |
| 영속 저장소 | MySQL 8.0 |
| 인메모리 저장소 | Redis 7 |
| 빌드 | Gradle |
| 테스트 | JUnit 5, Mockito, AssertJ, Testcontainers, H2 |
| 인프라 | Docker Compose |

---

## 실행 방법

### 1. 사전 준비

- JDK 21
- Docker Desktop (실행 중이어야 합니다)

### 2. MySQL · Redis 실행

프로젝트 루트에서:

```bash
docker compose up -d
```

컨테이너 두 개가 뜹니다.

| 서비스 | 컨테이너 | 호스트 포트 |
|---|---|---|
| MySQL 8.0 | `game-expert-mysql` | **3307** |
| Redis 7 | `game-expert-redis` | 6379 |

> MySQL 호스트 포트가 3307인 이유
> 로컬에 별도 설치된 MySQL 서비스가 3306을 점유하고 있어 충돌을 피하려 3307로 매핑했습니다.
> 컨테이너 내부 포트는 표준 3306 그대로입니다.

상태 확인:

```bash
docker compose ps
```

### 3. 애플리케이션 실행

```bash
./gradlew bootRun
```

접속 설정은 [`src/main/resources/application.properties`](src/main/resources/application.properties)에 있으며,
`spring.jpa.hibernate.ddl-auto=update` 로 실행 시 스키마가 자동 생성·갱신됩니다.

### 4. 테스트 실행

```bash
./gradlew test
```

Redis 연동 테스트(`PresenceServiceTest`, `RecentChatCacheTest`)는 Testcontainers로
**실제 Redis 컨테이너를 띄워서** 검증하므로 Docker Desktop이 실행 중이어야 합니다.
나머지 테스트는 H2 인메모리 DB와 Mockito만 사용해 Docker 없이 동작합니다.

---

## REST API

| Method | Path | 설명 | 성공 응답 |
|---|---|---|---|
| `POST` | `/players` | 플레이어 등록 | `201 Created` (본문 없음) |
| `GET` | `/worlds` | 월드 목록 조회 | `200 OK` |
| `POST` | `/worlds` | 월드 생성 (최대 3개) | `201 Created` |
| `DELETE` | `/worlds/{id}` | 월드 삭제 | `204 No Content` |
| `GET` | `/worlds/{worldId}/chats?limit=50` | 최근 채팅 조회 | `200 OK` |
| `GET` | `/worlds/{worldId}/chats/history` | 과거 채팅 커서 조회 | `200 OK` |

### 에러 응답 형식

모든 예외는 `GlobalExceptionHandler`에서 아래 형태로 변환됩니다.

```json
{ "error": "DUPLICATE_NICKNAME" }
```

| 에러 코드 | 상태 | 발생 상황 |
|---|---|---|
| `VALIDATION_FAILED` | 400 | 요청 값이 검증 규칙에 어긋남 |
| `WORLD_NOT_FOUND` | 404 | 존재하지 않는 월드 |
| `DUPLICATE_NICKNAME` | 409 | 이미 사용 중인 닉네임 |
| `WORLD_LIMIT_REACHED` | 409 | 기본 월드가 이미 3개 |
| `NOT_WORLD_OWNER` | 403 | 월드 소유자가 아님 |

### 닉네임 규칙

2~12글자, 영문 대소문자 / 숫자 / 밑줄만 허용 (`^[a-zA-Z0-9_]+$`).

---

## WebSocket

```
ws://localhost:8080/ws/worlds/{worldId}?nickname={nickname}
```

핸드셰이크 시점에 `NicknameHandshakeInterceptor`가 플레이어와 월드의 존재를 확인하고,
닉네임·월드 ID를 세션 속성에 저장합니다. 이후 모든 메시지는 **메시지 본문이 아니라 이 세션 속성**을
신원의 근거로 사용합니다.

| 연결 실패 코드 | 의미 |
|---|---|
| `4000` | 등록되지 않은 닉네임 |
| `4001` | 존재하지 않는 월드 |

### 메시지 타입

| 요청 `type` | 처리 핸들러 | 응답 | 수신 대상 |
|---|---|---|---|
| `ping` | `PingWsHandler` | `pong` | 요청자 |
| `move` | `MoveWsHandler` | (엔진 큐로 전달) | — |
| `chat` | `ChatWsHandler` | `chat` | 같은 월드 전원 |
| `onlineUsers` | `OnlineUsersWsHandler` | `onlineUsers` | 요청자 |

`MessageRouter`가 `type` 값으로 핸들러를 선택하고, 핸들러에서 발생한 예외를
`QUEUE_FULL` / `INVALID_MESSAGE` / `INTERNAL_ERROR` 로 변환해 연결을 유지한 채 클라이언트에 알립니다.

---

## 구현 범위

### 필수 기능

| Lv | 내용 | 주요 구현 위치 |
|---|---|---|
| 1 | Docker MySQL · Redis 환경 구성 | `docker-compose.yml`, `application.properties` |
| 2 | JPA 복합 인덱스 선언 | `chat/entity/ChatMessage` |
| 3 | 플레이어 등록과 요청 검증 | `player/` |
| 4 | 월드 생성 (최대 3개, 동시성 제어) | `world/service/WorldService` |
| 5 | 채팅 저장과 최근 내역 조회 | `chat/service/ChatService` |
| 6 | 최근 채팅 조회 API | `chat/controller/WorldChatController` |
| 7 | WebSocket 핸드셰이크 사용자 식별 | `ws/NicknameHandshakeInterceptor` |
| 8 | 인터셉터 등록 | `config/WebSocketConfig` |
| 9 | 월드별 세션 레지스트리 | `ws/WorldSessionRegistry` |
| 10 | Redis 접속 상태 관리 | `presence/PresenceService` |
| 11 | 메시지 라우팅과 ping/pong | `ws/MessageRouter`, `ws/handler/PingWsHandler` |
| 12 | 플레이어 이동 처리 | `ws/handler/MoveWsHandler` |
| 13 | 채팅 요청 처리와 응답 구성 | `ws/handler/ChatWsHandler` |
| 14 | 같은 월드 참여자에게 채팅 전송 | `chat/service/LocalChatSender` |
| 15 | 접속자 목록 조회 | `ws/handler/OnlineUsersWsHandler` |

### 도전 기능

| Lv | 내용 | 상태 |
|---|---|---|
| 16 | 낙관적 락 (`@Version`) | 완료 |
| 17 | 커서 페이지 조회 | 완료 |
| 18 | Redis 최근 채팅 캐시 | 완료 |
| 19 | Redis Lua 채팅 횟수 제한 | 미진행 |
| 20 | 멀티 서버 (Pub/Sub) | 미진행 |

> Lv 19는 현재 조회 → 판단 → 증가를 세 번의 Redis 왕복으로 처리하는 뼈대 구현이 남아 있어,
> 동시 요청 시 제한을 초과할 수 있습니다. Lua 스크립트로 원자화하는 작업이 남아 있습니다.
> Lv 20의 `ChatRelay` / `ChatSubscriptionConfig` 역시 TODO 상태이며,
> `webcraft.chat.pubsub-enabled` 속성이 `false`일 때는 단일 서버 경로(`LocalChatSender`)로 동작합니다.

---

## 설계 포인트

### 신원은 연결에서만 가져옵니다

`move`, `chat`, `onlineUsers` 메시지에는 `nickname`이나 `worldId` 필드가 실려 올 수 있지만
서버는 이를 무시합니다. 신뢰할 수 있는 값은 핸드셰이크 때 DB로 검증해 세션에 심어둔 값뿐이고,
메시지 본문을 믿으면 누구나 남의 캐릭터를 조종하거나 남의 이름으로 채팅할 수 있기 때문입니다.

### 접속 상태는 만료 시각을 점수로 갖는 Sorted Set

`world:{worldId}:presence` 키에 `connectionId`를 member로, **만료 시각(현재+90초)** 을 score로 저장합니다.
브라우저 강제 종료처럼 정상 종료 신호가 오지 않는 경우에도, 조회 시점에 만료된 원소를
`removeRangeByScore`로 정리해 유령 접속자가 남지 않습니다. 살아 있는 클라이언트는 `ping`으로
score를 갱신하고, 월드가 완전히 비면 키 자체가 180초 TTL로 사라집니다.

### 중복 닉네임은 두 겹으로 막습니다

`existsByNickname` 사전 확인으로 대부분을 거르고, 그 사이에 동시 요청이 끼어들어
DB unique 제약에 걸리는 경우는 `DataIntegrityViolationException`을 잡아 같은 에러 코드로 변환합니다.
사전 확인만 있으면 동시성에 뚫리고, 사후 처리만 있으면 불필요한 저장 시도가 발생합니다.

### 월드 생성은 잠금 안에서 개수를 셉니다

`worldOperations.duringCreation()`이 제공하는 생성 잠금 **안에서** 개수 확인과 저장을 함께 수행합니다.
잠금 밖에서 개수를 세면 동시 요청이 같은 숫자를 읽고 모두 통과해 제한을 넘길 수 있습니다.

### 커서 페이징은 `limit + 1`을 조회합니다

`COUNT` 쿼리 없이 다음 페이지 존재 여부를 판단하기 위해 한 건을 더 조회하고,
응답에는 `limit`개만 담습니다. 커서는 **여분의 행이 아니라 실제로 반환한 마지막 항목**에서 뽑아야
다음 페이지에서 한 건이 누락되지 않습니다. 생성 시각이 같은 메시지를 구분하기 위해
`(createdAt, id)` 쌍을 커서로 사용하며, 이는 Lv 2에서 만든 복합 인덱스 순서와 일치합니다.

### 캐시는 보조 저장소입니다

`RecentChatCache`의 모든 메서드는 `RuntimeException`을 삼킵니다. Redis에 장애가 생겨도
`read()`가 `null`을 반환하면 호출자가 DB를 조회하므로 서비스는 계속 동작합니다.
읽기는 TTL을 연장하지 않아, 5초마다 최신 데이터로 갱신되는 것이 보장됩니다.

---

## 프로젝트 구조

```
src/main/java/com/gameexpert/
├── chat/          채팅 (엔티티, 저장·조회 서비스, 캐시, 커서 페이징, 릴레이)
├── common/        공통 예외 처리와 에러 응답
├── config/        WebSocket 설정
├── player/        플레이어 등록
├── presence/      Redis 기반 접속 상태
├── trial/         시련 스포너 상태 (낙관적 락)
├── world/         월드 생성·조회·삭제
└── ws/            WebSocket 인터셉터, 라우터, 세션 레지스트리, 메시지 핸들러
```
