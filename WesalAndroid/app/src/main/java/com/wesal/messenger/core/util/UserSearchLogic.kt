package com.wesal.messenger.core.util

/**
 * Round 12 (addendum 8): query planning for the global user search,
 * extracted as pure logic so the planning is JVM-testable.
 *
 * PROVEN defect this fixes: the global search gated its numeric branch on
 * the spec §4.5 SEARCH range (10000..100000, [WesalIdUtils
 * .isSpecSearchNumericId]), but real accounts are allocated IDs across
 * 10000..1000000 ([WesalIdUtils.generateRandomNumericCandidate]). Any user
 * whose ID exceeds 100000 — e.g. 789671 — could therefore NEVER be found
 * by ID in the global search, while the admin search (which plans with
 * [WesalIdUtils.extractSearchNumericIdQuery], full range) found them
 * immediately. The plan now uses the SAME full-range extraction as the
 * admin path.
 *
 * Exact-only is preserved: a partial ID plans to null (never a prefix /
 * contains lookup over other people's IDs), and non-numeric input plans
 * to null so the caller falls through to handle/name search.
 */
object UserSearchLogic {

    /**
     * The exact numeric ID to look up for [rawInput], or null when the
     * input is not a complete, valid WESAL numeric ID. Accepts the same
     * decorations the UI has always tolerated ("#789671", "@789671",
     * "ID: 789671", Arabic-Indic digits) via [WesalIdUtils].
     */
    fun planNumericIdQuery(rawInput: String): String? =
        WesalIdUtils.extractNumericIdQuery(rawInput).takeIf { it.isNotBlank() }
}
