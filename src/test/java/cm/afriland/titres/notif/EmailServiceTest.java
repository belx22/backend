package cm.afriland.titres.notif;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock MailSettingsService settings;
    @InjectMocks EmailService email;

    // ─── isConfigured ────────────────────────────────────────────────────────

    @Test
    void isConfigured_delegue_a_usable() {
        when(settings.usable()).thenReturn(true);
        assertTrue(email.isConfigured());
        when(settings.usable()).thenReturn(false);
        assertFalse(email.isConfigured());
    }

    // ─── send : cas triviaux et simulation ──────────────────────────────────

    @Test
    void send_liste_vide_retourne_SENT() {
        assertEquals(EmailService.Status.SENT, email.send(List.of(), "sujet", "corps"));
        assertEquals(EmailService.Status.SENT, email.send(null, "sujet", "corps"));
    }

    @Test
    void send_messagerie_non_configuree_SIMULATED() {
        when(settings.usable()).thenReturn(false);
        assertEquals(EmailService.Status.SIMULATED,
                email.send(List.of("a@b.cm"), "sujet", "corps"));
    }

    @Test
    void sendOne_non_configuree_SIMULATED() {
        when(settings.usable()).thenReturn(false);
        assertEquals(EmailService.Status.SIMULATED, email.sendOne("a@b.cm", "s", "c"));
    }

    // ─── send : messagerie « activee » mais SMTP injoignable → FAILED ────────

    @Test
    void send_configuree_smtp_injoignable_FAILED() {
        when(settings.usable()).thenReturn(true);
        when(settings.get()).thenReturn(new MailSettingsService.MailSettings(
                "smtp.invalid.test", 2525, "user", "pass",
                "from@test.cm", "Exp", true, true, true, null, null));
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("smtp.invalid.test");
        sender.setPort(2525);
        when(settings.buildSender()).thenReturn(sender);
        when(settings.brand(anyString())).thenAnswer(i -> i.getArgument(0));

        EmailService.Status st = email.send(List.of("dest@test.cm"), "Sujet", "<p>Corps</p>");
        assertEquals(EmailService.Status.FAILED, st);
    }

    // ─── dispatch (non bloquant) : simulation instantanee ────────────────────

    @Test
    void dispatch_liste_vide_ne_fait_rien() {
        assertDoesNotThrow(() -> email.dispatch(List.of(), "s", "c"));
        assertDoesNotThrow(() -> email.dispatch(null, "s", "c"));
    }

    @Test
    void dispatch_non_configuree_simulation_synchrone() {
        when(settings.usable()).thenReturn(false);
        assertDoesNotThrow(() -> email.dispatch(List.of("a@b.cm"), "s", "c"));
    }

    @Test
    void dispatchOne_non_configuree_simulation() {
        when(settings.usable()).thenReturn(false);
        assertDoesNotThrow(() -> email.dispatchOne("a@b.cm", "s", "c"));
    }
}
