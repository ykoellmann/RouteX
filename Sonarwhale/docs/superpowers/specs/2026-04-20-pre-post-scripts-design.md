# Pre/Post Scripts — Design Spec

**Date:** 2026-04-20
**Status:** Approved

---

## Overview

Pre- and post-scripts allow users to run JavaScript before and after HTTP requests in Sonarwhale. The primary use case is auth flows: a pre-script calls a login endpoint, extracts the token, and injects it into the outgoing request. Scripts are real `.js` files in the project — the IDE provides syntax highlighting, error detection, and autocomplete via a bundled `sw.d.ts`.

---

## File Structure

Scripts live in `.sonarwhale/scripts/` at the project root. The directory hierarchy mirrors the script hierarchy:

```
.sonarwhale/
  scripts/
    sw.d.ts                          ← API type definitions for IDE autocomplete
    pre.js                           ← global pre-script
    post.js                          ← global post-script
    {Tag}/                           ← tag name, sanitized to valid directory name
      pre.js
      post.js
      {METHOD}__{path}/              ← e.g. GET__api_users_{id}
        pre.js
        post.js
        {Request-Name}/              ← e.g. Happy_Path
          pre.js
          post.js
          inherit.off                ← empty file: disables inheritance from all parent levels
```

**Directory sanitizing rules:**
- `/` → `_`
- `{`, `}` → `{`, `}` (kept as-is, valid on most filesystems)
- Spaces → `_`
- Method prepended with double underscore: `GET__api_users`

**Execution order — Pre-scripts** (outermost first):
```
global/pre.js → tag/pre.js → endpoint/pre.js → request/pre.js → [HTTP Request]
```

**Execution order — Post-scripts** (innermost first):
```
[HTTP Response] → request/post.js → endpoint/post.js → tag/post.js → global/post.js
```

**Inheritance control:** An `inherit.off` file at any level disables execution of all parent-level scripts for that level and all levels below it. Each level can independently declare `inherit.off`.

---

## JavaScript API (`sw` object)

Every script receives a global `sw` object. The full type definition is shipped as `.sonarwhale/scripts/sw.d.ts`:

```typescript
declare const sw: {
  /** Active environment variables — readable and writable */
  env: {
    get(key: string): string | undefined;
    set(key: string, value: string): void;
  };

  /** Outgoing request — writable in pre-scripts only */
  request: {
    url: string;
    method: string;
    headers: Record<string, string>;
    body: string;
    setHeader(key: string, value: string): void;
    setBody(body: string): void;
    setUrl(url: string): void;
  };

  /** Incoming response — available in post-scripts only */
  response: {
    status: number;
    headers: Record<string, string>;
    body: string;
    json<T = any>(): T;
  };

  /** Synchronous HTTP client for auth flows and request chaining */
  http: {
    get(url: string, headers?: Record<string, string>): SwResponse;
    post(url: string, body: string, headers?: Record<string, string>): SwResponse;
    request(method: string, url: string, body?: string, headers?: Record<string, string>): SwResponse;
  };

  /** Post-script assertions — results shown in Response panel "Tests" tab */
  test(name: string, fn: () => boolean | void): void;
  expect(value: any): SwExpect;
};

interface SwResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
  json<T = any>(): T;
}

interface SwExpect {
  toBe(expected: any): void;
  toEqual(expected: any): void;
  toBeTruthy(): void;
  toBeFalsy(): void;
  toContain(substr: string): void;
}
```

**Typical global auth pre-script:**
```js
// .sonarwhale/scripts/pre.js
const res = sw.http.post(
  sw.env.get("baseUrl") + "/auth/login",
  JSON.stringify({ username: sw.env.get("username"), password: sw.env.get("password") }),
  { "Content-Type": "application/json" }
);
sw.env.set("token", res.json().access_token);
sw.request.setHeader("Authorization", "Bearer " + sw.env.get("token"));
```

**JS Engine:** Mozilla Rhino (JVM-native, no extra dependency). Runs synchronously on a background thread. `sw.http` calls block synchronously (Rhino has no async/await). Timeout: 30 seconds per script.

---

## Plugin Architecture

### New files

