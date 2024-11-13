package de.tum.cit.aet.artemis.programming.service;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.core.domain.User;
import de.tum.cit.aet.artemis.core.exception.AccessForbiddenException;
import de.tum.cit.aet.artemis.exercise.domain.participation.StudentParticipation;
import de.tum.cit.aet.artemis.programming.domain.ParticipationVCSAccessToken;
import de.tum.cit.aet.artemis.programming.repository.ParticipationVCSAccessTokenRepository;
import de.tum.cit.aet.artemis.programming.repository.ProgrammingExerciseStudentParticipationRepository;
import de.tum.cit.aet.artemis.programming.service.localvc.LocalVCPersonalAccessTokenManagementService;

@Profile(PROFILE_CORE)
@Service
public class ParticipationVcsAccessTokenService {

    private static final Logger log = LoggerFactory.getLogger(ParticipationVcsAccessTokenService.class);

    private final ParticipationVCSAccessTokenRepository participationVcsAccessTokenRepository;

    private final ProgrammingExerciseStudentParticipationRepository programmingExerciseStudentParticipationRepository;

    public ParticipationVcsAccessTokenService(ParticipationVCSAccessTokenRepository participationVCSAccessTokenRepository,
            ProgrammingExerciseStudentParticipationRepository programmingExerciseStudentParticipationRepository) {
        this.participationVcsAccessTokenRepository = participationVCSAccessTokenRepository;
        this.programmingExerciseStudentParticipationRepository = programmingExerciseStudentParticipationRepository;
    }

    /**
     * Creates a vcs access token for a User,Participation pair and stores it in the database
     *
     * @param user          the user which is owner of the token
     * @param participation the participation which belongs to the token
     * @return the newly created ParticipationVCSAccessToken
     */
    public ParticipationVCSAccessToken createParticipationVCSAccessToken(User user, StudentParticipation participation) {
        ParticipationVCSAccessToken participationVCSAccessToken = new ParticipationVCSAccessToken();
        participationVCSAccessToken.setUser(user);
        participationVCSAccessToken.setParticipation(participation);
        participationVCSAccessToken.setVcsAccessToken(LocalVCPersonalAccessTokenManagementService.generateSecureVCSAccessToken());
        return participationVcsAccessTokenRepository.save(participationVCSAccessToken);
    }

    /**
     * Retrieves the participationVCSAccessToken for a User,Participation pair if it exists and if the user owns the participation
     *
     * @param user            the user which is owner of the token
     * @param participationId the participation's id which the token belongs to
     * @return an Optional participationVCSAccessToken,
     */
    public ParticipationVCSAccessToken findByUserAndParticipationIdOrElseThrow(User user, long participationId) {
        var participation = programmingExerciseStudentParticipationRepository.findByIdElseThrow(participationId);
        if (participation.isOwnedBy(user)) {
            return participationVcsAccessTokenRepository.findByUserIdAndParticipationIdOrElseThrow(user.getId(), participationId);
        }
        else {
            throw new AccessForbiddenException("Participation not owned by user");
        }
    }

    /**
     * Checks if the participationVCSAccessToken for a User,Participation pair exists, and creates a new one if not; if the user owns the participation
     *
     * @param user            the user's id which is owner of the token
     * @param participationId the participation's id which the token belongs to
     * @return an Optional participationVCSAccessToken,
     */
    public ParticipationVCSAccessToken createVcsAccessTokenForUserAndParticipationIdOrElseThrow(User user, long participationId) {
        participationVcsAccessTokenRepository.findByUserIdAndParticipationIdAndThrowIfExists(user.getId(), participationId);
        var participation = programmingExerciseStudentParticipationRepository.findByIdElseThrow(participationId);
        if (participation.isOwnedBy(user)) {
            return createParticipationVCSAccessToken(user, participation);
        }
        else {
            throw new AccessForbiddenException("Participation not owned by user");
        }
    }

    /**
     * Deletes the token connected to a participation
     *
     * @param participationId the participation id for which the token should get deleted
     */
    public void deleteByParticipationId(long participationId) {
        participationVcsAccessTokenRepository.deleteByParticipationId(participationId);
    }

    /**
     * Deletes all participationVcsAccessTokens of a user
     *
     * @param userId The user's id
     */
    public void deleteAllByUserId(long userId) {
        participationVcsAccessTokenRepository.deleteAllByUserId(userId);
    }
}
