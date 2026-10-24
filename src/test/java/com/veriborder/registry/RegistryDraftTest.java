package com.veriborder.registry;

import com.veriborder.doc.DocStyle;
import com.veriborder.doc.DocumentKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.4 registry-editor regressions ("保存版式仍提示需要名称" / "刷新跳回国家登记").
 *
 * <p>The screen used to rebuild its inputs as empty {@code EditBox}es and to
 * reopen itself on every server reply, which threw away what the operator typed
 * and reset the page. The state, the payload and the issuer option merge are pure
 * data now, so all three failures are pinned down here without a client.</p>
 */
class RegistryDraftTest {
    // ------------------------------------------------------- form persistence

    @Test
    void typedTemplateNameSurvivesARebuildAndReachesTheServer() {
        RegistryDraft draft = new RegistryDraft();
        draft.newTemplate();
        draft.setTemplateName("  电子护照  ");
        draft.setTemplateMrzCountry("chn");
        draft.setTemplateColor(0x123456);
        draft.setTemplateIcon("star");

        // "Rebuild": the screen re-seeds every widget from these two views. The
        // name must come back instead of an empty box (the old bug).
        RegistryDraft.TemplateView view = draft.templateView();
        assertEquals("  电子护照  ", view.name());
        assertEquals(DocumentKind.PASSPORT, view.kind());
        assertEquals("CHN", view.mrzCountry());
        assertEquals(0x123456, view.color());
        assertEquals("star", view.icon());

        // "Save": the payload is built from the fields, never from a widget.
        CompoundTag payload = draft.templatePayload();
        assertEquals("电子护照", payload.getString("name"));
        assertEquals("passport", payload.getString("kind"));
        assertEquals("cn_passport", payload.getString("style"));
        assertFalse(payload.contains("id"), "a fresh draft must not reuse a row id");

        RegistryService.PreparedTemplate prepared = RegistryService.prepareTemplate(payload);
        assertEquals(RegistryService.TemplateSaveStatus.OK, prepared.status());
        assertNotNull(prepared.template());
        assertEquals("电子护照", prepared.template().name());

        CredentialRegistryData registry = new CredentialRegistryData();
        assertTrue(registry.upsertTemplate(prepared.template()));
        String id = CredentialRegistryData.deriveTemplateId(DocumentKind.PASSPORT, "电子护照");
        assertNotNull(registry.template(id), "the typed name must end up stored");
    }

    @Test
    void changingTheCredentialKindKeepsTheTypedName() {
        RegistryDraft draft = new RegistryDraft();
        draft.newTemplate();
        draft.setTemplateName("旅行证");
        draft.setTemplateKind(DocumentKind.PERMIT);

        assertEquals("旅行证", draft.templateView().name());
        assertEquals(DocumentKind.PERMIT, draft.templateView().kind());
        assertEquals("hk_macao_permit", draft.templateView().styleId());
        assertFalse(draft.templateEditing());
        assertEquals("", draft.templatePayload().getString("id"));
    }

    @Test
    void typedCountryFieldsSurviveARebuild() {
        RegistryDraft draft = new RegistryDraft();
        draft.clearCountry();
        draft.setCountryCode("chn");
        draft.setCountryNameZh("中国");
        draft.setCountryNameEn("China");
        draft.setCountryNationality("中国");
        draft.setCountryNote("内部备注");

        RegistryDraft.CountryView view = draft.countryView();
        assertEquals("chn", view.code());
        assertEquals("中国", view.nameZh());
        assertEquals("China", view.nameEn());
        assertEquals("中国", view.defaultNationality());
        assertEquals("内部备注", view.note());

        CompoundTag payload = draft.countryPayload();
        assertEquals("CHN", payload.getString("code"));
        assertEquals("中国", payload.getString("nameZh"));
        assertTrue(new CredentialRegistryData().upsertCountry(
                CredentialRegistryData.Country.fromTag(payload)));
    }

    // ------------------------------------------------------------- page/tab

    @Test
    void aSnapshotRefreshKeepsTheTabAndTheTypedName() {
        RegistryDraft draft = new RegistryDraft();
        draft.setTab(RegistryDraft.TAB_TEMPLATES);
        draft.newTemplate();
        draft.setTemplateName("加急护照");

        // A refresh reply is a plain snapshot: no editor tab, no template, no echo.
        CompoundTag refresh = new CompoundTag();
        refresh.put("countries", new ListTag());
        refresh.put("templates", new ListTag());

        assertEquals(RegistryDraft.TAB_TEMPLATES,
                RegistryDraft.tabFromPayload(refresh, RegistryDraft.TAB_TEMPLATES),
                "a snapshot-only payload can never move the page");
        draft.applyPayload(refresh);
        assertEquals(RegistryDraft.TAB_TEMPLATES, draft.tab());
        assertEquals("加急护照", draft.templatePayload().getString("name"));
    }

    @Test
    void aServerEditorPayloadSelectsThePageItBelongsTo() {
        CompoundTag templateReply = new CompoundTag();
        templateReply.putString(RegistryDraft.EDITOR_TAB_KEY, "templates");
        assertEquals(RegistryDraft.TAB_TEMPLATES,
                RegistryDraft.tabFromPayload(templateReply, RegistryDraft.TAB_COUNTRIES));

        CompoundTag countryReply = new CompoundTag();
        countryReply.putString(RegistryDraft.EDITOR_TAB_KEY, "countries");
        assertEquals(RegistryDraft.TAB_COUNTRIES,
                RegistryDraft.tabFromPayload(countryReply, RegistryDraft.TAB_TEMPLATES));
    }

