package com.veriborder.doc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable view over the single NBT compound stored on a document ItemStack.
 * Every setter re-signs the document, so the universal modifier can freely edit
 * all fields while readers still detect external NBT tampering.
 */
public final class DocumentData {
    public static final String ROOT = "VeriBorder";
    public static final int SCHEMA = 1;

    private final CompoundTag tag;
    private DocStyle style;

    private DocumentData(CompoundTag tag) {
        this.tag = tag;
        this.style = DocStyle.byId(tag.getString("style"));
    }

    public static DocumentData createEmpty(DocStyle style) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", SCHEMA);
        tag.putString("style", style.id());
        tag.putString("kind", style.kind().name());
        tag.putString("name", "");
        tag.putString("nameLatin", "");
        tag.putString("sex", "M");
        tag.putString("number", "");
        tag.putString("birthDate", "1990-01-01");
        tag.putString("address", "");
        tag.putString("authority", style.defaultAuthority());
        tag.putString("issueDate", DateUtil.today());
        tag.putString("expiryDate", style.permanent() ? DateUtil.PERMANENT : DateUtil.plusYears(DateUtil.today(), style.validityYears()));
        tag.putString("nationality", style.defaultNationality());
        tag.putString("placeOfIssue", style.defaultPlace());
        tag.putString("status", DocumentStatus.VALID.id());
        tag.putString("mrz1", "");
        tag.putString("mrz2", "");
        tag.put("visas", new ListTag());
        tag.put("endorsements", new ListTag());
        tag.put("stamps", new ListTag());
        tag.put("visaFreeCodes", new ListTag());
        DocumentData data = new DocumentData(tag);
        data.sign();
        return data;
    }

    public static DocumentData fromTag(CompoundTag tag) {
        if (tag == null) {
            throw new IllegalArgumentException("Document tag cannot be null");
        }
        if (!tag.contains("style")) {
            tag.putString("style", DocStyle.CN_ID.id());
        }
        if (!tag.contains("kind")) {
            tag.putString("kind", DocStyle.byId(tag.getString("style")).kind().name());
        }
        if (!tag.contains("status")) {
            tag.putString("status", DocumentStatus.VALID.id());
        }
        if (!tag.contains("visas", Tag.TAG_LIST)) {
            tag.put("visas", new ListTag());
        }
        if (!tag.contains("endorsements", Tag.TAG_LIST)) {
            tag.put("endorsements", new ListTag());
        }
        if (!tag.contains("stamps", Tag.TAG_LIST)) {
            tag.put("stamps", new ListTag());
        }
        // "visaFreeCodes" is deliberately NOT materialised here: adding a key to
        // a legacy (pre-1.1.5) tag would change its checksum and flag every old
        // document as tampered. Readers treat a missing list as empty and the
        // list is created (and signed) on the first add.
        return new DocumentData(tag);
    }

    public CompoundTag tag() {
        return tag;
    }

    public CompoundTag copyTag() {
        return tag.copy();
    }

    public DocumentData copy() {
        return new DocumentData(tag.copy());
    }

    public DocStyle style() {
        return style;
    }

    public DocumentKind kind() {
        return style.kind();
    }

    public boolean customStyle() {
        return tag.getBoolean("customStyle");
    }

    /**
     * Free-text name stored independently from the custom cover. Blank when the
     * document simply uses its official layout name or its custom cover name.
     */
    public String displayNameOverride() {
        return tag.getString("displayName").trim();
    }

    public boolean hasCustomDisplayName() {
        return !displayNameOverride().isBlank();
    }

    /**
     * Sets the independent display name. An empty value removes the override so
     * the document falls back to the custom cover name or the official style.
     */
    public void setDisplayName(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            tag.remove("displayName");
        } else {
            tag.putString("displayName", trimmed);
        }
        sign();
    }

    /**
     * Resolution order: explicit display name override, then the custom cover's
     * cover name, then the official style translation.
     */
    public String displayName() {
        String override = displayNameOverride();
        if (!override.isBlank()) {
            return override;
        }
        if (customStyle()) {
            String custom = tag.getString("customName").trim();
            if (!custom.isBlank()) {
                return custom;
            }
        }
        return Component.translatable(style.displayNameKey()).getString();
    }

    public Component displayNameComponent() {
        String override = displayNameOverride();
        if (!override.isBlank()) {
            return Component.literal(override);
        }
        if (customStyle()) {
            String custom = tag.getString("customName").trim();
            if (!custom.isBlank()) {
                return Component.literal(custom);
            }
        }
        return Component.translatable(style.displayNameKey());
    }

    public String shortName() {
        if (customStyle()) {
            String custom = tag.getString("customShortName").trim();
            if (!custom.isBlank()) {
                return custom;
            }
        }
        return Component.translatable(style.shortNameKey()).getString();
    }

    public String documentType() {
        if (customStyle()) {
            String custom = tag.getString("customType").trim();
            if (!custom.isBlank()) {
                return custom;
            }
        }
        return Component.translatable(kind().translationKey()).getString();
    }

    public int accentColor() {
        if (customStyle()) {
            return tag.getInt("customColor") & 0x00FFFFFF;
        }
        return style.accentColor();
    }

    public int itemModelData() {
        return customStyle() ? 100 : style.modelData();
    }

    public void applyCustomStyle(String name, String shortName, String type, int color) {
        tag.putBoolean("customStyle", true);
        tag.putString("customName", name == null ? "" : name.trim());
        tag.putString("customShortName", shortName == null ? "" : shortName.trim());
        tag.putString("customType", type == null ? "" : type.trim());
        tag.putInt("customColor", color & 0x00FFFFFF);
        sign();
    }

    public void clearCustomStyle() {
        tag.remove("customStyle");
        tag.remove("customName");
        tag.remove("customShortName");
        tag.remove("customType");
        tag.remove("customColor");
        sign();
    }

    public String kindId() {
        return kind().name();
    }

    public String get(String key, String fallback) {
        String value = tag.getString(key);
        return value.isBlank() ? fallback : value;
    }

    public String name() {
        return tag.getString("name");
    }

    public String number() {
        return tag.getString("number");
    }

    public String sex() {
        return tag.getString("sex");
    }

    public String birthDate() {
        return tag.getString("birthDate");
    }

    public String address() {
        return tag.getString("address");
    }

    public String authority() {
        return tag.getString("authority");
    }

    public String issueDate() {
        return tag.getString("issueDate");
    }

    public String expiryDate() {
        return tag.getString("expiryDate");
    }

    public String nationality() {
        return tag.getString("nationality");
    }

    public String placeOfIssue() {
        return tag.getString("placeOfIssue");
    }

    public String nameLatin() {
        String latin = tag.getString("nameLatin").trim();
        if (!latin.isBlank()) {
            return latin;
        }
        String name = name();
        int slash = name.indexOf('/');
        if (slash >= 0) {
            String after = name.substring(slash + 1).trim();
            if (!after.isBlank()) {
                return after;
            }
        }
        if (name.matches(".*[A-Za-z].*")) {
            return name;
        }
        return "HOLDER";
    }

    public DocumentStatus status() {
        return DocumentStatus.byId(tag.getString("status"));
    }

    public boolean styleKnown() {
        String raw = tag.getString("style");
        for (DocStyle candidate : DocStyle.values()) {
            if (candidate.id().equals(raw)) {
                return true;
            }
        }
        return false;
    }

    public boolean statusKnown() {
        String raw = tag.getString("status");
        for (DocumentStatus candidate : DocumentStatus.values()) {
            if (candidate.id().equalsIgnoreCase(raw)) {
                return true;
            }
        }
        return false;
    }

    public void setStyle(DocStyle newStyle) {
        if (newStyle.kind() != kind()) {
            throw new IllegalArgumentException("Style kind mismatch");
        }
        this.style = newStyle;
        tag.putString("style", newStyle.id());
        tag.putString("kind", newStyle.kind().name());
        sign();
    }

    public void setName(String value) {
        tag.putString("name", value == null ? "" : value);
        sign();
    }

    public void setNameLatin(String value) {
        tag.putString("nameLatin", value == null ? "" : value);
        sign();
    }

    public void setNumber(String value) {
        tag.putString("number", value == null ? "" : value);
        sign();
    }

    public void setSex(String value) {
        String sex = value == null ? "M" : value.toUpperCase(Locale.ROOT);
        if (!sex.equals("M") && !sex.equals("F") && !sex.equals("X")) {
            sex = "M";
        }
        tag.putString("sex", sex);
        sign();
    }

    public void setBirthDate(String value) {
        tag.putString("birthDate", value == null ? "" : value);
        sign();
    }

    public void setAddress(String value) {
        tag.putString("address", value == null ? "" : value);
        sign();
    }

    public void setAuthority(String value) {
        tag.putString("authority", value == null ? "" : value);
        sign();
    }

    public void setIssueDate(String value) {
        tag.putString("issueDate", value == null ? "" : value);
        sign();
    }

    public void setExpiryDate(String value) {
        tag.putString("expiryDate", value == null ? "" : value);
        sign();
    }

    public void setNationality(String value) {
        tag.putString("nationality", value == null ? "" : value);
        sign();
    }

    public void setPlaceOfIssue(String value) {
        tag.putString("placeOfIssue", value == null ? "" : value);
        sign();
    }

    public void setStatus(DocumentStatus value) {
        tag.putString("status", value.id());
        sign();
    }

    public void setString(String key, String value) {
        tag.putString(key, value == null ? "" : value);
        sign();
    }

    public List<VisaRecord> visas() {
        ListTag list = tag.getList("visas", Tag.TAG_COMPOUND);
        List<VisaRecord> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(VisaRecord.fromTag(list.getCompound(i)));
        }
        return result;
    }

    public List<EndorsementRecord> endorsements() {
        ListTag list = tag.getList("endorsements", Tag.TAG_COMPOUND);
        List<EndorsementRecord> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(EndorsementRecord.fromTag(list.getCompound(i)));
        }
        return result;
    }

    public List<StampRecord> stamps() {
        ListTag list = tag.getList("stamps", Tag.TAG_COMPOUND);
        List<StampRecord> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(StampRecord.fromTag(list.getCompound(i)));
        }
        return result;
    }

    public List<CompoundTag> visaTags() {
        ListTag list = tag.getList("visas", Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag copy = list.getCompound(i).copy();
            // Editing helpers see the effective code, so a pre-1.1.5 record
            // shows (and keeps) its deterministic legacy code.
            copy.putString("code", effectiveCode(copy));
            result.add(copy);
        }
        return result;
    }

    public List<CompoundTag> endorsementTags() {
        ListTag list = tag.getList("endorsements", Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag copy = list.getCompound(i).copy();
            copy.putString("code", effectiveCode(copy));
            result.add(copy);
        }
        return result;
    }

    private static String effectiveCode(CompoundTag record) {
        String code = record.getString("code");
        if (CredentialCode.isValid(code)) {
            return code;
        }
        String id = record.getString("id");
        return id.isBlank() ? CredentialCode.random() : CredentialCode.legacy(id);
    }

    public List<CompoundTag> stampTags() {
        ListTag list = tag.getList("stamps", Tag.TAG_COMPOUND);
        List<CompoundTag> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(list.getCompound(i).copy());
        }
        return result;
    }

    /**
     * Visa-free entry codes carried by the document itself (a passport profile or
     * visa tab can hold several). Missing lists read as empty for compatibility
     * with documents issued before 1.1.5.
     */
    public List<String> visaFreeCodes() {
        ListTag list = tag.getList("visaFreeCodes", Tag.TAG_STRING);
        Set<String> codes = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String code = list.getString(i) == null ? "" : list.getString(i).trim();
            if (CredentialCode.isValid(code)) {
                codes.add(code);
            }
        }
        return new ArrayList<>(codes);
    }

    /** Adds a visa-free code. Invalid codes and duplicates are ignored. */
    public boolean addVisaFreeCode(String code) {
        String normalized = CredentialCode.sanitize(code);
        if (normalized.isBlank()) {
            return false;
        }
        // A visa-free code shares the same namespace as visa/endorsement codes.
        if (allCredentialCodes().contains(normalized)) {
            return false;
        }
        List<String> codes = visaFreeCodes();
        codes.add(normalized);
        writeVisaFreeCodes(codes);
        sign();
        return true;
    }

    /** Removes a visa-free code. Returns whether anything was removed. */
    public boolean removeVisaFreeCode(String code) {
        String normalized = CredentialCode.sanitize(code);
        if (normalized.isBlank()) {
            return false;
        }
        List<String> codes = visaFreeCodes();
        if (!codes.remove(normalized)) {
            return false;
        }
        writeVisaFreeCodes(codes);
        sign();
        return true;
    }

    public void clearVisaFreeCodes() {
        writeVisaFreeCodes(List.of());
        sign();
    }

    private void writeVisaFreeCodes(List<String> codes) {
        ListTag list = new ListTag();
        for (String code : CredentialCode.normalizeAll(codes)) {
            list.add(StringTag.valueOf(code));
        }
        tag.put("visaFreeCodes", list);
    }

    /**
     * Every code the document can present at a gate: all visa and endorsement
     * codes (including inactive records, whose codes stay reserved) plus the
     * visa-free list. Used to keep auto-generated codes unique and by tests.
     */
    public List<String> allCredentialCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (VisaRecord visa : visas()) {
            codes.add(visa.code());
        }
        for (EndorsementRecord endorsement : endorsements()) {
            codes.add(endorsement.code());
        }
        codes.addAll(visaFreeCodes());
        return new ArrayList<>(codes);
    }

    /**
     * Repairs malformed/duplicate credential codes in place and re-signs. Used
     * by the admin modifier save path, which accepts a whole NBT tree and could
     * otherwise persist two records sharing a code.
     */
    public boolean ensureUniqueCredentialCodes() {
        Set<String> used = new LinkedHashSet<>(allCredentialCodes());
        Set<String> seen = new LinkedHashSet<>();
        boolean changed = false;
        ListTag visas = tag.getList("visas", Tag.TAG_COMPOUND);
        for (int i = 0; i < visas.size(); i++) {
            CompoundTag entry = visas.getCompound(i);
            String code = CredentialCode.sanitize(entry.getString("code"));
            if (!CredentialCode.isValid(code) || !seen.add(code)) {
                code = CredentialCode.generateUnique(used);
                entry.putString("code", code);
                used.add(code);
                seen.add(code);
                changed = true;
            }
        }
        ListTag endorsements = tag.getList("endorsements", Tag.TAG_COMPOUND);
        for (int i = 0; i < endorsements.size(); i++) {
            CompoundTag entry = endorsements.getCompound(i);
            String code = CredentialCode.sanitize(entry.getString("code"));
            if (!CredentialCode.isValid(code) || !seen.add(code)) {
                code = CredentialCode.generateUnique(used);
                entry.putString("code", code);
                used.add(code);
                seen.add(code);
                changed = true;
            }
        }
        if (changed) {
            sign();
        }
        return changed;
    }

    public void addVisa(CompoundTag visa) {
        addVisa(visa, false);
    }

    /** Adds a visa preserving an explicit code even if another record shares it. */
    public void addVisaExact(CompoundTag visa) {
        addVisa(visa, true);
    }

    private void addVisa(CompoundTag visa, boolean allowDuplicateCode) {
        CompoundTag copy = visa.copy();
        if (copy.getString("id").isBlank()) {
            copy.putString("id", "V-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        }
        String code = CredentialCode.sanitize(copy.getString("code"));
        if (!CredentialCode.isValid(code) || (!allowDuplicateCode && allCredentialCodes().contains(code))) {
            copy.putString("code", CredentialCode.generateUnique(allCredentialCodes()));
        }
        ListTag list = tag.getList("visas", Tag.TAG_COMPOUND);
        list.add(copy);
        tag.put("visas", list);
        sign();
    }

    public void updateVisa(int index, CompoundTag visa) {
        ListTag list = tag.getList("visas", Tag.TAG_COMPOUND);
        if (index >= 0 && index < list.size()) {
            CompoundTag copy = visa.copy();
            String oldCode = list.getCompound(index).getString("code");
            String code = CredentialCode.sanitize(copy.getString("code"));
            boolean duplicate = allCredentialCodes().stream()
                    .filter(existing -> !existing.equals(oldCode))
                    .anyMatch(existing -> existing.equals(code));
            if (!CredentialCode.isValid(code) || duplicate) {
                copy.putString("code", CredentialCode.generateUnique(allCredentialCodes()));
            }
            list.set(index, copy);
            tag.put("visas", list);
            sign();
        }
    }

    public void removeVisa(int index) {
        ListTag list = tag.getList("visas", Tag.TAG_COMPOUND);
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            tag.put("visas", list);
            sign();
        }
    }

    public void clearVisas() {
        tag.put("visas", new ListTag());
        sign();
    }

    public void addEndorsement(CompoundTag endorsement) {
        addEndorsement(endorsement, false);
    }

    /** Adds an endorsement preserving an explicit code even if shared. */
    public void addEndorsementExact(CompoundTag endorsement) {
        addEndorsement(endorsement, true);
    }

    private void addEndorsement(CompoundTag endorsement, boolean allowDuplicateCode) {
        CompoundTag copy = endorsement.copy();
        if (copy.getString("id").isBlank()) {
            copy.putString("id", "E-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        }
        String code = CredentialCode.sanitize(copy.getString("code"));
        if (!CredentialCode.isValid(code) || (!allowDuplicateCode && allCredentialCodes().contains(code))) {
            copy.putString("code", CredentialCode.generateUnique(allCredentialCodes()));
        }
        ListTag list = tag.getList("endorsements", Tag.TAG_COMPOUND);
        list.add(copy);
        tag.put("endorsements", list);
        sign();
    }

    public void updateEndorsement(int index, CompoundTag endorsement) {
        ListTag list = tag.getList("endorsements", Tag.TAG_COMPOUND);
        if (index >= 0 && index < list.size()) {
            CompoundTag copy = endorsement.copy();
            String oldCode = list.getCompound(index).getString("code");
            String code = CredentialCode.sanitize(copy.getString("code"));
            boolean duplicate = allCredentialCodes().stream()
                    .filter(existing -> !existing.equals(oldCode))
                    .anyMatch(existing -> existing.equals(code));
            if (!CredentialCode.isValid(code) || duplicate) {
                copy.putString("code", CredentialCode.generateUnique(allCredentialCodes()));
            }
            list.set(index, copy);
            tag.put("endorsements", list);
            sign();
        }
    }

    public void removeEndorsement(int index) {
        ListTag list = tag.getList("endorsements", Tag.TAG_COMPOUND);
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            tag.put("endorsements", list);
            sign();
        }
    }

    public void clearEndorsements() {
        tag.put("endorsements", new ListTag());
        sign();
    }

    public void addStamp(String direction, String port, String country, String officer) {
        CompoundTag stamp = new CompoundTag();
        stamp.putString("id", "S-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        stamp.putString("direction", direction);
        stamp.putString("port", port);
        stamp.putString("country", country);
        stamp.putString("date", DateUtil.today());
        stamp.putString("officer", officer);
        addStampTag(stamp);
    }

    public void addStampTag(CompoundTag stamp) {
        CompoundTag copy = stamp.copy();
        if (copy.getString("id").isBlank()) {
            copy.putString("id", "S-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        }
        ListTag list = tag.getList("stamps", Tag.TAG_COMPOUND);
        list.add(copy);
        tag.put("stamps", list);
        sign();
    }

    public void removeStamp(int index) {
        ListTag list = tag.getList("stamps", Tag.TAG_COMPOUND);
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            tag.put("stamps", list);
            sign();
        }
    }

    public void clearStamps() {
        tag.put("stamps", new ListTag());
        sign();
    }

    public boolean isPermanent() {
        return DateUtil.isPermanent(expiryDate());
    }

    public boolean isExpired() {
        return DateUtil.expired(expiryDate());
    }

    public boolean isExpired(LocalDate date) {
        return DateUtil.expiredOn(expiryDate(), date);
    }

    public long daysRemaining() {
        return DateUtil.daysRemaining(expiryDate());
    }

    public boolean integrityValid() {
        return DocumentIntegrity.verify(tag);
    }

    public void sign() {
        tag.putInt("schema", SCHEMA);
        tag.putString("kind", kind().name());
        tag.putString("style", style.id());
        persistMissingCredentialCodes();
        if (kind() == DocumentKind.PASSPORT) {
            String[] mrz = MrzUtil.generate(style, number(), nameLatin(), birthDate(), sex(), expiryDate());
            tag.putString("mrz1", mrz[0]);
            tag.putString("mrz2", mrz[1]);
        }
        DocumentIntegrity.sign(tag);
    }

    /**
     * Writes the deterministic legacy code onto records that predate 1.1.5 so it
     * is persisted the next time the document is saved or edited. Records without
     * an id (malformed NBT) get a random code rather than an unstable legacy one.
     */
    private void persistMissingCredentialCodes() {
        ListTag visas = tag.getList("visas", Tag.TAG_COMPOUND);
        for (int i = 0; i < visas.size(); i++) {
            CompoundTag entry = visas.getCompound(i);
            if (!CredentialCode.isValid(entry.getString("code"))) {
                String id = entry.getString("id");
                entry.putString("code", id.isBlank() ? CredentialCode.random() : CredentialCode.legacy(id));
            }
        }
        ListTag endorsements = tag.getList("endorsements", Tag.TAG_COMPOUND);
        for (int i = 0; i < endorsements.size(); i++) {
            CompoundTag entry = endorsements.getCompound(i);
            if (!CredentialCode.isValid(entry.getString("code"))) {
                String id = entry.getString("id");
                entry.putString("code", id.isBlank() ? CredentialCode.random() : CredentialCode.legacy(id));
            }
        }
    }

    public String mrz1() {
        return tag.getString("mrz1");
    }

    public String mrz2() {
        return tag.getString("mrz2");
    }

    public String sexKey() {
        return switch (sex()) {
            case "F" -> "sex.veriborder.female";
            case "X" -> "sex.veriborder.x";
            default -> "sex.veriborder.male";
        };
    }

    public boolean isRecordUsable() {
        return integrityValid() && status() == DocumentStatus.VALID && !isExpired();
    }

    public String displayNameOrStyle() {
        String name = name();
        return name.isBlank() ? style.id() : name;
    }
}
