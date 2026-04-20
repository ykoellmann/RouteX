package com.sonarwhale.script

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.SavedRequest
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

@Service(Service.Level.PROJECT)
class SonarwhaleScriptService(private val project: Project) {

    private val resolver: ScriptChainResolver by lazy {
        ScriptChainResolver(scriptsRoot())
    }
    private val engine = ScriptEngine()

    /**
     * Executes pre-scripts and returns the modified [ScriptContext].
     * Must be called from a background thread — sw.http makes blocking network calls.
     */
    fun executePreScripts(
        endpoint: ApiEndpoint,
        request: SavedRequest,
        url: String,
        headers: Map<String, String>,
        body: String
    ): ScriptContext {
        val stateService = SonarwhaleStateService.getInstance(project)
        val env = stateService.getActiveEnvironment()?.variables?.toMutableMap() ?: mutableMapOf()
        val ctx = ScriptContext(
            envSnapshot = env,
            request = MutableRequestContext(
                url = url,
                method = endpoint.method.name,
                headers = headers.toMutableMap(),
                body = body
            )
        )
        val tag = endpoint.tags.firstOrNull() ?: "Default"
        val chain = resolver.resolvePreChain(tag, endpoint.method.name, endpoint.path, request.name)
        engine.executeChain(chain, ctx)
        flushEnvChanges(ctx.envSnapshot)
        return ctx
    }

    /**
     * Executes post-scripts and returns the collected [TestResult]s.
     * Must be called from a background thread.
     */
    fun executePostScripts(
        endpoint: ApiEndpoint,
        request: SavedRequest,
        statusCode: Int,
        responseHeaders: Map<String, String>,
        responseBody: String,
        scriptContext: ScriptContext
    ): List<TestResult> {
        val response = ResponseContext(statusCode, responseHeaders, responseBody)
        val postCtx = ScriptContext(
            envSnapshot = scriptContext.envSnapshot,
            request = scriptContext.request,
            response = response
        )
        val tag = endpoint.tags.firstOrNull() ?: "Default"
        val chain = resolver.resolvePostChain(tag, endpoint.method.name, endpoint.path, request.name)
        engine.executeChain(chain, postCtx)
        flushEnvChanges(postCtx.envSnapshot)
        return postCtx.testResults
    }

    /**
     * Creates the pre.js or post.js file at the appropriate level for the given endpoint+request.
     * Returns the path so the caller can open it in the IDE editor.
     * If the file already exists, returns the existing path without overwriting.
     */
    fun getOrCreateScript(
        endpoint: ApiEndpoint,
        request: SavedRequest,
        phase: ScriptPhase,
        level: ScriptLevel = ScriptLevel.REQUEST
    ): Path {
        val tag         = endpoint.tags.firstOrNull() ?: "Default"
        val endpointDir = ScriptChainResolver.sanitizeEndpointDir(endpoint.method.name, endpoint.path)
        val requestDir  = ScriptChainResolver.sanitizeName(request.name)
        val tagDir      = ScriptChainResolver.sanitizeName(tag)
        val fileName    = if (phase == ScriptPhase.PRE) "pre.js" else "post.js"
        val root        = scriptsRoot()

        val dir = when (level) {
            ScriptLevel.GLOBAL   -> root
            ScriptLevel.TAG      -> root.resolve(tagDir)
            ScriptLevel.ENDPOINT -> root.resolve(tagDir).resolve(endpointDir)
            ScriptLevel.REQUEST  -> root.resolve(tagDir).resolve(endpointDir).resolve(requestDir)
        }
        dir.createDirectories()
        ensureSwDts()

        val scriptPath = dir.resolve(fileName)
        if (!scriptPath.exists()) {
            val comment = when (phase) {
                ScriptPhase.PRE  -> "// Pre-script: runs before the HTTP request\n// Available: sw.env, sw.request, sw.http\n\n"
                ScriptPhase.POST -> "// Post-script: runs after the HTTP response\n// Available: sw.env, sw.request, sw.response, sw.http, sw.test, sw.expect\n\n"
            }
            scriptPath.writeText(comment)
        }
        return scriptPath
    }

    /** Writes sw.d.ts to .sonarwhale/scripts/ if it does not exist yet. */
    fun ensureSwDts() {
        val root = scriptsRoot()
        root.createDirectories()
        val dts = root.resolve("sw.d.ts")
        if (!dts.exists()) {
            dts.writeText(SW_DTS_CONTENT)
        }
    }

    private fun scriptsRoot(): Path =
        Path.of(project.basePath ?: ".").resolve(".sonarwhale").resolve("scripts")

    private fun flushEnvChanges(snapshot: MutableMap<String, String>) {
        val stateService = SonarwhaleStateService.getInstance(project)
        val env = stateService.getActiveEnvironment() ?: return
        val updated = env.copy(variables = LinkedHashMap(snapshot))
        stateService.upsertEnvironment(updated)
    }

    companion object {
        fun getInstance(project: Project): SonarwhaleScriptService = project.service()

        private val SW_DTS_CONTENT = """
// Sonarwhale Script API — auto-generated, do not edit
// Place sw.d.ts at the root of .sonarwhale/scripts/ for IDE autocomplete

interface SwResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
  error?: string;
  json<T = any>(): T;
}

interface SwExpect {
  toBe(expected: any): void;
  toEqual(expected: any): void;
  toBeTruthy(): void;
  toBeFalsy(): void;
  toContain(substr: string): void;
}

declare const sw: {
  env: {
    get(key: string): string | undefined;
    set(key: string, value: string): void;
  };
  request: {
    url: string;
    method: string;
    headers: Record<string, string>;
    body: string;
    setHeader(key: string, value: string): void;
    setBody(body: string): void;
    setUrl(url: string): void;
  };
  response: {
    status: number;
    headers: Record<string, string>;
    body: string;
    json<T = any>(): T;
  };
  http: {
    get(url: string, headers?: Record<string, string>): SwResponse;
    post(url: string, body: string, headers?: Record<string, string>): SwResponse;
    request(method: string, url: string, body?: string, headers?: Record<string, string>): SwResponse;
  };
  test(name: string, fn: () => void): void;
  expect(value: any): SwExpect;
};
        """.trimIndent()
    }
}
