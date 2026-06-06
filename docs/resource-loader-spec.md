# Spec: MavenResourceLoader — Kotlin Multiplatform

## Objetivo

Resolver artefactos Maven por GAV, descargar los JARs, cachearlos localmente y exponer su contenido interno como recursos navegables por el LSP. Compatible con JVM (M2 local + repositorios remotos) y Kotlin/JS (Node.js Desktop y VS Code Web).

---

## Configuración

El master YAML declara los repositorios Maven disponibles:

```yaml
maven:
  repositories:
    - id: central
      url: https://repo1.maven.org/maven2
    - id: acme-nexus
      url: https://nexus.acme.com/repository/maven-releases
      auth: ${NEXUS_TOKEN}          # Bearer token, variable de entorno
    - id: acme-nexus-basic
      url: https://nexus.acme.com/repository/maven-releases
      username: ${NEXUS_USER}
      password: ${NEXUS_PASSWORD}   # Basic auth alternativa
```

El modelo de datos correspondiente:

```kotlin
data class MavenRepository(
    val id: String,
    val url: String,                // sin trailing slash
    val auth: MavenAuth? = null
)

sealed class MavenAuth {
    data class Bearer(val token: String) : MavenAuth()
    data class Basic(val username: String, val password: String) : MavenAuth()
}

data class MavenCoordinates(
    val groupId: String,
    val artifactId: String,
    val version: String,            // puede ser "RELEASE" o "LATEST"
    val classifier: String? = null,
    val extension: String = "jar"
)
```

Las variables de entorno en los valores de auth se resuelven en tiempo de carga de configuración, no en tiempo de descarga.

---

## Resolución de versiones

### Versión explícita

Si `version` no es `RELEASE` ni `LATEST`, se usa directamente sin consultar metadata.

### RELEASE y LATEST

Se consulta `maven-metadata.xml` del artefacto en cada repositorio configurado, en orden de declaración, hasta obtener respuesta:

```
GET {repo.url}/{groupPath}/{artifactId}/maven-metadata.xml
```

Del XML se extrae:
- `RELEASE` → elemento `<release>` dentro de `<versioning>`
- `LATEST` → elemento `<latest>` dentro de `<versioning>`

Si el elemento no existe en el XML se considera que ese repositorio no tiene la versión pedida y se pasa al siguiente.

El resultado de la resolución se cachea con TTL de 1 hora. Después del TTL se vuelve a consultar metadata en el siguiente acceso.

---

## Construcción de URL

```
{repo.url}/{groupId con puntos→slashes}/{artifactId}/{version}/{artifactId}-{version}[-{classifier}].{extension}
```

Ejemplos:

```
https://repo1.maven.org/maven2/io/zenwave360/zenwave-sdk/2.6.0/zenwave-sdk-2.6.0.jar
https://repo1.maven.org/maven2/io/zenwave360/zenwave-sdk/2.6.0/zenwave-sdk-2.6.0-templates.jar
```

---

## Estrategia de caché del JAR

### JVM y Node.js Desktop

El JAR descargado se persiste en disco:

```
{cacheRoot}/maven/{groupPath}/{artifactId}/{version}/{artifactId}-{version}[-{classifier}].jar
```

`cacheRoot` por defecto:
- JVM: `~/.cache/zenwave/maven` (o `%LOCALAPPDATA%\zenwave\maven` en Windows)
- Node.js: mismo comportamiento vía `os.homedir()` + path convencional

Si el archivo existe en disco y la versión es explícita (no RELEASE/LATEST), se usa directamente sin hacer ninguna petición de red.

Si la versión fue resuelta desde RELEASE/LATEST, se verifica que el archivo en caché corresponde a la versión resuelta. Si no coincide se descarga de nuevo.

### VS Code Web

Sin acceso a filesystem real. La caché se mantiene en memoria durante la sesión (Map en el Web Worker). No hay persistencia entre sesiones. El TTL de metadata sigue aplicando dentro de la sesión.

---

## Descarga

La descarga se hace con `fetch()` estándar (disponible en Kotlin/JS y abstractible en JVM con expect/actual).

Se intenta cada repositorio en orden de declaración hasta obtener HTTP 200. Un 404 en un repositorio no es error: se continúa con el siguiente. Cualquier otro código HTTP (401, 403, 500) sí se registra como warning en el log.

Si ningún repositorio resuelve el artefacto se lanza `MavenArtifactNotFoundException` con el GAV y la lista de repositorios intentados.

La descarga no tiene retry automático. Si falla, el error se propaga al caller para que el LSP decida si mostrar un aviso o degradar la funcionalidad.

---

## Acceso al contenido del JAR

Una vez descargado o localizado en caché, el JAR se abre como ZIP. La interfaz de acceso:

```kotlin
interface JarContent {
    suspend fun readEntry(path: String): String?           // null si no existe
    suspend fun listEntries(prefix: String): List<String>  // rutas internas
    suspend fun exists(path: String): Boolean
}
```

`path` es la ruta interna del JAR tal como aparece en el ZIP, por ejemplo `templates/asyncapi.hbs` o `schemas/order-events.yaml`.

En JVM se implementa con `java.util.zip.ZipFile`. En JS con `jszip`. La apertura del ZIP es lazy: no se descomprime nada hasta que se llama a `readEntry`.

Si el mismo JAR se accede múltiples veces en la misma sesión, el handle del ZIP se mantiene abierto en memoria (no se cierra y reabre en cada lectura).

---

## Integración con ResourceResolver

El `MavenResourceLoader` se registra como fuente en la jerarquía de resolución del LSP. Un URI de recurso Maven tiene el scheme:

```
zenwave://maven/{groupId}/{artifactId}/{version}/{internalPath}
```

Ejemplo:

```
zenwave://maven/io.zenwave360/zenwave-sdk/2.6.0/templates/asyncapi.hbs
```

Cuando el `ResourceResolver` recibe un URI con ese scheme, delega en el `MavenResourceLoader`, que resuelve la versión si hace falta, localiza o descarga el JAR, y devuelve el contenido del entry interno.

---

## Comportamiento de errores y degradación

| Situación | Comportamiento |
|---|---|
| Repositorio no alcanzable (timeout) | Warning en log, continúa con el siguiente repositorio |
| 404 en todos los repositorios | `MavenArtifactNotFoundException`, el LSP muestra aviso al usuario |
| 401 / 403 | Warning con indicación de credenciales, no reintenta |
| JAR corrupto o ZIP inválido | `MavenJarCorruptedException`, se elimina de caché y se reintenta una vez |
| Entry interno no existe en el JAR | `readEntry` devuelve null, el caller decide |
| Metadata XML malformado | Se ignora ese repositorio, se continúa con el siguiente |

---

## Lo que está fuera de scope

- Resolución de dependencias transitivas del POM. Solo se descarga el JAR indicado explícitamente.
- Ejecución de código Java contenido en el JAR.
- Publicación o escritura en repositorios Maven.
- Soporte para repositorios que requieran autenticación con certificado cliente.
- Rangos de versión (`[1.0,2.0)`). Solo se soportan versiones explícitas, `RELEASE` y `LATEST`.
