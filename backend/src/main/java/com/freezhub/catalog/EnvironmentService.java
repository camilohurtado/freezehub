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
public class EnvironmentService {

    private final EnvironmentRepository environmentRepository;
    private final AuditTrail auditTrail;

    public EnvironmentService(EnvironmentRepository environmentRepository, AuditTrail auditTrail) {
        this.environmentRepository = environmentRepository;
        this.auditTrail = auditTrail;
    }

    @Transactional
    public Environment create(Long organizationId, AuditActor actor, String name) {
        requireNameNotTaken(organizationId, name);
        Environment created = environmentRepository.save(new Environment(organizationId, name));
        auditTrail.record(organizationId, actor, AuditAction.CATALOG_CREATED,
                AuditResourceType.ENVIRONMENT, created.getId(), AuditDetails.builder().with("name", name).toJson());
        return created;
    }

    public List<Environment> list(Long organizationId) {
        return environmentRepository.findAllByOrganizationId(organizationId);
    }

    public Environment get(Long organizationId, Long environmentId) {
        return findOwned(organizationId, environmentId);
    }

    @Transactional
    public Environment rename(Long organizationId, AuditActor actor, Long environmentId, String name) {
        Environment environment = findOwned(organizationId, environmentId);
        if (!environment.getName().equals(name)) {
            requireNameNotTaken(organizationId, name);
            // Recorded before the change, and only when there is one: renaming to the same
            // name is a no-op, and an entry claiming otherwise would be noise (FZ-072).
            String previous = environment.getName();
            environment.setName(name);
            auditTrail.record(organizationId, actor, AuditAction.CATALOG_RENAMED,
                    AuditResourceType.ENVIRONMENT, environment.getId(),
                    FieldChanges.builder().compare("name", previous, name).toJson());
        }
        return environment;
    }

    @Transactional
    public void delete(Long organizationId, AuditActor actor, Long environmentId) {
        Environment environment = findOwned(organizationId, environmentId);
        String name = environment.getName();
        CatalogDeletion.deleteOrReportInUse(environment, environmentRepository, "environment");
        // After the delete, so a deletion refused for being in use records nothing.
        auditTrail.record(organizationId, actor, AuditAction.CATALOG_DELETED,
                AuditResourceType.ENVIRONMENT, environmentId, AuditDetails.builder().with("name", name).toJson());
    }

    private Environment findOwned(Long organizationId, Long environmentId) {
        return environmentRepository.findByIdAndOrganizationId(environmentId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found"));
    }

    private void requireNameNotTaken(Long organizationId, String name) {
        if (environmentRepository.existsByOrganizationIdAndName(organizationId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An environment with this name already exists");
        }
    }

}
