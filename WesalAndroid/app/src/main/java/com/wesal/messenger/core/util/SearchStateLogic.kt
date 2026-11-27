package com.wesal.messenger.core.util

/**
 * Round 13 (Addendum M1): verdict-vs-failure separation for global
 * search.
 *
 * Device-proven defect (2026-11-27, owner's phone): searching the
 * numeric ID 68742 — سحر محمد (@omeslam), registered that night and
 * visible in his own admin-panel users list — showed BOTH the honest
 * failure line («تعذر إجراء البحث حاليًا…», the query had died on the
 * exhausted READ quota) AND «لم يتم العثور على حساب مطابق» below it:
 * a not-found VERDICT for a search that never completed.
 *
 * Rule: the not-found state is a verdict and may render ONLY after a
 * query completes successfully with zero results. A failed query shows
 * ONLY the retryable failure state. Pure and unit-tested.
 */
object SearchStateLogic {

    fun shouldShowNoMatch(
        query: String,
        isSearching: Boolean,
        hasError: Boolean,
        resultCount: Int
    ): Boolean =
        query.isNotBlank() && !isSearching && !hasError && resultCount == 0
}
