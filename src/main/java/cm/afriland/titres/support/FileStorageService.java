package cm.afriland.titres.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Service;

import cm.afriland.titres.config.AppProperties;
import cm.afriland.titres.error.ApiException;

/**
 * Stockage des fichiers televerses (captures faciales, pieces justificatives)
 * dans un repertoire HORS racine web ({@code app.upload-dir}).
 *
 * <p>Les fichiers ne sont jamais servis en statique : le telechargement passe
 * par un endpoint authentifie (back-office). Le nom de stockage est aleatoire
 * (UUID) pour eviter toute collision et toute fuite d'information via le nom.</p>
 */
@Service
public class FileStorageService {

    /** Resultat d'un enregistrement : chemin relatif (stocke en BD) + empreinte. */
    public record Stored(String relativePath, String sha256, long size) {
    }

    private final Path root;

    public FileStorageService(AppProperties props) {
        this.root = Paths.get(props.getUploadDir()).toAbsolutePath().normalize();
    }

    /**
     * Ecrit {@code bytes} sous {@code <sous-dossier>/<AAAA-MM-JJ>/<uuid>.<ext>} et
     * renvoie le chemin RELATIF (a stocker en BD) + le SHA-256.
     *
     * @param subDir    sous-dossier logique (ex. « faces », « justificatifs »)
     * @param extension extension sans point (ex. « jpg », « pdf »)
     */
    public Stored store(String subDir, String extension, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("Fichier vide.");
        }
        String safeSub = sanitizeSegment(subDir);
        String safeExt = sanitizeSegment(extension);
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        String name = UUID.randomUUID() + "." + safeExt;
        Path relative = Paths.get(safeSub, day, name);
        Path target = root.resolve(relative).normalize();

        // Defense en profondeur : la cible resolue doit rester sous la racine.
        if (!target.startsWith(root)) {
            throw ApiException.badRequest("Chemin de stockage invalide.");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Echec d'ecriture du fichier televerse.", e);
        }
        return new Stored(relative.toString().replace('\\', '/'), sha256Hex(bytes), bytes.length);
    }

    /** Lit un fichier prealablement stocke, a partir de son chemin relatif. */
    public byte[] read(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw ApiException.badRequest("Chemin de fichier invalide.");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw ApiException.notFound("Fichier introuvable.");
        }
    }

    /** N'autorise qu'un segment de chemin simple (anti path-traversal). */
    private static String sanitizeSegment(String s) {
        if (s == null || !s.matches("[A-Za-z0-9_-]{1,32}")) {
            throw ApiException.badRequest("Segment de chemin invalide.");
        }
        return s;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible.", e);
        }
    }
}
