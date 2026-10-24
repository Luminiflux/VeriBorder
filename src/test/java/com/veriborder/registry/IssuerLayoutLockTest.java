package com.veriborder.registry;

import com.veriborder.block.DeviceBlockEntity;
import com.veriborder.block.DeviceService;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentData;
import com.veriborder.doc.DocumentFactory;
import com.veriborder.doc.DocumentKind;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.4 bug fix: "发证机配置里选择自定义模板版式无法保存".
 *
 * <p>The layout-lock dropdown stores {@code template:<id>} for a registry
 * template. That value is not a {@link DocStyle} id, so writing it into the
 * device's {@code lockedStyle} made the lock unparsable (or left the stored
 * layout at an unrelated old value when the client could not resolve the
 * template). Saving now resolves the choice to the template's base official
 * layout, a deleted template falls back to the first official layout of the
 * machine's kind, and the server stores "" — never a rejection — for a value
 * that does not resolve.</p>
 */
class IssuerLayoutLockTest {
    private static final int TEMPLATE_COLOR = 0x2F6FE4;

    private static CredentialRegistryData.Template template(String id, DocumentKind kind, String style) {
        return new CredentialRegistryData.Template(id, kind, "Template " + id, style, "",
                "", "", TEMPLATE_COLOR, "custom_passport");
    }

    @Test
    void templateChoiceResolvesToItsBaseLayout() {
        List<CredentialRegistryData.Template> templates =
                List.of(template("diplomatic", DocumentKind.PASSPORT, "hk_passport"));

        IssuerChoices.LockedStyle locked =
                IssuerChoices.resolveLockedStyle("template:diplomatic", DocumentKind.PASSPORT, templates);

        assertEquals("hk_passport", locked.styleId(), "lockedStyle is the template's base style");
        assertEquals("diplomatic", locked.templateId());
        assertTrue(locked.isTemplate());
        assertFalse(locked.fellBack());
    }

    @Test
    void officialLayoutChoiceIsStoredVerbatim() {
        IssuerChoices.LockedStyle locked =
                IssuerChoices.resolveLockedStyle("us_passport", DocumentKind.PASSPORT, List.of());

        assertEquals("us_passport", locked.styleId());
        assertEquals("", locked.templateId());
        assertFalse(locked.fellBack());
    }

    @Test
    void deletedTemplateFallsBackToTheFirstOfficialLayout() {
        IssuerChoices.LockedStyle locked =
                IssuerChoices.resolveLockedStyle("template:gone", DocumentKind.PASSPORT, List.of());

        assertEquals("cn_passport", locked.styleId(), "fallback is the kind's first official layout");
        assertEquals("", locked.templateId());
        assertTrue(locked.fellBack(), "the administrator is told the template is gone");
        // The fallback is a real layout, so the save is accepted instead of failing.
        assertEquals("cn_passport", DeviceBlockEntity.normalizeLockedStyle(locked.styleId()));
    }

    @Test
    void unknownLayoutFallsBackAndIsFlagged() {
        IssuerChoices.LockedStyle locked =
                IssuerChoices.resolveLockedStyle("template:oops", DocumentKind.ID_CARD, List.of());
        assertEquals("cn_id", locked.styleId());
        assertTrue(locked.fellBack());

        IssuerChoices.LockedStyle bogus =
                IssuerChoices.resolveLockedStyle("not_a_layout", DocumentKind.PERMIT, List.of());
        assertEquals("hk_macao_permit", bogus.styleId());
        assertTrue(bogus.fellBack());
    }

    @Test
    void blankChoiceIsTheFirstOfficialLayoutWithoutAWarning() {
        IssuerChoices.LockedStyle locked =
                IssuerChoices.resolveLockedStyle("", DocumentKind.PASSPORT, List.of());

        assertEquals("cn_passport", locked.styleId());
        assertFalse(locked.fellBack(), "an untouched dropdown is not a fallback");
    }

    @Test
    void templateOfAnotherKindFallsBack() {
        List<CredentialRegistryData.Template> templates =
                List.of(template("passport_only", DocumentKind.PASSPORT, "hk_passport"));

        IssuerChoices.LockedStyle locked =
                IssuerChoices.resolveLockedStyle("template:passport_only", DocumentKind.ID_CARD, templates);

        assertEquals("cn_id", locked.styleId());
        assertEquals("", locked.templateId());
        assertTrue(locked.fellBack());
    }