    @Test
    void aRefusedSaveKeepsItsReasonAndTheSubmittedForm() {
        CompoundTag fields = new CompoundTag();
        fields.putString("kind", "passport");
        fields.putString("name", "普通护照");
        fields.putString("style", DocStyle.forKind(DocumentKind.PASSPORT).get(3).id());
        fields.putString("id", "passport_abc");

        CompoundTag payload = new CompoundTag();
        payload.putString(RegistryDraft.EDITOR_TAB_KEY, "templates");
        payload.putString(RegistryDraft.ERROR_KEY, "message.veriborder.registry.full");
        payload.put(RegistryDraft.ECHO_KEY, fields);

        RegistryDraft draft = new RegistryDraft();
        draft.applyPayload(payload);

        assertEquals(RegistryDraft.TAB_TEMPLATES, draft.tab());
        assertEquals("message.veriborder.registry.full", draft.errorKey());
        assertEquals("普通护照", draft.templatePayload().getString("name"),
                "a failed save must not drop the typed name");
        assertEquals("us_passport", draft.templatePayload().getString("style"));
        assertTrue(draft.templateEditing());
        assertEquals("passport_abc", draft.templatePayload().getString("id"));
    }

    @Test
    void aSavedTemplateIsSelectedAfterASave() {
        CredentialRegistryData.Template saved = new CredentialRegistryData.Template(
                "passport_电", DocumentKind.PASSPORT, "加急护照", "jp_passport",
                "JPN", "", "", 0x0A0B0C, "crown");
        CompoundTag payload = new CompoundTag();
        payload.put(RegistryDraft.HIGHLIGHT_KEY, saved.toTag());

        RegistryDraft draft = new RegistryDraft();
        draft.applyPayload(payload);

        assertEquals(RegistryDraft.TAB_TEMPLATES, draft.tab());
        assertEquals("加急护照", draft.templateView().name());
        assertEquals("jp_passport", draft.templateView().styleId());
        assertEquals(0x0A0B0C, draft.templateView().color());
        assertEquals("crown", draft.templateView().icon());
        assertEquals("", draft.errorKey());
    }

    @Test
    void refreshDoesNotReopenTheEditor() {
        assertFalse(RegistryService.reopensEditor(RegistryService.ACTION_REFRESH),
                "the open editor refreshes itself from the snapshot; reopening resets its page");
        assertFalse(RegistryService.reopensEditor(RegistryService.ACTION_SNAPSHOT_REQUEST));
        assertTrue(RegistryService.reopensEditor(RegistryService.ACTION_SAVE_TEMPLATE));
        assertTrue(RegistryService.reopensEditor(RegistryService.ACTION_SAVE_COUNTRY));
        assertEquals("templates", RegistryService.editorTabFor(RegistryService.ACTION_SAVE_TEMPLATE));
        assertEquals("templates", RegistryService.editorTabFor(RegistryService.ACTION_DELETE_TEMPLATE));
        assertEquals("countries", RegistryService.editorTabFor(RegistryService.ACTION_SAVE_COUNTRY));
    }

    // --------------------------------------------------------- server reasons

    @Test
    void aMissingNameCarriesTheConcreteServerReason() {
        CompoundTag blankName = new CompoundTag();
        blankName.putString("kind", "passport");
        blankName.putString("name", "   ");
        RegistryService.PreparedTemplate missing = RegistryService.prepareTemplate(blankName);
        assertEquals(RegistryService.TemplateSaveStatus.MISSING_NAME, missing.status());
        assertEquals("message.veriborder.registry.template_name_required",
                RegistryService.errorKeyFor(missing.status()));

        CompoundTag badKind = new CompoundTag();
        badKind.putString("kind", "spaceship");
        badKind.putString("name", "X");
        RegistryService.PreparedTemplate invalid = RegistryService.prepareTemplate(badKind);
        assertEquals(RegistryService.TemplateSaveStatus.INVALID_KIND, invalid.status());
        assertEquals("message.veriborder.registry.template_kind_invalid",
                RegistryService.errorKeyFor(invalid.status()));
    }

    // -------------------------------------------------- issuer layout picker

    @Test
    void aSavedTemplateAppearsInTheIssuerLayoutList() {
        CredentialRegistryData registry = new CredentialRegistryData();
        CompoundTag data = new CompoundTag();
        data.putString("kind", "passport");
        data.putString("name", "外交护照");
        // No style: the server repairs it to the first layout of the kind.
        RegistryService.PreparedTemplate prepared = RegistryService.prepareTemplate(data);
        assertEquals(RegistryService.TemplateSaveStatus.OK, prepared.status());
        assertTrue(registry.upsertTemplate(prepared.template()));

        List<IssuerChoices.Choice> choices = IssuerChoices.layoutChoices(
                DocumentKind.PASSPORT, registry.templates(), DocStyle::id);
        List<IssuerChoices.Choice> templates = choices.stream()
                .filter(IssuerChoices.Choice::isTemplate).toList();
        assertEquals(1, templates.size(), "the saved template is offered by the passport issuer");
        IssuerChoices.Choice choice = templates.get(0);
        assertEquals("外交护照", choice.label());
        assertEquals("template:" + choice.templateId(), choice.value());
        assertEquals("cn_passport", choice.style().id(), "the repaired style is what issuing writes");

        CredentialRegistryData.Template resolved =
                IssuerChoices.resolve(choice.value(), registry.templates());
        assertNotNull(resolved);
        assertEquals("外交护照", resolved.name());
        assertEquals("cn_passport", IssuerChoices.styleId(choice.value(), registry.templates()));

        // A template never leaks into another kind's issuer dropdown.
        assertTrue(IssuerChoices.layoutChoices(DocumentKind.ID_CARD, registry.templates(), DocStyle::id)
                .stream().noneMatch(IssuerChoices.Choice::isTemplate));
    }
}
