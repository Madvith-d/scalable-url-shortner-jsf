# Shortify --- Project Development Phases

## 1. Project Definition

**Project:** Shortify --- A Scalable URL Shortening and Analytics
Platform

**Primary stack** - Backend: Java + Spring Boot - Frontend: Next.js -
Database: PostgreSQL running in Docker - ORM: Spring Data JPA /
Hibernate - Cache and rate limiting: Redis - Authentication: Spring
Security + JWT - Build: Maven - API style: REST - Development
environment: Docker Compose

**Core engineering principle**

Build the project as a modular monolith first. Do not introduce
microservices, Kafka, Kubernetes, cloud infrastructure, or other
architectural complexity unless explicitly approved.

The implementation order is:

``` text
Foundation
    ↓
URL Shortening Core
    ↓
Authentication + URL Management
    ↓
Redis + Reliability + Analytics
    ↓
Next.js Frontend + Integration + Testing
```

Each phase must be completed and verified before starting the next
phase.

------------------------------------------------------------------------

# Phase 1 --- Backend and Database Foundation

## Objective

Create a working Spring Boot backend connected to PostgreSQL in Docker,
with a clean layered architecture and the initial URL data model.

## Tasks

### 1.1 Initialize the repository

Create:

``` text
shortify/
├── backend/
├── frontend/
├── docker-compose.yml
├── README.md
├── phases.md
└── scope.md
```

Initialize Git and keep backend and frontend independently runnable.

### 1.2 Create the Spring Boot application

Use:

-   Java 21+
-   Spring Boot
-   Maven
-   Spring Web
-   Spring Data JPA
-   PostgreSQL Driver
-   Validation
-   Lombok if useful

Use a package structure similar to:

``` text
com.shortify
├── controller
├── service
├── repository
├── entity
├── dto
├── exception
└── config
```

### 1.3 Configure PostgreSQL with Docker

Create a Docker Compose service for PostgreSQL.

The database must have:

``` text
database: shortify
user: shortify
```

Persist PostgreSQL data using a Docker volume.

The application must connect to PostgreSQL through environment-based
configuration rather than hard-coded production secrets.

### 1.4 Create the initial URL entity

Initial `ShortUrl` fields:

``` text
id
shortCode
originalUrl
createdAt
expiresAt
active
```

Constraints:

-   `shortCode` must be unique.
-   `originalUrl` must not be null.
-   `shortCode` must not be null.
-   Expiration is optional.
-   Active status must be explicit.

### 1.5 Create repository and service layers

Create:

``` text
ShortUrlRepository
ShortUrlService
```

The controller must not contain business logic.

Expected flow:

``` text
Controller
    ↓
Service
    ↓
Repository
    ↓
PostgreSQL
```

## Phase 1 Acceptance Criteria

Phase 1 is complete only when:

-   Spring Boot starts successfully.
-   PostgreSQL runs through Docker Compose.
-   Spring Boot connects successfully to PostgreSQL.
-   `ShortUrl` is persisted and retrieved through JPA.
-   Repository, service, and controller responsibilities are separated.
-   The project builds successfully with Maven.

Do not implement Redis, JWT, analytics, or the Next.js dashboard before
these criteria are satisfied.

------------------------------------------------------------------------

# Phase 2 --- Core URL Shortening and Redirect System

## Objective

Implement the minimum useful URL-shortening product.

## Tasks

### 2.1 Create URL API

Implement:

``` http
POST /api/urls
```

Request:

``` json
{
  "originalUrl": "https://example.com/some/long/path"
}
```

Response should contain:

``` json
{
  "shortCode": "a8K2x",
  "shortUrl": "http://localhost:8080/a8K2x"
}
```

Validate that the URL is syntactically valid.

### 2.2 Implement short-code generation

Use a deterministic unique-ID-to-Base62 strategy or another clearly
documented collision-safe strategy.

Requirements:

-   Short codes must be unique.
-   Collision detection must exist.
-   Generation logic belongs in the service layer or a dedicated
    generator component.
-   Do not expose unnecessary database internals through the API.

### 2.3 Implement redirect

Implement:

``` http
GET /{shortCode}
```

The backend must:

1.  Resolve the short code.
2.  Verify that the URL exists.
3.  Verify that it is active.
4.  Verify that it has not expired.
5.  Redirect the client to the original URL.

Use an appropriate HTTP redirect status.

### 2.4 Add URL management basics

Implement:

``` http
GET /api/urls/{id}
DELETE /api/urls/{id}
```

Only implement these operations as required for the core system.

### 2.5 Add global validation and exception handling

Handle:

``` text
Invalid URL
Short code not found
Expired URL
Inactive URL
Duplicate custom alias
Invalid request body
```

Use consistent JSON error responses.

## Phase 2 Acceptance Criteria

The core system must support:

``` text
Long URL
   ↓
POST /api/urls
   ↓
Short code
   ↓
GET /{shortCode}
   ↓
302 redirect
   ↓
Original URL
```

The following must work before moving forward:

-   URL creation.
-   Unique short-code generation.
-   Redirect.
-   Invalid URL handling.
-   Missing short-code handling.
-   Expiration handling.
-   Basic deletion/deactivation.
-   Unit tests for core service logic.

------------------------------------------------------------------------

# Phase 3 --- Authentication, Ownership, and URL Management

## Objective

Add secure user accounts and make URLs user-owned resources.

## Tasks

### 3.1 Create the user model

Create:

``` text
User
```

with only required fields such as:

``` text
id
email
passwordHash
createdAt
```

Passwords must never be stored in plain text.

### 3.2 Implement authentication

Implement:

