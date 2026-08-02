package io.github.mbaliga.fylz.storage

object FileNamePolicy {
    fun validate(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "A name is required." }
        require(value != "." && value != "..") { "That name is reserved." }
        require('/' !in value && '\u0000' !in value) {
            "The name contains an unsupported character."
        }
        return value
    }
}
