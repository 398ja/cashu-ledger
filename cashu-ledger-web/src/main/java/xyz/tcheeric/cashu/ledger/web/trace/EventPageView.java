package xyz.tcheeric.cashu.ledger.web.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One page of a cursor-paginated event listing. {@code cursor} is null at end of
 * stream (design §5.5).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventPageView(List<EventView> events, String cursor) {
}
