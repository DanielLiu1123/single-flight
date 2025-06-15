# Single Flight

[![Maven Central](https://img.shields.io/maven-central/v/io.github.danielliu1123/single-flight)](https://central.sonatype.com/artifact/io.github.danielliu1123/single-flight)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java Version](https://img.shields.io/badge/Java-8%2B-blue.svg)](https://openjdk.java.net/)

**Single Flight** is used to prevent the duplicate execution of expensive operations in concurrent environments.

## 🎯 What is Single Flight?

The [Single Flight pattern](https://www.codingexplorations.com/blog/understanding-singleflight-in-golang-a-solution-for-eliminating-redundant-work) is a concurrency control mechanism that ensures expensive operations (like database queries, API calls, or complex computations) 
are executed **only once per key** when multiple threads request the same resource concurrently.

```text
Without Single Flight:
┌──────────────────────────────────────────────────────────────┐
│ Thread-1 (key:"user_123") ──► DB Query-1 ──► Result-1        │
│ Thread-2 (key:"user_123") ──► DB Query-2 ──► Result-2        │
│ Thread-3 (key:"user_123") ──► DB Query-3 ──► Result-3        │
│ Thread-4 (key:"user_123") ──► DB Query-4 ──► Result-4        │
└──────────────────────────────────────────────────────────────┘
Result: 4 separate database calls for the same key
        (All results are identical but computed 4 times)

With Single Flight:
┌──────────────────────────────────────────────────────────────┐
│ Thread-1 (key:"user_123") ──► DB Query-1 ──► Result-1        │
│ Thread-2 (key:"user_123") ──► Wait       ──► Result-1        │
│ Thread-3 (key:"user_123") ──► Wait       ──► Result-1        │
│ Thread-4 (key:"user_123") ──► Wait       ──► Result-1        │
└──────────────────────────────────────────────────────────────┘
Result: 1 database call, all threads share the same result/exception
```

## 🚀 Quick Start

### Installation

**Maven:**

```xml
<dependency>
    <groupId>io.github.danielliu1123</groupId>
    <artifactId>single-flight</artifactId>
    <version>latest</version>
</dependency>
```

**Gradle:**

```gradle
implementation 'io.github.danielliu1123:single-flight:<latest>'
```

### Usage

```java
// Using the global instance
User user = SingleFlight.runDefault("user:123", () -> {
    return userService.loadUser("123");
});

// Using a dedicated instance
SingleFlight<String, User> userSingleFlight = new SingleFlight<>();

User user = userSingleFlight.run("123", () -> {
    return userService.loadUser("123");
});
```

## 🤔 When to Use Single Flight

### ✅ Perfect For:
- **Database queries** with high cache miss rates
- **External API calls** that are expensive or rate-limited
- **Complex computations** that are CPU-intensive
- **Cache warming** scenarios to prevent stampedes

### ❌ Not Suitable For:
- Operations that should always execute (like logging)
- Very fast operations where coordination overhead exceeds benefits
- Operations with side effects that must happen for each call

## 📄 License

The MIT License.
