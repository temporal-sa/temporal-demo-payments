package com.example.payouts.api

import com.example.payouts.scenario.ScenarioStore
import io.temporal.serviceclient.WorkflowServiceStubs
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.*
import java.net.URLEncoder

/**
 * The namespace page in the Temporal UI that cloud mode links to. The demo platform injects
 * `TEMPORAL_UI_URL` (the UI base: Temporal Cloud, or a crashable workspace's dev server UI) and
 * `TEMPORAL_UI_NAMESPACE`, and those win, since a literal namespace page can't be right in both.
 * Without them, [namespacePage] is `DEMO_TEMPORAL_UI_URL`.
 */
internal fun temporalNamespacePage(uiBase: String, uiNamespace: String, namespacePage: String): String {
    if (uiBase.isBlank() || uiNamespace.isBlank()) return namespacePage
    val namespace = URLEncoder.encode(uiNamespace, Charsets.UTF_8).replace("+", "%20")
    return "${uiBase.trimEnd('/')}/namespaces/$namespace"
}

@RestController
@RequestMapping("/demo-api")
class ControlController(
    private val scenarios: ScenarioStore,
    private val service: WorkflowServiceStubs,
    @Value("\${spring.temporal.namespace:default}") private val namespace: String,
    @Value("\${demo.hosting:local}") private val hosting: String,
    @Value("\${demo.temporal-ui-url:}") namespacePage: String,
    @Value("\${TEMPORAL_UI_URL:}") uiBase: String,
    @Value("\${TEMPORAL_UI_NAMESPACE:}") uiNamespace: String,
) {
    private val temporalUiUrl = temporalNamespacePage(uiBase, uiNamespace, namespacePage)

    /** SDK language and version, task queue, namespace, and whether the server is reachable. */
    @GetMapping("/health")
    fun health(): Map<String, Any> {
        val reachable = runCatching {
            service.blockingStub().getSystemInfo(
                io.temporal.api.workflowservice.v1.GetSystemInfoRequest.newBuilder().build(),
            )
        }.isSuccess
        return mapOf(
            "sdkLanguage" to "Kotlin",
            "sdk" to "Temporal Java SDK 1.38.0",
            "taskQueue" to "payouts",
            "namespace" to namespace,
            "hosting" to hosting,
            "temporalUiUrl" to temporalUiUrl,
            "worker" to if (reachable) "UP" else "DOWN",
            "serverReachable" to reachable,
        )
    }

    @GetMapping("/scenarios")
    fun scenarios() = scenarios.all()

    /**
     * Clears scenario config and counters only. It does not terminate workflows or empty
     * the workflow list -- that needs a fresh --db-filename, which is reset-demo.sh's job.
     */
    @PostMapping("/reset")
    fun reset(): Map<String, Any> {
        scenarios.clear()
        return mapOf(
            "cleared" to "scenario config",
            "note" to "Workflow history is untouched. Use scripts/reset-demo.sh for a clean slate.",
        )
    }
}
