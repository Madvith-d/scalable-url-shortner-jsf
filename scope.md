# Shortify --- Project Scope

## 1. Project Identity

**Project Name:** Shortify

**Project Title:** A Scalable URL Shortening and Analytics Platform

**Project Type:** Web-based URL shortening and analytics platform

**Backend:** Java + Spring Boot

**Frontend:** Next.js

**Database:** PostgreSQL

**ORM:** Spring Data JPA / Hibernate

**Cache and Rate Limiting:** Redis

**Authentication:** Spring Security + JWT

**Database Deployment:** PostgreSQL in Docker

**Project Context:** Java and Spring Framework Lab (CS3603-1), V
Semester, AY 2026-27.

The approved project abstract defines the system as a web-based platform
for converting long URLs into compact shareable links, with URL
management, custom aliases, optional expiration, PostgreSQL persistence,
Redis caching, JWT authentication, click analytics, background
processing, and rate limiting. The implementation must remain aligned
with that definition.

------------------------------------------------------------------------

# 2. Primary Goal

Build a focused URL-shortening platform that demonstrates practical
Spring Boot backend engineering.

The system must demonstrate:

``` text
REST API design
Spring layered architecture
Spring Data JPA
PostgreSQL
Spring Security + JWT
Redis caching
Rate limiting
Background processing
URL shortening
URL expiration
Click analytics
Next.js frontend
Docker-based infrastructure
```

The goal is **not** to build a general-purpose social platform,
enterprise SaaS platform, or distributed cloud system.

------------------------------------------------------------------------

# 3. In-Scope Features

## A. URL Shortening

The system MUST support:

-   Converting long URLs into short URLs.
-   Unique short-code generation.
-   Redirecting short URLs to their original URLs.
-   URL validation.
-   URL activation/deactivation.
-   Optional expiration.
-   Custom aliases.

Example:

``` text
Original:
https://example.com/products/very/long/path

Short:
https://shortify.example/a8K2x
```

------------------------------------------------------------------------

## B. User Authentication and Ownership

The system MUST support:

-   User registration.
-   User login.
-   Password hashing.
-   JWT-based authentication.
-   Authenticated URL management.
-   Ownership checks.

A user must only be able to manage and view analytics for URLs they own.

The public redirect endpoint must remain accessible without requiring
the visitor to create an account.

------------------------------------------------------------------------

## C. Analytics

The system MUST record useful click information including:

-   Access time.
-   Referrer.
-   Device/user-agent information.
-   Geographical information where reasonably available.

The owner must be able to view analytics for their URLs.

Analytics should focus on useful URL-performance information rather than
becoming a general analytics platform.

------------------------------------------------------------------------

## D. Redis

Redis MUST be used for:

-   Short-URL caching.
-   Rate limiting.
-   Temporary/fast-access state where appropriate.

The primary persistent source of truth remains PostgreSQL.

Expected redirect behavior:

``` text
Short Code
    ↓
Redis
    ↓
Cache HIT → Redirect

Cache MISS
    ↓
PostgreSQL
    ↓
Redis
    ↓
Redirect
```

------------------------------------------------------------------------

## E. Background Processing

The system MUST support background processing for explicitly required
tasks such as:

-   Expired URL cleanup.
-   Analytics processing where appropriate.

Scheduled processing should be implemented using Spring mechanisms
unless another technology is explicitly approved.

------------------------------------------------------------------------

## F. Frontend

The Next.js frontend MUST provide only the interfaces required to
operate the platform:

-   URL creation.
-   Login/registration.
-   User dashboard.
-   URL management.
-   Analytics display.

The frontend is a client of the Spring Boot REST API.

Do not duplicate backend business logic in Next.js.

------------------------------------------------------------------------

# 4. Required Architecture

The backend must follow a layered modular-monolith architecture:

``` text
Controller
    ↓
Service
    ↓
Repository
    ↓
PostgreSQL
```

Supporting components:

``` text
Spring Security
Redis
Background/Scheduled Processing
Analytics
Exception Handling
Validation
```

Recommended backend structure:

``` text
backend/
└── src/main/java/com/shortify/
    ├── controller/
    ├── service/
    ├── repository/
    ├── entity/
    ├── dto/
    ├── exception/
    ├── security/
    ├── cache/
    ├── analytics/
    └── config/
```

The application should remain a **modular monolith**.

Do not split the system into microservices.

------------------------------------------------------------------------

# 5. Data Scope

## Required entities

The initial data model should contain only entities justified by the
requirements:

``` text
User
ShortUrl
ClickEvent
```

Additional entities may be introduced only when they solve a clearly
identified requirement.

### User

``` text
id
email
passwordHash
createdAt
```

### ShortUrl

``` text
id
shortCode
originalUrl
userId
createdAt
expiresAt
active
```

### ClickEvent

``` text
id
shortUrlId
timestamp
referrer
userAgent/device information
geographical information where available
```

Do not store unnecessary user data.

------------------------------------------------------------------------

# 6. Technology Boundaries

## Mandatory

``` text
Java
Spring Boot
Spring Data JPA
PostgreSQL
Redis
Spring Security
JWT
Maven
Docker
Next.js
TypeScript
REST
```

## Allowed supporting libraries

Small libraries may be added when they directly support an in-scope
requirement, such as:

-   URL/QR generation.
-   Validation.
-   Testing.
-   API documentation.
-   IP/geographical lookup.

Any new library must have a specific requirement-based justification.

------------------------------------------------------------------------

# 7. Explicitly Out of Scope

The AI agent MUST NOT implement the following unless the project team
explicitly requests a scope change.

