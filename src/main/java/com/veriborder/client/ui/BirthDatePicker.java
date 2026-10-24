package com.veriborder.client.ui;

import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The three-dropdown date-of-birth control shared by every form that used to
 * take the birthday as free text (26.2): issuer, document generator, document
 * modifier and the border terminal's document editor.
 *
 * <p>The control is a group of three {@link Dropdown}s laid out side by side -
 * year ({@value #YEAR_WIDTH}px), month ({@value #MONTH_WIDTH}px) and day
 * ({@value #DAY_WIDTH}px) - so the value can only be picked, never typed. The
 * screen registers the three dropdowns through {@code VeriScreen.addDropdown}
 * and reads back {@link #value()}, which is still the zero-padded
 * {@code YYYY-MM-DD} string stored under the existing {@code birthDate} key.</p>
 *
 * <p>Behaviour that lives here rather than in a screen:</p>
 * <ul>
 *   <li>the year list is the current year and the 119 before it, newest first;
 *       a stored year outside that window stays selectable so an old save is
 *       never silently rewritten;</li>
 *   <li>the day list follows the selected year and month (29 February only in a
 *       leap year);</li>
 *   <li>changing the year or the month clamps an out-of-range day to the last
 *       day of that month;</li>
 *   <li>the existing label key {@code field.veriborder.birth} is drawn once, above
 *       the year control, so no translation key had to be added.</li>
 * </ul>
 */
public final class BirthDatePicker {
    /** Existing translation key of the old text field; reused verbatim. */
    public static final String LABEL_KEY = "field.veriborder.birth";

    public static final int YEAR_WIDTH = 80;
    public static final int MONTH_WIDTH = 56;
    public static final int DAY_WIDTH = 56;
    public static final int GAP = 4;

    private final Dropdown year;
    private final Dropdown month;
    private final Dropdown day;

    @Nullable
    private final Consumer<BirthDateField> onChange;

    private BirthDateField field;

    private BirthDatePicker(Dropdown year, Dropdown month, Dropdown day, BirthDateField field,
                            @Nullable Consumer<BirthDateField> onChange) {
        this.year = year;
        this.month = month;
        this.day = day;
        this.field = field;
        this.onChange = onChange;
    }

    /**
     * Builds the standard 80/56/56 group from a stored {@code YYYY-MM-DD} value
     * (blank or invalid falls back to 1990-01-01, the form's historical default).
     */
    public static BirthDatePicker at(int x, int y, @Nullable String stored,
                                     @Nullable Consumer<BirthDateField> onChange) {
        return at(x, y, YEAR_WIDTH, MONTH_WIDTH, DAY_WIDTH, stored, onChange);
    }

    /**
     * Same control with explicit widths, for a form whose column is narrower
     * than the 200px default (the document modifier uses 150px).
     */
    public static BirthDatePicker at(int x, int y, int yearWidth, int monthWidth, int dayWidth,
                                     @Nullable String stored,
                                     @Nullable Consumer<BirthDateField> onChange) {
        BirthDateField initial = BirthDateField.parse(stored);
        int width = Math.max(Dropdown.MIN_WIDTH, yearWidth);
        int monthWidthUsed = Math.max(Dropdown.MIN_WIDTH, monthWidth);
        int dayWidthUsed = Math.max(Dropdown.MIN_WIDTH, dayWidth);
        int monthX = x + width + GAP;
        int dayX = monthX + monthWidthUsed + GAP;

        Dropdown year = Dropdown.at(x, y, width)
                .labelKey(LABEL_KEY)
                .options(yearChoices(LocalDate.now().getYear(), initial.year()))
                .selected(String.valueOf(initial.year()));
        Dropdown month = Dropdown.at(monthX, y, monthWidthUsed)
                .options(monthChoices())
                .selected(String.valueOf(initial.month()));
        Dropdown day = Dropdown.at(dayX, y, dayWidthUsed)
                .options(dayChoices(initial))
                .selected(String.valueOf(initial.day()));
        BirthDatePicker picker = new BirthDatePicker(year, month, day, initial, onChange);
        year.onChange(picker::pickYear);
        month.onChange(picker::pickMonth);
        day.onChange(picker::pickDay);
        return picker;
    }

    /** The three dropdowns, in visual order: year, month, day. */
    public List<Dropdown> dropdowns() {
        return List.of(year, month, day);
    }

    /** Registers all three controls with the screen that owns them. */
    public void register(Consumer<Dropdown> sink) {
        if (sink == null) {
            return;
        }
        for (Dropdown dropdown : dropdowns()) {
            sink.accept(dropdown);
        }
    }

    /** Current value; always a valid, zero-padded {@code YYYY-MM-DD} string. */
    public String value() {
        return field.value();
    }

    private void pickYear(String id) {
        update(field.withYear(parseInt(id, field.year())));
    }

    private void pickMonth(String id) {
        update(field.withMonth(parseInt(id, field.month())));
    }

    private void pickDay(String id) {
        update(field.withDay(parseInt(id, field.day())));
    }

    private void update(BirthDateField next) {
        this.field = next;
        refresh();
        if (onChange != null) {
            onChange.accept(next);
        }
    }

    /**
     * Re-selects the current value and rebuilds the day list, so a day that no
     * longer exists in the chosen month disappears from the panel.
     */
    private void refresh() {
        year.selected(String.valueOf(field.year()));
        month.selected(String.valueOf(field.month()));
        day.options(dayChoices(field)).selected(String.valueOf(field.day()));
    }

    private static int parseInt(@Nullable String id, int fallback) {
        if (id == null || id.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(id.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<Dropdown.Option> yearChoices(int currentYear, int keepYear) {
        List<Dropdown.Option> choices = new ArrayList<>(BirthDateField.YEAR_SPAN + 1);
        for (int value : BirthDateField.yearOptions(currentYear, keepYear)) {
            choices.add(new Dropdown.Option(String.valueOf(value), String.valueOf(value)));
        }
        return choices;
    }

    private static List<Dropdown.Option> monthChoices() {
        List<Dropdown.Option> choices = new ArrayList<>(BirthDateField.MAX_MONTH);
        for (int value : BirthDateField.monthOptions()) {
            choices.add(new Dropdown.Option(String.valueOf(value), BirthDateField.padded(value)));
        }
        return choices;
    }

    private static List<Dropdown.Option> dayChoices(BirthDateField field) {
        List<Dropdown.Option> choices = new ArrayList<>(BirthDateField.daysInMonth(field.year(), field.month()));
        for (int value : field.dayOptions()) {
            choices.add(new Dropdown.Option(String.valueOf(value), BirthDateField.padded(value)));
        }
        return choices;
    }
}
