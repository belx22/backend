package cm.afriland.titres.seed;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import cm.afriland.titres.config.AppProperties;
import cm.afriland.titres.security.PasswordService;

/**
 * Jeu de donnees des TESTS — ne fait pas partie de l'application livree.
 *
 * <p>Cette classe vit sous {@code src/test} : elle n'est jamais empaquetee, et la
 * plateforme demarre donc sur une base vierge (cf. {@code StartupRunner}, qui se
 * borne a charger la matrice RBAC et a creer le premier administrateur).</p>
 *
 * <p>Elle fournit aux tests fonctionnels les comptes dont ils ont besoin (agent,
 * superviseur, admin, clients), un compte-titres JOINT pour la double signature,
 * et deux emissions publiees. Elle n'agit que sur une base vide et lorsque
 * {@code app.seed-on-start} est explicitement active — ce que seuls les tests
 * font.</p>
 */
@Component
public class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);

    /** Mot de passe commun aux comptes de demonstration. */
    private static final String DEMO_PASSWORD = "Demo1234";

    private final JdbcTemplate jdbc;
    private final PasswordService password;
    private final AppProperties props;

    public DemoSeedRunner(JdbcTemplate jdbc, PasswordService password, AppProperties props) {
        this.jdbc = jdbc;
        this.password = password;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (props.isSeedOnStart()) {
            Long count = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
            if (count != null && count == 0) {
                log.info("Base vide — insertion des comptes de demonstration "
                        + "(aucune autre donnee metier inseree).");
                seedUsers();
                seedJointAccount();
                seedEmissions();
                // Le mot de passe de demonstration n'est PAS journalise (secret).
                log.info("Comptes de demonstration inseres (dont 1 compte-titres joint) "
                        + "+ 2 emissions PUBLIE de demo (1 BTA, 1 OTA).");
            } else {
                log.info("Base deja peuplee ({} utilisateur(s)) — insertion ignoree.", count);
            }
        }
    }

    /** Insere uniquement les 5 comptes de demonstration. */
    private void seedUsers() {
        String hash = password.hash(DEMO_PASSWORD);
        // Colonnes : email, role, nom, prenom, compte_titres, compte_especes,
        //            solde, categorie, type_compte, telephone.
        // Le telephone des clients alimente l'indice de destination de l'OTP
        // (ecran de verification) et la supervision back-office.
        Object[][] seed = {
                {"jean.mballa@example.cm", "CLIENT_PP", "MBALLA", "Jean Paul",
                        "037 10001 00012345678", "037 10001 12345678901", 45_250_000L,
                        "NON_QUALIFIE", "INDIVIDUEL", "+237 699 123 678"},
                {"alucam@example.cm", "CLIENT_PM", "ALUCAM SA", null,
                        "037 10001 00045678912", "037 10001 45678912345", 2_340_000_000L,
                        "QUALIFIE", "INDIVIDUEL", "+237 677 456 912"},
                {"admin@afriland.cm", "ADMIN", "NDOMA", "Sandrine",
                        null, null, null, null, null, null},
                {"superviseur@afriland.cm", "SUPERVISEUR", "FOTSO", "Marie",
                        null, null, null, null, null, null},
                {"agent@afriland.cm", "AGENT", "BIYA", "Paul",
                        null, null, null, null, null, null},
        };
        for (Object[] u : seed) {
            jdbc.update(
                    "INSERT INTO users (email, password_hash, role, nom, prenom, compte_titres, "
                            + "compte_especes, solde, categorie, type_compte, telephone) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    u[0], hash, u[1], u[2], u[3], u[4], u[5], u[6], u[7], u[8], u[9]);
        }
    }

    /**
     * Compte-titres JOINT de demonstration : un titulaire principal et un
     * co-signataire, chacun avec SON login (mot de passe {@code Demo1234}). Tout
     * ordre passe sur ce compte requiert la double signature. Facilite la demo
     * sans dependre de l'envoi e-mail des identifiants provisoires.
     */
    private void seedJointAccount() {
        String hash = password.hash(DEMO_PASSWORD);

        // Titulaire principal (login + compte-titres partage).
        UUID primaryId = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, role, nom, prenom, compte_titres, "
                        + "compte_especes, solde, categorie, type_compte, telephone) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                UUID.class,
                "joint.titulaire@example.cm", hash, "CLIENT_PP", "TANDJA", "Amadou",
                "037 10001 00099887766", "037 10001 99887766554", 25_000_000L,
                "NON_QUALIFIE", "JOINT", "+237 690 111 222");

        // Co-signataire : login propre, rattache au compte du titulaire principal.
        jdbc.update(
                "INSERT INTO users (email, password_hash, role, nom, prenom, telephone, account_holder_id) "
                        + "VALUES (?,?,?,?,?,?,?)",
                "joint.cosignataire@example.cm", hash, "CLIENT_PP", "TANDJA", "Fatima",
                "+237 690 333 444", primaryId);

        // Dossier client (pour l'affichage back-office) + 1 sous-compte + signataires.
        jdbc.update("INSERT INTO client_profiles (user_id, type_personne, raison_sociale, compte_statut) "
                + "VALUES (?, 'PP', ?, 'ACTIF')", primaryId, "TANDJA Amadou & Fatima (compte joint)");
        jdbc.update("INSERT INTO sous_comptes_titres (user_id, numero, libelle, type, statut, "
                        + "date_ouverture, positions_count, valeur_totale, ordre) VALUES (?,?,?,?,?,?,?,?,?)",
                primaryId, "SC-JOINT-001", "Conservation principale", "CONSERVATION", "ACTIF",
                LocalDate.now(), 0, 0L, 0);
        jdbc.update("INSERT INTO client_contacts (user_id, type, nom, prenom, email, telephone_portable, ordre) "
                        + "VALUES (?, 'TITULAIRE', ?, ?, ?, ?, 0)",
                primaryId, "TANDJA", "Amadou", "joint.titulaire@example.cm", "+237 690 111 222");
        jdbc.update("INSERT INTO client_contacts (user_id, type, nom, prenom, email, telephone_portable, ordre) "
                        + "VALUES (?, 'TITULAIRE', ?, ?, ?, ?, 1)",
                primaryId, "TANDJA", "Fatima", "joint.cosignataire@example.cm", "+237 690 333 444");
    }

    /**
     * Deux emissions PUBLIE de demonstration (ouvertes a la souscription) : un BTA
     * (mode TAUX, sans coupon) et un OTA (mode PRIX, coupon annuel 7,50 %). Permet
     * de tester immediatement la souscription, l'adjudication, l'avis d'opere et le
     * parcours compte joint apres une reconstruction de la base.
     */
    private void seedEmissions() {
        UUID agentId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class,
                "agent@afriland.cm");
        UUID supId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class,
                "superviseur@afriland.cm");
        LocalDate today = LocalDate.now();
        OffsetDateTime now = OffsetDateTime.now();

        insertPublishedEmission("BTA-CMR-DEMO", "CM0000DEMO01", "BTA Cameroun 13 semaines (démo)",
                "BTA", "CMR", today, today.plusDays(91), today.plusDays(2),
                1_000_000L, 5_000_000_000L, 0.0, 1_000_000L, null, "TAUX", agentId, supId, now);

        insertPublishedEmission("OTA-GAB-DEMO", "GA0000DEMO02", "OTA Gabon 4 ans 7,50% (démo)",
                "OTA", "GAB", today, today.plusYears(4), today.plusDays(2),
                10_000L, 10_000_000_000L, 7.5, 1_000_000L, "ANNUEL", "PRIX", agentId, supId, now);
    }

    private void insertPublishedEmission(String code, String isin, String libelle, String nature,
            String pays, LocalDate dateEmission, LocalDate dateEcheance, LocalDate dateReglement,
            long vnu, long montantGlobal, double taux, long montantMin, String freqCoupon,
            String mode, UUID createdBy, UUID validatedBy, OffsetDateTime now) {
        int duree = (int) ChronoUnit.DAYS.between(dateEmission, dateEcheance);
        jdbc.update("INSERT INTO emissions (code, isin, libelle, nature, pays_code, date_emission, "
                        + "ouverture_souscription, fermeture_souscription, date_echeance, date_reglement, "
                        + "duree_jours, valeur_nominale_unitaire, montant_global, taux_nominal, "
                        + "montant_minimum, frequence_coupon, mode_adjudication, status, created_by, "
                        + "validated_by, date_validation, observation) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'PUBLIE', ?,?,?, 'NOUVELLE EMISSION')",
                code, isin, libelle, nature, pays, dateEmission,
                now, now.plusDays(30), dateEcheance, dateReglement, duree,
                vnu, montantGlobal, taux, montantMin, freqCoupon, mode, createdBy, validatedBy, now);
    }
}
