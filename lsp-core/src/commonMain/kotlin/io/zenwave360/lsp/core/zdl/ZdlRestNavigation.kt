package io.zenwave360.lsp.core.zdl

internal data class ZdlApiDescriptor(
    val name: String,
    val type: String?,
    val role: String?,
    val uri: String
)

internal data class ZdlRestOperationTarget(
    val api: ZdlApiDescriptor,
    val verb: String,
    val path: String,
    val operationId: String,
    val semanticPath: String
)

internal fun zdlApiDescriptors(uri: String, model: Map<String, Any?>): List<ZdlApiDescriptor> =
    model.mapAt("apis").entries.mapNotNull { (name, value) ->
        val api = value.asMap()
        val targetUri = api["uri"].asString()?.let { resolveRelativeUri(uri, it) } ?: return@mapNotNull null
        ZdlApiDescriptor(
            name = name,
            type = api["type"].asString(),
            role = api["role"].asString(),
            uri = targetUri
        )
    }

internal fun resolveRestOperationTarget(
    ownerUri: String,
    model: Map<String, Any?>,
    serviceName: String,
    methodName: String
): ZdlRestOperationTarget? {
    val service = model.mapAt("services")[serviceName].asMap()
    val method = service.mapAt("methods")[methodName].asMap()
    val serviceBasePath = annotationPath(service.mapAt("options")["rest"]) ?: ""
    val httpVerb = httpVerb(method.mapAt("options")) ?: return null
    val httpOption = method.mapAt("options")[httpVerb]
    val methodPath = annotationPath(httpOption) ?: ""
    val api = selectDefaultProviderOpenApi(zdlApiDescriptors(ownerUri, model)) ?: return null
    val effectivePath = normalizeHttpPath(serviceBasePath, methodPath)
    val operationId = annotationOperationId(httpOption) ?: methodName
    return ZdlRestOperationTarget(
        api = api,
        verb = httpVerb,
        path = effectivePath,
        operationId = operationId,
        semanticPath = openApiOperationSemanticPath(effectivePath, httpVerb)
    )
}

private fun selectDefaultProviderOpenApi(apis: List<ZdlApiDescriptor>): ZdlApiDescriptor? =
    apis.firstOrNull { it.type == "openapi" && it.role == "provider" && it.name == "default" }
        ?: apis.firstOrNull { it.type == "openapi" && it.role == "provider" }

private fun httpVerb(options: Map<String, Any?>): String? =
    listOf("get", "post", "put", "delete", "patch")
        .firstOrNull { options.containsKey(it) }

private fun annotationPath(option: Any?): String? =
    option.asString() ?: option.asMap()["path"].asString()

private fun annotationOperationId(option: Any?): String? =
    option.asMap()["operationId"].asString()

private fun normalizeHttpPath(basePath: String, relativePath: String): String {
    val base = basePath.trim()
    val relative = relativePath.trim()
    return when {
        base.isEmpty() && relative.isEmpty() -> "/"
        base.isEmpty() -> normalizeLeadingSlash(relative)
        relative.isEmpty() -> normalizeLeadingSlash(base)
        else -> normalizeLeadingSlash(base.removeSuffix("/") + "/" + relative.removePrefix("/"))
    }
}

private fun normalizeLeadingSlash(path: String): String =
    if (path.startsWith("/")) path else "/$path"

private fun openApiOperationSemanticPath(path: String, verb: String): String =
    "$.paths['${path.replace("'", "\\'")}'].$verb"
