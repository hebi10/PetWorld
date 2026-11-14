package com.haebi.petworld.data.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FirstImportGuardsTest {
    @Test
    fun previewApprovalCannotBeReplayedToAnotherFirebaseAccount() {
        val approved = FirstImportGuards.accountFingerprint("alice-uid")
        assertEquals(64, approved.length)
        assertNotEquals(approved, FirstImportGuards.accountFingerprint("bob-uid"))
        FirstImportGuards.requireSameAccount(approved, "alice-uid")
        expectReject { FirstImportGuards.requireSameAccount(approved, "bob-uid") }
        expectReject { FirstImportGuards.requireSameAccount("", "alice-uid") }
    }

    @Test
    fun sourceMustMatchPriorPreviewExactly() {
        FirstImportGuards.requireFreshSource("snapshot-a", "snapshot-a")
        expectReject { FirstImportGuards.requireFreshSource("snapshot-a", "snapshot-b") }
    }

    @Test
    fun cloudMutationAfterPreviewInvalidatesConsent() {
        FirstImportGuards.requireFreshReview(0, 0, 0, 0, 0, 0, false)
        expectReject {
            FirstImportGuards.requireFreshReview(0, 0, 0, 1, 0, 0, false)
        }
        expectReject {
            FirstImportGuards.requireFreshReview(0, 0, 0, 0, 1, 0, true)
        }
        expectReject {
            FirstImportGuards.requireFreshReview(0, 0, 0, 0, 0, 1, false)
        }
        expectReject {
            FirstImportGuards.requireFreshReview(0, 2, 0, 0, 2, 0, false)
        }
        FirstImportGuards.requireFreshReview(0, 2, 0, 0, 2, 0, true)
    }

    @Test
    fun identicalConsentIsStableOverRepeatedChecks() {
        repeat(1_000) {
            FirstImportGuards.requireSameAccount(
                FirstImportGuards.accountFingerprint("same-user"),
                "same-user",
            )
            FirstImportGuards.requireFreshSource("stable-sha256", "stable-sha256")
            FirstImportGuards.requireFreshReview(3, 1, 0, 3, 1, 0, true)
        }
    }

    private fun expectReject(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected unsafe migration to be rejected")
        } catch (_: IllegalArgumentException) {
            // Consent/data mismatch must fail before any cloud write.
        }
    }
}