``` http
POST /api/auth/register
POST /api/auth/login
```

Use:

-   Spring Security
-   Password hashing
-   JWT

Do not invent a custom authentication protocol.

### 3.3 Associate URLs with users

Change the model to:

``` text
User
  │
  └──< ShortUrl
```

Authenticated users should be able to manage their own URLs.

### 3.4 Add custom aliases

Allow a user to request:

``` json
{
  "originalUrl": "https://example.com",
  "customAlias": "github"
}
```

Result:

``` text
short.domain/github
```

Rules:

-   Alias must be unique.
-   Alias must satisfy defined length/character constraints.
-   Reserved system paths must not be usable as aliases.
-   Users cannot overwrite another user's alias.

### 3.5 Add expiration

Allow an optional expiration timestamp.

Expired links must not redirect.

## Phase 3 Acceptance Criteria

A complete authenticated flow must work:

``` text
Register
   ↓
Login
   ↓
JWT
   ↓
Create URL
   ↓
Manage own URLs
```

The system must prevent:

-   Access to another user's management data.
-   Duplicate aliases.
-   Invalid aliases.
-   Expired-link redirection.

Do not add analytics dashboards yet.

------------------------------------------------------------------------

# Phase 4 --- Redis, Analytics, Rate Limiting, and Background Processing

## Objective

Add the scalability and analytics components explicitly defined by the
project scope.

## Tasks

### 4.1 Add Redis

Run Redis through Docker Compose.

Use Redis for URL caching.

Redirect flow:

``` text
GET /{shortCode}
       ↓
     Redis
    /     \
  HIT     MISS
   ↓       ↓
Redirect PostgreSQL
           ↓
         Redis
           ↓
        Redirect
```

Implement cache invalidation when a URL is modified, disabled, or
deleted.

### 4.2 Implement click analytics

Record relevant click information:

``` text
short URL
access time
referrer
user agent / device information
geographical information where available
```

Do not store unnecessary personally identifiable information.

Analytics must not significantly delay the redirect response.

### 4.3 Implement background processing

Use Spring background processing for:

-   Analytics processing where appropriate.
-   Expired URL cleanup.
-   Other explicitly required asynchronous tasks.

Use scheduled jobs for periodic cleanup.

### 4.4 Implement rate limiting

Use Redis to limit excessive requests.

Rate limiting should at minimum protect:

``` text
URL creation APIs
Authentication APIs
Redirect endpoint
```

Return:

``` http
429 Too Many Requests
```

when a limit is exceeded.

The exact limits should be configurable rather than scattered as magic
numbers.

### 4.5 Add analytics API

Expose only the analytics required by the project:

``` http
GET /api/urls/{id}/analytics
```

The response should support metrics such as:

``` text
total clicks
clicks over time
referrer information
device information
geographical information
```

## Phase 4 Acceptance Criteria

Verify:

-   Redirects can be served from Redis after cache population.
-   Cache misses correctly fall back to PostgreSQL.
-   Cache invalidation works.
-   Click events are recorded.
-   Analytics do not block the primary redirect unnecessarily.
-   Expired URLs are cleaned up automatically.
-   Rate limits return HTTP 429 when exceeded.
-   Users can access analytics only for URLs they own.

Do not introduce Kafka or microservices merely to process analytics.

------------------------------------------------------------------------

# Phase 5 --- Next.js Frontend, Integration, Testing, and Deployment

## Objective

Build the user-facing application and integrate all completed backend
functionality.

## Tasks

### 5.1 Create the Next.js application

Use:

``` text
Next.js
TypeScript
```

Build only the screens required by the scope:

``` text
Landing / URL creation
Login / Registration
User dashboard
URL management
Analytics view
```

### 5.2 Integrate authentication

Connect Next.js to the Spring Boot JWT authentication APIs.

Implement:

``` text
Register
Login
Logout
Protected dashboard
```

Do not create a second independent authentication system in Next.js.

### 5.3 Build URL management UI

The dashboard should allow users to:

-   Create shortened URLs.
-   Create custom aliases.
-   Set expiration.
-   View their URLs.
-   Disable/delete their URLs.

### 5.4 Build analytics UI

Display the backend analytics in a simple dashboard.

Keep visualizations focused on:

``` text
Total clicks
Clicks over time
Referrers
Devices
Geography
```

Do not build a general-purpose BI dashboard.

### 5.5 Test and containerize

Add:

-   Unit tests.
-   Integration tests for important backend flows.
-   API validation tests.
-   Redis/PostgreSQL integration tests where practical.
-   Docker configuration for deployment.

Document:

``` text
Setup
Environment variables
Docker commands
Backend startup
Frontend startup
API usage
```

## Final Acceptance Criteria

The complete application must support:

``` text
User
 ↓
Register/Login
 ↓
Create Short URL
 ↓
Optional Custom Alias
 ↓
Optional Expiration
 ↓
Share Short URL
 ↓
Visitor Opens Link
 ↓
Redis/PostgreSQL Resolution
 ↓
Redirect
 ↓
Analytics Recorded
 ↓
Owner Views Analytics
```

The final system must be stable, documented, tested, and runnable from a
clean setup.

------------------------------------------------------------------------

# Definition of Done

The project is considered complete when:

1.  All five phases meet their acceptance criteria.
2.  Core functionality is covered by automated tests.
3.  PostgreSQL and Redis can be started through Docker Compose.
4.  Backend and frontend can be started from documented instructions.
5.  Authentication and URL ownership are enforced.
6.  Redis caching and rate limiting are demonstrably functional.
7.  Analytics and expiration processing work.
8.  No unapproved features have been introduced.
