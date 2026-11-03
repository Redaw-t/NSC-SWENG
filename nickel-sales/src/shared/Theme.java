package shared;

/**
 * The app's colour palette and font, from the design slide. Use these constants
 * instead of typing hex codes, so a colour change is made in one place.
 */
public final class Theme {

    private Theme() { }

    public static final String FONT_FAMILY = "Segoe UI";

    public static final String APP_BACKGROUND = "#F5F7FA";
    public static final String SURFACE = "#FFFFFF";          // cards, fields
    public static final String BORDER = "#D9DEE7";           // borders, dividers

    public static final String PRIMARY_BLUE = "#2457A6";
    public static final String SECONDARY_BLUE = "#173B70";
    public static final String ACCENT_RED = "#E45757";

    public static final String PRIMARY_TEXT = "#1F2937";
    public static final String SECONDARY_TEXT = "#6B7280";

    public static final String SUCCESS = "#2E8B57";
    public static final String WARNING = "#D99A24";
    public static final String ERROR = "#D64545";
    public static final String INFORMATION = "#3B82C4";
}
