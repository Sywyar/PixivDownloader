package top.sywyar.pixivdownload.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.color.ANSIConstants;
import ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase;

public class MdcColorConverter extends ForegroundCompositeConverterBase<ILoggingEvent> {
    public static final String key = "MDCCOLORCONVERTER_KEY";

    @Override
    protected String getForegroundColorCode(ILoggingEvent event) {
        return switch (event.getLevel().levelInt) {
            case Level.INFO_INT -> ANSIConstants.GREEN_FG;
            case Level.WARN_INT -> ANSIConstants.YELLOW_FG;
            case Level.ERROR_INT -> ANSIConstants.RED_FG;
            case Level.DEBUG_INT -> ANSIConstants.CYAN_FG;
            case Level.TRACE_INT -> ANSIConstants.MAGENTA_FG;
            default -> ANSIConstants.DEFAULT_FG;
        };
    }
}
