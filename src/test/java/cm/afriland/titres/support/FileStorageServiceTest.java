package cm.afriland.titres.support;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cm.afriland.titres.config.AppProperties;
import cm.afriland.titres.error.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stockage de fichiers : ecriture sous la racine, empreinte SHA-256,
 * anti-path-traversal, relecture.
 */
class FileStorageServiceTest {

    @TempDir
    Path tmp;

    private FileStorageService storage;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setUploadDir(tmp.toString());
        storage = new FileStorageService(props);
    }

    @AfterEach
    void tearDown() {
        // TempDir est nettoye automatiquement par JUnit.
    }

    @Test
    void store_ecrit_le_fichier_et_renvoie_chemin_relatif_et_sha256() {
        byte[] data = "contenu-image".getBytes();
        FileStorageService.Stored s = storage.store("faces", "jpg", data);

        assertThat(s.relativePath()).startsWith("faces/").endsWith(".jpg");
        assertThat(s.size()).isEqualTo(data.length);
        assertThat(s.sha256()).hasSize(64); // hex SHA-256
        assertThat(Files.exists(tmp.resolve(s.relativePath()))).isTrue();
    }

    @Test
    void deux_fichiers_ont_des_noms_distincts() {
        FileStorageService.Stored a = storage.store("faces", "jpg", "a".getBytes());
        FileStorageService.Stored b = storage.store("faces", "jpg", "b".getBytes());
        assertThat(a.relativePath()).isNotEqualTo(b.relativePath());
    }

    @Test
    void read_relit_le_contenu_stocke() {
        byte[] data = "roundtrip".getBytes();
        FileStorageService.Stored s = storage.store("justificatifs", "pdf", data);
        assertThat(storage.read(s.relativePath())).isEqualTo(data);
    }

    @Test
    void store_refuse_un_contenu_vide() {
        assertThatThrownBy(() -> storage.store("faces", "jpg", new byte[0]))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void store_refuse_un_sous_dossier_ou_une_extension_hostile() {
        byte[] data = "x".getBytes();
        assertThatThrownBy(() -> storage.store("../evil", "jpg", data))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.store("faces", "../sh", data))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.store("faces", "j/g", data))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void read_refuse_un_chemin_hors_racine() {
        assertThatThrownBy(() -> storage.read("../../etc/passwd"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void read_d_un_fichier_absent_renvoie_not_found() {
        assertThatThrownBy(() -> storage.read("faces/2026-01-01/inexistant.jpg"))
                .isInstanceOf(ApiException.class);
    }
}
