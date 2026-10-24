package com.veriborder.doc;

import com.veriborder.block.DeviceType;
import com.veriborder.border.BorderService;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Derived visa / endorsement state (26.3 round 12, item 4).
 *
 * <p>The stored status flag is only an administrator decision. What a record
 * actually <em>is</em> is derived: a used-up record reads EXHAUSTED, an expired
 * one EXPIRED, a revoked one REVOKED and only an otherwise-usable record VALID.
 * A gate accepts a credential only while it is VALID (which already implies a
 * spare entry), so no gate can ever be opened by a stale record.</p>
 */
class CredentialStateTest {
    private static final String TODAY = DateUtil.today();
    private static final String YESTERDAY = DateUtil.plusDays(TODAY, -1);
    private static final String TOMORROW = DateUtil.plusDays(TODAY, 1);

    private static DocumentData passport() {
        DocumentData doc = DocumentData.createEmpty(DocStyle.US_PASSPORT);
        doc.setName("ALEX CARTER");
        doc.setNumber("E12345678");
        doc.setBirthDate("1990-01-01");
        return doc;
    }

    /** Adds one visa record with the given budget/expiry/status and returns its code. */
    private static String addVisa(DocumentData doc, int entries, int usedEntries, String expiry,
                                  String status, String code) {
        CompoundTag visa = DocumentFactory.createVisa("日本", VisaType.TOURIST, entries, TODAY, expiry,
                "日本驻华使领馆", code);
        visa.putInt("usedEntries", usedEntries);
        visa.putString("status", status);
        doc.addVisaExact(visa);
        return code;
    }

    @Test
    void theFourStatesAreDerivedFromBudgetExpiryAndRevocation() {
        assertEquals(CredentialState.VALID,
                CredentialState.derive(VisaRecord.ACTIVE, 2, 0, TOMORROW, LocalDate.now()));
        assertEquals(CredentialState.EXHAUSTED,
                CredentialState.derive(VisaRecord.ACTIVE, 1, 1, TOMORROW, LocalDate.now()),
                "a used-up record is EXHAUSTED");
        assertEquals(CredentialState.EXPIRED,
                CredentialState.derive(VisaRecord.ACTIVE, 2, 0, YESTERDAY, LocalDate.now()),
                "a past expiry is EXPIRED");
        assertEquals(CredentialState.REVOKED,
                CredentialState.derive(VisaRecord.REVOKED, 2, 0, TOMORROW, LocalDate.now()),
                "a stored revocation is REVOKED");
        // entries <= 0 means unlimited (the "∞" budget), never EXHAUSTED.
        assertEquals(CredentialState.VALID,
                CredentialState.derive(VisaRecord.ACTIVE, 0, 99, TOMORROW, LocalDate.now()));
        // A blank status tag counts as ACTIVE (never touched by an administrator).
        assertEquals(CredentialState.VALID, CredentialState.derive("", 1, 0, TOMORROW, LocalDate.now()));
    }

    @Test
    void theDocumentedOrderIsExhaustedThenExpiredThenRevoked() {
        // A record that is all three reads EXHAUSTED (first rule wins) ...
        assertEquals(CredentialState.EXHAUSTED,
                CredentialState.derive(VisaRecord.REVOKED, 1, 1, YESTERDAY, LocalDate.now()));
        // ... and an expired + revoked record reads EXPIRED, not REVOKED.
        assertEquals(CredentialState.EXPIRED,
                CredentialState.derive(VisaRecord.REVOKED, 2, 0, YESTERDAY, LocalDate.now()));
    }

    @Test
    void visaRecordsExposeTheirDerivedState() {
        DocumentData doc = passport();
        String validCode = addVisa(doc, 2, 0, TOMORROW, VisaRecord.ACTIVE, "11112222");
        String spentCode = addVisa(doc, 1, 1, TOMORROW, VisaRecord.ACTIVE, "22223333");
        String staleCode = addVisa(doc, 2, 0, YESTERDAY, VisaRecord.ACTIVE, "33334444");
        String revokedCode = addVisa(doc, 2, 0, TOMORROW, VisaRecord.REVOKED, "44445555");

        assertEquals(CredentialState.VALID, record(doc, validCode).state());
        assertEquals(CredentialState.EXHAUSTED, record(doc, spentCode).state());
        assertEquals(CredentialState.EXPIRED, record(doc, staleCode).state());
        assertEquals(CredentialState.REVOKED, record(doc, revokedCode).state());

        assertTrue(record(doc, validCode).active());
        assertFalse(record(doc, spentCode).active());
        assertFalse(record(doc, staleCode).active());
        assertFalse(record(doc, revokedCode).active());
        // The UI colour is part of the contract (green / amber / red / purple).
        assertEquals(CredentialState.VALID.color(), record(doc, validCode).stateColor());
        assertEquals(CredentialState.EXHAUSTED.color(), record(doc, spentCode).stateColor());
    }

    @Test
    void endorsementRecordsDeriveTheSameWay() {
        DocumentData permit = DocumentData.createEmpty(DocStyle.HK_MACAO_PERMIT);
        permit.setName("ALEX CARTER");
        permit.setNumber("H1234567");
        permit.setBirthDate("1990-01-01");
        CompoundTag spent = DocumentFactory.createEndorsement(EndorsementType.THREE_MONTH_SINGLE, 1, TODAY,
                TOMORROW, "香港入境事务处", "55556666");
        spent.putInt("usedEntries", 1);
        permit.addEndorsementExact(spent);
        CompoundTag revoked = DocumentFactory.createEndorsement(EndorsementType.ONE_YEAR_MULTI, 2, TODAY,
                TOMORROW, "香港入境事务处", "66667777");
        revoked.putString("status", VisaRecord.REVOKED);
        permit.addEndorsementExact(revoked);

        assertEquals(CredentialState.EXHAUSTED,
                EndorsementRecord.fromTag(permit.endorsementTags().get(0)).state());
        assertEquals(CredentialState.REVOKED,
                EndorsementRecord.fromTag(permit.endorsementTags().get(1)).state());
        assertEquals("credential.status.veriborder.exhausted",
                CredentialState.EXHAUSTED.translationKey());
    }

    @Test
    void gatesAcceptOnlyValidRecordsWithEntriesLeft() {
        DocumentData doc = passport();
        String spent = addVisa(doc, 1, 1, TOMORROW, VisaRecord.ACTIVE, "77778888");
        // An exhausted code is on the document but must not open the gate.
        assertNull(BorderService.resolveEntry(doc, "visa", List.of(spent)));
        assertFalse(DocumentValidator.validateGate(doc, DeviceType.EGATE, "visa", List.of(spent), "")
                .valid(), "an exhausted visa is refused at the gate");

        String valid = addVisa(doc, 2, 0, TOMORROW, VisaRecord.ACTIVE, "88889999");
        assertNotNull(BorderService.resolveEntry(doc, "visa", List.of(valid)));
        assertTrue(DocumentValidator.validateGate(doc, DeviceType.EGATE, "visa", List.of(valid), "")
                .valid());
    }

    /** Visa record with this code (records are immutable value objects). */
    private static VisaRecord record(DocumentData doc, String code) {
        for (VisaRecord visa : doc.visas()) {
            if (visa.code().equals(code)) {
                return visa;
            }
        }
        throw new AssertionError("no visa record " + code);
    }
}
