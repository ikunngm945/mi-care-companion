# Automatic Route and Advice Prompt Implementation Plan

> **Design reference:** This plan supersedes the dedicated-plugin transport in the [initial plan](<2026-10-17-mi-companion.md>) and [initial specification](<../specs/2026-10-17-mi-companion-design.md>): use AstrBot's official HTTP API, resolve the target profile and persist generation/delivery barriers on the client. The tasks below describe design and verification targets, not a session log or a claim about a particular deployment.

**Goal:** Automatically resolve target UMO to the existing AstrBot configuration profile, and add editable advice prompts preserving server persona and a 500-codepoint output cap.

**Architecture:** Reuse official HTTP transport and write-ahead delivery barriers. Parse the routing response in textual insertion order, validate its shape and match Python fnmatchcase-compatible patterns. Resolve before the generating barrier; explicitly send config_id, never silently substitute default after failed GET. UI stores prompt alongside encrypted-key settings; event snapshot freezes prompt independently from later edits.

**Tech Stack:** Java 8 source, Android target API 36, org.json, native views, JVM HttpServer fixtures and Android instrumentation. No additional dependencies.

## Global Constraints

- Never print, package or log API keys, response bodies or user data. Keep test credentials and private setup outside published sources.
- HTTP opt-in, no redirects, safe deadlines, strict JSON/SSE and tool rejection remain.
- Preserve collectors and strict >3h off then >10min continuous unlocked trigger, manual test independence and no-repeat uncertain states.
- Route inspection uses read-only official endpoints. POST chat may internally register its own temporary session route; test this behavior with fixtures, and require explicit authorization for real model or message calls.
- Preserve first-match route ordering; reject malformed/duplicate/unsupported patterns rather than guess. Validate selected profile exists.
- Simulate official chat's exact-key route append/update/delete to detect earlier wildcard blocking desired config; reject instead of claiming persona matched.
- The editable/restorable default prompt analyzes data while retaining the server persona; prefer 100–200 characters and enforce at most 500 Unicode codepoints in output.

## Task 1: Official contracts and ordered resolver

Files: [RouteResolver](<../../../android/src/cn/local/micompanion/RouteResolver.java>), [RouteResolverTest](<../../../android/tests/RouteResolverTest.java>), [OfficialApi](<../../../android/src/cn/local/micompanion/OfficialApi.java>) and [OfficialApiTest](<../../../android/tests/OfficialApiTest.java>).

- Verify the router contract and the `value.val` storage wrapper against supported AstrBot versions.
- Validate GET config-routes/config-profiles: HTTP 200, status ok and data.routing/data.info_list; the API key needs config-read scope.
- Test first-match routing, UMO colons/brackets/empty segments, duplicates, malformed patterns and no-match-default behavior.
- Use loopback HTTP fixtures for GET routes/profiles and explicit config_id POST; prohibit POST on permission, format or profile validation failures. Retain no-redirect and SSE tests.
- Implement Selection, GET validation and the generate overload with explicit Selection, without editing global AstrBot configuration.

Interfaces:
```java
RouteResolver.resolve(String rawEnvelope, String umo);
RouteResolver.verifyChatRoute(String rawEnvelope, String webchatUmo, String desiredConfigId);
OfficialApi.Selection { public final String configId, profileName; }
OfficialApi.check(SettingsStore.Config cfg); // returns Selection
OfficialApi.check(SettingsStore.Config cfg, String eventId);
OfficialApi.generate(SettingsStore.Config cfg, String id, String snapshot, OfficialApi.Selection selected);
```

## Task 2: Prompt contract, UI and persistence

Files: [AdvicePrompt](<../../../android/src/cn/local/micompanion/AdvicePrompt.java>), [AdvicePromptTest](<../../../android/tests/AdvicePromptTest.java>), [SettingsStore](<../../../android/src/cn/local/micompanion/SettingsStore.java>), [MainActivity](<../../../android/src/cn/local/micompanion/MainActivity.java>) and [MainActivityUiProbe](<../../../android/device-tests/MainActivityUiProbe.java>).

- Test default-for-empty, inclusion of user prompt, immutable data boundaries and persona retention; limit prompt input to 4000 codepoints, reject controls and cap output at 500 codepoints including emoji/ellipsis.
- Persist Config.prompt with backward-compatible constructors; old configuration defaults, and legacy save overloads preserve the current prompt.
- Provide multiline draft editing, save and restore-default controls; draft changes take effect only on save. Respect busy state and prevent autofill/saved-state leakage.
- Instrument default/restore/save behavior and secure report layout without real model or message calls.

## Task 3: Delivery integration and regression

Files: [EventClient](<../../../android/src/cn/local/micompanion/EventClient.java>) and [ClientTransportTest](<../../../android/tests/ClientTransportTest.java>).

- Freeze the prompt in the event database; persist matched configuration selection before the generating barrier and construct chat requests from Selection.
- Cached ready text must not silently switch profiles after routing changes; retain unknown/no-repeat rules.
- Run JVM and Python regressions using [the test script](<../../../tools/test.sh>); check compilation separately using [the build script](<../../../tools/build.sh>).
- Review scope, durability, ordering, bounds and credential handling; cover significant defects with regression tests.

## Task 4: Compatibility and acceptance targets

- Verify upgrade installation preserves encrypted settings and unrelated configuration. Test key/prompt changes only with synthetic or explicitly authorized configuration.
- Verify prompt persistence/roundtrip and old configuration compatibility without requiring live generation or delivery.
- Keep probe packages, temporary credentials, signing keys, dependency caches and private snapshots out of published sources and artifacts.
- Distinguish API preflight, model generation, adapter send success and end-to-end receipt in verification reports; none implies the others.
