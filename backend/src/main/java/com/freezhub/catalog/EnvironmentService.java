package com.freezhub.catalog;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EnvironmentService {

    private final EnvironmentRepository environmentRepository;

    public EnvironmentService(EnvironmentRepository environmentRepository) {
        this.environmentRepository = environmentRepository;
    }

    @Transactional
    public Environment create(Long organizationId, String name) {
        requireNameNotTaken(organizationId, name);
        return environmentRepository.save(new Environment(organizationId, name));
    }

    public List<Environment> list(Long organizationId) {
        return environmentRepository.findAllByOrganizationId(organizationId);
    }

    public Environment get(Long organizationId, Long environmentId) {
        return findOwned(organizationId, environmentId);
    }

    @Transactional
    public Environment rename(Long organizationId, Long environmentId, String name) {
        Environment environment = findOwned(organizationId, environmentId);
        if (!environment.getName().equals(name)) {
            requireNameNotTaken(organizationId, name);
            environment.setName(name);
        }
        return environment;
    }

    @Transactional
    public void delete(Long organizationId, Long environmentId) {
        Environment environment = findOwned(organizationId, environmentId);
        CatalogDeletion.deleteOrReportInUse(environment, environmentRepository, "environment");
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
