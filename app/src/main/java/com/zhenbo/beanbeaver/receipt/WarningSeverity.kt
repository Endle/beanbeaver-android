package com.zhenbo.beanbeaver.receipt

import uniffi.bb_receipt_ffi.ReceiptResult
import uniffi.bb_receipt_ffi.ReceiptWarning
import uniffi.bb_receipt_ffi.ReceiptWarningKind

/**
 * How loudly this app reports a parser finding. Kotlin twin of iOS
 * `WarningSeverity.swift`.
 *
 * The core reports *what* it found and says nothing about what it's worth —
 * [ReceiptWarningKind] deliberately carries no severity, because the ledger
 * formatter, the matcher and this app all rank the same finding differently.
 * This file is where the phone answers the question, and it should stay the
 * only place that does: no screen may re-derive severity from a kind, and
 * nothing anywhere may read [ReceiptWarning.message] to work out what happened.
 *
 * Deliberately free of Compose and Android imports, like [SpendSummary]: the
 * ranking is the part worth pinning with a JVM test, and the colors it earns
 * live in `ui/Format.kt` next to the rest of the display vocabulary.
 */
enum class WarningSeverity {
    /**
     * True, recorded, and not worth a word of the user's attention. The card
     * may already be showing it by other means.
     */
    INFO,

    /** Worth reading before filing, not worth flagging the whole receipt over. */
    NOTICE,

    /** The numbers are wrong or incomplete — the receipt gets a badge. */
    ATTENTION,
}

/**
 * The rank this build gives a finding.
 *
 * `when` over an enum with an `else` is normally worth avoiding, but kinds are
 * **additive**: a core release may introduce one this build has never heard of,
 * and the generated Kotlin enum will happily hand it over. Degrade to "show it
 * quietly" rather than crash.
 */
val ReceiptWarningKind.severity: WarningSeverity
    get() = when (this) {
        // The transaction doesn't add up. Nothing else here is as bad, and both
        // of these mean a posting is missing or duplicated.
        ReceiptWarningKind.TOTAL_MISMATCH,
        ReceiptWarningKind.SUBTOTAL_MISMATCH,
        -> WarningSeverity.ATTENTION

        // Something was probably lost — a price with no description, or a line
        // thrown away for being implausible. Worth showing; not proof of a
        // defect, since receipts print stray amounts that are not items.
        ReceiptWarningKind.POSSIBLE_MISSED_ITEM,
        ReceiptWarningKind.DROPPED_IMPLAUSIBLE_PRICE,
        -> WarningSeverity.NOTICE

        // The payment block and the TOTAL row disagree, so one of the two is
        // definitely misread — worth a look before filing. Not ATTENTION
        // though: the core cannot tell which side is wrong from the arithmetic
        // alone, so it repairs nothing and the formatter falls back to a single
        // payment posting. The entry still balances; what is unreliable is the
        // breakdown of *how* it was paid.
        ReceiptWarningKind.TENDER_MISMATCH -> WarningSeverity.NOTICE

        // The parser repaired a mangled price and reconciled it against the
        // summary. Nothing to do — the note exists so the repair is auditable.
        ReceiptWarningKind.PRICE_AUTO_CORRECTED -> WarningSeverity.INFO

        // An item matched no rule. Normal on any real receipt (166 of them
        // across the 124-receipt corpus), and the item row already says
        // "Uncategorized" in place of its tags — so it must never badge. This
        // is the whole reason kinds exist: as an untyped warning it made two
        // receipts in three look broken.
        ReceiptWarningKind.UNCATEGORIZED_ITEM -> WarningSeverity.INFO

        else -> WarningSeverity.NOTICE
    }

val ReceiptWarning.severity: WarningSeverity
    get() = kind.severity

/** The findings worth showing in the result card. */
val List<ReceiptWarning>.worthShowing: List<ReceiptWarning>
    get() = filter { it.severity >= WarningSeverity.NOTICE }

/**
 * One finding shown on a receipt: a parser warning the core reported, or one this
 * app worked out for itself from the parse.
 *
 * Not every finding worth a word is a [ReceiptWarning]. A missing date is a
 * *field* of [ReceiptResult] (`dateIsPlaceholder`), not a warning, and core stays
 * that way on purpose — what it *does* about an unknown date is a formatter
 * decision, and what that is *worth* is this file's call, like every other
 * severity here. This type is where the two kinds meet, so the banner and the
 * badge read one list instead of two.
 */
data class ReceiptFinding(val message: String, val severity: WarningSeverity)

/**
 * Everything about this parse worth telling the user, loudest first.
 *
 * A receipt with no date is [WarningSeverity.ATTENTION], on the same footing as a
 * total that doesn't add up: core substitutes *today* so the entry is still valid
 * beancount, which means an uncorrected one files a February shop under August
 * and nothing downstream can tell. That it lights on roughly half of a real scan
 * pile is the finding, not a reason to soften it — unlike
 * [ReceiptWarningKind.UNCATEGORIZED_ITEM], whose badge was retired for firing on
 * correct parses, every one of these is a receipt whose ledger date is wrong.
 */
val ReceiptResult.findings: List<ReceiptFinding>
    get() = buildList {
        if (dateIsPlaceholder) {
            add(
                ReceiptFinding(
                    "No date found on this receipt — it will be filed under today's date.",
                    WarningSeverity.ATTENTION,
                ),
            )
        }
        addAll(warnings.worthShowing.map { ReceiptFinding(it.message, it.severity) })
    }

/**
 * Two receivers, one name, so a `@JvmName` is required on one of them: both erase
 * to `getHighestSeverity(List)` on the JVM.
 */
val List<ReceiptWarning>.highestSeverity: WarningSeverity?
    @JvmName("highestWarningSeverity")
    get() = maxOfOrNull { it.severity }

val List<ReceiptFinding>.highestSeverity: WarningSeverity?
    get() = maxOfOrNull { it.severity }
