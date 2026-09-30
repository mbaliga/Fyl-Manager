package io.github.mbaliga.fylz.index

import android.content.Context
import io.github.mbaliga.fylz.data.FylzDatabase

/**
 * App-private storage for the local file index and its smart collections.
 *
 * P1.12: backed by [FylzDatabase] (plain SQLite, matching [io.github.mbaliga.fylz.data.OperationsDao]'s
 * own precedent) instead of four separate JSON files under `filesDir/local-index/`. Every method
 * here keeps the exact synchronous, no-coroutine signature its two callers
 * ([io.github.mbaliga.fylz.IndexManagerActivity], [io.github.mbaliga.fylz.PostV1ToolsActivity])
 * already call directly from Compose click handlers -- [FylzDatabase] migrates the old JSON files
 * into the new tables once, the first time it's opened on a given install, so neither caller here
 * needs to know that migration happened.
 */
class LocalIndexStore(context: Context) {
    private val database = FylzDatabase(context.applicationContext)

    fun files(): List<IndexedFile> = IndexDao.files(database.readableDatabase)

    fun replaceFiles(rootUri: String, entries: List<IndexedFile>) =
        IndexDao.replaceFilesForRoot(database.writableDatabase, rootUri, entries)

    fun removeRoot(rootUri: String) = IndexDao.removeRoot(database.writableDatabase, rootUri)

    fun clearFiles() = IndexDao.clearFiles(database.writableDatabase)

    fun scopes(): List<IndexScope> = IndexDao.scopes(database.readableDatabase)

    fun putScope(scope: IndexScope) = IndexDao.putScope(database.writableDatabase, scope)

    fun putScopes(scopes: List<IndexScope>) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            scopes.forEach { IndexDao.putScope(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun collections(): List<SmartCollection> = IndexDao.collections(database.readableDatabase)

    fun putCollection(collection: SmartCollection) =
        IndexDao.putCollection(database.writableDatabase, collection.copy(updatedAtMillis = System.currentTimeMillis()))

    fun removeCollection(id: String) = IndexDao.removeCollection(database.writableDatabase, id)

    fun state(): IndexState = IndexDao.state(database.readableDatabase)

    fun putState(state: IndexState) = IndexDao.putState(database.writableDatabase, state)

    fun setPaused(paused: Boolean) = putState(state().copy(paused = paused))

    /**
     * Substring-style listing, unchanged in spirit from the pre-P1.12 JSON store: every term is
     * matched case-insensitively against name, extension or a tag, not FTS token matching (which
     * would, for example, miss "report.pdf" for the query "port") -- this is the query
     * [io.github.mbaliga.fylz.IndexManagerActivity]/[io.github.mbaliga.fylz.PostV1ToolsActivity]'s
     * own free-text field calls directly, and neither expects FTS-style tokenization.
     */
    fun query(text: String = "", collectionId: String? = null): List<IndexedFile> {
        val db = database.readableDatabase
        val collection = collectionId?.let { id -> IndexDao.collections(db).firstOrNull { it.id == id } }
        return IndexDao.likeSearch(db, text.trim().lowercase())
            .asSequence()
            .filter { collection == null || SmartCollectionEngine.matches(it, collection) }
            .sortedWith(compareByDescending<IndexedFile> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }
}
