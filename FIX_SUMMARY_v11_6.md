# TradeRakshak v11.6 Build Fix Summary

This package contains the revised source code after resolving the build issues found in the TradeRakshak Java + Angular application.

## Frontend Fixes

### 1. Fixed Angular import paths

Several nested standalone components were importing services and pipes using paths that were one directory too shallow. These were corrected so Angular can resolve them during production builds.

Updated files:

- `frontend/src/app/features/auth/login/login.component.ts`
- `frontend/src/app/features/auth/register/register.component.ts`
- `frontend/src/app/features/strategy-builder/advanced-builder/advanced-builder.component.ts`
- `frontend/src/app/features/strategy-builder/ai-builder/ai-builder.component.ts`
- `frontend/src/app/features/strategy-builder/backtesting/backtesting.component.ts`
- `frontend/src/app/features/teaching/courses/courses.component.ts`
- `frontend/src/app/features/teaching/leaderboard/leaderboard.component.ts`
- `frontend/src/app/features/teaching/quiz/quiz.component.ts`

### 2. Fixed malformed leaderboard component syntax

The teaching leaderboard component had an invalid constructor body:

```ts
constructor() {{} 
```

It was corrected to:

```ts
constructor() {}
```

Updated file:

- `frontend/src/app/features/teaching/leaderboard/leaderboard.component.ts`

### 3. Fixed API service generic typing

Angular strict compilation treated untyped API responses as `unknown`, which broke code that reads `response.data`.

The shared API helper now defaults generic response types to `any` when callers do not specify a type.

Updated file:

- `frontend/src/app/core/services/api.service.ts`

### 4. Fixed internal service imports

Core services were importing `ApiService` through a longer path that pointed back through `core/services`. These imports were simplified to local service imports.

Updated files:

- `frontend/src/app/core/services/strategy-builder.service.ts`
- `frontend/src/app/core/services/teaching.service.ts`
- `frontend/src/app/core/services/user-hierarchy.service.ts`

### 5. Fixed production build failure caused by online font inlining

Angular production optimization was trying to download Google Fonts at build time. This fails in restricted or offline environments.

Production build optimization now keeps scripts and styles optimized while disabling only external font inlining.

Updated file:

- `frontend/angular.json`

## Backend Fixes

### 1. Fixed Java version mismatch

The project was configured for Java 21, but the available installed compiler was Java 17. Spring Boot 3.3 supports Java 17, and no Java 21-only source features were found.

The Maven compiler configuration now uses Java 17 through the shared `java.version` property.

Updated file:

- `backend/pom.xml`

### 2. Fixed Apache POI DateUtil shadowing

`StockTrackerService` uses the app's own `com.traderakshak.util.DateUtil`, which shadowed Apache POI's `DateUtil`.

The Excel date-cell check now fully qualifies the Apache POI class:

```java
org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)
```

Updated file:

- `backend/src/main/java/com/traderakshak/service/StockTrackerService.java`

### 3. Fixed Windows Maven wrapper

The Windows Maven wrapper script was malformed and printed command errors instead of running Maven. It was replaced with a compact plain-ASCII wrapper that delegates to the bundled Maven installation.

Updated file:

- `backend/mvnw.cmd`

### 4. Fixed Windows installer startup parsing error

The installer generated `START.bat` inside a parenthesized batch block. The text `TradeRakshak(tm)` contained a closing parenthesis, so Windows stopped with:

```bat
v11.5 was unexpected at this time.
```

The display text was changed to `TradeRakshak TM` in the installer and start/stop scripts so batch parsing no longer breaks.

Updated files:

- `INSTALL.bat`
- `START.bat`
- `STOP.bat`

### 5. Fixed invalid Spring Boot port configuration

The application failed during startup because `application.yml` escaped the server port placeholder:

```yaml
server:
  port: \${PORT:8080}
```

Spring interpreted that as `\8080`, which is not a valid integer port. It was corrected to:

```yaml
server:
  port: ${PORT:8080}
```

Updated file:

- `backend/src/main/resources/application.yml`

### 6. Fixed SQLite migration syntax

Several Flyway migrations used:

```sql
ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...
```

SQLite does not support `IF NOT EXISTS` with `ALTER TABLE ... ADD COLUMN`, so the app failed while creating the local database. The migrations now use SQLite-compatible `ADD COLUMN` statements.

SQLite also cannot add a `UNIQUE` column through `ALTER TABLE`, so the `users.referral_code` and `users.api_key` uniqueness rules were moved to unique indexes.

V7 also tried to add `backtests.initial_capital` even though that column already exists in the initial schema. That duplicate column addition was removed.

V9 also extended the older `agent_commissions` table before creating indexes and enterprise queries against newer columns such as `agent_user_id` and `total_commission`.

V10 now extends `system_settings` with `created_at` before inserting timestamped platform settings.

V10 also upgrades the older `competitions` table with enterprise seed columns such as `competition_type`, `scoring_engine`, `entry_capital`, and `prize_pool`.

V12 now seeds index rows into `stock_master.company_name`, matching the original stock schema.

Hibernate local startup now uses `ddl-auto: none` because Flyway owns the schema and SQLite metadata validation fails on this large schema with `too many terms in compound SELECT`.

The web app now serves from `http://localhost:8080/` while REST controllers are prefixed with `/api` through Spring MVC path matching. This avoids Tomcat 404 at the root URL and keeps Angular API calls such as `/api/auth/login` working.

`STOP.bat` now stops the Java process listening on the configured app port instead of only printing a message, and the installer template generates the same corrected stop script.

JWT signing now derives a 512-bit HS512 key from the configured secret, preventing login from failing with a generic support error when the local default secret is shorter than the HS512 raw-key requirement.

Audit logging now uses the active login transaction in SQLite instead of opening a second write transaction. This prevents `database is locked` from surfacing as the generic "Something went wrong. Please contact support." message during successful login.

Updated files:

- `backend/src/main/resources/db/migration/V3__user_hierarchy.sql`
- `backend/src/main/resources/db/migration/V5__teaching_lms.sql`
- `backend/src/main/resources/db/migration/V7__strategy_builder.sql`
- `backend/src/main/resources/db/migration/V9__enterprise_enhancements.sql`

## Verification Completed

The following checks were run successfully after the fixes:

```bat
cd frontend
npm.cmd run build
```

```bat
cd backend
.\mvnw.cmd -DskipTests package
```

Result:

- Frontend production bundle builds successfully.
- Backend Spring Boot package builds successfully.
- Final JAR was generated at `backend/target/traderakshak-backend-11.5.0.jar`.

Note: Angular still reports one non-blocking style budget warning in `stock-tracker.component.ts`. It does not stop the build.
