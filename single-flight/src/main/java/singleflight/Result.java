package singleflight;

import lombok.Data;

/**
 * @author Freeman
 * @since 2025/7/19
 */
@Data
final class Result {
    /**
     * One of value or exception will be set.
     */
    private final Object value;
    /**
     * One of value or exception will be set.
     */
    private final Throwable exception;
}
