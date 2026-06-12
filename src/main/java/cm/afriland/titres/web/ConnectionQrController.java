package cm.afriland.titres.web;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

import javax.imageio.ImageIO;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import cm.afriland.titres.config.AppProperties;
import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.security.AuthUser;

/**
 * Partage du lien de connexion a la plateforme via un QR code.
 *
 * <p>Le QR code encode l'URL publique de la page de connexion ({@code /auth/login}),
 * derivee de l'origine du frontend ({@code app.frontend-origin}). Un acteur
 * back-office peut ainsi afficher / imprimer le code pour qu'un client rejoigne
 * rapidement la plateforme depuis son telephone.</p>
 *
 * <p>Acces reserve aux acteurs internes (AGENT, SUPERVISEUR, ADMIN). Les clients
 * n'ont aucune raison de generer ce code.</p>
 */
@RestController
@RequestMapping("/api/v1/connection-qr")
public class ConnectionQrController {

    /** Taille (en pixels) de l'image QR generee — cote du carre. */
    private static final int QR_SIZE = 320;

    private final AppProperties props;

    public ConnectionQrController(AppProperties props) {
        this.props = props;
    }

    /** Reponse : lien de connexion + image QR (PNG encodee en data URL base64). */
    public record ConnectionQrResponse(String url, String qrCodeDataUrl) {
    }

    /**
     * {@code GET /connection-qr} — renvoie le lien de connexion et son QR code.
     * Reserve au back-office (AGENT, SUPERVISEUR, ADMIN).
     */
    @GetMapping
    public ConnectionQrResponse generate(AuthUser user) {
        ApiException.ensure(user.isStaff(),
                "Generation du QR code reservee au back-office.");

        String url = loginUrl();
        String dataUrl = "data:image/png;base64," + encodeQr(url);
        return new ConnectionQrResponse(url, dataUrl);
    }

    /** Construit l'URL publique de la page de connexion (origine principale). */
    private String loginUrl() {
        return props.getPrimaryFrontendOrigin() + "/auth/login";
    }

    /** Encode le texte en QR code PNG et renvoie sa representation base64. */
    private static String encodeQr(String text) {
        try {
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, "UTF-8",
                    EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter()
                    .encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(MatrixToImageWriter.toBufferedImage(matrix), "PNG", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "QR_GENERATION_FAILED", "Generation du QR code impossible.");
        }
    }
}
