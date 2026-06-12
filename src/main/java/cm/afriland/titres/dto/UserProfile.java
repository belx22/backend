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
        boolean mustChangePassword) {

    /** Copie du profil dont le solde est efface — destinee aux reponses adressees aux clients. */
    public UserProfile withoutBalance() {
        return new UserProfile(id, nom, prenom, email, role, statut,
                compteTitres, compteEspeces, null, categorie, typeCompte, telephone,
                mustChangePassword);
    }
}
