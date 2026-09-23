package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Provider-neutral, cancellable copy and move implementation for SAF documents. */
class FileOperationService(
    private val context: Context,
    private val journal: OperationJournal = OperationJournal(context),
    private val recycleBin: RecycleBinService = RecycleBinService(context),
    private val transferEngines: TransferEngines = TransferEngines(context),
    private val verifySettings: VerifySettings = VerifySettings(context),
) {
    private val resolver: ContentResolver get() = context.contentResolver

    data class Progress(
        val itemIndex: Int,
        val itemCount: Int,
        val displayName: String,
        val completedBytes: Long,
        val totalBytes: Long?,
    )

    private data class TargetPlan(
        val requestedName: String,
        val existing: DocNode? = null,
    )

    suspend fun copy(
        sourceUris: List<Uri>,
        destinationTreeUri: Uri,
        conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
        nameOverrides: Map<Uri, String> = emptyMap(),
        conflictResolutions: Map<Uri, ConflictPolicy> = emptyMap(),
        onProgress: (Progress) -> Unit = {},
    ): List<Uri> = transfer(sourceUris, destinationTreeUri, false, conflictPolicy, nameOverrides, conflictResolutions, onProgress)

    suspend fun move(
        sourceUris: List<Uri>,
        destinationTreeUri: Uri,
        conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
        nameOverrides: Map<Uri, String> = emptyMap(),
        conflictResolutions: Map<Uri, ConflictPolicy> = emptyMap(),
        onProgress: (Progress) -> Unit = {},
    ): List<Uri> = transfer(sourceUris, destinationTreeUri, true, conflictPolicy, nameOverrides, conflictResolutions, onProgress)

    fun operations(): List<FileOperation> = journal.list()

    /**
     * Completes a move whose verified destination was committed but whose original could not be
     * removed. This never copies again, so retry cannot create another destination duplicate.
     *
     * Uses [androidx.documentfile.provider.DocumentFile.fromSingleUri]-style single-URI lookups
     * (via [DocNode.load]) rather than a full [DocNode] tree walk: every check here is a plain
     * exists/length/delete on one already-known URI, none of which need a parent or children, so
     * this isn't the `fromSingleUri` defect P0.1 fixes (that defect is `listFiles()`/`parentFile`
     * on a single-URI node, neither of which this method calls).
     */
    suspend fun finishMoveCleanup(operationId: String): FileOperation = withContext(Dispatchers.IO) {
        val operation = journal.find(operationId) ?: error("Move operation not found.")
        require(operation.type == FileOperationType.MOVE) { "Only move operations support source cleanup." }
        var changed = false
        val items = operation.items.map { item ->
            if (item.errorCode != MOVE_SOURCE_DELETE_PENDING) return@map item
            coroutineContext.ensureActive()
            val destinationUri = item.destination
                ?: return@map item.copy(state = OperationState.NEEDS_ATTENTION, errorCode = MOVE_DESTINATION_MISSING)
            val destination = DocNode.load(resolver, destinationUri)
            if (destination == null) {
                return@map item.copy(state = OperationState.NEEDS_ATTENTION, errorCode = MOVE_DESTINATION_MISSING)
            }
            val source = DocNode.load(resolver, item.source)
            if (source == null) {
                changed = true
                return@map item.copy(state = OperationState.SUCCEEDED, errorCode = null)
            }
            if (!source.isDirectory && !destination.isDirectory) {
                val expected = source.size
                val actual = destination.size
                if (expected != null && actual != null && expected != actual) {
                    return@map item.copy(state = OperationState.NEEDS_ATTENTION, errorCode = MOVE_DESTINATION_UNVERIFIED)
                }
            }
            if (source.delete(resolver)) {
                changed = true
                item.copy(state = OperationState.SUCCEEDED, errorCode = null)
            } else {
                item.copy(state = OperationState.NEEDS_ATTENTION, errorCode = MOVE_SOURCE_DELETE_PENDING)
            }
        }
        val state = if (items.all { it.state == OperationState.SUCCEEDED }) {
            OperationState.SUCCEEDED
        } else {
            OperationState.NEEDS_ATTENTION
        }
        val updated = operation.copy(
            items = items,
            state = state,
            updatedAtMillis = if (changed) System.currentTimeMillis() else operation.updatedAtMillis,
        )
        journal.put(updated)
        updated
    }

    private suspend fun transfer(
        sourceUris: List<Uri>,
        destinationTreeUri: Uri,
        move: Boolean,
        conflictPolicy: ConflictPolicy,
        nameOverrides: Map<Uri, String>,
        conflictResolutions: Map<Uri, ConflictPolicy>,
        onProgress: (Progress) -> Unit,
    ): List<Uri> = withContext(Dispatchers.IO) {
        require(sourceUris.isNotEmpty()) { "Choose at least one item." }
        // destinationTreeUri comes from the system OpenDocumentTree picker (A2), so it names a
        // tree, not yet a document within it; its own root is the destination document.
        val destinationRootUri = DocumentsContract.buildDocumentUriUsingTree(
            destinationTreeUri,
            DocumentsContract.getTreeDocumentId(destinationTreeUri),
        )
        val destination = DocNode.load(resolver, destinationRootUri)
            ?: error("Unable to open the destination folder.")
        require(destination.isDirectory && destination.canWrite) {
            "The destination folder is not writable."
        }

        // P1.4: decided once for the whole transfer, from the destination's own root -- every
        // item in a batch lands on the same volume, so there is nothing per-item to re-classify.
        val verifyThisTransfer = shouldVerify(verifySettings.mode.value, classifyDestination(context, destinationTreeUri))

        val operation = FileOperation(
            type = if (move) FileOperationType.MOVE else FileOperationType.COPY,
            items = sourceUris.map { uri ->
                val source = DocNode.load(resolver, uri)
                OperationItem(
                    source = uri,
                    destination = destinationTreeUri,
                    displayName = nameOverrides[uri] ?: source?.name ?: "untitled",
                    expectedBytes = source?.size?.takeIf { source.isDirectory.not() },
                    state = OperationState.QUEUED,
                )
            },
            conflictPolicy = conflictPolicy,
            state = OperationState.PREFLIGHT,
        )
        journal.put(operation)
        var current = operation.copy(
            state = OperationState.RUNNING,
            items = operation.items.map { it.copy(state = OperationState.RUNNING) },
            updatedAtMillis = System.currentTimeMillis(),
        )
        journal.put(current)

        try {
            val result = buildList {
                sourceUris.forEachIndexed { index, sourceUri ->
                    coroutineContext.ensureActive()
                    val source = DocNode.load(resolver, sourceUri)
                        ?: error("Unable to open a selected item.")
                    // P1.5: a Preflight sheet's own auto-rename choice, when the source's own name
                    // has a problem at the destination -- the copy lands under this name, the
                    // source itself is never touched.
                    val sourceName = nameOverrides[sourceUri] ?: source.name
                    // P1.6: a ConflictSheet's own per-item choice, resolved up front against this
                    // exact item before the transfer ever started; the batch-level conflictPolicy
                    // is only a fallback for a conflict nothing pre-resolved (see resolveTargetPlan's
                    // own ASK doc -- normally unreachable once every conflict has been checked and
                    // resolved before this call, per the UI's own PreflightSheet-then-ConflictSheet
                    // sequencing in FylzV1App.kt).
                    val itemPolicy = conflictResolutions[sourceUri] ?: conflictPolicy
                    val plan = resolveTargetPlan(destination, sourceName, source.lastModified, itemPolicy)
                    if (plan == null) {
                        current = updateItem(current, index) {
                            it.copy(state = OperationState.SUCCEEDED, errorCode = "SKIPPED_CONFLICT")
                        }
                        journal.put(current)
                        return@forEachIndexed
                    }

                    val total = source.size.takeIf { !source.isDirectory }

                    // P1.3/A5: try a direct move first -- LocalFileTransfer's File.renameTo on
                    // the same volume, or DocumentsTransfer's DocumentsContract.moveDocument when
                    // the provider supports it -- before falling back to the copy-then-delete
                    // path below. Only when there's no conflict to resolve: a Replace needs the
                    // aside-then-recycle dance finalizeTarget already handles, which a direct move
                    // bypasses entirely. sourceParent is always null here -- no caller of
                    // FileOperationService.move() tracks a selected item's parent folder today, so
                    // DocumentsTransfer's own moveDocument fast path (the only one that needs it)
                    // stays unreachable until a future caller supplies one; LocalFileTransfer's
                    // rename fast path needs no parent and works today.
                    if (move && plan.existing == null) {
                        val moved = transferEngines.forPair(source, destination).moveFile(
                            source = source,
                            sourceParent = null,
                            destinationDirectory = destination,
                            requestedName = plan.requestedName,
                        )
                        if (moved != null) {
                            current = updateItem(current, index) { item ->
                                item.copy(
                                    destination = moved.uri,
                                    completedBytes = item.expectedBytes ?: total ?: item.completedBytes,
                                    state = OperationState.SUCCEEDED,
                                    errorCode = null,
                                )
                            }
                            journal.put(current)
                            add(moved.uri)
                            return@forEachIndexed
                        }
                    }

                    val progressThrottle = ProgressWriteThrottle()
                    val staged = copyDocument(
                        source = source,
                        destinationDirectory = destination,
                        requestedName = stagingName(current.id, index, plan.requestedName),
                        progressName = plan.requestedName,
                        itemIndex = index,
                        itemCount = sourceUris.size,
                        totalBytes = total,
                        onStaged = { uri ->
                            // Recorded before the first byte is written (P0.6): OperationRunner.recover
                            // can find and delete exactly this document if the process dies mid-copy.
                            current = updateItem(current, index) { it.copy(stagingUri = uri) }
                            journal.put(current)
                        },
                    ) { progress ->
                        current = updateItem(current, index) { item ->
                            item.copy(
                                completedBytes = progress.completedBytes,
                                expectedBytes = progress.totalBytes ?: item.expectedBytes,
                                state = OperationState.RUNNING,
                            )
                        }
                        // P1.2: no more often than every 250 ms or every 8 MiB -- this used to call
                        // journal.put on every single buffer read (every 8 KiB).
                        val isFinalForThisFile = progress.totalBytes != null &&
                            progress.completedBytes >= progress.totalBytes
                        if (progressThrottle.shouldWrite(progress.completedBytes, isFinalForThisFile)) {
                            journal.put(current)
                        }
                        onProgress(progress)
                    }
                    // P1.4: only ever for a file, never a directory -- a folder has no single
                    // byte stream for one hash to describe, and operation_items.sha256 has room
                    // for exactly one. A mismatch throws ChecksumMismatchException, which unwinds
                    // straight out of this coroutine (finalizeTarget below is never reached, so
                    // staged is never renamed to its final name) to the whole-operation catch
                    // below, which marks this item FAILED with that exception's own name -- and,
                    // deliberately unlike a size mismatch, never deletes staged: a checksum
                    // failure is worth keeping to inspect, not just corruption to discard.
                    if (verifyThisTransfer && !source.isDirectory) {
                        val hash = verifyChecksum(resolver, source, staged)
                        current = updateItem(current, index) { it.copy(sha256 = hash) }
                        journal.put(current)
                    }

                    val copied = finalizeTarget(destination, plan, staged)

                    if (move && !source.delete(resolver)) {
                        current = updateItem(current, index) { item ->
                            item.copy(
                                destination = copied.uri,
                                completedBytes = item.expectedBytes ?: item.completedBytes,
                                state = OperationState.NEEDS_ATTENTION,
                                errorCode = MOVE_SOURCE_DELETE_PENDING,
                                stagingUri = null,
                            )
                        }
                        journal.put(current)
                        add(copied.uri)
                        return@forEachIndexed
                    }

                    current = updateItem(current, index) { item ->
                        item.copy(
                            destination = copied.uri,
                            completedBytes = item.expectedBytes ?: item.completedBytes,
                            state = OperationState.SUCCEEDED,
                            errorCode = null,
                            stagingUri = null,
                        )
                    }
                    journal.put(current)
                    add(copied.uri)
                }
            }
            val finalState = if (current.items.any { it.state == OperationState.NEEDS_ATTENTION }) {
                OperationState.NEEDS_ATTENTION
            } else {
                OperationState.SUCCEEDED
            }
            current = current.copy(state = finalState, updatedAtMillis = System.currentTimeMillis())
            journal.put(current)
            result
        } catch (cancelled: CancellationException) {
            current = current.copy(
                state = OperationState.CANCELLED,
                items = current.items.map {
                    if (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) {
                        it.copy(state = OperationState.CANCELLED, errorCode = "USER_CANCELLED")
                    } else it
                },
                updatedAtMillis = System.currentTimeMillis(),
            )
            journal.put(current)
            throw cancelled
        } catch (failure: Throwable) {
            current = current.copy(
                state = if (current.items.any { it.state == OperationState.NEEDS_ATTENTION }) {
                    OperationState.NEEDS_ATTENTION
                } else {
                    OperationState.FAILED
                },
                items = current.items.map {
                    if (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) {
                        it.copy(state = OperationState.FAILED, errorCode = failure::class.java.simpleName)
                    } else it
                },
                updatedAtMillis = System.currentTimeMillis(),
            )
            journal.put(current)
            throw failure
        }
    }

    private fun updateItem(
        operation: FileOperation,
        index: Int,
        transform: (OperationItem) -> OperationItem,
    ): FileOperation = operation.copy(
        items = operation.items.mapIndexed { itemIndex, item ->
            if (itemIndex == index) transform(item) else item
        },
        updatedAtMillis = System.currentTimeMillis(),
    )

    /**
     * Copies [source] (a file or a whole tree) under [destinationDirectory] as [requestedName].
     *
     * Every file this recursion touches, at every depth, is verified against its source right
     * after it's written -- not just the top-level item, which is what let a truncated nested
     * file in a large folder copy go unnoticed before. A failure at any depth deletes exactly the
     * node this call created (the recursion above it does the same for its own node) and
     * rethrows, so a move's source delete (in [transfer]) is only ever reached once every nested
     * item has verified clean.
     */
    private suspend fun copyDocument(
        source: DocNode,
        destinationDirectory: DocNode,
        requestedName: String,
        progressName: String,
        itemIndex: Int,
        itemCount: Int,
        totalBytes: Long?,
        onStaged: (Uri) -> Unit = {},
        onProgress: (Progress) -> Unit,
    ): DocNode {
        coroutineContext.ensureActive()
        if (source.isDirectory) {
            val directory = destinationDirectory.createChild(
                resolver,
                DocumentsContract.Document.MIME_TYPE_DIR,
                requestedName,
            )
            onStaged(directory.uri)
            try {
                source.children(resolver).forEach { child ->
                    copyDocument(
                        source = child,
                        destinationDirectory = directory,
                        requestedName = child.name,
                        progressName = child.name,
                        itemIndex = itemIndex,
                        itemCount = itemCount,
                        totalBytes = null,
                        // Nested children live under a still-staged parent, already hidden from
                        // listings; only the top-level node's uri matters for orphan recovery.
                        onProgress = onProgress,
                    )
                }
                return directory
            } catch (failure: Throwable) {
                directory.delete(resolver)
                throw failure
            }
        }

        // P1.3/A5: LocalFileTransfer (File.renameTo-adjacent sendfile/splice, when both ends are
        // local to this device) or DocumentsTransfer (the provider-neutral path this used to run
        // unconditionally, now with copyDocument and a 512 KiB stream fallback instead of an 8 KiB
        // one) -- picked per file, transparently to every caller of copyDocument.
        val written = transferEngines.forPair(source, destinationDirectory).copyFile(
            source = source,
            destinationDirectory = destinationDirectory,
            requestedName = requestedName,
            onStaged = { staged -> onStaged(staged.uri) },
        ) { completed ->
            onProgress(
                Progress(
                    itemIndex = itemIndex,
                    itemCount = itemCount,
                    displayName = progressName,
                    completedBytes = completed,
                    totalBytes = totalBytes,
                ),
            )
        }
        try {
            verifyFile(source, written)
        } catch (failure: Throwable) {
            written.delete(resolver)
            throw failure
        }
        return written
    }

    private fun verifyFile(source: DocNode, target: DocNode) {
        val expected = source.size
        val actual = target.size
        if (expected != null && actual != null) {
            check(expected == actual) {
                "Copy verification failed for ${source.name}: expected $expected bytes, wrote $actual bytes."
            }
        }
    }

    /**
     * [staged] is always still under its `.fylz-part-*` staging name at this point (P0.6): every
     * copy writes there first, regardless of conflict policy, and only reaches its real name here,
     * after verification. On a plain create or Keep-both (no [TargetPlan.existing]), that's a
     * direct rename. A Replace conflict goes through [RecycleBinService]'s shared policy (also used
     * by its own restore): the existing item is renamed aside and recycled -- into [destination]'s
     * `.fylz-trash` if it has one -- only after the replacement has actually landed under the
     * requested name, never before.
     */
    private suspend fun finalizeTarget(destination: DocNode, plan: TargetPlan, staged: DocNode): DocNode {
        val existing = plan.existing
        if (existing == null) {
            return if (staged.name == plan.requestedName) staged else staged.rename(resolver, plan.requestedName)
        }
        return recycleBin.replaceWithRecycleFallback(
            destinationRoot = destination,
            existing = existing,
            staged = staged,
            requestedName = plan.requestedName,
            originalParentUri = destination.uri,
        )
    }

    /**
     * @param policy the effective per-item policy (P1.6) -- [ASK][ConflictPolicy.ASK] reaching
     *   here at all means a conflict this specific item never got a real resolution for: the UI's
     *   own `ConflictSheet` (`ui/FylzV1App.kt`) checks every source item against the destination
     *   and resolves each one before ever starting the transfer, so in practice this only throws
     *   for a caller that bypasses that check entirely (a direct `copy`/`move` call, as every
     *   existing test still makes with its default `ConflictPolicy.ASK`) or a conflict that
     *   appeared in the narrow window between that check and this actually running.
     */
    private fun resolveTargetPlan(
        destination: DocNode,
        requestedName: String,
        sourceLastModified: Long?,
        policy: ConflictPolicy,
    ): TargetPlan? {
        val existing = destination.findChild(resolver, requestedName)
            ?: return TargetPlan(requestedName = requestedName)
        return when (policy) {
            ConflictPolicy.ASK -> error("A file named $requestedName already exists.")
            ConflictPolicy.SKIP -> null
            ConflictPolicy.KEEP_BOTH -> TargetPlan(requestedName = uniqueName(destination, requestedName))
            ConflictPolicy.REPLACE -> TargetPlan(requestedName = requestedName, existing = existing)
            ConflictPolicy.REPLACE_IF_NEWER -> {
                val existingModified = existing.lastModified
                val newer = sourceLastModified != null && existingModified != null && sourceLastModified > existingModified
                if (newer) TargetPlan(requestedName = requestedName, existing = existing) else null
            }
        }
    }

    private fun uniqueName(destination: DocNode, requestedName: String): String {
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$base ($index)$extension"
            if (destination.findChild(resolver, candidate) == null) return candidate
            index += 1
        }
    }

    private companion object {
        const val MOVE_SOURCE_DELETE_PENDING = "MOVE_SOURCE_DELETE_PENDING"
        const val MOVE_DESTINATION_MISSING = "MOVE_DESTINATION_MISSING"
        const val MOVE_DESTINATION_UNVERIFIED = "MOVE_DESTINATION_UNVERIFIED"
    }
}

