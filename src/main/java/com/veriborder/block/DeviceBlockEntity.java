package com.veriborder.block;

import com.veriborder.doc.CredentialCode;
import com.veriborder.doc.DocStyle;
import com.veriborder.doc.EndorsementType;
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

    /** Gate modes: all documents, visa code, endorsement code, visa or endorsement code. */
    public static final String GATE_MODE_ALL = "all";
    public static final String GATE_MODE_VISA = "visa";
    public static final String GATE_MODE_ENDORSEMENT = "endorsement";
    public static final String GATE_MODE_BOTH = "both";

    /** Legacy pre-1.1.5 mode names; {@link #normalizeGateMode(String)} remaps them. */
    @Deprecated
    public static final String GATE_MODE_ANY = GATE_MODE_ALL;
    @Deprecated
    public static final String GATE_MODE_PASSPORT = GATE_MODE_VISA;
    @Deprecated
    public static final String GATE_MODE_PERMIT = GATE_MODE_ENDORSEMENT;

    /**
     * Persisted + synced issuer lock configuration. Locked values are enforced on
     * the server, so a client that ignores them still cannot issue a different
     * document name, authority, layout, place, date, validity, visa type/code,
     * endorsement type or passport-attached visa (include/entries/country/issue/
     * validity/name).
     */
    public record Locks(boolean lockDocumentName, String lockedDocumentName,
                        boolean lockAuthority, String lockedAuthority,
                        boolean lockStyle, String lockedStyle,
                        boolean lockPlace, String lockedPlace,
                        boolean lockIssueDate, String lockedIssueDate,
                        boolean lockValidity, int lockedValidityYears,
                        boolean lockVisaType, String lockedVisaType,
                        boolean lockVisaCode, String lockedVisaCode,
                        boolean lockEndorsementType, String lockedEndorsementType,
                        boolean lockIncludeVisa, boolean lockedIncludeVisa,
                        boolean lockVisaEntries, int lockedVisaEntries,
                        boolean lockVisaCountry, String lockedVisaCountry,
                        boolean lockVisaIssueDate, String lockedVisaIssueDate,
                        boolean lockVisaValidity, int lockedVisaValidityYears,
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
                     boolean lockValidity, int lockedValidityYears,
                     boolean lockVisaType, String lockedVisaType,
                     boolean lockVisaCode, String lockedVisaCode,
                     boolean lockEndorsementType, String lockedEndorsementType) {
            this(lockDocumentName, lockedDocumentName, lockAuthority, lockedAuthority,
                    lockStyle, lockedStyle, lockPlace, lockedPlace,
                    lockIssueDate, lockedIssueDate, lockValidity, lockedValidityYears,
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
                    tag.getInt("lockedValidityYears"),
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
                    tag.getInt("lockedVisaValidityYears"),
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
            tag.putInt("lockedValidityYears", lockedValidityYears);
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
            tag.putInt("lockedVisaValidityYears", lockedVisaValidityYears);
            tag.putBoolean("lockVisaName", lockVisaName);
            tag.putString("lockedVisaName", lockedVisaName);
            return tag;
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    private String port = "北京首都国际机场 T3";
    private String country = "中国";
    private String officer = "边检警官";
    private String airportCountry = "";
    private final ListTag presets = new ListTag();

    /** Gate binding: "all" | "visa" | "endorsement" | "both". */
    private String gateMode = GATE_MODE_ALL;
    /** Eight-digit codes accepted by this gate (no limit, deduplicated, order kept). */
    private final ListTag gateCodes = new ListTag();
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
    /** Locked place of issue for documents / country for visas. */
    private boolean lockPlace;
    private String lockedPlace = "";
    /** Locked issue date (YYYY-MM-DD) for documents, visas and endorsements. */
    private boolean lockIssueDate;
    private String lockedIssueDate = "";
    /** Locked validity in years; 0 (or less) means permanent. */
    private boolean lockValidity;
    private int lockedValidityYears;
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
    /** Passport issuer: locked attached-visa validity in years; 0 means permanent. */
    private boolean lockVisaValidity;
    private int lockedVisaValidityYears;
    /** Passport/visa issuer: locked attached-visa display label. */
    private boolean lockVisaName;
    private String lockedVisaName = "";
    /** Fixed code used by a self-service visa/endorsement kiosk; blank = auto. */
    private String issuedCode = "";
    /** Permit issuer: lock the initial endorsement code instead of auto-generating it. */
    private boolean lockEndorsementCode;
    private String lockedEndorsementCode = "";

    /**
     * Gate behaviour: write the IN/OUT immigration stamp automatically when a
     * traveller passes (passport and HK/Macau permit only).
     */
    public static final int DEFAULT_OVERSTAY_DAYS = 30;
    /** Upper bound for {@link #overstayDays()} so the day maths never overflows. */
    public static final int MAX_OVERSTAY_DAYS = 3650;
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

    public boolean lockStyle() {
        return lockStyle;
    }

    public String lockedStyle() {
        return lockedStyle;
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

    public int lockedValidityYears() {
        return lockedValidityYears;
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

    public int lockedVisaValidityYears() {
        return lockedVisaValidityYears;
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
        String key = lockedStyle.trim();
        if (key.isEmpty()) {
            return null;
        }
        for (DocStyle candidate : DocStyle.values()) {
            if (candidate.id().equalsIgnoreCase(key)) {
                return candidate;
            }
        }
        return null;
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

    public void setLockedStyle(String value) {
        lockedStyle = value == null ? "" : value.trim();
        changed();
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

    public void setLockedValidityYears(int value) {
        lockedValidityYears = value;
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

    public void setLockedVisaValidityYears(int value) {
        lockedVisaValidityYears = value;
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

    /** The full lock configuration (also used for NBT round-trip tests). */
    public Locks locks() {
        return new Locks(lockDocumentName, lockedDocumentName, lockAuthority, lockedAuthority,
                lockStyle, lockedStyle, lockPlace, lockedPlace, lockIssueDate, lockedIssueDate,
                lockValidity, lockedValidityYears, lockVisaType, lockedVisaType,
                lockVisaCode, lockedVisaCode, lockEndorsementType, lockedEndorsementType,
                lockIncludeVisa, lockedIncludeVisa, lockVisaEntries, lockedVisaEntries,
                lockVisaCountry, lockedVisaCountry, lockVisaIssueDate, lockedVisaIssueDate,
                lockVisaValidity, lockedVisaValidityYears, lockVisaName, lockedVisaName);
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

    public static String normalizeGateMode(String value) {
        if (value == null) {
            return GATE_MODE_ALL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case GATE_MODE_VISA, "passport" -> GATE_MODE_VISA;
            case GATE_MODE_ENDORSEMENT, "permit" -> GATE_MODE_ENDORSEMENT;
            case GATE_MODE_BOTH -> GATE_MODE_BOTH;
            case GATE_MODE_ALL, "any" -> GATE_MODE_ALL;
            default -> GATE_MODE_ALL;
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
            value = Math.min(target, value + speed * partialTick);
        } else if (value > target) {
            value = Math.max(target, value - speed * partialTick);
        }
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
        level.playSound(null, worldPosition, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.45F, 1.6F);
        if (level instanceof ServerLevel serverLevel) {
            Vec3 front = front();
            serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK, front.x, front.y + 0.65, front.z,
                    10, 0.22, 0.35, 0.22, 0.015);
        }
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
        stateTicks = passed ? 110 : 70;
        gateMessage = passed ? "gate.veriborder.granted" : "gate.veriborder.denied";
        if (level instanceof ServerLevel serverLevel) {
            Vec3 front = front();
            if (passed) {
                serverLevel.playSound(null, worldPosition, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1.0F, 0.85F);
                serverLevel.playSound(null, worldPosition, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.8F, 1.35F);
                serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, front.x, front.y + 0.6, front.z,
                        22, 0.4, 0.5, 0.4, 0.02);
                serverLevel.sendParticles(ParticleTypes.END_ROD, front.x, front.y + 0.6, front.z,
                        14, 0.25, 0.4, 0.25, 0.03);
            } else {
                serverLevel.playSound(null, worldPosition, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.9F, 0.55F);
                serverLevel.playSound(null, worldPosition, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 0.8F, 0.8F);
                serverLevel.sendParticles(ParticleTypes.SMOKE, front.x, front.y + 0.6, front.z,
                        24, 0.35, 0.45, 0.35, 0.02);
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
        port = tag.contains("port") ? tag.getString("port") : port;
        country = tag.contains("country") ? tag.getString("country") : country;
        officer = tag.contains("officer") ? tag.getString("officer") : officer;
        airportCountry = tag.getString("airportCountry");
        gateMode = normalizeGateMode(tag.getString("gateMode"));
        loadGateCodes(tag.getList("gateCodes", Tag.TAG_STRING));
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

    private void applyLocks(CompoundTag tag) {
        Locks loaded = Locks.fromTag(tag);
        lockDocumentName = loaded.lockDocumentName();
        lockedDocumentName = loaded.lockedDocumentName();
        lockAuthority = loaded.lockAuthority();
        lockedAuthority = loaded.lockedAuthority();
        lockStyle = loaded.lockStyle();
        lockedStyle = loaded.lockedStyle();
        lockPlace = loaded.lockPlace();
        lockedPlace = loaded.lockedPlace();
        lockIssueDate = loaded.lockIssueDate();
        lockedIssueDate = loaded.lockedIssueDate();
        lockValidity = loaded.lockValidity();
        lockedValidityYears = loaded.lockedValidityYears();
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
        lockedVisaValidityYears = loaded.lockedVisaValidityYears();
        lockVisaName = loaded.lockVisaName();
        lockedVisaName = loaded.lockedVisaName();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("port", port);
        tag.putString("country", country);
        tag.putString("officer", officer);
        tag.putString("airportCountry", airportCountry);
        tag.putString("gateMode", gateMode);
        tag.put("gateCodes", gateCodesTag());
        tag.putBoolean("autoStamp", autoStamp);
        tag.putInt("overstayDays", overstayDays);
        tag.putString("requiredVisaType", requiredVisaType);
        tag.putString("requiredEndorsementType", requiredEndorsementType);
        locks().writeTo(tag);
        tag.putString("issuedCode", issuedCode);
        tag.putBoolean("lockEndorsementCode", lockEndorsementCode);
        tag.putString("lockedEndorsementCode", lockedEndorsementCode);
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
        tag.putString("gateMode", gateMode);
        tag.put("gateCodes", gateCodesTag());
        tag.putBoolean("autoStamp", autoStamp);
        tag.putInt("overstayDays", overstayDays);
        tag.putString("requiredVisaType", requiredVisaType);
        tag.putString("requiredEndorsementType", requiredEndorsementType);
        locks().writeTo(tag);
        tag.putString("issuedCode", issuedCode);
        tag.putBoolean("lockEndorsementCode", lockEndorsementCode);
        tag.putString("lockedEndorsementCode", lockedEndorsementCode);
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
        gateMode = normalizeGateMode(tag.getString("gateMode"));
        loadGateCodes(tag.getList("gateCodes", Tag.TAG_STRING));
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
        tag.putString("gateMode", gateMode);
        tag.put("gateCodes", gateCodesTag());
        tag.putBoolean("autoStamp", autoStamp);
        tag.putInt("overstayDays", overstayDays);
        tag.putString("requiredVisaType", requiredVisaType);
        tag.putString("requiredEndorsementType", requiredEndorsementType);
        locks().writeTo(tag);
        tag.putString("issuedCode", issuedCode);
        tag.putBoolean("lockEndorsementCode", lockEndorsementCode);
        tag.putString("lockedEndorsementCode", lockedEndorsementCode);
        tag.put("presets", presets());
        tag.putString("gateState", gateState.name());
        tag.putString("gateMessage", gateMessage);
        return tag;
    }
}