## A. Distributed Infrastructure

Do not add:

-   Microservices.
-   Kubernetes.
-   Service mesh.
-   API gateway infrastructure.
-   Eureka/Consul service discovery.
-   Distributed tracing infrastructure.
-   Cloud-native orchestration.

The project is a modular monolith.

## B. Unrequested Product Features

Do not add:

-   Social following.
-   Comments.
-   Likes.
-   Teams/workspaces.
-   Chat.
-   Public social feeds.
-   Affiliate management.
-   Advertising.
-   Subscription/billing systems.
-   Cryptocurrency/payment functionality.

## C. Unrequested AI Features

Do not add:

-   LLM integration.
-   AI-generated aliases.
-   AI analytics summaries.
-   Recommendation systems.
-   Chatbots.
-   Machine-learning prediction.

AI is not part of the defined project scope.

## D. Excessive Analytics

Do not build:

-   Full business intelligence systems.
-   Heat maps unless specifically required.
-   Session replay.
-   Mouse tracking.
-   Screen recording.
-   Fingerprinting systems.
-   Detailed personal profiling.

Analytics must remain focused on URL click performance.

## E. Excessive File/Media Features

Do not add:

-   File uploads.
-   Image hosting.
-   Video hosting.
-   Media processing.

The system shortens URLs; it is not a file-hosting platform.

------------------------------------------------------------------------

# 8. Kafka Boundary

Apache Kafka is **not required for the core implementation**.

Do not introduce Kafka simply because analytics are asynchronous.

First use Spring-supported background processing and scheduled tasks.

Kafka may only be introduced if the project team explicitly approves an
architecture extension for event-driven analytics.

If Kafka is introduced later, it must not change the product scope.

------------------------------------------------------------------------

# 9. Database and ORM Boundary

PostgreSQL is the persistent database.

Spring Data JPA/Hibernate is the ORM.

Do not introduce:

-   MongoDB.
-   MySQL.
-   SQLite.
-   DynamoDB.
-   Multiple primary databases.

Redis is a cache/temporary-state system, not a replacement for
PostgreSQL.

Do not treat Redis as the permanent source of URL data.

------------------------------------------------------------------------

# 10. API Boundary

The backend should expose only APIs required by the application.

Core API groups:

``` text
/api/auth/*
/api/urls/*
/api/urls/{id}/analytics
/{shortCode}
```

Do not create speculative APIs for features that are not implemented.

Every API must have:

-   Input validation.
-   Appropriate HTTP status codes.
-   Consistent error responses.
-   Authentication/authorization where required.

------------------------------------------------------------------------

# 11. Security Boundary

Security requirements include:

-   Password hashing.
-   JWT authentication.
-   Authorization for user-owned resources.
-   Input validation.
-   Rate limiting.
-   Protection against unauthorized URL-management operations.

Do not implement an unnecessarily complex identity provider.

Do not store raw passwords.

Do not expose sensitive implementation details in API error messages.

Avoid storing raw IP addresses permanently unless there is a clearly
justified requirement; prefer privacy-conscious processing such as
hashing or deriving coarse geographical information.

------------------------------------------------------------------------

# 12. Performance Boundary

The main performance objective is fast short-link resolution.

Redis should reduce repeated PostgreSQL lookups.

The desired path is:

``` text
Visitor
   ↓
GET /shortCode
   ↓
Redis
   ↓
Original URL
   ↓
Redirect
```

Analytics processing must not unnecessarily block the redirect path.

Do not prematurely optimize the system with distributed infrastructure.

Measure first; optimize only where the project requirements justify it.

------------------------------------------------------------------------

# 13. AI Agent Rules

When an AI coding agent works on this project, it MUST follow these
rules:

### Rule 1 --- Follow the current phase

Only implement tasks belonging to the current phase unless explicitly
instructed otherwise.

### Rule 2 --- Do not invent features

A feature is not required merely because it would be "useful",
"production-ready", or "industry standard".

### Rule 3 --- Preserve the architecture

Use the existing modular Spring Boot architecture.

Do not restructure the project into microservices without explicit
approval.

### Rule 4 --- Prefer simple implementations

When two solutions satisfy the requirement, use the simpler solution.

### Rule 5 --- Ask before scope expansion

If a requested implementation appears to require a new technology, major
architectural change, or new product feature, stop and request approval
instead of silently adding it.

------------------------------------------------------------------------

# 14. Scope Change Protocol

Any feature not explicitly listed as in-scope requires a scope change.

Before implementing it, the agent must identify:

``` text
Requested feature
Why it is needed
Which existing requirement it supports
Technologies it introduces
Impact on current architecture
```

Implementation must begin only after explicit approval.

------------------------------------------------------------------------

# 15. Final Product Boundary

The final product is:

``` text
A secure web-based URL shortening platform
with URL management, custom aliases,
expiration, Redis caching, rate limiting,
JWT authentication, click analytics,
background processing, and a Next.js frontend.
```

The final request flow is:

``` text
                    ┌───────────────┐
                    │    Next.js    │
                    │    Frontend   │
                    └───────┬───────┘
                            │
                         REST API
                            │
                            ▼
                  ┌───────────────────┐
                  │   Spring Boot     │
                  │                   │
                  │ URL Management    │
                  │ Authentication    │
                  │ Analytics         │
                  │ Rate Limiting     │
                  └───────┬─────┬─────┘
                          │     │
                         JPA   Redis
                          │     │
                          ▼     ▼
                    PostgreSQL  Cache
```

The project is complete when the defined requirements work reliably and
are tested. Additional features are not part of completion criteria.
