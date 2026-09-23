package io.github.mbaliga.fylz.storage.testing

import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Describes one node of a synthetic file tree fixture (P0.0), so operation tests can build a
 * directory shape once and assert against it after a copy, move or recycle.
 */
sealed interface TreeNode {
    val name: String

    /** A file of [sizeBytes], filled with reproducible pseudo-random bytes derived from [seed]. */
    data class FileNode(
        override val name: String,
        val sizeBytes: Int,
        val seed: Long = name.hashCode().toLong(),
    ) : TreeNode

    data class DirNode(override val name: String, val children: List<TreeNode>) : TreeNode
}

/** Materializes [nodes] as real files and directories under [root]. */
fun buildTree(root: File, nodes: List<TreeNode>) {
    require(root.isDirectory || root.mkdirs()) { "Unable to create fixture root $root" }
    nodes.forEach { node -> writeNode(root, node) }
}

private fun writeNode(parent: File, node: TreeNode) {
    when (node) {
        is TreeNode.DirNode -> {
            val dir = File(parent, node.name)
            check(dir.mkdirs()) { "Unable to create fixture directory $dir" }
            node.children.forEach { writeNode(dir, it) }
        }
        is TreeNode.FileNode -> {
            val file = File(parent, node.name)
            file.outputStream().use { out ->
                val random = Random(node.seed)
                val buffer = ByteArray(minOf(node.sizeBytes, 64 * 1024).coerceAtLeast(1))
                var remaining = node.sizeBytes
                while (remaining > 0) {
                    val chunk = minOf(remaining, buffer.size)
                    random.nextBytes(buffer, 0, chunk)
                    out.write(buffer, 0, chunk)
                    remaining -= chunk
                }
            }
        }
    }
}

/**
 * Deep, byte-for-byte comparison of two directory trees. Returns a human-readable diff, one entry
 * per mismatch; empty means the trees are identical (same relative paths, same file bytes).
 */
fun diffTrees(expected: File, actual: File): List<String> {
    val differences = mutableListOf<String>()
    compareDir(expected, actual, "", differences)
    return differences
}

private fun compareDir(expected: File, actual: File, relative: String, differences: MutableList<String>) {
    if (!actual.isDirectory) {
        differences += "missing directory: $relative"
        return
    }
    val expectedNames = expected.list()?.toSortedSet().orEmpty()
    val actualNames = actual.list()?.toSortedSet().orEmpty()
    (expectedNames - actualNames).forEach { differences += "missing: $relative/$it" }
    (actualNames - expectedNames).forEach { differences += "unexpected: $relative/$it" }
    expectedNames.intersect(actualNames).forEach { name ->
        val expectedChild = File(expected, name)
        val actualChild = File(actual, name)
        val childRelative = "$relative/$name"
        when {
            expectedChild.isDirectory != actualChild.isDirectory ->
                differences += "type mismatch: $childRelative"
            expectedChild.isDirectory -> compareDir(expectedChild, actualChild, childRelative, differences)
            !filesEqual(expectedChild, actualChild) -> differences += "content mismatch: $childRelative"
        }
    }
}

private fun filesEqual(a: File, b: File): Boolean {
    if (a.length() != b.length()) return false
    return sha256(a).contentEquals(sha256(b))
}

private fun sha256(file: File): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest()
}
