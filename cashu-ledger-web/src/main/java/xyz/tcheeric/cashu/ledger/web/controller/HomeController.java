package xyz.tcheeric.cashu.ledger.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import xyz.tcheeric.cashu.ledger.web.config.WebLedgerProperties;

/**
 * Serves the single-page inspection UI and supplies it with runtime configuration
 * so the page reflects the relay it is actually reading from rather than a build-time default.
 */
@Controller
public class HomeController {

    private final WebLedgerProperties properties;

    public HomeController(WebLedgerProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("relay", String.join(", ", properties.getRelays()));
        return "index";
    }
}