/** Recognizes a staged write's name, wherever a caller (P0.6) needs to hide one from a listing or
 * a search -- must match what [stagingName] produces. */
internal const val STAGING_NAME_PREFIX = ".fylz-part-"

/**
 * Every file (or top-level folder) copy writes under this name first, regardless of conflict
 * policy, and reaches [requestedName] only after verification (P0.6, defect 5): a process that
 * dies mid-write leaves a `.fylz-part-*` orphan, never a half-written file under the name the user
 * would actually see. [operationId] and [itemIndex] make the name unique across concurrent and
 * historical operations without needing a provider round trip to check for collisions, and let
 * [OperationRunner.recover] identify which operation and item an orphan belongs to purely from its
 * name if the journal record itself is ever unreadable. Truncated to 255 UTF-8 bytes -- the limit
 * most filesystems this app's providers sit on enforce -- without splitting a multi-byte character.
 */
internal fun stagingName(operationId: String, itemIndex: Int, requestedName: String): String {
    val safeName = requestedName.replace('/', '_')
    return "$STAGING_NAME_PREFIX$operationId-$itemIndex-$safeName".truncateUtf8Bytes(255)
}

/** True for any name [stagingName] could have produced -- a caller hiding staged writes from a
 * listing or a search only needs to check this, not reconstruct the exact name. */
internal fun isStagingName(name: String): Boolean = name.startsWith(STAGING_NAME_PREFIX)

private fun String.truncateUtf8Bytes(maxBytes: Int): String {
    val bytes = toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return this
    var end = maxBytes
    // Back off until not mid-way through a multi-byte UTF-8 sequence: a continuation byte's two
    // high bits are `10`, so anywhere else is a safe place to cut.
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return String(bytes, 0, end, Charsets.UTF_8)
}
