package xyz.tcheeric.cashu.ledger.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import xyz.tcheeric.cashu.ledger.web.config.WebLedgerProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for HomeController, verifying the inspection UI receives the relay it is
 * actually configured to read from rather than a build-time default.
 */
class HomeControllerTest {

    /**
     * Tests that the configured relay is exposed to the view so the page reflects runtime configuration.
     */
    @Test
    void shouldExposeConfiguredRelayToView() {
        // Arrange: properties configured with a single relay
        WebLedgerProperties properties = new WebLedgerProperties();
        properties.setRelays(List.of("wss://relay.staging.398ja.xyz"));
        HomeController controller = new HomeController(properties);
        Model model = new ConcurrentModel();

        // Act: render the home view
        String view = controller.index(model);

        // Then: the view name and relay attribute are correct
        assertThat(view).isEqualTo("index");
        assertThat(model.getAttribute("relay")).isEqualTo("wss://relay.staging.398ja.xyz");
    }

    /**
     * Tests that multiple configured relays are joined into a single readable attribute.
     */
    @Test
    void shouldJoinMultipleRelaysWithComma() {
        // Arrange: properties configured with two relays
        WebLedgerProperties properties = new WebLedgerProperties();
        properties.setRelays(List.of("wss://relay.one", "wss://relay.two"));
        HomeController controller = new HomeController(properties);
        Model model = new ConcurrentModel();

        // Act: render the home view
        controller.index(model);

        // Then: relays are comma-separated
        assertThat(model.getAttribute("relay")).isEqualTo("wss://relay.one, wss://relay.two");
    }
}
