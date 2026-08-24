package com.freezhub.catalog;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TeamService {

    private final TeamRepository teamRepository;

    public TeamService(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    @Transactional
    public Team create(Long organizationId, String name) {
        requireNameNotTaken(organizationId, name);
        return teamRepository.save(new Team(organizationId, name));
    }

    public List<Team> list(Long organizationId) {
        return teamRepository.findAllByOrganizationId(organizationId);
    }

    public Team get(Long organizationId, Long teamId) {
        return findOwned(organizationId, teamId);
    }

    @Transactional
    public Team rename(Long organizationId, Long teamId, String name) {
        Team team = findOwned(organizationId, teamId);
        if (!team.getName().equals(name)) {
            requireNameNotTaken(organizationId, name);
            team.setName(name);
        }
        return team;
    }

    @Transactional
    public void delete(Long organizationId, Long teamId) {
        Team team = findOwned(organizationId, teamId);
        teamRepository.delete(team);
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
