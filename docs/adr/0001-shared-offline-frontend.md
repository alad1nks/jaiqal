# ADR 0001: Shared offline-first frontend

**Status:** Accepted

The backend is the source of truth so concurrent clients cannot let stale SQLite rows overwrite newer server state. SQLDelight is the cache because it provides typed, observable SQLite access on Android and iOS and keeps previously loaded content useful offline. Mutations remain online-only in this release.

SSE is preferred to continuous polling for low-latency foreground updates with less radio and server work. REST refresh after reconnect repairs gaps. Common ViewModels and shared Compose UI keep behavior and state transitions consistent across Android and iOS while narrow platform adapters handle networking, Firebase secure state, connectivity and lifecycle.

Feature packages inside the existing `:app:shared` module are preferred over many Gradle modules: the product is young, boundaries are still changing, and extra build graph complexity would not yet provide isolation benefits.
