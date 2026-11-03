package com.veriborder.block;

import java.util.Arrays;
import java.util.List;

public enum DeviceType {
    ID_ISSUER("id_issuer", Category.ISSUER, ScreenKind.ISSUER_ID, 0x4EA1D3),
    PASSPORT_ISSUER("passport_issuer", Category.ISSUER, ScreenKind.ISSUER_PASSPORT, 0x4EA1D3),
    PERMIT_ISSUER("permit_issuer", Category.ISSUER, ScreenKind.ISSUER_PERMIT, 0x4EA1D3),
    VISA_ISSUER("visa_issuer", Category.ISSUER, ScreenKind.VISA_ISSUER, 0x7C6AF2),
    SELF_VISA("self_visa", Category.ISSUER, ScreenKind.VISA_ISSUER, 0x3E8FC4),
    ENDORSEMENT_MACHINE("endorsement_machine", Category.ISSUER, ScreenKind.ENDORSEMENT, 0x7C6AF2),
    SELF_ENDORSEMENT("self_endorsement", Category.ISSUER, ScreenKind.ENDORSEMENT, 0x9B6AF2),

    HOTEL_READER("hotel_reader", Category.READER, ScreenKind.READER, 0x2FC6B7),
    DESK_SCANNER("desk_scanner", Category.READER, ScreenKind.READER, 0x2FC6B7),
    MANUAL_TERMINAL("manual_terminal", Category.READER, ScreenKind.READER, 0x2FC6B7),
    AIRPORT_CHECKIN("airport_checkin", Category.READER, ScreenKind.READER, 0x2FC6B7),
    STATION_VERIFIER("station_verifier", Category.READER, ScreenKind.READER, 0x2FC6B7),
    EGATE("egate", Category.GATE, ScreenKind.EGATE, 0x2FC6B7),
    EXIT_GATE("exit_gate", Category.GATE, ScreenKind.EGATE, 0xE0663A),
    /** One-stop dual-clearance gate (一地两检): exit stage then entry stage. */
    DUAL_GATE("dual_gate", Category.GATE, ScreenKind.EGATE, 0x1F5E3A),

    ENTRY_STAMP("entry_stamp", Category.STAMP, ScreenKind.STAMP_CONFIG, 0xE0A84A),
    EXIT_STAMP("exit_stamp", Category.STAMP, ScreenKind.STAMP_CONFIG, 0xE0A84A),
    STAMP_HISTORY("stamp_history", Category.STAMP, ScreenKind.STAMP_HISTORY, 0xE0A84A),

    LOSS_TERMINAL("loss_terminal", Category.SERVICE, ScreenKind.LOSS, 0xF05A5A),
    RENEWAL_MACHINE("renewal_machine", Category.SERVICE, ScreenKind.RENEWAL, 0xF05A5A),
    PRINT_PREVIEW("print_preview", Category.SERVICE, ScreenKind.PRINT, 0xF05A5A),
    BLACKLIST_TERMINAL("blacklist_terminal", Category.SERVICE, ScreenKind.BLACKLIST, 0xF05A5A);

    public enum Category {
        ISSUER, READER, STAMP, SERVICE, GATE
    }

    public enum ScreenKind {
        ISSUER_ID, ISSUER_PASSPORT, ISSUER_PERMIT, VISA_ISSUER, ENDORSEMENT,
        READER, STAMP_CONFIG, STAMP_HISTORY, LOSS, RENEWAL, PRINT, BLACKLIST, EGATE
    }

    private final String id;
    private final Category category;
    private final ScreenKind screen;
    private final int accentColor;

    DeviceType(String id, Category category, ScreenKind screen, int accentColor) {
        this.id = id;
        this.category = category;
        this.screen = screen;
        this.accentColor = accentColor;
    }

    public String id() {
        return id;
    }

    public Category category() {
        return category;
    }

    public ScreenKind screen() {
        return screen;
    }

    public int accentColor() {
        return accentColor;
    }

    public String translationKey() {
        return "block.veriborder." + id;
    }

    /**
     * 26.2.2: what the machine's location field is <em>called</em> on this device.
     *
     * <p>The field used to be labelled "port / checkpoint" everywhere, which reads
     * wrong on an issuing machine (it prints the place of issue, not a checkpoint).
     * Each device category now names the field the way a real terminal would.</p>
     */
    public String placeLabelKey() {
        return switch (this) {
            case ID_ISSUER, PASSPORT_ISSUER, PERMIT_ISSUER -> "field.veriborder.issue_place";
            case VISA_ISSUER, SELF_VISA, ENDORSEMENT_MACHINE, SELF_ENDORSEMENT ->
                    "field.veriborder.issuing_place";
            case AIRPORT_CHECKIN -> "field.veriborder.airport";
            case STATION_VERIFIER -> "field.veriborder.station";
            case HOTEL_READER, DESK_SCANNER, MANUAL_TERMINAL -> "field.veriborder.location";
            case LOSS_TERMINAL, RENEWAL_MACHINE, PRINT_PREVIEW, BLACKLIST_TERMINAL,
                    STAMP_HISTORY -> "field.veriborder.office";
            case EGATE, EXIT_GATE, DUAL_GATE, ENTRY_STAMP, EXIT_STAMP ->
                    "field.veriborder.port";
        };
    }

    /**
     * 26.2.2: the location a freshly placed machine starts with.
     *
     * <p>Every machine used to default to "Beijing Capital Airport T3", including
     * passport issuers and station verifiers. The default now follows the device,
     * so a terminal opens on a plausible place for its own role.</p>
     */
    public String defaultPlace() {
        return switch (this) {
            case ID_ISSUER, PASSPORT_ISSUER, PERMIT_ISSUER -> "中国·北京";
            case VISA_ISSUER, SELF_VISA, ENDORSEMENT_MACHINE, SELF_ENDORSEMENT -> "中国·北京";
            case AIRPORT_CHECKIN -> "北京首都国际机场 T3";
            case STATION_VERIFIER -> "轨道交通 大足站";
            case HOTEL_READER -> "酒店前台";
            case DESK_SCANNER, MANUAL_TERMINAL -> "边检柜台";
            case LOSS_TERMINAL, RENEWAL_MACHINE -> "证件服务中心";
            case PRINT_PREVIEW -> "证件打印室";
            case BLACKLIST_TERMINAL -> "口岸查验区";
            case STAMP_HISTORY -> "口岸验讫窗口";
            case EGATE, EXIT_GATE, DUAL_GATE, ENTRY_STAMP, EXIT_STAMP ->
                    "北京首都国际机场 T3";
        };
    }

    public String descriptionKey() {
        return "device.veriborder." + id + ".desc";
    }

    public boolean isIssuer() {
        return category == Category.ISSUER;
    }

    public boolean isReader() {
        return category == Category.READER;
    }

    public boolean isStamp() {
        return category == Category.STAMP;
    }

    public boolean isService() {
        return category == Category.SERVICE;
    }

    public boolean isGate() {
        return category == Category.GATE;
    }

    public static DeviceType byId(String id) {
        for (DeviceType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return ID_ISSUER;
    }

    public static List<DeviceType> all() {
        return Arrays.asList(values());
    }
}
