package io.zenwave360.lsp.core.platform

/**
 * One spelling for the `file:` URIs that mean the same file, for use as a key: `file:///C:/x`, `file:///c:/x`,
 * `file:///c%3A/x`, `file://C:/x` and `file://localhost/c:/x` are all `file:///c:/x`. The drive letter is lower case
 * and written with `:`; every other segment is decoded and written as `encodeURIComponent` writes it. The case of the
 * rest of the path is kept, as a POSIX file system tells `A` and `a` apart. Anything that is not a `file:` URI is
 * returned as it is.
 *
 * Keys only: a URI shown to a client stays as the client sent it.
 */
object FileUris {
    fun key(uri: String): String {
        if (!uri.startsWith("file:", ignoreCase = true)) return uri
        val fragmentAt = uri.indexOf('#')
        val fragment = if (fragmentAt < 0) "" else uri.substring(fragmentAt)
        val path = uri.substring(5, if (fragmentAt < 0) uri.length else fragmentAt).trimStart('/')
        val segments = path.split('/').toMutableList()
        if (segments.size > 1 && segments[0].equals("localhost", ignoreCase = true)) segments.removeAt(0)
        val canonical = segments.mapIndexed { index, raw ->
            val decoded = decode(raw)
            if (index == 0 && decoded.length == 2 && decoded[0].isLetter() && decoded[1] == ':') {
                decoded.lowercase()
            } else {
                encodeSegment(decoded)
            }
        }
        return "file:///" + canonical.joinToString("/") + fragment
    }

    /** [value], a URI or a semantic id (`uri#path`), with the `file:` URI in it written as [key] writes it. */
    fun keyOfIdentifier(value: String): String {
        if (!value.startsWith("file:", ignoreCase = true)) return value
        val at = value.indexOf('#')
        return if (at < 0) key(value) else key(value.substring(0, at)) + value.substring(at)
    }

    /** A path segment as `encodeURIComponent` writes it, which is how VS Code writes the URIs it sends. */
    fun encodeSegment(name: String): String = buildString {
        for (byte in name.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.!~*'()")) append(c.toChar())
            else append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
    }

    private fun decode(value: String): String {
        if ('%' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val c = value[index]
            val hex = if (c == '%' && index + 2 < value.length) {
                value.substring(index + 1, index + 3).toIntOrNull(16)
            } else {
                null
            }
            if (hex != null) {
                bytes.add(hex.toByte())
                index += 3
            } else {
                c.toString().encodeToByteArray().forEach { bytes.add(it) }
                index += 1
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"
}
