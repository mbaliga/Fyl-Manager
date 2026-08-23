package io.github.mbaliga.fylz.ai

import android.content.Context
import android.content.Intent

/**
 * The seam where ASOM (A System of Models) plugs in — the constellation's model/inferencing
 * router: download models and configure inference once, every app connects.
 *
 * This file is deliberately only the seam (docs/product/constellation-contract.md §3):
 * detection and the contract constants. The AIDL surface and binding code wait on ASOM
 * actually exposing the service — building both halves of an IPC contract from one side
 * bakes guesses into a shared boundary. When ASOM ships its side, [isInstalled] gates the
 * route: ASOM first, then the app's own local packs ([LocalModelManager]), then BYOK
 * ([AiClient]) — and [AiTransmissionPolicy]'s per-request preview keeps applying to anything
 * that leaves the device *whoever* routed it.
 *
 * Both name constants are PROPOSED: the creator names apps and their contracts, so these are
 * his to correct, and nothing else in the app may hardcode them.
 */
object AsomLink {

    /** ASOM's application id. PROPOSED, pending the creator's confirmation. */
    const val PROPOSED_PACKAGE = "dev.aarso.asom"

    /** The routing service's intent action. PROPOSED, pending the creator's confirmation. */
    const val PROPOSED_ROUTE_ACTION = "dev.aarso.asom.action.ROUTE"

    /**
     * Whether an app answering the routing contract is present. Resolves the service by
     * action+package rather than checking the package alone: an installed-but-older ASOM
     * without the routing service is honestly "not available to route", not a null-pointer
     * later at bind time.
     */
    fun isInstalled(context: Context): Boolean {
        val intent = Intent(PROPOSED_ROUTE_ACTION).setPackage(PROPOSED_PACKAGE)
        return context.packageManager.resolveService(intent, 0) != null
    }
}
