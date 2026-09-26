package io.github.mbaliga.fylz.actions

/**
 * `<namespace>.<segment>(.<segment>)*`; namespace is `fylz`, `user`, or a bundle id, each shaped
 * like any other segment: `[a-z0-9]+(-[a-z0-9]+)*`. Mirrored in `core/crates/fylz-actions`.
 */
@JvmInline
value class ActionId(val value: String) {
    override fun toString(): String = value

    companion object {
        private val SEGMENT = Regex("[a-z0-9]+(-[a-z0-9]+)*")

        fun isValid(value: String): Boolean {
            val parts = value.split('.')
            return parts.size >= 2 && parts.all { SEGMENT.matches(it) }
        }

        fun parse(value: String): ActionId {
            require(isValid(value)) { "Invalid action id: $value" }
            return ActionId(value)
        }
    }
}