    @Test
    void issuedDocumentUsesTheLockedTemplateLayout() {
        List<CredentialRegistryData.Template> templates =
                List.of(template("diplomatic", DocumentKind.PASSPORT, "hk_passport"));
        IssuerChoices.LockedStyle locked =
                IssuerChoices.resolveLockedStyle("template:diplomatic", DocumentKind.PASSPORT, templates);

        // What the device now stores as lockedStyle is a real layout id…
        DocStyle lockedStyle = DocStyle.byIdOrNull(locked.styleId());
        assertNotNull(lockedStyle);
        assertEquals(DocumentKind.PASSPORT, lockedStyle.kind());

        // …and issuing with that lock writes exactly that layout.
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("birthDate", "1991-02-03");
        DocumentData doc = DocumentFactory.fromForm(lockedStyle, form);
        assertEquals("hk_passport", doc.style().id());
        assertEquals(DocumentKind.PASSPORT, doc.kind());
        assertTrue(doc.integrityValid());
    }

    @Test
    void aTemplateIdIsNeverStoredAsTheLockedLayout() {
        // The exact failure: "template:<id>" reaching the device as lockedStyle.
        assertEquals("", DeviceBlockEntity.normalizeLockedStyle("template:diplomatic"));
        assertEquals("", DeviceBlockEntity.normalizeLockedStyle("nope"));
        assertEquals("", DeviceBlockEntity.normalizeLockedStyle(null));
        assertEquals("hk_passport", DeviceBlockEntity.normalizeLockedStyle(" hk_passport "));
        // A plain official layout is still accepted, so a locked save is never rejected.
        assertEquals("us_passport", DeviceBlockEntity.normalizeLockedStyle("us_passport"));
    }

    @Test
    void lockedTemplateCarriesItsColourAndIconToTheDocument() {
        CredentialRegistryData.Template diplomatic =
                template("diplomatic", DocumentKind.PASSPORT, "hk_passport");
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("birthDate", "1991-02-03");

        DeviceService.applyLockedStyleTemplate(form, diplomatic, DocStyle.HK_PASSPORT);
        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_PASSPORT, form);

        assertEquals("hk_passport", doc.style().id());
        assertEquals(TEMPLATE_COLOR, doc.accentColor(), "the template colour is used");
        assertEquals("custom_passport", doc.customIcon(), "the template icon is carried");
        assertFalse(doc.customStyle(), "a template colour is not a custom cover");
        assertTrue(doc.integrityValid());
    }

    @Test
    void deletedTemplateContributesNoColourOrIcon() {
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("birthDate", "1991-02-03");

        DeviceService.applyLockedStyleTemplate(form, null, DocStyle.HK_PASSPORT);
        assertFalse(form.contains(DocumentFactory.LOCKED_STYLE_COLOR));
        assertFalse(form.contains(DocumentFactory.LOCKED_STYLE_ICON));

        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_PASSPORT, form);
        assertEquals(DocStyle.HK_PASSPORT.accentColor(), doc.accentColor());
        assertEquals("", doc.customIcon());
        assertTrue(doc.integrityValid());
    }

    @Test
    void templateThatNoLongerMatchesTheLockedLayoutContributesNothing() {
        // The operator re-pointed the template at another layout after the lock
        // was saved: the lock keeps its own layout and the colour is ignored.
        CredentialRegistryData.Template moved =
                template("diplomatic", DocumentKind.PASSPORT, "us_passport");
        CompoundTag form = new CompoundTag();
        form.putString("name", "ALEX MORGAN");
        form.putString("birthDate", "1991-02-03");

        DeviceService.applyLockedStyleTemplate(form, moved, DocStyle.HK_PASSPORT);
        assertFalse(form.contains(DocumentFactory.LOCKED_STYLE_COLOR));

        DocumentData doc = DocumentFactory.fromForm(DocStyle.HK_PASSPORT, form);
        assertEquals("hk_passport", doc.style().id());
        assertEquals(DocStyle.HK_PASSPORT.accentColor(), doc.accentColor());
    }

    @Test
    void staleLockedLayoutFromOlderNbtDoesNotBreakTheLock() {
        // A device saved by the buggy build holds "template:<id>" as lockedStyle.
        CompoundTag deviceTag = new CompoundTag();
        deviceTag.putBoolean("lockStyle", true);
        deviceTag.putString("lockedStyle", "template:diplomatic");

        DeviceBlockEntity.Locks locks = DeviceBlockEntity.Locks.fromTag(deviceTag);
        assertNull(DocStyle.byIdOrNull(locks.lockedStyle()),
                "the stale value is not an official layout");
        assertEquals("", DeviceBlockEntity.normalizeLockedStyle(locks.lockedStyle()),
                "reading it normalises the lock away instead of breaking issuing");
    }
}
