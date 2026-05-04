# Sonarwhale TODO

## Code Quality

### Medium priority
- Replace stringly-typed script levels — `disabledPreLevels: Set<String>` in HierarchyConfig crosses serialization boundaries raw; silent failure on invalid level names
- Consolidate `RequestPanel` state fields — `currentEndpoint`, `currentRequest`, `currentRequestName`, `previewMode` → sealed `RequestState` class

## Features

### Script debugging (deferred)
- Line numbers in error messages (catch RhinoException, extract lineNumber/columnNumber/getScriptStack)
- Rhino Swing debugger (Debug button in RequestPanel, attach Dim to ContextFactory, step/breakpoints/variable inspection)
- Execution delta after pre-script (show which env vars changed, final URL/headers/body vs initial)

## Phase 3 (Diff & Snapshots)
- SnapshotService — diff after each refresh, delta: added/modified/removed (ID-based)
- REMOVED endpoints in tree (rendering already prepared in EndpointTree)
- Diff tab in Detail panel
- Breaking-change badge on Tool Window icon

## Phase 5 (More Language Scanners)
- PythonScanner — FastAPI / Flask
- JavaScanner — Spring Boot