```
src/rider/main/kotlin/com/sonarwhale/
  script/
    ScriptContext.kt          ← mutable request/response/env data passed between scripts
    ScriptFile.kt             ← metadata: level, phase, path, inheritOff
    ScriptChainResolver.kt    ← builds ordered List<ScriptFile> for a given endpoint+request
    ScriptEngine.kt           ← executes a chain via Rhino, exposes sw.* API
    SonarwhaleScriptService.kt ← project-level service, coordinates everything
```

### Data flow

```
RequestPanel.sendRequest()
  │
  ├── SonarwhaleScriptService.executePreScripts(endpoint, savedRequest)
  │     ├── ScriptChainResolver → List<ScriptFile> (pre, ordered global→request)
  │     ├── ScriptEngine.run(chain, context)     ← modifies request + env
  │     └── returns modified ScriptContext
  │
  ├── HTTP request (using context.request — url, headers, body)
  │
  ├── SonarwhaleScriptService.executePostScripts(endpoint, savedRequest, response)
  │     ├── ScriptChainResolver → List<ScriptFile> (post, ordered request→global)
  │     ├── ScriptEngine.run(chain, context)     ← runs assertions, sets env vars
  │     └── returns List<TestResult>
  │
  └── ResponsePanel.show(response, testResults)  ← new "Tests" tab
```

### `ScriptContext`

```kotlin
data class MutableRequestContext(
    var url: String,
    var method: String,
    var headers: MutableMap<String, String>,
    var body: String
)

class ScriptContext(
    val envSnapshot: MutableMap<String, String>,
    val request: MutableRequestContext,
    val response: ResponseContext? = null   // null during pre-scripts
)
```

**Extensibility:** New `sw` namespaces (e.g. `sw.crypto`, `sw.db`) are added as additional fields on `ScriptContext` and exposed to Rhino via `ScriptEngine`. Existing scripts are unaffected.

### `ScriptChainResolver`

Walks the directory tree from global → tag → endpoint → request, collecting pre.js or post.js files. Stops at any level where `inherit.off` exists. Post-script chain is reversed before execution.

### Script creation UX

No scripts are auto-created. The RequestPanel toolbar gets two buttons:

- **"Create Pre-Script"** — creates the appropriate `pre.js` file for the current request's level and opens it in the IDE editor
- **"Create Post-Script"** — same for `post.js`

If a script already exists at that level, the button opens it instead of recreating it.

### `sw.d.ts` generation

On first plugin startup (or when missing), `SonarwhaleScriptService` writes `sw.d.ts` into `.sonarwhale/scripts/`. The IDE's JS/TS language service picks it up automatically for autocomplete in any `.js` file under that directory.

---

## Response Panel — Tests Tab

A new "Tests" tab appears in `ResponsePanel` after any request that has post-scripts. Each `sw.test()` call produces a `TestResult`:

```kotlin
data class TestResult(
    val name: String,
    val passed: Boolean,
    val error: String?   // null if passed
)
```

Display: green checkmark / red X per test, script error messages shown inline.

---

## Error Handling

| Scenario | Behavior |
|---|---|
| Script file has a syntax error | Script is skipped, error shown in Response panel |
| Script throws at runtime | Chain stops, error shown, HTTP request is NOT sent (pre) / results shown (post) |
| Script exceeds 30s timeout | Interrupted, error shown |
| `inherit.off` present | All parent scripts skipped for this level |
| Script file missing at referenced path | Silently skipped (file may have been deleted) |

---

## Persistence

Scripts are `.js` files — no changes to `sonarwhale.xml` or `SonarwhaleStateService`. The `.sonarwhale/scripts/` directory should be committed to git. `.sonarwhale/` is not inside `.idea/` so it is naturally git-tracked.

`SavedRequest` requires no new fields. The script chain is resolved by convention from the endpoint's tag, method, path, and request name.

---

## Out of Scope (for this iteration)

- `inherit.on` to re-enable inheritance below an `inherit.off`
- Script-level timeout configuration
- Debugging / breakpoints inside scripts
- Additional `sw` namespaces beyond `env`, `request`, `response`, `http`, `test`, `expect`
- Python/Java scanner scripts (only JS)
