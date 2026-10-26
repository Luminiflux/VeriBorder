package com.veriborder.block;

import com.veriborder.doc.CredentialCode;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.EndorsementType;
import com.veriborder.doc.MrzUtil;
import com.veriborder.doc.VerificationResult;
import com.veriborder.doc.VisaType;
import com.veriborder.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class DeviceBlockEntity extends BlockEntity {
    public enum GateState {
        IDLE, VERIFYING, GRANTED, DENIED
    }

    /**
     * Gate modes (26.3): visa code, endorsement code, or either. The old
     * {@code all} mode is gone — an entry gate always requires a matching visa,
     * endorsement or permanent code.
     */
    public static final String GATE_MODE_VISA = "visa";
    public static final String GATE_MODE_ENDORSEMENT = "endorsement";
    public static final String GATE_MODE_BOTH = "both";

    /** Legacy pre-26.3 mode names; {@link #normalizeGateMode(String)} remaps them to {@code both}. */
    @Deprecated
    public static final String GATE_MODE_ALL = GATE_MODE_BOTH;
    @Deprecated
    public static final String GATE_MODE_ANY = GATE_MODE_BOTH;
    @Deprecated
    public static final String GATE_MODE_PASSPORT = GATE_MODE_VISA;
    @Deprecated
    public static final String GATE_MODE_PERMIT = GATE_MODE_ENDORSEMENT;

    /**
     * Persisted + synced issuer lock configuration. Locked values are enforced on
     * the server, so a client that ignores them still cannot issue a different
     * document name, authority, layout, place, date, months-based validity, visa
     * type/code, endorsement type or passport-attached visa
     * (include/entries/country/issue/validity/name).
     *
     * <p>26.2 month precision: the two validity locks are stored as <b>months</b>
     * ({@code lockedValidityMonths} / {@code lockedVisaValidityMonths}). The old
     * year keys are still written as {@code months / 12} for older readers and are
     * migrated to months ({@code years * 12}) when a legacy tag is loaded.</p>
     */
    public record Locks(boolean lockDocumentName, String lockedDocumentName,
                        boolean lockAuthority, String lockedAuthority,
                        boolean lockStyle, String lockedStyle,
                        boolean lockPlace, String lockedPlace,
                        boolean lockIssueDate, String lockedIssueDate,
                        boolean lockValidity, int lockedValidityMonths,
                        boolean lockVisaType, String lockedVisaType,
                        boolean lockVisaCode, String lockedVisaCode,
                        boolean lockEndorsementType, String lockedEndorsementType,
                        boolean lockIncludeVisa, boolean lockedIncludeVisa,
                        boolean lockVisaEntries, int lockedVisaEntries,
                        boolean lockVisaCountry, String lockedVisaCountry,
                        boolean lockVisaIssueDate, String lockedVisaIssueDate,
                        boolean lockVisaValidity, int lockedVisaValidityMonths,
                        boolean lockVisaName, String lockedVisaName) {
        public static final Locks NONE = new Locks(false, "", false, "",
                false, "", false, "", false, "", false, 0,
                false, "", false, "", false, "",
                false, false, false, 0, false, "", false, "", false, 0, false, "");

        public Locks {
            lockedDocumentName = normalize(lockedDocumentName);
            lockedAuthority = normalize(lockedAuthority);
            lockedStyle = normalize(lockedStyle);
            lockedPlace = normalize(lockedPlace);
            lockedIssueDate = normalize(lockedIssueDate);
            lockedVisaType = normalize(lockedVisaType);
            lockedVisaCode = normalize(lockedVisaCode);
            lockedEndorsementType = normalize(lockedEndorsementType);
            lockedVisaCountry = normalize(lockedVisaCountry);
            lockedVisaIssueDate = normalize(lockedVisaIssueDate);
            lockedVisaName = normalize(lockedVisaName);
            lockedVisaEntries = Math.max(0, lockedVisaEntries);
            lockedValidityMonths = clampValidityMonths(lockedValidityMonths);
            lockedVisaValidityMonths = clampValidityMonths(lockedVisaValidityMonths);
        }

        /** Pre-1.2.1 lock view (document name and authority only). */
        public Locks(boolean lockDocumentName, String lockedDocumentName,
                     boolean lockAuthority, String lockedAuthority) {
            this(lockDocumentName, lockedDocumentName, lockAuthority, lockedAuthority,
                    false, "", false, "", false, "", false, 0,
                    false, "", false, "", false, "");
        }

        /** Pre-26.1 lock view: no passport-attached visa locks. */
        public Locks(boolean lockDocumentName, String lockedDocumentName,
                     boolean lockAuthority, String lockedAuthority,
                     boolean lockStyle, String lockedStyle,
                     boolean lockPlace, String lockedPlace,
                     boolean lockIssueDate, String lockedIssueDate,
                     boolean lockValidity, int lockedValidityMonths,
                     boolean lockVisaType, String lockedVisaType,
                     boolean lockVisaCode, String lockedVisaCode,
                     boolean lockEndorsementType, String lockedEndorsementType) {
            this(lockDocumentName, lockedDocumentName, lockAuthority, lockedAuthority,
                    lockStyle, lockedStyle, lockPlace, lockedPlace,
                    lockIssueDate, lockedIssueDate, lockValidity, lockedValidityMonths,
                    lockVisaType, lockedVisaType, lockVisaCode, lockedVisaCode,
                    lockEndorsementType, lockedEndorsementType,
                    false, false, false, 0, false, "", false, "", false, 0, false, "");
        }

        public static Locks fromTag(CompoundTag tag) {
            if (tag == null) {
                return NONE;
            }
            return new Locks(
                    tag.getBoolean("lockDocumentName"),
                    tag.getString("lockedDocumentName"),
                    tag.getBoolean("lockAuthority"),
                    tag.getString("lockedAuthority"),
                    tag.getBoolean("lockStyle"),
                    tag.getString("lockedStyle"),
                    tag.getBoolean("lockPlace"),
                    tag.getString("lockedPlace"),
                    tag.getBoolean("lockIssueDate"),
                    tag.getString("lockedIssueDate"),
                    tag.getBoolean("lockValidity"),
                    validityMonths(tag),
                    tag.getBoolean("lockVisaType"),
                    tag.getString("lockedVisaType"),
                    tag.getBoolean("lockVisaCode"),
                    tag.getString("lockedVisaCode"),
                    tag.getBoolean("lockEndorsementType"),
                    tag.getString("lockedEndorsementType"),
                    tag.getBoolean("lockIncludeVisa"),
                    tag.getBoolean("lockedIncludeVisa"),
                    tag.getBoolean("lockVisaEntries"),
                    tag.getInt("lockedVisaEntries"),
                    tag.getBoolean("lockVisaCountry"),
                    tag.getString("lockedVisaCountry"),
                    tag.getBoolean("lockVisaIssueDate"),
                    tag.getString("lockedVisaIssueDate"),
                    tag.getBoolean("lockVisaValidity"),
                    visaValidityMonths(tag),
                    tag.getBoolean("lockVisaName"),
                    tag.getString("lockedVisaName")
            );
        }

        public CompoundTag writeTo(CompoundTag tag) {
            tag.putBoolean("lockDocumentName", lockDocumentName);
            tag.putString("lockedDocumentName", lockedDocumentName);
            tag.putBoolean("lockAuthority", lockAuthority);
            tag.putString("lockedAuthority", lockedAuthority);
            tag.putBoolean("lockStyle", lockStyle);
            tag.putString("lockedStyle", lockedStyle);
            tag.putBoolean("lockPlace", lockPlace);
            tag.putString("lockedPlace", lockedPlace);
            tag.putBoolean("lockIssueDate", lockIssueDate);
            tag.putString("lockedIssueDate", lockedIssueDate);
            tag.putBoolean("lockValidity", lockValidity);
            // 26.2 month precision: months are authoritative, the legacy year key
            // is kept (floor) so an older build still reads a usable value.
            tag.putInt("lockedValidityMonths", lockedValidityMonths);
            tag.putInt("lockedValidityYears", lockedValidityMonths / 12);
            tag.putBoolean("lockVisaType", lockVisaType);
            tag.putString("lockedVisaType", lockedVisaType);
            tag.putBoolean("lockVisaCode", lockVisaCode);
            tag.putString("lockedVisaCode", lockedVisaCode);
            tag.putBoolean("lockEndorsementType", lockEndorsementType);
            tag.putString("lockedEndorsementType", lockedEndorsementType);
            tag.putBoolean("lockIncludeVisa", lockIncludeVisa);
            tag.putBoolean("lockedIncludeVisa", lockedIncludeVisa);
            tag.putBoolean("lockVisaEntries", lockVisaEntries);
            tag.putInt("lockedVisaEntries", lockedVisaEntries);
            tag.putBoolean("lockVisaCountry", lockVisaCountry);
            tag.putString("lockedVisaCountry", lockedVisaCountry);
            tag.putBoolean("lockVisaIssueDate", lockVisaIssueDate);
            tag.putString("lockedVisaIssueDate", lockedVisaIssueDate);
            tag.putBoolean("lockVisaValidity", lockVisaValidity);
            tag.putInt("lockedVisaValidityMonths", lockedVisaValidityMonths);
            tag.putInt("lockedVisaValidityYears", lockedVisaValidityMonths / 12);
            tag.putBoolean("lockVisaName", lockVisaName);
            tag.putString("lockedVisaName", lockedVisaName);
            return tag;
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /** Upper bound for a validity lock (100 years) so a forged tag cannot overflow. */
    public static final int MAX_VALIDITY_MONTHS = 1200;

    /** 0 months means "permanent"; everything else is clamped into a sane range. */
    public static int clampValidityMonths(int months) {
        return Math.max(0, Math.min(MAX_VALIDITY_MONTHS, months));
    }

    /**
     * Reads a month-based validity from NBT, migrating a legacy year value
     * ({@code lockedValidityYears} × 12) when the month key is absent. Pure, so
     * the migration rule is unit-testable.
     */
    public static int validityMonths(CompoundTag tag) {
        return migrateValidityMonths(tag, "lockedValidityMonths", "lockedValidityYears");
    }

    /** Visa counterpart of {@link #validityMonths(CompoundTag)}. */
    public static int visaValidityMonths(CompoundTag tag) {
        return migrateValidityMonths(tag, "lockedVisaValidityMonths", "lockedVisaValidityYears");
    }

    /**
     * Month-first read with a legacy year fallback: an explicit month key wins
     * (including 0 = permanent); otherwise the old year key is expanded ×12.
     */
    public static int migrateValidityMonths(CompoundTag tag, String monthsKey, String yearsKey) {
        if (tag == null) {
            return 0;
        }
        if (tag.contains(monthsKey)) {
            return clampValidityMonths(tag.getInt(monthsKey));
        }
        return clampValidityMonths(tag.getInt(yearsKey) * 12);
    }

    private String port = "北京首都国际机场 T3";
    private String country = "中国";
    private String officer = "边检警官";
    private String airportCountry = "";
    private final ListTag presets = new ListTag();
    /**
     * Dual-clearance gate only: the two-stage configuration applied by
     * {@code DualGateBrushItem} (exit/entry identity, entry mode and both
     * independent eight-digit code lists: {@code exitCodes} for the exit stage,
     * {@code entryCodes} for the entry stage). Empty on every other device and on
     * a freshly placed dual gate, in which case the use logic falls back to the
     * gate's own port/country/officer, mode {@code all} and generic gate codes.
     * Schema: {@code com.veriborder.item.DualGateBrushConfig}.
     */
    private CompoundTag dualGate = new CompoundTag();

    /** Gate binding: "visa" | "endorsement" | "both" (26.3; {@code all} is gone). */
    private String gateMode = GATE_MODE_BOTH;
    /** Eight-digit codes accepted by this gate (no limit, deduplicated, order kept). */
    private final ListTag gateCodes = new ListTag();
    /**
     * Exit gate only: dedicated eight-digit exit codes enforced on departure.
     * Empty falls back to {@link #gateCodes}, so an existing gate keeps working
     * before an administrator configures a dedicated exit list.
     */
    private final ListTag exitCodes = new ListTag();
    /**
     * Entry-stage whitelist of allowed issuing-country codes ("允许国家/地区码"):
     * a document whose {@code issuerCountry} matches one of them is a visa-free
     * pass (the old permanent-code slot) and needs no visa/endorsement code.
     */
    private final ListTag entryCountryCodes = new ListTag();
    /**
     * Exit-stage whitelist of allowed issuing-country codes: unlike the entry
     * stage it is directional — a document issued by an allowed country/region
     * passes the exit stage directly, without an exit-code match.
     */
    private final ListTag exitCountryCodes = new ListTag();
    /**
     * Dormant pre-1.1.5 type requirements. Kept only so old NBT/old config
     * payloads still round-trip; validation never reads them.
     */
    private String requiredVisaType = "";
    private String requiredEndorsementType = "";

    private boolean lockDocumentName;
    private String lockedDocumentName = "";
    private boolean lockAuthority;
    private String lockedAuthority = "";
    /** Locked document layout (DocStyle id); applied only when the kind matches. */
    private boolean lockStyle;
    private String lockedStyle = "";
    /**
     * 26.4: registry template the locked layout came from ("" for a plain
     * official layout). Kept next to {@link #lockedStyle} so issuing can carry
     * the template's colour/icon; a deleted template is simply ignored.
     */
    private String lockedStyleTemplate = "";
    /** Locked place of issue for documents / country for visas. */
    private boolean lockPlace;
    private String lockedPlace = "";
    /** Locked issue date (YYYY-MM-DD) for documents, visas and endorsements. */
    private boolean lockIssueDate;
    private String lockedIssueDate = "";
    /** Locked validity in <b>months</b> (26.2 month precision); 0 means permanent. */
    private boolean lockValidity;
    private int lockedValidityMonths;
    /** Locked visa type id, used by visa issuers, self-visa and attached visas. */
    private boolean lockVisaType;
    private String lockedVisaType = "";
    /** Locked eight-digit visa code (passport-attached visas). */
    private boolean lockVisaCode;
    private String lockedVisaCode = "";
    /** Locked endorsement type id, used by endorsement machines. */
    private boolean lockEndorsementType;
    private String lockedEndorsementType = "";
    /** Passport issuer: force whether an attached visa is issued at all. */
    private boolean lockIncludeVisa;
    private boolean lockedIncludeVisa;
    /** Passport issuer: locked attached-visa entries; 0 means unlimited. */
    private boolean lockVisaEntries;
    private int lockedVisaEntries;
    /** Passport issuer: locked attached-visa country (lockPlace stays the legacy fallback). */
    private boolean lockVisaCountry;
    private String lockedVisaCountry = "";
    /** Passport issuer: locked attached-visa issue date (YYYY-MM-DD). */
    private boolean lockVisaIssueDate;
    private String lockedVisaIssueDate = "";
    /** Passport issuer: locked attached-visa validity in months; 0 means permanent. */
    private boolean lockVisaValidity;
    private int lockedVisaValidityMonths;
    /** Passport/visa issuer: locked attached-visa display label. */
    private boolean lockVisaName;
    private String lockedVisaName = "";
    /** Fixed code used by a self-service visa/endorsement kiosk; blank = auto. */
    private String issuedCode = "";
    /** Permit issuer: lock the initial endorsement code instead of auto-generating it. */
    private boolean lockEndorsementCode;
    private String lockedEndorsementCode = "";

    // ---- 26.2 issuer policy (device-side, configured through the brush GUI) ----
    // These used to live on the applicant-facing IssuerScreen; they are now
    // administrator configuration stored on the block entity.
    /** Keep the document number system-generated (the form number is ignored). */
    private boolean lockNumber = true;
    /**
     * Force the holder nationality of every document this issuer creates. The
     * locked value is applied server-side on issue (see
     * {@code DeviceService#issueDocument}) and the applicant form field becomes
     * read-only while it is set. Independent of the legacy {@link Locks} record.
     */
    private boolean lockNationality;
    /** Locked holder nationality: trimmed, at most {@link #MAX_NATIONALITY_LENGTH} chars. */
    private String lockedNationality = "";
    /** CN_ID six-digit region override; blank uses the layout number prefix. */
    private String numberRegion = "";
    /** CN_ID two-digit sequence override; blank generates a random sequence. */
    private String numberSequence = "";
    /** Passport MRZ issuing-country override (three letters); blank uses the layout code. */
    private String mrzCountry = "";
    /**
     * 26.3 registry: issuing-country code of this machine (three letters A-Z).
     * Chosen from the credential registry; it is the single source for the
     * document {@code issuerCountry} and the passport MRZ code.
     */
    private String issuerCountry = "";
    /** A freshly issued document starts the holder's entry session when possible. */
    private boolean issueEntryState = true;
    /** Permanent codes attached to every document this issuer creates (8 digits, deduped). */
    private final ListTag issueCodes = new ListTag();

    /**
     * Gate behaviour: write the IN/OUT immigration stamp automatically when a
     * traveller passes (passport and HK/Macau permit only).
     */
    public static final int DEFAULT_OVERSTAY_DAYS = 30;
    /** Upper bound for {@link #overstayDays()} so the day maths never overflows. */
    public static final int MAX_OVERSTAY_DAYS = 3650;
    /** Upper bound for the locked holder nationality so NBT/config payloads stay bounded. */
    public static final int MAX_NATIONALITY_LENGTH = 48;
    /** 26.4: upper bound for the registry template id carried by the layout lock. */
    public static final int MAX_STYLE_TEMPLATE_LENGTH = 64;
    private boolean autoStamp = true;
    /** Days a traveller may stay before the exit gate reports an overstay. */
    private int overstayDays = DEFAULT_OVERSTAY_DAYS;

    private GateState gateState = GateState.IDLE;
    private int stateTicks;
    private float openProgress;
    private String gateMessage = "";
    private UUID pendingPlayer;
    private VerificationResult pendingResult;

    public DeviceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DEVICE.get(), pos, state);
    }

    /**
     * Live (chunk-loaded) device block entities of this JVM, weakly referenced so
     * an unloaded world never keeps a block entity alive. 26.3 round 12 uses this
     * for the credential-registry reference check: deleting a country/template
     * that a loaded gate or issuer still references is refused. Devices in
     * unloaded chunks cannot be inspected without loading them, so the check
     * covers the loaded world (documented on the registry screen).
     */
    private static final java.util.Set<DeviceBlockEntity> LOADED =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level != null && !level.isClientSide) {
            LOADED.add(this);
        }
    }

    @Override
    public void setRemoved() {
        LOADED.remove(this);
        super.setRemoved();
    }

    /** Snapshot of the loaded devices that belong to {@code server} (may be empty). */
    public static List<DeviceBlockEntity> loadedDevices(@Nullable net.minecraft.server.MinecraftServer server) {
        List<DeviceBlockEntity> result = new ArrayList<>();
        if (server == null) {
            return result;
        }
        List<DeviceBlockEntity> snapshot;
        synchronized (LOADED) {
            snapshot = new ArrayList<>(LOADED);
        }
        for (DeviceBlockEntity device : snapshot) {
            if (device == null || device.isRemoved()) {
                continue;
            }
            Level level = device.getLevel();
            if (level != null && level.getServer() == server) {
                result.add(device);
            }
        }
        return result;
    }

    public DeviceType deviceType() {
        return getBlockState().getBlock() instanceof DeviceBlock block ? block.deviceType() : DeviceType.ID_ISSUER;
    }

    public String port() {
        return port;
    }

    public String country() {
        return country;
    }

    public String officer() {
        return officer;
    }

    public String airportCountry() {
        return airportCountry;
    }

    public void setPort(String value) {
        port = safe(value, "口岸");
        changed();
    }

    public void setCountry(String value) {
        country = safe(value, "中国");
        changed();
    }

    public void setOfficer(String value) {
        officer = safe(value, "边检警官");
        changed();
    }

    public void setAirportCountry(String value) {
        airportCountry = value == null ? "" : value;
        changed();
    }

    public String gateMode() {
        return gateMode;
    }

    public void setGateMode(String value) {
        gateMode = normalizeGateMode(value);
        changed();
    }

    /** The configured gate codes, normalized (valid 8 digits, deduplicated). */
    public List<String> gateCodes() {
        List<String> values = new ArrayList<>(gateCodes.size());
        for (int i = 0; i < gateCodes.size(); i++) {
            values.add(gateCodes.getString(i));
        }
        return CredentialCode.normalizeAll(values);
    }

    /** Copy of the gate code list for NBT/config payloads. */
    public ListTag gateCodesTag() {
        ListTag copy = new ListTag();
        for (String code : gateCodes()) {
            copy.add(StringTag.valueOf(code));
        }
        return copy;
    }

    public void setGateCodes(ListTag codes) {
        List<String> values = new ArrayList<>();
        if (codes != null) {
            for (int i = 0; i < codes.size(); i++) {
                values.add(codes.getString(i));
            }
        }
        setGateCodes(values);
    }

    public void setGateCodes(Collection<String> codes) {
        List<String> normalized = CredentialCode.normalizeAll(codes);
        gateCodes.clear();
        for (String code : normalized) {
            gateCodes.add(StringTag.valueOf(code));
        }
        changed();
    }

    /** Adds one code. Invalid codes and duplicates are ignored. */
    public boolean addGateCode(String code) {
        String normalized = CredentialCode.sanitize(code);
        if (normalized.isBlank() || gateCodes().contains(normalized)) {
            return false;
        }
        gateCodes.add(StringTag.valueOf(normalized));
        changed();
        return true;
    }

    public boolean removeGateCode(String code) {
        String normalized = CredentialCode.sanitize(code);
        if (normalized.isBlank()) {
            return false;
        }
        List<String> values = gateCodes();
        if (!values.remove(normalized)) {
            return false;
        }
        setGateCodes(values);
        return true;
    }

    public void clearGateCodes() {
        if (!gateCodes.isEmpty()) {
            gateCodes.clear();
            changed();
        }
    }

    public static List<String> normalizeGateCodes(Collection<String> codes) {
        return CredentialCode.normalizeAll(codes);
    }

    /** The configured exit codes, normalized (valid 8 digits, deduplicated). */
    public List<String> exitCodes() {
        return readCodes(exitCodes);
    }

    /** Copy of the dedicated exit-code list for NBT/config payloads. */
    public ListTag exitCodesTag() {
        return copyCodes(exitCodes);
    }

    public void setExitCodes(ListTag codes) {
        setExitCodes(readCodes(codes));
    }

    public void setExitCodes(Collection<String> codes) {
        writeCodes(exitCodes, codes);
        changed();
    }

    /** The entry-stage allowed-country whitelist, normalized to 3-letter codes. */
    public List<String> entryCountryCodes() {
        return normalizeCountryCodes(readRawCodes(entryCountryCodes));
    }

    /** The exit-stage allowed-country whitelist, normalized to 3-letter codes. */
    public List<String> exitCountryCodes() {
        return normalizeCountryCodes(readRawCodes(exitCountryCodes));
    }

    /** Copy of the entry whitelist for NBT/config payloads. */
    public ListTag entryCountryCodesTag() {
        return countryCodesTag(entryCountryCodes);
    }

    /** Copy of the exit whitelist for NBT/config payloads. */
    public ListTag exitCountryCodesTag() {
        return countryCodesTag(exitCountryCodes);
    }

    public void setEntryCountryCodes(ListTag codes) {
        setEntryCountryCodes(readRawCodes(codes));
    }

    public void setEntryCountryCodes(Collection<String> codes) {
        writeCountryCodes(entryCountryCodes, codes);
        changed();
    }

    public void setExitCountryCodes(ListTag codes) {
        setExitCountryCodes(readRawCodes(codes));
    }

    public void setExitCountryCodes(Collection<String> codes) {
        writeCountryCodes(exitCountryCodes, codes);
        changed();
    }

    private static ListTag countryCodesTag(ListTag source) {
        ListTag copy = new ListTag();
        for (String code : normalizeCountryCodes(readRawCodes(source))) {
            copy.add(StringTag.valueOf(code));
        }
        return copy;
    }

    private static void writeCountryCodes(ListTag target, Collection<String> codes) {
        target.clear();
        for (String code : normalizeCountryCodes(codes)) {
            target.add(StringTag.valueOf(code));
        }
    }

    /** Normalizes a country-code collection to three-letter A-Z codes. */
    private static List<String> normalizeCountryCodes(Collection<String> codes) {
        List<String> normalized = new ArrayList<>();
        if (codes == null) {
            return normalized;
        }
        for (String code : codes) {
            String cleaned = MrzUtil.countryCode(code);
            if (!cleaned.isEmpty() && !normalized.contains(cleaned)) {
                normalized.add(cleaned);
            }
        }
        return normalized;
    }

    private static List<String> readCodes(ListTag list) {
        List<String> values = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            values.add(list.getString(i));
        }
        return CredentialCode.normalizeAll(values);
    }

    /** Raw string values of a code list (used for 3-letter country codes). */
    private static List<String> readRawCodes(ListTag list) {
        List<String> values = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            values.add(list.getString(i));
        }
        return values;
    }

    private static ListTag copyCodes(ListTag list) {
        ListTag copy = new ListTag();
        for (String code : readCodes(list)) {
            copy.add(StringTag.valueOf(code));
        }
        return copy;
    }

    private static void writeCodes(ListTag target, Collection<String> codes) {
        List<String> normalized = CredentialCode.normalizeAll(codes);
        target.clear();
        for (String code : normalized) {
            target.add(StringTag.valueOf(code));
        }
    }

    /** Copy of the dual-clearance gate configuration (empty when unconfigured). */
    public CompoundTag dualGateConfig() {
        return dualGate.copy();
    }

    /**
     * Replaces the dual-clearance gate configuration. The caller
     * ({@code DualGateBrushConfig#applyTo}) is responsible for sanitising it.
     */
    public void setDualGateConfig(CompoundTag value) {
        dualGate = value == null ? new CompoundTag() : value.copy();
        changed();
    }

    /** Whether this gate stamps IN/OUT automatically for passport/permit holders. */
    public boolean autoStamp() {
        return autoStamp;
    }

    public void setAutoStamp(boolean value) {
        autoStamp = value;
        changed();
    }

    /** Days allowed between entry and exit before the exit gate warns. */
    public int overstayDays() {
        return overstayDays;
    }

    public void setOverstayDays(int value) {
        overstayDays = clampOverstay(value);
        changed();
    }

    private static int clampOverstay(int value) {
        return Math.max(0, Math.min(MAX_OVERSTAY_DAYS, value));
    }

    public String requiredVisaType() {
        return requiredVisaType;
    }

    public void setRequiredVisaType(String value) {
        requiredVisaType = normalizeVisaType(value);
        changed();
    }

    public String requiredEndorsementType() {
        return requiredEndorsementType;
    }

    public void setRequiredEndorsementType(String value) {
        requiredEndorsementType = normalizeEndorsementType(value);
        changed();
    }

    /**
     * Typed view of the dormant legacy visa requirement. Unused by validation;
     * kept for old add-on compatibility.
     */
    @Nullable
    public VisaType requiredVisa() {
        return visaTypeOrNull(requiredVisaType);
    }

    /** Typed view of the dormant legacy endorsement requirement. */
    @Nullable
    public EndorsementType requiredEndorsement() {
        return EndorsementType.byIdOrNull(requiredEndorsementType);
    }

    public boolean lockDocumentName() {
        return lockDocumentName;
    }

    public String lockedDocumentName() {
        return lockedDocumentName;
    }

    public boolean lockAuthority() {
        return lockAuthority;
    }

    public String lockedAuthority() {
        return lockedAuthority;
    }

    public String issuedCode() {
        return issuedCode;
    }

    public boolean lockEndorsementCode() {
        return lockEndorsementCode;
    }

    public String lockedEndorsementCode() {
        return lockedEndorsementCode;
    }

    /** Whether this issuer keeps the document number system-generated. */
    public boolean lockNumber() {
        return lockNumber;
    }

    /** Whether this issuer forces the holder nationality of its documents. */
    public boolean lockNationality() {
        return lockNationality;
    }

    /** The forced holder nationality; blank means the lock has no effect. */
    public String lockedNationality() {
        return lockedNationality;
    }

    /** CN_ID region override (six digits, blank = layout default). */
    public String numberRegion() {
        return numberRegion;
    }

    /** CN_ID sequence override (two digits, blank = random). */
    public String numberSequence() {
        return numberSequence;
    }

    /** Passport MRZ issuing-country override (three letters, blank = layout default). */
    public String mrzCountry() {
        return mrzCountry;
    }

    /**
     * 26.3 registry: issuing-country code of this machine. It is the single
     * source of the document {@code issuerCountry} and of the passport MRZ code.
     */
    public String issuerCountry() {
        return MrzUtil.countryCode(issuerCountry);
    }

    public void setIssuerCountry(String value) {
        issuerCountry = MrzUtil.countryCode(value);
        changed();
    }

    /** Whether a newly issued document starts the holder's entry session. */
    public boolean issueEntryState() {
        return issueEntryState;
    }

    /** The configured permanent codes, normalized (valid 8 digits, deduplicated). */
    public List<String> issueCodes() {
        List<String> values = new ArrayList<>(issueCodes.size());
        for (int i = 0; i < issueCodes.size(); i++) {
            values.add(issueCodes.getString(i));
        }
        return CredentialCode.normalizeAll(values);
    }

    /** Copy of the permanent-code list for NBT/config payloads. */
    public ListTag issueCodesTag() {
        ListTag copy = new ListTag();
        for (String code : issueCodes()) {
            copy.add(StringTag.valueOf(code));
        }
        return copy;
    }

    public boolean lockStyle() {
        return lockStyle;
    }

    public String lockedStyle() {
        return lockedStyle;
    }

    /** 26.4: registry template behind the locked layout, or "" for a plain layout. */
    public String lockedStyleTemplate() {
        return lockedStyleTemplate;
    }

    public boolean lockPlace() {
        return lockPlace;
    }

    public String lockedPlace() {
        return lockedPlace;
    }

    public boolean lockIssueDate() {
        return lockIssueDate;
    }

    public String lockedIssueDate() {
        return lockedIssueDate;
    }

    public boolean lockValidity() {
        return lockValidity;
    }

    /** Locked document validity in months (0 = permanent). */
    public int lockedValidityMonths() {
        return lockedValidityMonths;
    }

    public boolean lockVisaType() {
        return lockVisaType;
    }

    public String lockedVisaType() {
        return lockedVisaType;
    }

    public boolean lockVisaCode() {
        return lockVisaCode;
    }

    public String lockedVisaCode() {
        return lockedVisaCode;
    }

    public boolean lockEndorsementType() {
        return lockEndorsementType;
    }

    public String lockedEndorsementType() {
        return lockedEndorsementType;
    }

    public boolean lockIncludeVisa() {
        return lockIncludeVisa;
    }

    public boolean lockedIncludeVisa() {
        return lockedIncludeVisa;
    }

    public boolean lockVisaEntries() {
        return lockVisaEntries;
    }

    public int lockedVisaEntries() {
        return lockedVisaEntries;
    }

    public boolean lockVisaCountry() {
        return lockVisaCountry;
    }

    public String lockedVisaCountry() {
        return lockedVisaCountry;
    }

    public boolean lockVisaIssueDate() {
        return lockVisaIssueDate;
    }

    public String lockedVisaIssueDate() {
        return lockedVisaIssueDate;
    }

    public boolean lockVisaValidity() {
        return lockVisaValidity;
    }

    /** Locked attached-visa validity in months (0 = permanent). */
    public int lockedVisaValidityMonths() {
        return lockedVisaValidityMonths;
    }

    public boolean lockVisaName() {
        return lockVisaName;
    }

    public String lockedVisaName() {
        return lockedVisaName;
    }

    /**
     * Resolves the configured locked layout. Blank/unknown ids return {@code null}
     * so the caller falls back to the submitted layout; the {@link #lockStyle()}
     * flag is checked separately.
     */
    @Nullable
    public DocStyle lockedStyleOrNull() {
        return DocStyle.byIdOrNull(lockedStyle);
    }

    /**
     * Resolves the configured locked visa type. Blank/unknown ids return
     * {@code null}; the {@link #lockVisaType()} flag is checked separately.
     */
    @Nullable
    public VisaType lockedVisaTypeOrNull() {
        return visaTypeOrNull(lockedVisaType);
    }

    /**
     * Resolves the configured locked endorsement type. Blank/unknown ids return
     * {@code null}; the {@link #lockEndorsementType()} flag is checked separately.
     */
    @Nullable
    public EndorsementType lockedEndorsementTypeOrNull() {
        return EndorsementType.byIdOrNull(lockedEndorsementType);
    }

    public void setLockStyle(boolean value) {
        lockStyle = value;
        changed();
    }

    /**
     * 26.4 fix: stores only a real official layout id. The layout lock is written
     * from a dropdown that may still carry a {@code template:<id>} value; an
     * unusable value becomes "" (the layout lock then falls back to the submitted
     * layout) instead of reaching the issuing path as an unparsable string.
     */
    public void setLockedStyle(String value) {
        lockedStyle = normalizeLockedStyle(value);
        if (lockedStyle.isEmpty()) {
            lockedStyleTemplate = "";
        }
        changed();
    }

    /** 26.4: registry template of the locked layout (ignored without a locked layout). */
    public void setLockedStyleTemplate(String value) {
        String template = normalizeLockedStyleTemplate(value);
        lockedStyleTemplate = lockedStyle.isEmpty() ? "" : template;
        changed();
    }

    /** Canonical official layout id, or "" when {@code value} is not one. */
    public static String normalizeLockedStyle(String value) {
        DocStyle style = DocStyle.byIdOrNull(value);
        return style == null ? "" : style.id();
    }

    /** Trimmed, length-capped registry template id ("" when unset). */
    public static String normalizeLockedStyleTemplate(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        return cleaned.length() > MAX_STYLE_TEMPLATE_LENGTH
                ? cleaned.substring(0, MAX_STYLE_TEMPLATE_LENGTH) : cleaned;
    }

    public void setLockPlace(boolean value) {
        lockPlace = value;
        changed();
    }

    public void setLockedPlace(String value) {
        lockedPlace = value == null ? "" : value.trim();
        changed();
    }

    public void setLockIssueDate(boolean value) {
        lockIssueDate = value;
        changed();
    }

    public void setLockedIssueDate(String value) {
        lockedIssueDate = value == null ? "" : value.trim();
        changed();
    }

    public void setLockValidity(boolean value) {
        lockValidity = value;
        changed();
    }

    /** Stores the locked document validity in months (clamped, 0 = permanent). */
    public void setLockedValidityMonths(int value) {
        lockedValidityMonths = clampValidityMonths(value);
        changed();
    }

    public void setLockVisaType(boolean value) {
        lockVisaType = value;
        changed();
    }

    public void setLockedVisaType(String value) {
        lockedVisaType = value == null ? "" : value.trim();
        changed();
    }

    public void setLockVisaCode(boolean value) {
        lockVisaCode = value;
        changed();
    }

    public void setLockedVisaCode(String value) {
        lockedVisaCode = CredentialCode.sanitize(value);
        changed();
    }

    public void setLockEndorsementType(boolean value) {
        lockEndorsementType = value;
        changed();
    }

    public void setLockedEndorsementType(String value) {
        lockedEndorsementType = value == null ? "" : value.trim();
        changed();
    }

    public void setLockIncludeVisa(boolean value) {
        lockIncludeVisa = value;
        changed();
    }

    public void setLockedIncludeVisa(boolean value) {
        lockedIncludeVisa = value;
        changed();
    }

    public void setLockVisaEntries(boolean value) {
        lockVisaEntries = value;
        changed();
    }

    public void setLockedVisaEntries(int value) {
        lockedVisaEntries = Math.max(0, value);
        changed();
    }

    public void setLockVisaCountry(boolean value) {
        lockVisaCountry = value;
        changed();
    }

    public void setLockedVisaCountry(String value) {
        lockedVisaCountry = value == null ? "" : value.trim();
        changed();
    }

    public void setLockVisaIssueDate(boolean value) {
        lockVisaIssueDate = value;
        changed();
    }

    public void setLockedVisaIssueDate(String value) {
        lockedVisaIssueDate = value == null ? "" : value.trim();
        changed();
    }

    public void setLockVisaValidity(boolean value) {
        lockVisaValidity = value;
        changed();
    }

    /** Stores the locked attached-visa validity in months (clamped, 0 = permanent). */
    public void setLockedVisaValidityMonths(int value) {
        lockedVisaValidityMonths = clampValidityMonths(value);
        changed();
    }

    public void setLockVisaName(boolean value) {
        lockVisaName = value;
        changed();
    }

    public void setLockedVisaName(String value) {
        lockedVisaName = value == null ? "" : value.trim();
        changed();
    }

    public void setIssuedCode(String value) {
        String cleaned = value == null ? "" : value.trim();
        issuedCode = CredentialCode.isValid(cleaned) ? cleaned : "";
        changed();
    }

    public void setLockEndorsementCode(boolean value) {
        lockEndorsementCode = value;
        changed();
    }

    public void setLockedEndorsementCode(String value) {
        String cleaned = value == null ? "" : value.trim();
        lockedEndorsementCode = CredentialCode.isValid(cleaned) ? cleaned : "";
        changed();
    }

    public void setLockNumber(boolean value) {
        lockNumber = value;
        changed();
    }

    public void setLockNationality(boolean value) {
        lockNationality = value;
        changed();
    }

    public void setLockedNationality(String value) {
        lockedNationality = normalizeNationality(value);
        changed();
    }

    public void setNumberRegion(String value) {
        numberRegion = normalizeRegion(value);
        changed();
    }

    public void setNumberSequence(String value) {
        numberSequence = normalizeSequence(value);
        changed();
    }

    public void setMrzCountry(String value) {
        mrzCountry = normalizeMrzCountry(value);
        changed();
    }

    public void setIssueEntryState(boolean value) {
        issueEntryState = value;
        changed();
    }

    public void setIssueCodes(ListTag codes) {
        List<String> values = new ArrayList<>();
        if (codes != null) {
            for (int i = 0; i < codes.size(); i++) {
                values.add(codes.getString(i));
            }
        }
        setIssueCodes(values);
    }

    public void setIssueCodes(Collection<String> codes) {
        List<String> normalized = CredentialCode.normalizeAll(codes);
        issueCodes.clear();
        for (String code : normalized) {
            issueCodes.add(StringTag.valueOf(code));
        }
        changed();
    }

    /** The full lock configuration (also used for NBT round-trip tests). */
    public Locks locks() {
        return new Locks(lockDocumentName, lockedDocumentName, lockAuthority, lockedAuthority,
                lockStyle, lockedStyle, lockPlace, lockedPlace, lockIssueDate, lockedIssueDate,
                lockValidity, lockedValidityMonths, lockVisaType, lockedVisaType,
                lockVisaCode, lockedVisaCode, lockEndorsementType, lockedEndorsementType,
                lockIncludeVisa, lockedIncludeVisa, lockVisaEntries, lockedVisaEntries,
                lockVisaCountry, lockedVisaCountry, lockVisaIssueDate, lockedVisaIssueDate,
                lockVisaValidity, lockedVisaValidityMonths, lockVisaName, lockedVisaName);
    }

    public void setLockDocumentName(boolean value) {
        lockDocumentName = value;
        changed();
    }

    public void setLockedDocumentName(String value) {
        lockedDocumentName = value == null ? "" : value.trim();
        changed();
    }

    public void setLockAuthority(boolean value) {
        lockAuthority = value;
        changed();
    }

    public void setLockedAuthority(String value) {
        lockedAuthority = value == null ? "" : value.trim();
        changed();
    }

    /**
     * Canonical gate mode (26.3). The removed {@code all} mode, {@code any}, a
     * blank value, an unknown value and {@code null} all become
     * {@link #GATE_MODE_BOTH}, so a device saved by an older build (or a forged
     * config payload) can never re-enable the "accept any document" behaviour.
     */
    public static String normalizeGateMode(String value) {
        if (value == null) {
            return GATE_MODE_BOTH;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case GATE_MODE_VISA, "passport" -> GATE_MODE_VISA;
            case GATE_MODE_ENDORSEMENT, "permit" -> GATE_MODE_ENDORSEMENT;
            default -> GATE_MODE_BOTH;
        };
    }

    private static String normalizeVisaType(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        VisaType resolved = visaTypeOrNull(cleaned);
        return resolved == null ? cleaned : resolved.id();
    }

    private static String normalizeEndorsementType(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        EndorsementType resolved = EndorsementType.byIdOrNull(cleaned);
        return resolved == null ? cleaned : resolved.id();
    }

    /** Resolves a visa type id/name; blank or unknown values return {@code null}. */
    @Nullable
    public static VisaType visaTypeOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (VisaType candidate : VisaType.values()) {
            if (candidate.id().equalsIgnoreCase(value.trim()) || candidate.name().equalsIgnoreCase(value.trim())) {
                return candidate;
            }
        }
        return null;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public ListTag presets() {
        return presets.copy();
    }

    public void savePreset(String name, CompoundTag values) {
        String cleaned = name == null || name.isBlank() ? "Preset" : name.trim();
        for (int i = presets.size() - 1; i >= 0; i--) {
            CompoundTag entry = presets.getCompound(i);
            if (entry.getString("name").equalsIgnoreCase(cleaned)) {
                presets.remove(i);
            }
        }
        CompoundTag entry = new CompoundTag();
        entry.putString("name", cleaned);
        entry.put("values", values.copy());
        presets.add(entry);
        while (presets.size() > 32) {
            presets.remove(0);
        }
        changed();
    }

    public void deletePreset(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        for (int i = presets.size() - 1; i >= 0; i--) {
            if (presets.getCompound(i).getString("name").equalsIgnoreCase(name.trim())) {
                presets.remove(i);
            }
        }
        changed();
    }

    public GateState gateState() {
        return gateState;
    }

    public String gateMessage() {
        return gateMessage;
    }

    public float openProgress(float partialTick) {
        boolean openState = getBlockState().hasProperty(EGateBlock.OPEN)
                && getBlockState().getValue(EGateBlock.OPEN);
        float target = openState ? 1.0F : 0.0F;
        float speed = openState ? 0.09F : 0.065F;
        float value = openProgress;
        if (value < target) {
            value = Math.min(target, value + speed);
        } else if (value > target) {
            value = Math.max(target, value - speed);
        }
        openProgress = value;
        return value;
    }

    public boolean startVerification(ServerPlayer player, VerificationResult result) {
        if (gateState == GateState.VERIFYING) {
            return false;
        }
        setOpen(false);
        gateState = GateState.VERIFYING;
        stateTicks = 55;
        openProgress = 0.0F;
        pendingPlayer = player.getUUID();
        pendingResult = result;
        gateMessage = "gate.veriborder.verifying";
        changed();
        sync();
        // Short mechanical read-head click while the document is checked; no
        // beacon/anvil fanfare and no particle burst for a gate.
        level.playSound(null, worldPosition, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.45F, 1.45F);
        return true;
    }

    @Nullable
    public VerificationResult pendingResult() {
        return pendingResult;
    }

    private void completeVerification() {
        boolean passed = pendingResult != null && pendingResult.valid();
        setOpen(passed);
        gateState = passed ? GateState.GRANTED : GateState.DENIED;
        stateTicks = passed ? 60 : 70;
        gateMessage = passed ? "gate.veriborder.granted" : "gate.veriborder.denied";
        if (level instanceof ServerLevel serverLevel) {
            // No pass/fail particle burst: the door movement, the status lens and
            // the buzzer carry the feedback. The open/close servo sound is played
            // by setOpen() so every door movement is heard exactly once.
            if (!passed) {
                serverLevel.playSound(null, worldPosition, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS,
                        0.85F, 0.62F);
            }
            if (pendingPlayer != null) {
                ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(pendingPlayer);
                if (player != null) {
                    player.displayClientMessage(Component.translatable(gateMessage), true);
                    if (!passed && pendingResult != null) {
                        player.displayClientMessage(pendingResult.message(), false);
                    }
                }
            }
        }
        pendingPlayer = null;
        pendingResult = null;
        changed();
        sync();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, DeviceBlockEntity entity) {
        if (level == null) {
            return;
        }
        entity.animate();
        if (level.isClientSide) {
            return;
        }
        if (entity.gateState == GateState.IDLE && entity.getBlockState().hasProperty(EGateBlock.OPEN)
                && entity.getBlockState().getValue(EGateBlock.OPEN)) {
            entity.setOpen(false);
        }
        if (entity.gateState == GateState.VERIFYING) {
            if (--entity.stateTicks <= 0) {
                entity.completeVerification();
            }
        } else if (entity.gateState == GateState.GRANTED || entity.gateState == GateState.DENIED) {
            if (--entity.stateTicks <= 0) {
                entity.setOpen(false);
                entity.gateState = GateState.IDLE;
                entity.gateMessage = "gate.veriborder.idle";
                entity.changed();
                entity.sync();
            }
        }
    }

    private void setOpen(boolean open) {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = getBlockState();
        if (state.hasProperty(EGateBlock.OPEN) && state.getValue(EGateBlock.OPEN) != open) {
            level.setBlock(worldPosition, state.setValue(EGateBlock.OPEN, open), 3);
            playGateSound(open);
        }
    }

    /**
     * Mechanical gate audio. Open = one short servo extension; close = the servo
     * retracting plus a soft latch; both are pitched up a little so they read as
     * a motor rather than a wooden piston.
     */
    private void playGateSound(boolean open) {
        if (open) {
            level.playSound(null, worldPosition, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.55F, 1.55F);
        } else {
            level.playSound(null, worldPosition, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.5F, 1.45F);
            level.playSound(null, worldPosition, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.35F, 1.35F);
        }
    }

    private void animate() {
        // The OPEN block state is the authoritative, reliably synced source for the
        // physical passage. Driving the wing animation from it means the glass
        // wings always match the collision shape even if a block-entity update
        // packet arrives late or is missed.
        boolean openState = getBlockState().hasProperty(EGateBlock.OPEN)
                && getBlockState().getValue(EGateBlock.OPEN);
        float target = openState ? 1.0F : 0.0F;
        float speed = openState ? 0.09F : 0.065F;
        if (openProgress < target) {
            openProgress = Math.min(target, openProgress + speed);
        } else if (openProgress > target) {
            openProgress = Math.max(target, openProgress - speed);
        }
    }

    public float rawOpenProgress() {
        return openProgress;
    }

    private Vec3 front() {
        Direction facing = getBlockState().hasProperty(DeviceBlock.FACING)
                ? getBlockState().getValue(DeviceBlock.FACING) : Direction.NORTH;
        return Vec3.atCenterOf(worldPosition).add(facing.getStepX() * 0.7, 0.15, facing.getStepZ() * 0.7);
    }

    public void changed() {
        setChanged();
    }

    public void sync() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        // 26.3 schema upgrade: legacy gate-mode names, missing defaults and the
        // old single-string code list are migrated once, then stamped. Idempotent.
        DeviceSchema.migrate(tag);
        port = tag.contains("port") ? tag.getString("port") : port;
        country = tag.contains("country") ? tag.getString("country") : country;
        officer = tag.contains("officer") ? tag.getString("officer") : officer;
        airportCountry = tag.getString("airportCountry");
        dualGate = tag.getCompound("dualGate").copy();
        gateMode = normalizeGateMode(tag.getString("gateMode"));
        loadGateCodes(tag.getList("gateCodes", Tag.TAG_STRING));
        loadCodes(exitCodes, tag.getList("exitCodes", Tag.TAG_STRING));
        loadCodes(entryCountryCodes, tag.getList("entryCountryCodes", Tag.TAG_STRING));
        loadCodes(exitCountryCodes, tag.getList("exitCountryCodes", Tag.TAG_STRING));
        autoStamp = !tag.contains("autoStamp") || tag.getBoolean("autoStamp");
        overstayDays = clampOverstay(tag.contains("overstayDays")
                ? tag.getInt("overstayDays") : DEFAULT_OVERSTAY_DAYS);
        requiredVisaType = normalizeVisaType(tag.getString("requiredVisaType"));
        requiredEndorsementType = normalizeEndorsementType(tag.getString("requiredEndorsementType"));
        applyLocks(tag);
        String loadedIssued = tag.getString("issuedCode").trim();
        issuedCode = CredentialCode.isValid(loadedIssued) ? loadedIssued : "";
        lockEndorsementCode = tag.getBoolean("lockEndorsementCode");
        String loadedEndorsementCode = tag.getString("lockedEndorsementCode").trim();
        lockedEndorsementCode = CredentialCode.isValid(loadedEndorsementCode) ? loadedEndorsementCode : "";
        applyConfigTag(tag);
        if (tag.contains("presets", Tag.TAG_LIST)) {
            presets.clear();
            ListTag loaded = tag.getList("presets", Tag.TAG_COMPOUND);
            for (int i = 0; i < loaded.size(); i++) {
                presets.add(loaded.getCompound(i).copy());
            }
        }
        gateState = GateState.IDLE;
        stateTicks = 0;
        openProgress = 0.0F;
    }

    private void loadGateCodes(ListTag loaded) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < loaded.size(); i++) {
            values.add(loaded.getString(i));
        }
        List<String> normalized = CredentialCode.normalizeAll(values);
        gateCodes.clear();
        for (String code : normalized) {
            gateCodes.add(StringTag.valueOf(code));
        }
    }

    /** Loads one normalised code list into an existing list tag. */
    private static void loadCodes(ListTag target, ListTag loaded) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < loaded.size(); i++) {
            values.add(loaded.getString(i));
        }
        writeCodes(target, values);
    }

    /**
     * Applies the 26.2 issuer-policy keys carried by NBT ({@link #load}) and by the
     * block-entity sync packet ({@link #handleUpdateTag}). Absent keys keep the
     * historical defaults ({@code lockNumber} and {@code issueEntryState} on), so
     * a pre-26.2 device upgrades without changing behaviour. The {@code configTag}
     * written by this class is the authoritative payload of the brush GUI.
     */
    public void applyConfigTag(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        lockNumber = !tag.contains("lockNumber") || tag.getBoolean("lockNumber");
        // 26.2 nationality lock. Absent keys keep the historical default (off and
        // blank), so a device saved by an older build upgrades without a lock.
        lockNationality = tag.contains("lockNationality") && tag.getBoolean("lockNationality");
        lockedNationality = normalizeNationality(tag.getString("lockedNationality"));
        numberRegion = normalizeRegion(tag.getString("numberRegion"));
        numberSequence = normalizeSequence(tag.getString("numberSequence"));
        mrzCountry = normalizeMrzCountry(tag.getString("mrzCountry"));
        issuerCountry = MrzUtil.countryCode(tag.getString("issuerCountry"));
        issueEntryState = !tag.contains("issueEntryState") || tag.getBoolean("issueEntryState");
        loadIssueCodes(tag.getList("issueCodes", Tag.TAG_STRING));
    }

    private void loadIssueCodes(ListTag loaded) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < loaded.size(); i++) {
            values.add(loaded.getString(i));
        }
        List<String> normalized = CredentialCode.normalizeAll(values);
        issueCodes.clear();
        for (String code : normalized) {
            issueCodes.add(StringTag.valueOf(code));
        }
    }

    /** CN_ID region: digits only, at most six. Blank is meaningful (layout default). */
    private static String normalizeRegion(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        return digits.length() > 6 ? digits.substring(0, 6) : digits;
    }

    /** CN_ID sequence: digits only, at most two (the last two win, like the generator). */
    private static String normalizeSequence(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        return digits.length() > 2 ? digits.substring(digits.length() - 2) : digits;
    }

    /** MRZ country: exactly three letters, upper-cased; anything else means "layout default". */
    private static String normalizeMrzCountry(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        return cleaned.length() == 3 ? cleaned : "";
    }

    /**
     * Locked nationality: trimmed (matching the {@link Locks} normalization) and
     * capped at {@link #MAX_NATIONALITY_LENGTH} characters so an oversized config
     * payload can never grow the block-entity NBT without bound.
     */
    private static String normalizeNationality(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        return cleaned.length() > MAX_NATIONALITY_LENGTH
                ? cleaned.substring(0, MAX_NATIONALITY_LENGTH) : cleaned;
    }

    private void applyLocks(CompoundTag tag) {
        Locks loaded = Locks.fromTag(tag);
        lockDocumentName = loaded.lockDocumentName();
        lockedDocumentName = loaded.lockedDocumentName();
        lockAuthority = loaded.lockAuthority();
        lockedAuthority = loaded.lockedAuthority();
        lockStyle = loaded.lockStyle();
        lockedStyle = normalizeLockedStyle(loaded.lockedStyle());
        lockPlace = loaded.lockPlace();
        lockedPlace = loaded.lockedPlace();
        lockIssueDate = loaded.lockIssueDate();
        lockedIssueDate = loaded.lockedIssueDate();
        lockValidity = loaded.lockValidity();
        lockedValidityMonths = loaded.lockedValidityMonths();
        lockVisaType = loaded.lockVisaType();
        lockedVisaType = loaded.lockedVisaType();
        lockVisaCode = loaded.lockVisaCode();
        lockedVisaCode = loaded.lockedVisaCode();
        lockEndorsementType = loaded.lockEndorsementType();
        lockedEndorsementType = loaded.lockedEndorsementType();
        lockIncludeVisa = loaded.lockIncludeVisa();
        lockedIncludeVisa = loaded.lockedIncludeVisa();
        lockVisaEntries = loaded.lockVisaEntries();
        lockedVisaEntries = loaded.lockedVisaEntries();
        lockVisaCountry = loaded.lockVisaCountry();
        lockedVisaCountry = loaded.lockedVisaCountry();
        lockVisaIssueDate = loaded.lockVisaIssueDate();
        lockedVisaIssueDate = loaded.lockedVisaIssueDate();
        lockVisaValidity = loaded.lockVisaValidity();
        lockedVisaValidityMonths = loaded.lockedVisaValidityMonths();
        lockVisaName = loaded.lockVisaName();
        lockedVisaName = loaded.lockedVisaName();
        lockedStyleTemplate = lockedStyle.isEmpty()
                ? "" : normalizeLockedStyleTemplate(tag.getString("lockedStyleTemplate"));
    }

    /** Writes the 26.2 issuer policy to NBT, the sync tag and the config payload. */
    private void writeIssuerPolicy(CompoundTag tag) {
        tag.putBoolean("lockNumber", lockNumber);
        tag.putBoolean("lockNationality", lockNationality);
        tag.putString("lockedNationality", lockedNationality);
        // 26.4: registry template behind the locked layout (empty for a plain one).
        tag.putString("lockedStyleTemplate", lockedStyleTemplate);
        tag.putString("numberRegion", numberRegion);
        tag.putString("numberSequence", numberSequence);
        tag.putString("mrzCountry", mrzCountry);
        tag.putString("issuerCountry", issuerCountry);
        tag.putBoolean("issueEntryState", issueEntryState);
        tag.put("issueCodes", issueCodesTag());
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(DeviceSchema.SCHEMA_KEY, DeviceSchema.CURRENT);
        tag.putString("port", port);
        tag.putString("country", country);
        tag.putString("officer", officer);
        tag.putString("airportCountry", airportCountry);
        tag.put("dualGate", dualGate.copy());
        tag.putString("gateMode", gateMode);
        tag.put("gateCodes", gateCodesTag());
        tag.put("exitCodes", exitCodesTag());
        tag.put("entryCountryCodes", entryCountryCodesTag());
        tag.put("exitCountryCodes", exitCountryCodesTag());
        tag.putBoolean("autoStamp", autoStamp);
        tag.putInt("overstayDays", overstayDays);
        tag.putString("requiredVisaType", requiredVisaType);
        tag.putString("requiredEndorsementType", requiredEndorsementType);
        locks().writeTo(tag);
        tag.putString("issuedCode", issuedCode);
        tag.putBoolean("lockEndorsementCode", lockEndorsementCode);
        tag.putString("lockedEndorsementCode", lockedEndorsementCode);
        writeIssuerPolicy(tag);
        tag.put("presets", presets.copy());
        tag.putString("gateState", GateState.IDLE.name());
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("port", port);
        tag.putString("country", country);
        tag.putString("officer", officer);
        tag.putString("airportCountry", airportCountry);
        tag.put("dualGate", dualGate.copy());
        tag.putString("gateMode", gateMode);
        tag.put("gateCodes", gateCodesTag());
        tag.put("exitCodes", exitCodesTag());
        tag.put("entryCountryCodes", entryCountryCodesTag());
        tag.put("exitCountryCodes", exitCountryCodesTag());
        tag.putBoolean("autoStamp", autoStamp);
        tag.putInt("overstayDays", overstayDays);
        tag.putString("requiredVisaType", requiredVisaType);
        tag.putString("requiredEndorsementType", requiredEndorsementType);
        locks().writeTo(tag);
        tag.putString("issuedCode", issuedCode);
        tag.putBoolean("lockEndorsementCode", lockEndorsementCode);
        tag.putString("lockedEndorsementCode", lockedEndorsementCode);
        writeIssuerPolicy(tag);
        tag.putString("gateState", gateState.name());
        tag.putFloat("openProgress", openProgress);
        tag.putString("gateMessage", gateMessage);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        port = tag.contains("port") ? tag.getString("port") : port;
        country = tag.contains("country") ? tag.getString("country") : country;
        officer = tag.contains("officer") ? tag.getString("officer") : officer;
        airportCountry = tag.getString("airportCountry");
        dualGate = tag.getCompound("dualGate").copy();
        gateMode = normalizeGateMode(tag.getString("gateMode"));
        loadGateCodes(tag.getList("gateCodes", Tag.TAG_STRING));
        loadCodes(exitCodes, tag.getList("exitCodes", Tag.TAG_STRING));
        loadCodes(entryCountryCodes, tag.getList("entryCountryCodes", Tag.TAG_STRING));
        loadCodes(exitCountryCodes, tag.getList("exitCountryCodes", Tag.TAG_STRING));
        autoStamp = !tag.contains("autoStamp") || tag.getBoolean("autoStamp");
        overstayDays = clampOverstay(tag.contains("overstayDays")
                ? tag.getInt("overstayDays") : DEFAULT_OVERSTAY_DAYS);
        requiredVisaType = normalizeVisaType(tag.getString("requiredVisaType"));
        requiredEndorsementType = normalizeEndorsementType(tag.getString("requiredEndorsementType"));
        applyLocks(tag);
        String loadedIssued = tag.getString("issuedCode").trim();
        issuedCode = CredentialCode.isValid(loadedIssued) ? loadedIssued : "";
        lockEndorsementCode = tag.getBoolean("lockEndorsementCode");
        String loadedEndorsementCode = tag.getString("lockedEndorsementCode").trim();
        lockedEndorsementCode = CredentialCode.isValid(loadedEndorsementCode) ? loadedEndorsementCode : "";
        applyConfigTag(tag);
        try {
            gateState = GateState.valueOf(tag.getString("gateState"));
        } catch (IllegalArgumentException exception) {
            gateState = GateState.IDLE;
        }
        openProgress = tag.getFloat("openProgress");
        gateMessage = tag.getString("gateMessage");
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public CompoundTag configTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("port", port);
        tag.putString("country", country);
        tag.putString("officer", officer);
        tag.putString("airportCountry", airportCountry);
        tag.put("dualGate", dualGate.copy());
        tag.putString("gateMode", gateMode);
        tag.put("gateCodes", gateCodesTag());
        tag.put("exitCodes", exitCodesTag());
        tag.put("entryCountryCodes", entryCountryCodesTag());
        tag.put("exitCountryCodes", exitCountryCodesTag());
        tag.putBoolean("autoStamp", autoStamp);
        tag.putInt("overstayDays", overstayDays);
        tag.putString("requiredVisaType", requiredVisaType);
        tag.putString("requiredEndorsementType", requiredEndorsementType);
        locks().writeTo(tag);
        tag.putString("issuedCode", issuedCode);
        tag.putBoolean("lockEndorsementCode", lockEndorsementCode);
        tag.putString("lockedEndorsementCode", lockedEndorsementCode);
        writeIssuerPolicy(tag);
        tag.put("presets", presets());
        tag.putString("gateState", gateState.name());
        tag.putString("gateMessage", gateMessage);
        return tag;
    }
}
