package com.freezhub.catalog;

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

    public ApplicationService(ApplicationRepository applicationRepository, TeamRepository teamRepository,
                               TeamApplicationRepository teamApplicationRepository) {
        this.applicationRepository = applicationRepository;
        this.teamRepository = teamRepository;
        this.teamApplicationRepository = teamApplicationRepository;
    }

    @Transactional
    public Application create(Long organizationId, String name) {
        requireNameNotTaken(organizationId, name);
        return applicationRepository.save(new Application(organizationId, name));
    }

    public List<Application> list(Long organizationId) {
        return applicationRepository.findAllByOrganizationId(organizationId);
    }

    public Application get(Long organizationId, Long applicationId) {
        return findOwnedApplication(organizationId, applicationId);
    }

    @Transactional
    public Application rename(Long organizationId, Long applicationId, String name) {
        Application application = findOwnedApplication(organizationId, applicationId);
        if (!application.getName().equals(name)) {
            requireNameNotTaken(organizationId, name);
            application.setName(name);
        }
        return application;
    }

    @Transactional
    public void delete(Long organizationId, Long applicationId) {
        Application application = findOwnedApplication(organizationId, applicationId);
        applicationRepository.delete(application);
    }

    public List<Long> teamIds(Long applicationId) {
        return teamApplicationRepository.findAllByApplicationId(applicationId).stream()
                .map(TeamApplication::getTeamId)
                .toList();
    }

    @Transactional
    public void associateTeam(Long organizationId, Long applicationId, Long teamId) {
        findOwnedApplication(organizationId, applicationId);
        findOwnedTeam(organizationId, teamId);
        if (!teamApplicationRepository.existsByTeamIdAndApplicationId(teamId, applicationId)) {
            teamApplicationRepository.save(new TeamApplication(teamId, applicationId));
        }
    }

    @Transactional
    public void disassociateTeam(Long organizationId, Long applicationId, Long teamId) {
        findOwnedApplication(organizationId, applicationId);
        findOwnedTeam(organizationId, teamId);
        teamApplicationRepository.deleteByTeamIdAndApplicationId(teamId, applicationId);
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
