package com.zhenbo.beanbeaver.receipt

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

val List<ReceiptWarning>.highestSeverity: WarningSeverity?
    get() = maxOfOrNull { it.severity }
