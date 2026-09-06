# iView Engineering Rules

## Product goal

iView replaces the dashboard and MySCADA systems with one JVM-based industrial data platform.
The old repositories are read-only references. Never edit them from this project.

## Non-negotiable rules

- A legacy feature is complete only after behavior-level parity is verified and recorded in the
  release acceptance evidence maintained outside the public repository.
- Protocol drivers depend only on `protocol-spi` and `core-model`; business modules never contain
  protocol-specific branches.
- Dashboard components consume datasets or explicit realtime subscriptions. They do not query
  protocol drivers directly.
- Raw point samples, machine-state intervals, and calculated aggregates are separate models.
- A calculation result must retain the rule version and time window that produced it.
- Domain logic remains framework-independent and testable.
- New code must not depend on packages from the legacy repositories.

## Definition of done

- Production code has automated tests for deterministic behavior.
- Public APIs and persisted schemas are documented.
- Failure, timeout, reconnect, and bad-quality behavior are considered for device communication.
- The feature parity row links to tests or an acceptance record before becoming `VERIFIED`.
