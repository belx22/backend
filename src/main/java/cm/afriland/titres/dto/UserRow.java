package cm.afriland.titres.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;

/**
 * Reflet d'une ligne de la table {@code users}. {@code passwordHash} n'est
 * jamais serialise : aucune route ne l'expose au client.
 */
public record UserRow(
        UUID id,
        String email,
        String passwordHash,
        String role,
        String nom,
        String prenom,
        String statut,
        String compteTitres,
        String compteEspeces,
        Long solde,
        String categorie,
        String typeCompte,
        String telephone,
        boolean mustChangePassword,
        int failedLoginAttempts,
        OffsetDateTime lockedUntil,
        /** Titulaire principal du compte-titres si cet utilisateur est co-signataire. */
        UUID accountHolderId) {

    /** Colonnes {@code users} — reutilisees par toutes les requetes SELECT. */
    public static final String COLUMNS = "id, email, password_hash, role, nom, prenom, statut, "
            + "compte_titres, compte_especes, solde, categorie, type_compte, telephone, "
            + "must_change_password, failed_login_attempts, locked_until, account_holder_id";

    public static final RowMapper<UserRow> MAPPER = (rs, rowNum) -> new UserRow(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getString("nom"),
            rs.getString("prenom"),
            rs.getString("statut"),
            rs.getString("compte_titres"),
            rs.getString("compte_especes"),
            rs.getObject("solde", Long.class),
            rs.getString("categorie"),
            rs.getString("type_compte"),
            rs.getString("telephone"),
            rs.getBoolean("must_change_password"),
            rs.getInt("failed_login_attempts"),
            rs.getObject("locked_until", OffsetDateTime.class),
            rs.getObject("account_holder_id", UUID.class));

    /**
     * Vrai si l'utilisateur participe a un compte-titres a plusieurs signataires.
     *
     * <p>Deux cas, et le second est facile a oublier : le titulaire principal, dont
     * {@code type_compte} vaut JOINT ou INDIVIS ; et le <b>co-signataire</b>, dont
     * le compte propre ne porte aucun {@code type_compte} mais qui est rattache au
     * titulaire par {@code account_holder_id}. Or c'est precisement lui que l'on
     * sollicite pour valider les ordres.</p>
     */
    public boolean compteJoint() {
        return accountHolderId != null
                || "JOINT".equals(typeCompte) || "INDIVIS".equals(typeCompte);
    }

    /** Projection vers le profil public (sans empreinte de mot de passe). */
    public UserProfile toProfile() {
        return new UserProfile(id, nom, prenom, email, role, statut,
                compteTitres, compteEspeces, solde, categorie, typeCompte, telephone,
                mustChangePassword, compteJoint());
    }
}
