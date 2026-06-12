package cm.afriland.titres.dto;

/**
 * Reponse d'authentification : couple de jetons + profil utilisateur.
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserProfile user) {
}
