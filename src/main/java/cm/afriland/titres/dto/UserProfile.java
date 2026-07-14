package cm.afriland.titres.dto;

import java.util.UUID;

/**
 * Profil utilisateur expose par l'API — jamais d'empreinte de mot de passe.
 *
 * <p>{@code solde} represente uniquement la valeur de cache en base. Les clients
 * ne doivent JAMAIS le voir : seuls les acteurs back-office y ont acces, et la
 * source de verite reste le serveur Amplitude/AIF (cf. {@link
 * cm.afriland.titres.web.AccountBalanceController}).</p>
 */
public record UserProfile(
        UUID id,
        String nom,
        String prenom,
        String email,
        String role,
        String statut,
        String compteTitres,
        String compteEspeces,
        Long solde,
        String categorie,
        String typeCompte,
        String telephone,
        boolean mustChangePassword,
        /**
         * Vrai si l'utilisateur participe a un compte-titres a plusieurs signataires
         * (titulaire d'un compte JOINT/INDIVIS, ou co-signataire rattache a un tel
         * compte). Pilote l'affichage du menu « Validations » cote client.
         */
        boolean compteJoint) {

    /**
     * Profil tel qu'un CLIENT a le droit de le voir : sans solde, et sans compte
     * de depot.
     *
     * <p>Regle unique, a appeler partout ou une reponse part vers un client. Les
     * deux masquages etaient jusqu'ici appliques separement — et l'un d'eux avait
     * ete oublie dans les reponses d'authentification, ou le compte de depot
     * repartait donc en clair.</p>
     */
    public UserProfile pourClient() {
        return withoutBalance().withoutCompteTitres();
    }

    /** Copie du profil dont le solde est efface — destinee aux reponses adressees aux clients. */
    public UserProfile withoutBalance() {
        return new UserProfile(id, nom, prenom, email, role, statut,
                compteTitres, compteEspeces, null, categorie, typeCompte, telephone,
                mustChangePassword, compteJoint);
    }

    /**
     * Retire le compte de depot (compte-titres, p. ex. {@code CCEICMCXX345}).
     *
     * <p>C'est un identifiant <b>interne</b> au depositaire : le back-office s'en
     * sert, le client ne doit jamais le voir. Le masquer a l'AFFICHAGE ne suffirait
     * pas — il resterait lisible dans la reponse de l'API ; on l'efface donc a la
     * source.</p>
     */
    public UserProfile withoutCompteTitres() {
        return new UserProfile(id, nom, prenom, email, role, statut,
                null, compteEspeces, solde, categorie, typeCompte, telephone,
                mustChangePassword, compteJoint);
    }
}
