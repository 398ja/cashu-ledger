package xyz.tcheeric.cashu.ledger.e2e.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import xyz.tcheeric.cashu.ledger.core.service.VoucherLedgerService;
import xyz.tcheeric.cashu.ledger.trace.core.NostrEventMetadata;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.PrivacyMode;
import xyz.tcheeric.cashu.ledger.trace.core.ProofRef;
import xyz.tcheeric.cashu.ledger.trace.core.StoredEvent;
import xyz.tcheeric.cashu.ledger.trace.core.TraceEventStore;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.web.CashuLedgerWebApplication;

/**
 * Headless-Chromium E2E for the trace visualisation UI (US4 T062, SC-009). Verifies the
 * graph console loads its vendored Cytoscape assets and that, without an operator signer,
 * the UI degrades safely: the auth banner warns that authentication is required and a render
 * attempt is rejected — no graph nodes and no secrets are shown to an unauthenticated user.
 *
 * <p>The authenticated positive path (secrets visible to an operator) requires an in-browser
 * NIP-07 signer and is covered at the REST layer by the trace security integration test.</p>
 */
@Tag("e2e")
@SpringBootTest(classes = CashuLedgerWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceVisualisationE2ETest {

    private static final String CHROME_BINARY = "/usr/bin/google-chrome";
    private static final String EVENT_ID = "evte2egraph0000000000000000000000000000000000000000000000000aa";

    @LocalServerPort
    private int port;

    @Autowired
    private TraceEventStore traceEventStore;

    @MockBean
    private VoucherLedgerService voucherLedgerService;

    private WebDriver driver;

    @BeforeEach
    void setUp() {
        assumeTrue(Files.exists(Path.of(CHROME_BINARY)), "Google Chrome not installed");
        traceEventStore.store(StoredEvent.of(mintEvent()));
        ChromeOptions options = new ChromeOptions();
        options.setBinary(CHROME_BINARY);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
                "--disable-gpu", "--window-size=1280,900");
        driver = new ChromeDriver(options);
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }

    /** The graph console loads its assets and refuses to render for an unauthenticated user. */
    @Test
    void shouldLoadGraphConsoleAndGateUnauthenticatedRender() {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
        driver.get("http://localhost:" + port + "/");
        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("localStorage.setItem('cashu-ledger-api-base','/api/v1');");
        driver.navigate().refresh();

        // Open the Graph section
        wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("button.nav-btn[data-target='trace']"))).click();

        // The vendored Cytoscape library loaded
        Object cytoscapeType = js.executeScript("return typeof window.cytoscape;");
        assertThat(cytoscapeType).isEqualTo("function");

        // No NIP-07 signer in headless Chrome -> the banner warns auth is required
        wait.until(ExpectedConditions.textToBePresentInElementLocated(
                By.id("trace-auth-banner"), "authentication"));
        String banner = driver.findElement(By.id("trace-auth-banner")).getText();
        assertThat(banner).contains("Operator authentication is required");

        // Attempting a render is rejected -> no graph, no secrets shown
        driver.findElement(By.id("trace-anchor-id")).sendKeys(EVENT_ID);
        driver.findElement(By.id("trace-render-btn")).click();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(
                By.id("trace-detail"), "Unauthorised"));
        assertThat(driver.findElement(By.id("trace-detail")).getText()).contains("Unauthorised");
    }

    private static TransactionEvent mintEvent() {
        ProofRef out = new ProofRef(64, "00ad12ef", "02" + "a1".repeat(32),
                Optional.of("secret-e2e"), Optional.of("0288a1"), Optional.empty(), Optional.empty());
        return new TransactionEvent(
                Optional.of(EVENT_ID), "op-e2e-1", OperationKind.MINT, "https://mint.imani.casa", "sat",
                Instant.ofEpochMilli(1740000003000L), Instant.ofEpochSecond(1740000003L), "producerpk",
                Optional.empty(), List.of(), List.of(out), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(0L), Optional.empty(), Optional.empty(), Optional.empty(),
                PrivacyMode.FULL, Optional.empty(), Optional.empty(), 1,
                new NostrEventMetadata(Optional.of(EVENT_ID), 9079, Optional.empty(),
                        Optional.empty(), Instant.ofEpochSecond(1740000003L)));
    }
}
