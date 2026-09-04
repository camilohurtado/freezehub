package com.freezhub.catalog;

import com.freezhub.audit.AuditAction;
import com.freezhub.audit.AuditActor;
import com.freezhub.audit.AuditDetails;
import com.freezhub.audit.AuditResourceType;
import com.freezhub.audit.AuditTrail;
import com.freezhub.subscription.SubscriptionService;
import com.freezhub.audit.FieldChanges;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final TeamRepository teamRepository;
    private final TeamApplicationRepository teamApplicationRepository;
    private final AuditTrail auditTrail;
    private final SubscriptionService subscriptions;

    public ApplicationService(ApplicationRepository applicationRepository, TeamRepository teamRepository,
                               TeamApplicationRepository teamApplicationRepository,
                               AuditTrail auditTrail,
                               SubscriptionService subscriptions) {
        this.applicationRepository = applicationRepository;
        this.teamRepository = teamRepository;
        this.teamApplicationRepository = teamApplicationRepository;
        this.auditTrail = auditTrail;
        this.subscriptions = subscriptions;
    }

    /**
     * Registers an application, if the plan has room for one more.
     *
     * <p>Applications are the metric FreezeHub is priced on ({@code D-20}), so this is the
     * limit that matters. It is checked inside the transaction, which is what makes the
     * count meaningful: two concurrent creations at the limit would otherwise both read
     * one below it and both succeed.
     */
    @Transactional
    public Application create(Long organizationId, AuditActor actor, String name) {
        subscriptions.requireApplicationHeadroom(organizationId,
                () -> applicationRepository.countByOrganizationId(organizationId));
        requireNameNotTaken(organizationId, name);
        Application created = applicationRepository.save(new Application(organizationId, name));
        auditTrail.record(organizationId, actor, AuditAction.CATALOG_CREATED,
                AuditResourceType.APPLICATION, created.getId(),
                AuditDetails.builder().with("name", name).toJson());
        return created;
    }

    public List<Application> list(Long organizationId) {
        return applicationRepository.findAllByOrganizationId(organizationId);
    }

    public Application get(Long organizationId, Long applicationId) {
        return findOwnedApplication(organizationId, applicationId);
    }

    @Transactional
    public Application rename(Long organizationId, AuditActor actor, Long applicationId, String name) {
        Application application = findOwnedApplication(organizationId, applicationId);
        if (!application.getName().equals(name)) {
            requireNameNotTaken(organizationId, name);
            // The rename that matters most: because an unrecognised name blocks (D-14),
            // every pipeline still sending the old one starts being refused, and this is
            // the only thing that explains why (FZ-072).
            String previous = application.getName();
            application.setName(name);
            auditTrail.record(organizationId, actor, AuditAction.CATALOG_RENAMED,
                    AuditResourceType.APPLICATION, application.getId(),
                    FieldChanges.builder().compare("name", previous, name).toJson());
        }
        return application;
    }

    @Transactional
    public void delete(Long organizationId, AuditActor actor, Long applicationId) {
        Application application = findOwnedApplication(organizationId, applicationId);
        String name = application.getName();
        CatalogDeletion.deleteOrReportInUse(application, applicationRepository, "application");
        // After the delete, so a deletion refused for being in use records nothing.
        auditTrail.record(organizationId, actor, AuditAction.CATALOG_DELETED,
                AuditResourceType.APPLICATION, applicationId,
                AuditDetails.builder().with("name", name).toJson());
    }

    public List<Long> teamIds(Long applicationId) {
        return teamApplicationRepository.findAllByApplicationId(applicationId).stream()
                .map(TeamApplication::getTeamId)
                .toList();
    }

    @Transactional
    public void associateTeam(Long organizationId, AuditActor actor, Long applicationId, Long teamId) {
        Application application = findOwnedApplication(organizationId, applicationId);
        Team team = findOwnedTeam(organizationId, teamId);
        // Assignment is idempotent, so only a real change is recorded — repeating a PUT
        // must not fill the trail with entries for nothing happening.
        if (!teamApplicationRepository.existsByTeamIdAndApplicationId(teamId, applicationId)) {
            teamApplicationRepository.save(new TeamApplication(teamId, applicationId));
            recordAssignment(organizationId, actor, AuditAction.APPLICATION_TEAM_ASSIGNED, application, team);
        }
    }

    @Transactional
    public void disassociateTeam(Long organizationId, AuditActor actor, Long applicationId, Long teamId) {
        Application application = findOwnedApplication(organizationId, applicationId);
        Team team = findOwnedTeam(organizationId, teamId);
        if (teamApplicationRepository.existsByTeamIdAndApplicationId(teamId, applicationId)) {
            teamApplicationRepository.deleteByTeamIdAndApplicationId(teamId, applicationId);
            recordAssignment(organizationId, actor, AuditAction.APPLICATION_TEAM_UNASSIGNED, application, team);
        }
    }

    /**
     * Both names, denormalised, so the entry reads without a join — and still reads after
     * either of them is renamed.
     *
     * <p>Recorded against the application, because that is what a reader is looking at
     * when they ask why a team-scoped freeze started or stopped covering it.
     */
    private void recordAssignment(Long organizationId, AuditActor actor, AuditAction action,
                                  Application application, Team team) {
        auditTrail.record(organizationId, actor, action, AuditResourceType.APPLICATION, application.getId(),
                AuditDetails.builder()
                        .with("application", application.getName())
                        .with("team", team.getName())
                        .toJson());
    }

    private Application findOwnedApplication(Long organizationId, Long applicationId) {
        return applicationRepository.findByIdAndOrganizationId(applicationId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found"));
    }

    private Team findOwnedTeam(Long organizationId, Long teamId) {
        return teamRepository.findByIdAndOrganizationId(teamId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
    }

    private void requireNameNotTaken(Long organizationId, String name) {
        if (applicationRepository.existsByOrganizationIdAndName(organizationId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An application with this name already exists");
        }
    }

}
