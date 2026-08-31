package com.freezhub.catalog;

import com.freezhub.audit.AuditAction;
import com.freezhub.audit.AuditActor;
import com.freezhub.audit.AuditDetails;
import com.freezhub.audit.AuditResourceType;
import com.freezhub.audit.AuditTrail;
import com.freezhub.audit.FieldChanges;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final AuditTrail auditTrail;

    public TeamService(TeamRepository teamRepository, AuditTrail auditTrail) {
        this.teamRepository = teamRepository;
        this.auditTrail = auditTrail;
    }

    @Transactional
    public Team create(Long organizationId, AuditActor actor, String name) {
        requireNameNotTaken(organizationId, name);
        Team created = teamRepository.save(new Team(organizationId, name));
        auditTrail.record(organizationId, actor, AuditAction.CATALOG_CREATED,
                AuditResourceType.TEAM, created.getId(), AuditDetails.builder().with("name", name).toJson());
        return created;
    }

    public List<Team> list(Long organizationId) {
        return teamRepository.findAllByOrganizationId(organizationId);
    }

    public Team get(Long organizationId, Long teamId) {
        return findOwned(organizationId, teamId);
    }

    @Transactional
    public Team rename(Long organizationId, AuditActor actor, Long teamId, String name) {
        Team team = findOwned(organizationId, teamId);
        if (!team.getName().equals(name)) {
            requireNameNotTaken(organizationId, name);
            // Recorded before the change, and only when there is one: renaming to the same
            // name is a no-op, and an entry claiming otherwise would be noise (FZ-072).
            String previous = team.getName();
            team.setName(name);
            auditTrail.record(organizationId, actor, AuditAction.CATALOG_RENAMED,
                    AuditResourceType.TEAM, team.getId(),
                    FieldChanges.builder().compare("name", previous, name).toJson());
        }
        return team;
    }

    @Transactional
    public void delete(Long organizationId, AuditActor actor, Long teamId) {
        Team team = findOwned(organizationId, teamId);
        String name = team.getName();
        CatalogDeletion.deleteOrReportInUse(team, teamRepository, "team");
        // After the delete, so a deletion refused for being in use records nothing.
        auditTrail.record(organizationId, actor, AuditAction.CATALOG_DELETED,
                AuditResourceType.TEAM, teamId, AuditDetails.builder().with("name", name).toJson());
    }

    private Team findOwned(Long organizationId, Long teamId) {
        return teamRepository.findByIdAndOrganizationId(teamId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
    }

    private void requireNameNotTaken(Long organizationId, String name) {
        if (teamRepository.existsByOrganizationIdAndName(organizationId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A team with this name already exists");
        }
    }

}
