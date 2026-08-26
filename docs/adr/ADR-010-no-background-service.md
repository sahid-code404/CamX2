# ADR-010: No background service

Status: Accepted

## Context

Ordinary camera and development update work is foreground, lifecycle-bound, and short-lived. Services
add process/lifecycle competition and can delay camera startup.

## Decision

Do not use Service, ForegroundService, JobService, WorkManager, or a background component for normal
camera or dev OTA behavior. Use in-process lifecycle coroutines after first frame.

## Consequences

Checks/downloads cancel with process/lifecycle and may resume only by explicit repository policy.
Long-running future features require a new ADR and cannot inherit camera ownership.
