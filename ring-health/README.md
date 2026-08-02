# R08 Ring Health backend

Android library with the R08 health transport, persistence and scheduling logic. It contains no
Activity, Service, notification, gesture recognizer, accessibility service or gesture-mode command.
The host application owns lifecycle and UI.

## Host integration

```kotlin
dependencies {
    implementation(project(":ring-health"))
}
```

Create one process-wide backend after the host has obtained the Bluetooth permissions declared by
the library manifest:

```java
RingHealthBackend backend = new RingHealthBackend(applicationContext);
backend.addListener(snapshot -> renderOrPublish(snapshot));
backend.start();
```

The public host surface is:

- `RingHealthBackend`: connection lifecycle, manual measurements, auto-measurement settings,
  manual history sync and queued periodic sync.
- `RingHealthSnapshot`: immutable current connection/settings/sync state plus recent samples.
- `HealthSample`, `StepDay`, `SleepSession`, `HealthMetric`, `Capabilities`,
  `AutoMeasurementSettings`, `ConnectionState`:
  UI-independent data contracts.
- `PeriodicSyncPolicy`: supported 30/60/120-minute sync grid.

Steps use QRing's ring-specific `0x48` today-total and `0x43` 15-minute detail commands. The
backend persists one total per local calendar day, publishes `todaySteps`, clears that visible
counter at local midnight, and retains daily totals for 7/30-day host charts. Autosync reads the
small today total every cycle and refreshes QRing's six retained history days at most once per day.

Call `stop()` when the host no longer wants the BLE connection. A foreground service is not part of
the library; R08 Access Bridge can own the lifecycle appropriate for its already-running process.
