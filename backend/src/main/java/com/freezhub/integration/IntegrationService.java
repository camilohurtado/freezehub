package com.freezhub.integration;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntegrationService {

    private final IntegrationRepository integrationRepository;

    public IntegrationService(IntegrationRepository integrationRepository) {
        this.integrationRepository = integrationRepository;
    }

    public List<Integration> list(Long organizationId) {
        return integrationRepository.findAllByOrganizationId(organizationId);
    }

    @Transactional
    public Integration create(Long organizationId, IntegrationType type, String config) {
        IntegrationConfigs.validate(type, config);
        return integrationRepository.save(new Integration(organizationId, type, config));
    }

    /** Switches a destination off without discarding its configuration. */
    @Transactional
    public Integration setEnabled(Long organizationId, Long integrationId, boolean enabled) {
        Integration integration = findOwned(organizationId, integrationId);
        integration.setEnabled(enabled);
        return integration;
    }

    @Transactional
    public Integration replaceConfig(Long organizationId, Long integrationId, String config) {
        Integration integration = findOwned(organizationId, integrationId);
        IntegrationConfigs.validate(integration.getType(), config);
        integration.setConfig(config);
        return integration;
    }

    /**
     * Removes a destination entirely.
     *
     * <p>Queued notifications for it are discarded with it — the schema cascades, because
     * a delivery attempt to a destination that no longer exists has nowhere to go. Use
     * {@link #setEnabled} to stop notifying without losing history.
     */
    @Transactional
    public void delete(Long organizationId, Long integrationId) {
        integrationRepository.delete(findOwned(organizationId, integrationId));
    }

    /** Another organization's integration is indistinguishable from one that never existed. */
    private Integration findOwned(Long organizationId, Long integrationId) {
        return integrationRepository.findByIdAndOrganizationId(integrationId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Integration not found"));
    }

}
